package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DelayStepHandler implements StepHandler {

    /** Engine-internal marker: absolute instant this delay may clear. */
    static final String DEADLINE_PREFIX = "delayDeadline_";

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "DELAY";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.delayDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("DELAY", "Delay Result");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
        Integer duration = config.getDuration() != null ? config.getDuration() : 5;
        String unit = config.getUnit() != null ? config.getUnit().toUpperCase() : "SECONDS";

        // A "not before" gate rather than an active timer: the platform has no
        // scheduler, so the step clears itself the first time the journey is
        // resumed after the deadline. It previously waited on a flag that nothing
        // in the platform ever wrote, which made every DELAY a permanent dead end.
        String deadlineKey = DEADLINE_PREFIX + step.getStepOrder();
        Object storedDeadline = context.getInternal(deadlineKey);

        if (storedDeadline != null) {
            Instant deadline = parseDeadline(storedDeadline);
            // resumeOnEvent: re-entry means something resumed this run — typically an
            // incoming user message — which the author asked to cut the wait short.
            boolean interruptedByEvent = Boolean.TRUE.equals(config.getResumeOnEvent());
            if (interruptedByEvent || deadline == null || !Instant.now().isBefore(deadline)) {
                context.removeInternal(deadlineKey);
                Map<String, Object> result = Map.of("waited", duration, "unit", unit);
                variableContext.storeOutput(context, step, result);
                return StepResult.success(result, "Delay completed.");
            }
            return stillWaiting(step, context, duration, unit, deadline);
        }

        Instant deadline = Instant.now().plus(toDuration(duration, unit));
        context.setInternal(deadlineKey, deadline.toString());
        return stillWaiting(step, context, duration, unit, deadline);
    }

    private StepResult stillWaiting(JourneyStep step, ExecutionContext context,
                                    Integer duration, String unit, Instant deadline) {
        context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "TEMPORAL_PAUSE");
        metadata.put("resumeAt", deadline.toString());
        metadata.put("duration", duration);
        metadata.put("unit", unit);

        String prompt = (step.getMessage() != null && !step.getMessage().isEmpty())
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : "Sequence paused for " + duration + " " + unit;

        return StepResult.waiting(prompt, metadata);
    }

    private static Duration toDuration(Integer duration, String unit) {
        return switch (unit) {
            case "MINUTES" -> Duration.ofMinutes(duration);
            case "HOURS" -> Duration.ofHours(duration);
            default -> Duration.ofSeconds(duration);
        };
    }

    /** A deadline we cannot parse must not trap the run, so treat it as elapsed. */
    private static Instant parseDeadline(Object raw) {
        try {
            return Instant.parse(String.valueOf(raw));
        } catch (Exception e) {
            log.warn("DELAY: unreadable deadline '{}' — treating the delay as elapsed", raw);
            return null;
        }
    }

}
