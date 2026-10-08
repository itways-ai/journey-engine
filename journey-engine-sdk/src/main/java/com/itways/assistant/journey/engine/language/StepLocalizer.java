package com.itways.assistant.journey.engine.language;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itways.assistant.journey.engine.service.StepTextPort;
import com.itways.assistant.journey.engine.service.TextTranslator;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepText;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Swaps a step's authored text for the same step's text in the run's language.
 *
 * <p>
 * Applied once per step, immediately before its handler runs, so every handler
 * gets localized text without any of them knowing that translations exist.
 *
 * <p>
 * Always returns a <em>copy</em>. The {@code Journey} it is given is shared —
 * fetched once per turn and, on a resumed run, describing steps that other
 * conversations are executing concurrently in other languages. Mutating it in
 * place would leak one conversation's language into another's.
 */
@Component
@Slf4j
public class StepLocalizer {

    /**
     * The only step type whose {@code actionTarget} is prose.
     *
     * <p>
     * Everywhere else it is a URL, a template id or an intent code. Translating
     * those does not produce a badly worded step, it produces a broken one, so
     * the guard is on type rather than on whether the string looks like text.
     */
    private static final String RESPONSE = "RESPONSE";

    private final ObjectMapper objectMapper;
    private final LanguageDetector detector;
    private final TextTranslator translator;
    private final StepTextPort stepTextPort;

    public StepLocalizer(ObjectMapper objectMapper, LanguageDetector detector,
            org.springframework.beans.factory.ObjectProvider<TextTranslator> translator,
            org.springframework.beans.factory.ObjectProvider<StepTextPort> stepTextPort) {
        this.objectMapper = objectMapper;
        this.detector = detector;
        // Both optional: an engine embedded without a translation store or an AI
        // provider still runs, it just never localizes authored text.
        this.translator = translator.getIfAvailable(() -> TextTranslator.NONE);
        this.stepTextPort = stepTextPort.getIfAvailable(() -> StepTextPort.NONE);
    }

    /**
     * @param translations the map from {@link com.itways.assistant.journey.engine.service.StepTextPort},
     *                     keyed by step id
     * @return a localized copy, or the original when nothing applies
     */
    public JourneyStep localize(JourneyStep step, Map<Long, StepText> translations,
            String accountId, ConversationLanguage language) {
        if (step == null || step.getId() == null || language == null) {
            return step;
        }

        StepText text = translations != null ? translations.get(step.getId()) : null;
        if (text == null || text.isEmpty()) {
            // Nobody has written this step in this language. Fall back to machine
            // translation rather than to English -- an approximate sentence in the
            // right language beats an exact one the reader cannot read.
            text = machineTranslate(step, accountId, language);
        }
        if (text == null || text.isEmpty()) {
            return step;
        }

        JourneyStep copy = copyOf(step);

        if (hasText(text.message())) {
            copy.setMessage(text.message());
        }
        if (hasText(text.actionTarget()) && RESPONSE.equalsIgnoreCase(step.getActionType())) {
            copy.setActionTarget(text.actionTarget());
        }

        String patchedConfig = patchApiConfig(step.getApiConfig(), text);
        if (patchedConfig != null) {
            copy.setApiConfig(patchedConfig);
        }

        return copy;
    }

    /**
     * Translates a step that has no stored variant, and remembers the result.
     *
     * <p>
     * Lazy on purpose: only steps a run actually reaches are translated, so a
     * twenty-step journey where the user takes a three-step branch pays for
     * three. The result is written back through the port, so the cost is paid
     * once per step and language across the whole tenant rather than per run.
     *
     * <p>
     * Returns null -- leaving the step as authored -- when there is nothing to
     * translate, when the text is already in the right language, or when the
     * translator fails.
     */
    private StepText machineTranslate(JourneyStep step, String accountId, ConversationLanguage language) {
        StepText authored = authoredTextOf(step);
        if (authored.isEmpty()) {
            return null;
        }

        // Detected rather than read from a column: a journey has no declared
        // language, and the text itself is the most reliable statement of what it
        // was written in. A step already in the target language is the common case
        // for a bilingual author and must cost nothing.
        ConversationLanguage authoredLanguage = detector.detect(longestOf(authored));
        if (authoredLanguage == null || authoredLanguage == language) {
            return null;
        }

        StepText translated;
        try {
            translated = new StepText(
                    translator.translate(accountId, authored.message(), authoredLanguage, language),
                    translator.translate(accountId, authored.actionTarget(), authoredLanguage, language),
                    translator.translate(accountId, authored.confirmationMessage(), authoredLanguage, language),
                    translator.translate(accountId, authored.instruction(), authoredLanguage, language));
        } catch (Exception e) {
            log.warn("Translating step {} into {} failed; using authored text: {}",
                    step.getId(), language.code(), e.getMessage());
            return null;
        }

        if (translated.isEmpty()) {
            return null;
        }

        stepTextPort.saveMachineTranslation(accountId, step.getId(), language, translated);
        return translated;
    }

