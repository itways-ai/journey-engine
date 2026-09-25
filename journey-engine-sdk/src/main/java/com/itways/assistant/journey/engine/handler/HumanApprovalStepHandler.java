package com.itways.assistant.journey.engine.handler;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class HumanApprovalStepHandler implements StepHandler {

    /** Engine-internal marker: absolute instant this approval stops accepting a decision. */
    static final String DEADLINE_PREFIX = "approvalDeadline_";

    private static final String MODE_STAKEHOLDER = "STAKEHOLDER";
    private static final int DEFAULT_TIMEOUT_HOURS = 24;

    /**
     * Reads a decision out of whatever the person typed.
     *
     * <p>
     * The vocabulary used to live here, and moved to {@link DecisionWords} when
     * USER_INPUT's pre-fill confirmation needed the same question answered. The
     * behaviour is unchanged, including the part that matters: anything outside
     * the known words is an unclear reply and re-prompts, because this step once
     * accepted <em>any</em> non-null answer as approval and "no" granted the
     * approval it was refusing.
     */
    private final DecisionWords decisionWords;

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final EngineMessages messages;

    @Override
    public String getType() {
        return "HUMAN_APPROVAL";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.humanApprovalDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("HUMAN_APPROVAL", "Approval Decision", "boolean");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
        String deadlineKey = DEADLINE_PREFIX + step.getStepOrder();
        Map<String, Object> inputs = variableContext.getInputs(context);
        Object answer = inputs.get("answer");

        if (answer != null) {
            inputs.remove("answer");
            Boolean decision = interpret(answer);

            if (decision == null) {
                // Consumed but unusable. Ask again rather than guessing, and leave the
                // deadline running so an endless stream of unclear replies still expires.
                return awaitDecision(step, context, config, deadlineKey,
                        messages.get(context.resolvedLanguage(), "step.approval.unclear"));
            }

            context.removeInternal(deadlineKey);
            variableContext.storeOutput(context, step, decision);
            log.info("HUMAN_APPROVAL step '{}' resolved: {}", step.getStepName(), decision ? "APPROVED" : "REJECTED");

            if (decision) {
                return StepResult.success(Boolean.TRUE,
                        messages.get(context.resolvedLanguage(), "step.approval.granted"));
            }
            // A refusal must stop the run. Returning success here let the engine
            // walk straight into the step the gate was protecting, so answering
            // "reject" performed the very action being refused — the opposite of
            // what a step named HUMAN_APPROVAL promises.
            //
            // The decision is still stored as this step's output before we return,
            // so a journey that wants an explicit rejection path can branch on it;
            // the default, though, is now to halt.
            return StepResult.error("Human approval rejected.",
                    messages.get(context.resolvedLanguage(), "step.approval.rejected"));
        }

        // No answer this pass. An elapsed deadline auto-rejects: nothing should be
        // approved by inaction. The platform has no scheduler, so this can only be
        // noticed the next time the run is resumed (same gate DELAY uses).
        Instant deadline = readDeadline(context.getInternal(deadlineKey));
        if (deadline != null && !Instant.now().isBefore(deadline)) {
            context.removeInternal(deadlineKey);
            variableContext.storeOutput(context, step, Boolean.FALSE);
            log.info("HUMAN_APPROVAL step '{}' auto-rejected: deadline {} elapsed.",
                    step.getStepName(), deadline);
            // Same reasoning as an explicit refusal: an expired gate must not let
            // the protected step run. Nothing is approved by inaction.
            return StepResult.error("Human approval timed out and was automatically rejected.",
                    messages.get(context.resolvedLanguage(), "step.approval.timedOut"));
        }

        return awaitDecision(step, context, config, deadlineKey, null);
    }

    /** Parks the run until a decision arrives, opening the timeout window on first entry. */
    private StepResult awaitDecision(JourneyStep step, ExecutionContext context, ApiConfig config,
                                     String deadlineKey, String overridePrompt) {
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

        if (context.getInternal(deadlineKey) == null) {
            int hours = config.getTimeout() != null && config.getTimeout() > 0
                    ? config.getTimeout()
                    : DEFAULT_TIMEOUT_HOURS;
            context.setInternal(deadlineKey, Instant.now().plus(Duration.ofHours(hours)).toString());
        }

        boolean stakeholderMode = MODE_STAKEHOLDER.equalsIgnoreCase(config.getApprovalMode());
        String stakeholders = engineUtils.replacePlaceholders(
                config.getStakeholders() != null ? config.getStakeholders() : "", context.getVariables());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "HUMAN_GOVERNANCE");
        // approvalMode was collected by the builder and never read. The client needs
        // it to tell "confirm this yourself" apart from "someone else must sign off".
        metadata.put("approvalMode", stakeholderMode ? MODE_STAKEHOLDER : "SELF_CONFIRM");
        metadata.put("instruction", engineUtils.replacePlaceholders(
                config.getInstruction() != null ? config.getInstruction() : "", context.getVariables()));
        metadata.put("awaiting", true);
        metadata.put("expectedAnswers", Map.of("approve", "approve", "reject", "reject"));
        metadata.put("resumeBy", context.getInternal(deadlineKey));
        if (stakeholderMode) {
            metadata.put("stakeholders", stakeholders);
        }

        String prompt = overridePrompt;
        if (prompt == null) {
            prompt = (step.getMessage() != null && !step.getMessage().isEmpty())
                    ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                    : defaultPrompt(context, stakeholderMode, stakeholders);
        }

        return StepResult.waiting(prompt, metadata);
    }

    private String defaultPrompt(ExecutionContext context, boolean stakeholderMode, String stakeholders) {
        if (stakeholderMode && stakeholders != null && !stakeholders.isBlank()) {
            return messages.get(context.resolvedLanguage(), "step.approval.promptStakeholder", stakeholders);
        }
        return messages.get(context.resolvedLanguage(), "step.approval.prompt");
    }

    /**
     * Maps an answer onto a decision, or null when it is neither. Booleans arrive
     * from structured form replies; everything else is free text from a channel.
     */
    private Boolean interpret(Object answer) {
        return decisionWords.interpret(answer);
    }

    /** An unreadable deadline must not trap the run, so treat it as not yet set. */
    private static Instant readDeadline(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(raw));
        } catch (Exception e) {
            log.warn("HUMAN_APPROVAL: unreadable deadline '{}' — ignoring the timeout window", raw);
            return null;
        }
    }
}
