package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.MESSAGES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.answer;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.context.EndUserAuth;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.JourneyEngine;
import com.itways.assistant.journey.engine.service.JourneyLookupPort;
import com.itways.assistant.journey.engine.service.StepObserver;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyDefinition;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/**
 * TRIGGER_JOURNEY in isolation: a stand-in engine records what the handler asks
 * of it. The real nested run (parent and child through the real engine) is in
 * JourneyEngineNestedJourneyTest.
 */
class TriggerJourneyStepHandlerTest {

    private static final UUID ASSISTANT = UUID.randomUUID();
    private static final JourneyDefinition CHILD = JourneyDefinition.builder().id(9L).versionId(90L).name("child")
            .triggerIntent("ORDER_STATUS").steps(List.of()).build();

    /** Records start/resume calls and answers them with a scripted result. */
    private static final class RecordingEngine implements JourneyEngine {
        final List<Map<String, Object>> startParams = new ArrayList<>();
        final List<Map<String, Object>> resumeParams = new ArrayList<>();
        final List<UUID> assistants = new ArrayList<>();
        Map<String, Object> next;

        @Override
        public Map<String, Object> start(JourneyDefinition journey, String accountId, UUID assistantId,
                Map<String, Object> initialParams) {
            startParams.add(initialParams);
            assistants.add(assistantId);
            return next;
        }

        @Override
        public Map<String, Object> start(JourneyDefinition journey, String accountId, UUID assistantId,
                Map<String, Object> initialParams, StepObserver observer) {
            return start(journey, accountId, assistantId, initialParams);
        }

        @Override
        public Map<String, Object> resume(JourneyDefinition journey, ExecutionContext context,
                Map<String, Object> inputParams) {
            resumeParams.add(inputParams);
            return next;
        }

        @Override
        public Map<String, Object> resume(JourneyDefinition journey, ExecutionContext context,
                Map<String, Object> inputParams, StepObserver observer) {
            return resume(journey, context, inputParams);
        }
    }

    private final RecordingEngine engine = new RecordingEngine();
    private JourneyDefinition byIntent = CHILD;
    private JourneyDefinition byVersion = CHILD;
    private final JourneyLookupPort lookup = new JourneyLookupPort() {
        @Override
        public JourneyDefinition findByTriggerIntent(String accountId, UUID assistantId, String intent) {
            return intent.equals("ORDER_STATUS") ? byIntent : null;
        }

        @Override
        public JourneyDefinition findByVersionId(String accountId, Long versionId) {
            return byVersion;
        }
    };
    private final TriggerJourneyStepHandler handler = new TriggerJourneyStepHandler(lookup, UTILS, VARIABLES, SCHEMAS,
            engine, MESSAGES);

    private static JourneyStep step(String target) {
        return JourneyStep.builder().stepOrder(3).stepName("sub").actionType("TRIGGER_JOURNEY").actionTarget(target)
                .build();
    }

    private static ExecutionContext parent() {
        ExecutionContext context = run(Map.of("intent", "ORDER_STATUS", "orderId", "A-17"));
        context.setAssistantId(ASSISTANT);
        context.setRootExecutionId("root-1");
        context.setInternal(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK, new ArrayList<>(List.of("MAIN")));
        VARIABLES.writeStepOutput(context, JourneyStep.builder().stepOrder(1).build(), "parent-only");
        return context;
    }

