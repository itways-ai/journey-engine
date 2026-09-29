package com.itways.assistant.journey.engine.impl;

import static com.itways.assistant.journey.engine.impl.EngineFixture.journey;
import static com.itways.assistant.journey.engine.impl.EngineFixture.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.itways.assistant.journey.model.JourneyDefinition;
import com.itways.assistant.journey.model.RunHistoryEvent;
import com.itways.assistant.journey.model.RunStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle event a port receives is what run history stores: the run's
 * identity, its times as millisecond instants, and its variables and step
 * results as {@code contextData} JSON.
 */
class JourneyEngineRunHistoryEventTest {

    private final EngineFixture f = new EngineFixture();

    private RunHistoryEvent event(RunStatus status) {
        return f.events.stream().filter(e -> e.status() == status).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void aTopLevelRunIsItsOwnRootAndCarriesTheJourneysIdentity() {
        JourneyDefinition journey = journey("ORDER", step(1, "RECORD"));

        Map<String, Object> result = f.start(journey, Map.of());

        RunHistoryEvent running = event(RunStatus.RUNNING);
        assertThat(running.executionId()).isEqualTo(result.get("executionId"));
        assertThat(running.rootExecutionId()).isEqualTo(running.executionId());
        assertThat(running.parentExecutionId()).isNull();
        assertThat(running.journeyId()).isEqualTo(7L);
        assertThat(running.triggerIntent()).isEqualTo("ORDER");
        assertThat(running.stepLogs()).isEmpty();
        assertThat(running.startedAt()).isNotNull();
        assertThat(running.startedAt().getNano() % 1_000_000).isZero();
    }

    @Test
    void contextDataHoldsTheVariablesAndTheStepResultsByOrder() throws Exception {
        JourneyDefinition journey = journey("ORDER", step(1, "RECORD"), step(2, "RECORD"));

        f.start(journey, Map.of("orderId", "A-17"));

        RunHistoryEvent completed = event(RunStatus.COMPLETED);
        JsonNode context = f.json.readTree(completed.contextData());
        assertThat(context.fieldNames()).toIterable().containsExactlyInAnyOrder("variables", "stepResults");
        assertThat(context.at("/variables/inputs/entities/orderId").asText()).isEqualTo("A-17");
        assertThat(context.at("/stepResults/1").asText()).isEqualTo("s1");
        assertThat(context.at("/stepResults/2").asText()).isEqualTo("s2");
        assertThat(completed.stepLogs()).extracting(log -> log.stepName()).containsExactly("s1", "s2");
        assertThat(completed.completedAt().getNano() % 1_000_000).isZero();
        assertThat(completed.durationMs()).isNotNull().isNotNegative();
    }

    @Test
    void theEndUserIdComesFromTheExtractedEntities() {
        f.start(journey("ORDER", step(1, "RECORD")), Map.of("userId", "u-9"));

        assertThat(event(RunStatus.COMPLETED).userId()).isEqualTo("u-9");
    }

    @Test
    void aRehearsalEmitsNoEvents() {
        f.start(journey("ORDER", step(1, "RECORD")), Map.of("simulate", true));

        assertThat(f.events).isEmpty();
        assertThat(f.executed).isEqualTo(List.of("s1"));
    }
}
