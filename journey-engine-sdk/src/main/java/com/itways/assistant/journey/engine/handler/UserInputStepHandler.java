package com.itways.assistant.journey.engine.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.ChannelCapabilities;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.DecisionWords;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.engine.validation.AnswerValidator;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UserInputStepHandler implements StepHandler {

    /** Engine-internal marker: this step has asked for confirmation and is awaiting it. */
    static final String AWAITING_CONFIRM_PREFIX = "userInputAwaitingConfirm_";

    /**
     * Engine-internal: the value offered for confirmation, held while we wait.
     *
     * <p>
     * Held rather than re-derived, because the confirming turn's answer is "yes"
     * — and storing <em>that</em> as the step's output is the obvious bug this
     * design has to avoid. The user agrees to a value; the value is what gets
     * stored.
     */
    static final String PREFILL_PENDING_PREFIX = "userInputPrefill_";

    /** Engine-internal: how many invalid answers this step has already refused. */
    static final String ATTEMPTS_PREFIX = "userInputAttempts_";

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final EngineMessages messages;
    private final DecisionWords decisionWords;

    /**
     * How many times a step re-asks before giving up.
     *
     * <p>
     * Bounded because the alternative is a loop with no exit: a user who cannot
     * produce what the field demands — a phone number for someone who has none,
     * a pattern nobody can satisfy because the author's regex is wrong — would
     * be asked forever. Three tries is enough for a typo and short enough that a
     * genuinely impossible question surfaces as a failed run someone can look at.
     */
    @org.springframework.beans.factory.annotation.Value("${nibras.journey.user-input.max-attempts:3}")
    private int maxAttempts = 3;

    @Override
    public String getType() {
        return "USER_INPUT";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.userInputDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.userInputSchema(step);
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        ApiConfig uiConfig = engineUtils.parseApiConfig(step.getApiConfig());
        Map<String, Object> inputs = variableContext.getInputs(context);
        Object answer = inputs.get("answer");

        // Pre-fill: an answer this step already has, offered back for one yes.
        //
        // Ordered before the normal answer path because a pending offer changes
        // what "yes" means. Outside that, this whole block is inert unless the
        // author wrote fillFrom, so no existing journey changes behaviour.
        String prefillKey = PREFILL_PENDING_PREFIX + step.getStepOrder();
        Object offered = context.getInternal(prefillKey);
        if (offered != null) {
            StepResult settled = settlePrefill(step, context, uiConfig, inputs, answer, offered, prefillKey);
            if (settled != null) {
                return settled;
            }
            // Neither yes nor no: the reply is the user answering the original
            // question in their own words, so fall through and treat it as one.
        } else if (answer == null) {
            Object candidate = prefillCandidate(uiConfig, inputs);
            if (candidate != null) {
                return offerPrefill(step, context, uiConfig, candidate, prefillKey);
            }
        }

        // A multi-field form on a channel that cannot show one — a phone call, a
        // chat thread — is collected one question at a time. A form that did
        // arrive whole (a Map) takes the normal path regardless.
        if ("STRUCTURED".equalsIgnoreCase(uiConfig.getInputMode())
                && !ChannelCapabilities.supportsForm(context)
                && !(answer instanceof Map)) {
            StepResult fieldByField = askFieldByField(step, context, uiConfig, inputs, answer);
            if (fieldByField != null) {
                return fieldByField;
            }
        }

        if (answer != null) {
            // Validate before storing. Until this existed the engine took any
            // answer at all: a widget enforced the author's rules and every
            // messaging channel ignored them.
            StepResult rejected = rejectInvalidAnswer(step, context, uiConfig, inputs, answer);
            if (rejected != null) {
                return rejected;
            }
            context.removeInternal(ATTEMPTS_PREFIX + step.getStepOrder());

            variableContext.storeOutput(context, step, answer);
            inputs.remove("answer");

            // INTERACTIVE mode asks the user to verify a free-text answer once.
            // The awaiting-confirmation marker is what makes the second pass
            // through this step count as the confirmation; without it a plain-text
            // reply (every channel user) re-triggered the prompt forever.
            String awaitingKey = AWAITING_CONFIRM_PREFIX + step.getStepOrder();
            boolean awaitingConfirmation = Boolean.TRUE.equals(context.getInternal(awaitingKey));

            if ("INTERACTIVE".equalsIgnoreCase(uiConfig.getInputMode())
                    && answer instanceof String
                    && !awaitingConfirmation) {
                context.setInternal(awaitingKey, true);
                context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
                Map<String, Object> metadata = prepareMetadata(step, uiConfig);
                metadata.put("subStatus", "CONFIRMATION_REQUIRED");
                metadata.put("parsedData", answer);
                return StepResult.waiting(
                        messages.get(context.resolvedLanguage(), "step.userInput.confirm"),
                        metadata);
            }

            context.removeInternal(awaitingKey);

            String successPrompt = (step.getMessage() != null && !step.getMessage().isEmpty())
                    ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                    : step.getMessage();

            return StepResult.success(answer, successPrompt);
        }

        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
        Map<String, Object> metadata = prepareMetadata(step, uiConfig);
        if ("STRUCTURED".equalsIgnoreCase(uiConfig.getInputMode())) {
            metadata.put("subStatus", "DIRECT_FORM");
        }

        String prompt = (step.getMessage() != null && !step.getMessage().isEmpty())
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : messages.get(context.resolvedLanguage(), "step.userInput.waiting", step.getStepName());

        return StepResult.waiting(prompt, metadata);
    }

    /**
     * Re-asks when the answer breaks a declared constraint, or null to accept it.
     *
     * <p>
     * Two things count as unusable: an answer that fails one of the author's
     * field validations, and a blank one. Blank matters on its own because a
     * messaging channel can deliver an empty or whitespace message, and storing
     * that used to satisfy the step — the journey moved on with {@code ""} where
     * it expected a name.
     *
     * <p>
     * The rejected answer is removed from inputs on the way out. Left there it
     * would be re-read on the next turn and refused again without the user
     * having said anything.
     */
    private StepResult rejectInvalidAnswer(JourneyStep step, ExecutionContext context, ApiConfig uiConfig,
                                           Map<String, Object> inputs, Object answer) {
        boolean blank = answer instanceof String text && text.isBlank();
        List<AnswerValidator.FieldError> errors = blank
                ? List.of()
                : AnswerValidator.validate(answer, uiConfig.getFields(), uiConfig.getRules());
        if (!blank && errors.isEmpty()) {
            return null;
        }

        inputs.remove("answer");

        String attemptsKey = ATTEMPTS_PREFIX + step.getStepOrder();
        int attempts = (context.getInternal(attemptsKey) instanceof Number n ? n.intValue() : 0) + 1;

        if (attempts >= maxAttempts) {
            context.removeInternal(attemptsKey);
            // Halts the run rather than storing the bad value. The diagnostic
            // names the step for run history; the user gets a plain sentence.
            return StepResult.error(
                    "USER_INPUT step '" + step.getStepName() + "' gave up after " + attempts
                            + " invalid answers: " + describe(errors, context),
                    messages.get(context.resolvedLanguage(), "step.userInput.tooManyAttempts"));
        }
        context.setInternal(attemptsKey, attempts);
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

        Map<String, Object> metadata = prepareMetadata(step, uiConfig);
        metadata.put("subStatus", "VALIDATION_FAILED");
        metadata.put("attemptsRemaining", maxAttempts - attempts);
        if (!errors.isEmpty()) {
            // Per-field, so a widget can highlight the offending inputs rather
            // than only printing a sentence above the form.
            List<Map<String, Object>> rendered = new java.util.ArrayList<>();
            for (AnswerValidator.FieldError error : errors) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("field", error.field());
                entry.put("label", error.label());
                entry.put("message", messages.get(context.resolvedLanguage(), error.messageKey(), error.args()));
                rendered.add(entry);
            }
            metadata.put("validationErrors", rendered);
        }

        String message = blank
                ? messages.get(context.resolvedLanguage(), "step.userInput.empty")
                : messages.get(context.resolvedLanguage(), "step.userInput.fixErrors", describe(errors, context));
        return StepResult.waiting(message, metadata);
    }

    /** "Email: enter a valid email address" — joined for a one-line reply. */
    private String describe(List<AnswerValidator.FieldError> errors, ExecutionContext context) {
        StringBuilder text = new StringBuilder();
        for (AnswerValidator.FieldError error : errors) {
            if (!text.isEmpty()) {
                text.append(" ");
            }
            String sentence = messages.get(context.resolvedLanguage(), error.messageKey(), error.args());
            // A form-wide failure has no field to name; it reads as a sentence alone.
            text.append(error.label() == null ? sentence : error.label() + ": " + sentence);
        }
        return text.toString();
    }

    /**
     * A value the conversation already established that could answer this step.
     *
     * <p>
     * Opt-in via {@code fillFrom}, naming an entity the intent classifier
     * extracted — {@code inputs.entities.task} for
     * {@code fillFrom: "task"}. Explicit rather than matched by name, because the
     * model chooses its own entity names: asked to complete a task it returned
     * {@code task}, while the step collecting that value is called
     * {@code taskName}. A name-matching version would work often enough to look
     * finished and fail without a reason anyone could see.
     *
     * <p>
     * Only scalars qualify. A structured step wants a whole form filled and a
     * single extracted string is not that, so STRUCTURED is left alone; there is
     * nothing to confirm one field of.
     */
    private Object prefillCandidate(ApiConfig uiConfig, Map<String, Object> inputs) {
        String from = uiConfig.getFillFrom();
        if (from == null || from.isBlank() || "STRUCTURED".equalsIgnoreCase(uiConfig.getInputMode())) {
            return null;
        }
        Object entities = inputs.get("entities");
        if (!(entities instanceof Map<?, ?> map)) {
            return null;
        }
        Object value = map.get(from.trim());
        if (value == null || value instanceof Map || value instanceof Iterable) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** Parks the run on "is this right?" rather than answering for the user. */
    private StepResult offerPrefill(JourneyStep step, ExecutionContext context, ApiConfig uiConfig,
                                    Object candidate, String prefillKey) {
        context.setInternal(prefillKey, candidate);
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

        Map<String, Object> metadata = prepareMetadata(step, uiConfig);
        // A distinct subStatus, not INTERACTIVE's CONFIRMATION_REQUIRED. That one
        // means "here is the form, correct it and submit"; this one means "answer
        // yes or no", and a client that renders a form for it would submit the
        // form's contents as a decision.
        metadata.put("subStatus", "PREFILL_CONFIRMATION");
        metadata.put("prefill", candidate);
        metadata.put("expectedAnswers", Map.of("confirm", "yes", "reject", "no"));

        return StepResult.waiting(
                messages.get(context.resolvedLanguage(), "step.userInput.prefillConfirm", candidate),
                metadata);
    }

    /**
     * Resolves a pending offer, or returns null when the reply was neither
     * yes nor no.
     *
     * <p>
     * That third case is the one worth spelling out: told "Use 'board deck'?" a
     * user may simply type the task they actually meant. Treating that as
     * unclear and re-asking would be maddening, so the caller falls through and
     * takes the reply as the answer — which is what the step would have done had
     * it never offered anything.
     */
    private StepResult settlePrefill(JourneyStep step, ExecutionContext context, ApiConfig uiConfig,
                                     Map<String, Object> inputs, Object answer, Object offered,
                                     String prefillKey) {
        if (answer == null) {
            // Resumed with nothing new — re-offer rather than silently accepting.
            return offerPrefill(step, context, uiConfig, offered, prefillKey);
        }

        Boolean decision = decisionWords.interpret(answer);
        if (decision == null) {
            context.removeInternal(prefillKey);
            return null;
        }

        inputs.remove("answer");
        context.removeInternal(prefillKey);

        if (!decision) {
            // Declined. Ask the author's original question, with no offer this
            // time — repeating one the user just refused is not a conversation.
            context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);
            Map<String, Object> metadata = prepareMetadata(step, uiConfig);
            if ("STRUCTURED".equalsIgnoreCase(uiConfig.getInputMode())) {
                metadata.put("subStatus", "DIRECT_FORM");
            }
            return StepResult.waiting(prompt(step, context), metadata);
        }

        // Agreed. The offered value is stored, never the "yes" that agreed to it.
        variableContext.storeOutput(context, step, offered);
        return StepResult.success(offered, prompt(step, context));
    }

    /** The author's own wording where there is any, the engine's default otherwise. */
    private String prompt(JourneyStep step, ExecutionContext context) {
        return (step.getMessage() != null && !step.getMessage().isEmpty())
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : messages.get(context.resolvedLanguage(), "step.userInput.waiting", step.getStepName());
    }

    // ───────────────────────── one field at a time ─────────────────────────

    /** Engine-internal: the answers collected so far for a form asked field by field. */
    static final String PARTIAL_PREFIX = "userInputPartial_";
    /** Engine-internal: index of the field currently being asked. */
    static final String CURSOR_PREFIX = "userInputCursor_";
    /** What a waiting step publishes while collecting a form one field at a time. */
    static final String SUB_STATUS_FIELD_BY_FIELD = "FIELD_BY_FIELD";

    /**
     * Collects a multi-field form as a sequence of single questions, or returns
     * null when the form has at most one askable field — the existing path
     * already reads a scalar as that field's value.
     *
     * <p>
     * Each answer is validated against its own field with the same rules a form
     * submission would face, so a rejected value re-asks the same question. When
     * the last field is in, the assembled map is stored exactly as a widget's
     * form would have been: downstream steps cannot tell the two apart.
     */
    @SuppressWarnings("unchecked")
    private StepResult askFieldByField(JourneyStep step, ExecutionContext context, ApiConfig uiConfig,
                                       Map<String, Object> inputs, Object answer) {
        List<Map<String, Object>> fields = AnswerValidator.fieldsToAsk(uiConfig.getFields(), uiConfig.getRules());
        if (fields.size() <= 1) {
            return null;
        }
        String partialKey = PARTIAL_PREFIX + step.getStepOrder();
        String cursorKey = CURSOR_PREFIX + step.getStepOrder();
        String attemptsKey = ATTEMPTS_PREFIX + step.getStepOrder();

        Map<String, Object> partial = context.getInternal(partialKey) instanceof Map<?, ?> m
                ? new HashMap<>((Map<String, Object>) m)
                : null;
        int cursor = context.getInternal(cursorKey) instanceof Number n ? n.intValue() : 0;
        if (partial == null) {
            // First entry. Anything already in `answer` was not said in reply to
            // a question of ours, so it is not an answer to the first field.
            partial = new HashMap<>();
            cursor = 0;
            answer = null;
        }

        while (cursor < fields.size()) {
            Map<String, Object> field = fields.get(cursor);
            String name = String.valueOf(field.get("name")).trim();
            if (isFileField(field)) {
                clearFieldByField(context, partialKey, cursorKey, attemptsKey);
                return StepResult.error(
                        "USER_INPUT step '" + step.getStepName() + "' needs a file for field '" + name
                                + "', which this channel cannot carry",
                        messages.get(context.resolvedLanguage(), "step.userInput.fileUnsupported"));
            }
            if (answer == null) {
                return askField(step, context, uiConfig, field, cursor, fields.size(), partial, partialKey,
                        cursorKey, null);
            }

            inputs.remove("answer");
            Object value = matchOption(field, answer);
            boolean blank = value instanceof String text && text.isBlank();
            Map<String, Object> single = new HashMap<>();
            single.put(name, value);
            List<AnswerValidator.FieldError> errors = blank
                    ? List.of()
                    : AnswerValidator.validate(single, List.of(field), uiConfig.getRules());
            if (blank || !errors.isEmpty()) {
                int attempts = (context.getInternal(attemptsKey) instanceof Number n ? n.intValue() : 0) + 1;
                if (attempts >= maxAttempts) {
                    clearFieldByField(context, partialKey, cursorKey, attemptsKey);
                    return StepResult.error(
                            "USER_INPUT step '" + step.getStepName() + "' gave up after " + attempts
                                    + " invalid answers to field '" + name + "': " + describe(errors, context),
                            messages.get(context.resolvedLanguage(), "step.userInput.tooManyAttempts"));
                }
                context.setInternal(attemptsKey, attempts);
                String complaint = blank
                        ? messages.get(context.resolvedLanguage(), "step.userInput.empty")
                        : messages.get(context.resolvedLanguage(), "step.userInput.fixErrors",
                                describe(errors, context));
                return askField(step, context, uiConfig, field, cursor, fields.size(), partial, partialKey,
                        cursorKey, complaint);
            }

            context.removeInternal(attemptsKey);
            partial.put(name, value);
            cursor++;
            answer = null;
        }

        clearFieldByField(context, partialKey, cursorKey, attemptsKey);
        variableContext.storeOutput(context, step, partial);
        return StepResult.success(partial, prompt(step, context));
    }

    /** Parks the run on one field, remembering what has been collected so far. */
    private StepResult askField(JourneyStep step, ExecutionContext context, ApiConfig uiConfig,
                                Map<String, Object> field, int cursor, int total, Map<String, Object> partial,
                                String partialKey, String cursorKey, String complaint) {
        context.setInternal(partialKey, partial);
        context.setInternal(cursorKey, cursor);
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

        String label = field.get("label") != null && !String.valueOf(field.get("label")).isBlank()
                ? String.valueOf(field.get("label")).trim()
                : String.valueOf(field.get("name")).trim();
        StringBuilder question = new StringBuilder();
        if (complaint != null) {
            question.append(complaint).append(' ');
        } else if (cursor == 0 && partial.isEmpty() && step.getMessage() != null && !step.getMessage().isEmpty()) {
            // The author's own framing of the form, once, before the first field.
            question.append(engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())).append(' ');
        }
        question.append(messages.get(context.resolvedLanguage(), "step.userInput.fieldOf", cursor + 1, total, label));
        String options = optionLabels(field);
        if (!options.isEmpty()) {
            question.append(' ').append(messages.get(context.resolvedLanguage(), "step.userInput.fieldOptions", options));
        }

        Map<String, Object> metadata = prepareMetadata(step, uiConfig);
        metadata.put("subStatus", SUB_STATUS_FIELD_BY_FIELD);
        metadata.put("fieldIndex", cursor);
        metadata.put("fieldCount", total);
        Map<String, Object> current = new HashMap<>();
        current.put("name", field.get("name"));
        current.put("label", label);
        current.put("type", field.get("type"));
        metadata.put("field", current);
        return StepResult.waiting(question.toString().trim(), metadata);
    }

    private void clearFieldByField(ExecutionContext context, String partialKey, String cursorKey,
                                   String attemptsKey) {
        context.removeInternal(partialKey);
        context.removeInternal(cursorKey);
        context.removeInternal(attemptsKey);
    }

    private static boolean isFileField(Map<String, Object> field) {
        String type = field.get("type") == null ? "" : String.valueOf(field.get("type")).toLowerCase(java.util.Locale.ROOT);
        return type.equals("file") || type.equals("files") || type.equals("attachment") || type.equals("image");
    }

    /**
     * A spoken or typed answer to a choice field matched against the choices:
     * the label or the value, case-insensitively. Anything else is kept as
     * said, and validation decides.
     */
    private static Object matchOption(Map<String, Object> field, Object answer) {
        if (!(answer instanceof String said) || !(field.get("options") instanceof Iterable<?> options)) {
            return answer;
        }
        String wanted = said.trim().toLowerCase(java.util.Locale.ROOT);
        for (Object option : options) {
            if (!(option instanceof Map<?, ?> choice)) {
                continue;
            }
            String label = choice.get("label") == null ? "" : String.valueOf(choice.get("label")).trim();
            String value = choice.get("value") == null ? "" : String.valueOf(choice.get("value")).trim();
            if (wanted.equals(label.toLowerCase(java.util.Locale.ROOT))
                    || wanted.equals(value.toLowerCase(java.util.Locale.ROOT))) {
                return value.isEmpty() ? label : value;
            }
        }
        return answer;
    }

    private static String optionLabels(Map<String, Object> field) {
        if (!(field.get("options") instanceof Iterable<?> options)) {
            return "";
        }
        List<String> labels = new java.util.ArrayList<>();
        for (Object option : options) {
            if (option instanceof Map<?, ?> choice && choice.get("label") != null) {
                labels.add(String.valueOf(choice.get("label")).trim());
            }
        }
        return String.join(", ", labels);
    }

    private Map<String, Object> prepareMetadata(JourneyStep step, ApiConfig uiConfig) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("stepName", step.getStepName());
        metadata.put("inputMode", uiConfig.getInputMode());
        metadata.put("formConfig", Map.of(
                "fields", uiConfig.getFields() != null ? uiConfig.getFields() : new Object[] {},
                "rules", uiConfig.getRules() != null ? uiConfig.getRules() : new Object[] {}));
        metadata.put("allowResubmit", uiConfig.isAllowResubmit());
        return metadata;
    }

}
