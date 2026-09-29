package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.model.StepStatus;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Hands the conversation to a human and stops the journey.
 *
 * <p>
 * The platform had no escalation path at all: a journey that could not help
 * either failed or kept asking. For an assistant answering customers that is
 * the one exit every real deployment needs, so it is a step type rather than
 * something each author reinvents with a RESPONSE and a prayer.
 *
 * <p>
 * <b>What this step does and does not do.</b> It ends the run cleanly, tells
 * the user a person is coming, and publishes {@code handoff} metadata carrying
 * the queue and a context note. It does not itself deliver anything to an
 * agent: the platform has no agent desk and no account-level routing, and
 * inventing a half-connected one here would be worse than an honest hand-off
 * point. The host acts on the metadata — the same way the Web SDK acts on
 * REDIRECT's — and the run history records where and why the escalation
 * happened.
 *
 * <p>
 * Ending the run matters as much as the message. A bot that says "a colleague
 * will take over" and then asks its next scripted question has not handed
 * anything over.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandoffStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final EngineMessages messages;

    @Override
    public String getType() {
        return "HANDOFF";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.handoffDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.handoffSchema();
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());

        String queue = trimmed(config.getQueue());
        // The note is where the author explains the case to whoever picks it up,
        // so it interpolates: "{{inputs.entities.orderId}} disputed twice" is
        // worth more to an agent than the step name.
        String note = config.getNote() == null || config.getNote().isBlank()
                ? null
                : engineUtils.replacePlaceholders(config.getNote(), context.getVariables());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("handedOff", true);
        output.put("queue", queue);
        output.put("note", note);
        variableContext.storeOutput(context, step, output);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("handoff", true);
        metadata.put("queue", queue);
        metadata.put("note", note);
        metadata.put("subStatus", "HANDED_OFF");
        if (config.getTimeoutMinutes() != null) {
            metadata.put("timeoutMinutes", config.getTimeoutMinutes());
        }

        String message = step.getMessage() != null && !step.getMessage().isBlank()
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : messages.get(context.resolvedLanguage(), "step.handoff.waiting");

        log.info("HANDOFF step '{}' escalating run {} to queue '{}'",
                step.getStepName(), context.getExecutionId(), queue == null ? "default" : queue);

        // Ends the run. COMPLETED rather than WAITING_FOR_INPUT on purpose: a
        // parked run would swallow the user's next message back into this
        // journey, which is precisely what must not happen once a person is
        // taking over. The engine reports FINISHED and stops the step loop.
        context.setStatus(ExecutionStatus.COMPLETED);

        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(output)
                .message(message)
                .metadata(metadata)
                .build();
    }

    private static String trimmed(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : value;
    }
}
