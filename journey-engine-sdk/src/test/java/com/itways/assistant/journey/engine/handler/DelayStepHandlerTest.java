package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/**
 * DELAY is a "not before" gate: the first pass parks the run and records a
 * deadline; a later resume clears it once the deadline has passed (or at once,
 * with resumeOnEvent).
 */
class DelayStepHandlerTest {

    private static final String KEY = DelayStepHandler.DEADLINE_PREFIX + 3;

    private final DelayStepHandler handler = new DelayStepHandler(UTILS, VARIABLES, SCHEMAS);

    private static JourneyStep step(String apiConfig, String message) {
        return JourneyStep.builder().stepOrder(3).stepName("wait").actionType("DELAY").apiConfig(apiConfig)
                .message(message).build();
    }

    @Test
    void theFirstPassParksTheRunUntilTheDeadline() {
        ExecutionContext context = run();
        Instant before = Instant.now();

        StepResult result = handler.execute(step("{\"duration\":2,\"unit\":\"minutes\"}", null), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(context.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
        assertThat(result.getMessage()).isEqualTo("Sequence paused for 2 MINUTES");
        assertThat(result.getMetadata()).containsEntry("type", "TEMPORAL_PAUSE").containsEntry("unit", "MINUTES")
                .containsEntry("duration", 2);
        Instant deadline = Instant.parse((String) context.getInternal(KEY));
        assertThat(deadline).isCloseTo(before.plus(Duration.ofMinutes(2)), within(5, ChronoUnit.SECONDS));
        assertThat(result.getMetadata()).containsEntry("resumeAt", deadline.toString());
    }

    @Test
    void theDefaultIsFiveSecondsAndTheMessageInterpolates() {
        ExecutionContext context = run(Map.of("name", "Sara"));

        StepResult result = handler.execute(step(null, "Hold on, {{inputs.entities.name}}"), context);

        assertThat(result.getMessage()).isEqualTo("Hold on, Sara");
        assertThat(result.getMetadata()).containsEntry("duration", 5).containsEntry("unit", "SECONDS");
    }

    @Test
    void aResumeBeforeTheDeadlineKeepsWaiting() {
        ExecutionContext context = run();
        handler.execute(step("{\"duration\":1,\"unit\":\"HOURS\"}", null), context);
        Object deadline = context.getInternal(KEY);

        StepResult again = handler.execute(step("{\"duration\":1,\"unit\":\"HOURS\"}", null), context);

        assertThat(again.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(context.getInternal(KEY)).isEqualTo(deadline);
    }

    @Test
    void aResumeAfterTheDeadlineCompletesTheStep() {
        ExecutionContext context = run();
        context.setInternal(KEY, Instant.now().minusSeconds(1).toString());

        StepResult result = handler.execute(step("{\"duration\":10}", null), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getData()).isEqualTo(Map.of("waited", 10, "unit", "SECONDS"));
        assertThat(output(context, 3)).isEqualTo(Map.of("waited", 10, "unit", "SECONDS"));
        assertThat(context.getInternal(KEY)).isNull();
    }

    @Test
    void resumeOnEventCutsTheWaitShort() {
        ExecutionContext context = run();
        String config = "{\"duration\":1,\"unit\":\"HOURS\",\"resumeOnEvent\":true}";
        assertThat(handler.execute(step(config, null), context).getStatus()).isEqualTo(StepStatus.WAITING);

        assertThat(handler.execute(step(config, null), context).getStatus()).isEqualTo(StepStatus.SUCCESS);
    }

    /** A deadline that cannot be read must not trap the run for good. */
    @Test
    void anUnreadableDeadlineCountsAsElapsed() {
        ExecutionContext context = run();
        context.setInternal(KEY, "not-a-time");

        assertThat(handler.execute(step("{\"duration\":1,\"unit\":\"HOURS\"}", null), context).getStatus())
                .isEqualTo(StepStatus.SUCCESS);
    }
}