    private static Map<String, Object> childResult(String status, String message, Map<Integer, Object> stepResults) {
        ExecutionContext child = ExecutionContext.builder().executionId("child-1").journeyId(9L)
                .status(ExecutionStatus.RUNNING).stepResults(new HashMap<>(stepResults)).build();
        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("message", message);
        result.put("context", child);
        result.put("stepResults", List.of(Map.of("type", "RESPONSE", "status", "SUCCESS")));
        return result;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFinishedChildHandsBackItsLastOutputAndGetsAnIsolatedCopyOfTheVariables() {
        engine.next = childResult("FINISHED", "done", Map.of(1, "first", 2, Map.of("eta", "today")));
        ExecutionContext context = parent();
        context.setInternal(EndUserAuth.INTERNAL_USER_TOKEN, "user-jwt");
        answer(context, "leftover");

        StepResult result = handler.execute(step("{{inputs.entities.intent}}"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getData()).isEqualTo(Map.of("eta", "today"));
        assertThat(output(context, 3)).isEqualTo(Map.of("eta", "today"));
        assertThat(result.getMessage()).isEqualTo("Triggered journey: ORDER_STATUS");
        assertThat(result.getMetadata().get(TriggerJourneyStepHandler.META_NESTED_STEP_RESULTS)).asList().hasSize(1);

        Map<String, Object> params = engine.startParams.get(0);
        assertThat(params.get(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK)).isEqualTo(List.of("MAIN", "ORDER_STATUS"));
        assertThat(params).containsEntry(TriggerJourneyStepHandler.PARENT_EXECUTION_ID, "exec-1")
                .containsEntry(TriggerJourneyStepHandler.ROOT_EXECUTION_ID, "root-1")
                .containsEntry(EndUserAuth.PARAM_USER_TOKEN, "user-jwt");
        assertThat((Map<String, Object>) params.get("steps")).isEmpty();
        Map<String, Object> inputs = (Map<String, Object>) params.get("inputs");
        assertThat(inputs).doesNotContainKey("answer");
        assertThat((Map<String, Object>) inputs.get("entities")).containsEntry("orderId", "A-17");
        assertThat(engine.assistants).containsExactly(ASSISTANT);
    }

    @Test
    void aWaitingChildParksTheParentPinnedToTheChildsVersion() {
        engine.next = childResult("WAITING", "Which order?", Map.of());
        ExecutionContext context = parent();

        StepResult result = handler.execute(step("ORDER_STATUS"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(result.getMessage()).isEqualTo("Which order?");
        assertThat(result.getMetadata()).containsEntry(TriggerJourneyStepHandler.META_SKIP_SELF_VIEW, true);
        assertThat(context.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
        Map<?, ?> active = (Map<?, ?>) context.getInternal(TriggerJourneyStepHandler.ACTIVE_TRIGGERED_JOURNEY);
        assertThat(active.get("versionId")).isEqualTo(90L);
        assertThat(active.get("executionId")).isEqualTo("child-1");

        // Next turn: the parked child is resumed with this turn's input, not restarted.
        context.setInternal(TriggerJourneyStepHandler.PENDING_RESUME_INPUT, Map.of("answer", "A-17"));
        engine.next = childResult("FINISHED", "Arriving today", Map.of(1, "A-17"));

        StepResult resumed = handler.execute(step("ORDER_STATUS"), context);

        assertThat(resumed.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(engine.startParams).hasSize(1);
        assertThat(engine.resumeParams).singleElement().satisfies(p -> assertThat(p).containsEntry("answer", "A-17"));
        assertThat(context.getInternal(TriggerJourneyStepHandler.ACTIVE_TRIGGERED_JOURNEY)).isNull();
        assertThat(context.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
    }

    /** A publish between the child's question and the answer must not rewire the answer. */
    @Test
    void aParkedChildWhoseVersionIsGoneEndsTheStep() {
        engine.next = childResult("WAITING", "Which order?", Map.of());
        ExecutionContext context = parent();
        handler.execute(step("ORDER_STATUS"), context);
        byVersion = null;

        StepResult result = handler.execute(step("ORDER_STATUS"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getUserMessage()).startsWith("This conversation can no longer continue");
        assertThat(context.getInternal(TriggerJourneyStepHandler.ACTIVE_TRIGGERED_JOURNEY)).isNull();
        assertThat(engine.resumeParams).isEmpty();
    }

    @Test
    void aFailedChildFailsTheStepWithTheChildsMessage() {
        engine.next = childResult("ERROR", "Order service is down", Map.of());

        StepResult result = handler.execute(step("ORDER_STATUS"), parent());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("Order service is down");
        assertThat(result.getMetadata()).containsKey(TriggerJourneyStepHandler.META_NESTED_STEP_RESULTS);
    }

    @Test
    void noIntentOrAnUnknownOneIsAnError() {
        assertThat(handler.execute(step("  "), parent()).getMessage()).contains("actionTarget (journey intent) is required");
        assertThat(handler.execute(step("NOPE"), parent()).getMessage())
                .isEqualTo("TRIGGER_JOURNEY: journey not found for intent 'NOPE'");
        assertThat(engine.startParams).isEmpty();
    }

    @Test
    void aJourneyAlreadyOnTheCallStackIsNotTriggeredAgain() {
        ExecutionContext context = parent();
        context.setInternal(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK, List.of("MAIN", "ORDER_STATUS"));

        StepResult result = handler.execute(step("ORDER_STATUS"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).contains("already on the call stack");
        assertThat(engine.startParams).isEmpty();
    }

    @Test
    void nestingStopsAtFiveLevels() {
        ExecutionContext context = parent();
        context.setInternal(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK, List.of("A", "B", "C", "D", "E"));

        StepResult result = handler.execute(step("ORDER_STATUS"), context);

        assertThat(result.getMessage()).isEqualTo("TRIGGER_JOURNEY: max nesting depth (5) exceeded");
    }
}