    /** The step's own prose, gathered into the same shape a translation uses. */
    private StepText authoredTextOf(JourneyStep step) {
        String actionTarget = RESPONSE.equalsIgnoreCase(step.getActionType()) ? step.getActionTarget() : null;
        String confirmation = readConfigText(step.getApiConfig(), "confirmationMessage");
        String instruction = readConfigText(step.getApiConfig(), "instruction");
        return new StepText(step.getMessage(), actionTarget, confirmation, instruction);
    }

    /**
     * The longest of the step's strings, for language detection.
     *
     * <p>
     * Detection needs script to work with, and a step's fields vary wildly in
     * length -- a one-word prompt beside a paragraph of instruction. Picking the
     * longest gives the detector its best sample instead of whichever field
     * happens to be first.
     */
    private static String longestOf(StepText text) {
        String longest = "";
        for (String candidate : new String[] {
                text.message(), text.actionTarget(), text.confirmationMessage(), text.instruction() }) {
            if (candidate != null && candidate.length() > longest.length()) {
                longest = candidate;
            }
        }
        return longest;
    }

    private String readConfigText(String apiConfig, String field) {
        if (apiConfig == null || apiConfig.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(apiConfig).get(field);
            return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Rewrites only the two prose fields inside the step's apiConfig JSON.
     *
     * <p>
     * Parsed and re-serialised rather than string-replaced: apiConfig also holds
     * request bodies, header maps and scripts, and a textual substitution would
     * happily corrupt any of them.
     *
     * @return the patched JSON, or null when there was nothing to change
     */
    private String patchApiConfig(String apiConfig, StepText text) {
        boolean wantsConfirmation = hasText(text.confirmationMessage());
        boolean wantsInstruction = hasText(text.instruction());
        if (!wantsConfirmation && !wantsInstruction) {
            return null;
        }
        if (apiConfig == null || apiConfig.isBlank()) {
            return null;
        }

        try {
            JsonNode parsed = objectMapper.readTree(apiConfig);
            if (!(parsed instanceof ObjectNode node)) {
                return null;
            }
            if (wantsConfirmation) {
                node.put("confirmationMessage", text.confirmationMessage());
            }
            if (wantsInstruction) {
                node.put("instruction", text.instruction());
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            // An unparseable apiConfig is a pre-existing problem that the step's
            // own handler will report properly. Losing the translation is the
            // right trade against replacing that error with a JSON one.
            log.warn("Could not localize apiConfig; leaving it as authored: {}", e.getMessage());
            return null;
        }
    }

    private static JourneyStep copyOf(JourneyStep step) {
        return JourneyStep.builder()
                .id(step.getId())
                .stepKey(step.getStepKey())
                .stepOrder(step.getStepOrder())
                .stepName(step.getStepName())
                .actionType(step.getActionType())
                .actionTarget(step.getActionTarget())
                .requiredParams(step.getRequiredParams())
                .apiConfig(step.getApiConfig())
                .continueOnError(step.isContinueOnError())
                .conditionExpression(step.getConditionExpression())
                .branchName(step.getBranchName())
                .parentOrder(step.getParentOrder())
                .parentOrders(step.getParentOrders())
                .message(step.getMessage())
                .clientVisible(step.getClientVisible())
                .build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
