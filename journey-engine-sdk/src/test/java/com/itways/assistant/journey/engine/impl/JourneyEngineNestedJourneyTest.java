package com.itways.assistant.journey.engine.impl;

import static com.itways.assistant.journey.engine.impl.EngineFixture.context;
import static com.itways.assistant.journey.engine.impl.EngineFixture.step;
import static com.itways.assistant.journey.engine.impl.EngineFixture.trace;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.engine.handler.TriggerJourneyStepHandler;
import com.itways.assistant.journey.engine.service.JourneyLookupPort;
import com.itways.assistant.journey.model.JourneyDefinition;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.RunHistoryEvent;
import com.itways.assistant.journey.model.RunStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * TRIGGER_JOURNEY through the real engine: the child runs as its own execution
 * linked to the parent, a question inside the child parks the parent, the
 * answer resumes the child, and the parent carries on with the child's output.
 */
class JourneyEngineNestedJourneyTest {

    private final EngineFixture f = new EngineFixture();
    private final Map<String, JourneyDefinition> byIntent = new HashMap<>();
    private final Map<Long, JourneyDefinition> byVersion = new HashMap<>();
    private final List<UUID> lookedUpFor = new ArrayList<>();

    JourneyEngineNestedJourneyTest() {
        JourneyLookupPort lookup = new JourneyLookupPort() {
            @Override
            public JourneyDefinition findByTriggerIntent(String accountId, UUID assistantId, String intent) {
                lookedUpFor.add(assistantId);
                return byIntent.get(intent);
            }

            @Override
            public JourneyDefinition findByVersionId(String accountId, Long versionId) {
                return byVersion.get(versionId);
            }
        };
        f.with(new TriggerJourneyStepHandler(lookup, f.utils, f.variables, f.schemas, f.engine, f.messages));
    }

    private void publish(long id, String intent, JourneyStep.JourneyStepBuilder... steps) {
        List<JourneyStep> built = new ArrayList<>();
        for (JourneyStep.JourneyStepBuilder step : steps) {
            built.add(step.build());
        }
        JourneyDefinition journey = JourneyDefinition.builder().id(id).versionId(id * 10).name(intent)
                .triggerIntent(intent).steps(built).build();
        byIntent.put(intent, journey);
        byVersion.put(id * 10, journey);
    }

    private List<RunHistoryEvent> eventsOf(Object executionId) {
        return f.events.stream().filter(e -> e.executionId().equals(executionId)).toList();
    }

    private void orderJourneys() {
        publish(2, "ORDER_STATUS",
                step(1, "USER_INPUT").message("Which order, {{inputs.entities.name}}?"),
                step(2, "RESPONSE").actionTarget("Order {{steps.1.output}} ships today"));
        publish(1, "MAIN",
                step(1, "RESPONSE").actionTarget("Let me check."),
                step(2, "TRIGGER_JOURNEY").actionTarget("ORDER_STATUS"),
                step(3, "RESPONSE").actionTarget("{{steps.2.output}}. Anything else?"));
    }

    @Test
    void aQuestionInsideTheChildParksTheParentAndTheAnswerResumesBoth() {
        orderJourneys();
        UUID assistant = UUID.randomUUID();
        JourneyDefinition main = byIntent.get("MAIN");

        Map<String, Object> first = f.engine.start(main, "acc-1", assistant, Map.of("name", "Sara"));

        assertThat(first.get("status")).isEqualTo("WAITING");
        assertThat(first.get("message")).isEqualTo("Which order, Sara?");
        // The child's own step views, not a view for the trigger step itself.
        assertThat(trace(first)).containsExactly("RESPONSE:SUCCESS", "USER_INPUT:WAITING");
        assertThat(lookedUpFor).containsExactly(assistant);

        String parentId = (String) first.get("executionId");
        RunHistoryEvent childRunning = f.events.stream()
                .filter(e -> parentId.equals(e.parentExecutionId())).findFirst().orElseThrow();
        String childId = childRunning.executionId();
        assertThat(childId).isNotEqualTo(parentId);
        assertThat(childRunning.rootExecutionId()).isEqualTo(parentId);
        assertThat(eventsOf(childId)).extracting(RunHistoryEvent::status)
                .containsExactly(RunStatus.RUNNING, RunStatus.WAITING);
        assertThat(eventsOf(parentId)).extracting(RunHistoryEvent::status)
                .containsExactly(RunStatus.RUNNING, RunStatus.WAITING);

        Map<String, Object> second = f.engine.resume(main, context(first), Map.of("answer", "A-17"));

        assertThat(second.get("status")).isEqualTo("FINISHED");
        assertThat(second.get("message")).isEqualTo("Order A-17 ships today. Anything else?");
        assertThat(trace(second)).containsExactly("USER_INPUT:SUCCESS", "RESPONSE:SUCCESS", "TRIGGER_JOURNEY:SUCCESS",
                "RESPONSE:SUCCESS");
        assertThat(eventsOf(childId)).extracting(RunHistoryEvent::status)
                .containsExactly(RunStatus.RUNNING, RunStatus.WAITING, RunStatus.COMPLETED);
        assertThat(eventsOf(parentId)).extracting(RunHistoryEvent::status)
                .containsExactly(RunStatus.RUNNING, RunStatus.WAITING, RunStatus.COMPLETED);
        assertThat(context(first).getInternal(TriggerJourneyStepHandler.ACTIVE_TRIGGERED_JOURNEY)).isNull();
    }

    /** The child starts with the parent's inputs but none of its step outputs or state. */
    @Test
    void theChildSeesTheParentsInputsButNotItsSteps() {
        publish(2, "PEEK", step(1, "RESPONSE").actionTarget("[{{inputs.entities.name}}|{{steps.1.output}}|{{state.secret}}]"));
        publish(1, "MAIN",
                step(1, "STATE_STORE").apiConfig("{\"variable\":\"secret\",\"source\":\"s3cr3t\"}"),
                step(2, "TRIGGER_JOURNEY").actionTarget("PEEK"));

        Map<String, Object> result = f.engine.start(byIntent.get("MAIN"), "acc-1", null, Map.of("name", "Sara"));

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(context(result).getStepResults().get(2)).isEqualTo("[Sara||]");
    }

    @Test
    void aFailingChildFailsTheParent() {
        f.with(EngineFixture.stub("FAIL", (step, context) -> com.itways.assistant.journey.engine.model.StepResult
                .error("upstream down")));
        publish(2, "BROKEN", step(1, "FAIL"));
        publish(1, "MAIN", step(1, "TRIGGER_JOURNEY").actionTarget("BROKEN"), step(2, "RECORD"));

        Map<String, Object> result = f.engine.start(byIntent.get("MAIN"), "acc-1", null, Map.of());

        assertThat(result.get("status")).isEqualTo("ERROR");
        assertThat(f.executed).isEmpty();
        assertThat(trace(result)).containsExactly("FAIL:ERROR", "TRIGGER_JOURNEY:ERROR");
    }

    /** A journey that triggers itself, directly or through another, is stopped at the repeat. */
    @Test
    void aTriggerCycleIsRefused() {
        publish(2, "B", step(1, "TRIGGER_JOURNEY").actionTarget("A"));
        publish(1, "A", step(1, "TRIGGER_JOURNEY").actionTarget("B"));

        Map<String, Object> result = f.engine.start(byIntent.get("A"), "acc-1", null, Map.of());

        assertThat(result.get("status")).isEqualTo("ERROR");
        assertThat(EngineFixture.views(result).get(0).get("message")).asString()
                .contains("cannot trigger 'A'").contains("already on the call stack");
    }
}
