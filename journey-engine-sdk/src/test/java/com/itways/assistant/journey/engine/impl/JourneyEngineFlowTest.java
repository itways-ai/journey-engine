package com.itways.assistant.journey.engine.impl;

import static com.itways.assistant.journey.engine.impl.EngineFixture.context;
import static com.itways.assistant.journey.engine.impl.EngineFixture.journey;
import static com.itways.assistant.journey.engine.impl.EngineFixture.step;
import static com.itways.assistant.journey.engine.impl.EngineFixture.stub;
import static com.itways.assistant.journey.engine.impl.EngineFixture.trace;
import static com.itways.assistant.journey.engine.impl.EngineFixture.views;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.context.Simulation;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyDefinition;
import com.itways.assistant.journey.model.RunStatus;

/**
 * JourneyEngineImpl's core loop, run for real: ordering, CONDITION / SWITCH /
 * JUMP routing, parking on USER_INPUT and resuming, step failures with and
 * without continueOnError, and which lifecycle statuses the run reports.
 */
class JourneyEngineFlowTest {

    private final EngineFixture f = new EngineFixture();

    private static Map<String, Object> entities(Object... keyValues) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    // ───────────────────────── ordering ─────────────────────────

    @Test
    void stepsRunByOrderNotByListPositionAndTheRunFinishes() {
        JourneyDefinition journey = journey("J", step(3, "RECORD"), step(1, "RECORD"), step(2, "RECORD"));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(f.executed).containsExactly("s1", "s2", "s3");
        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(result.get("message")).isEqualTo("s3 done");
        assertThat(context(result).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.get("executionId")).isEqualTo(context(result).getExecutionId());
        assertThat(result.get("rootExecutionId")).isEqualTo(result.get("executionId"));
        assertThat(f.statuses()).containsExactly(RunStatus.RUNNING, RunStatus.COMPLETED);
    }

    @Test
    void runtimeFactsArePublishedForAuthors() {
        JourneyDefinition journey = journey("GREET",
                step(1, "RESPONSE").actionTarget("{{runtime.triggerIntent}} in {{runtime.language}} at step {{runtime.stepOrder}}"));

        assertThat(f.start(journey, Map.of()).get("message")).isEqualTo("GREET in en at step 1");
    }

    /** The observer sees each step as it finishes; one that throws cannot break the run. */
    @Test
    void aFailingObserverDoesNotStopTheRun() {
        List<Object> seen = new ArrayList<>();
        JourneyDefinition journey = journey("J", step(1, "RECORD"), step(2, "RECORD"));

        Map<String, Object> result = f.engine.start(journey, "acc-1", null, Map.of(), view -> {
            seen.add(view.get("stepName"));
            throw new IllegalStateException("client went away");
        });

        assertThat(seen).containsExactly("s1", "s2");
        assertThat(result.get("status")).isEqualTo("FINISHED");
    }

    // ───────────────────────── branching ─────────────────────────

    private static JourneyDefinition vipJourney() {
        return journey("VIP",
                step(1, "CONDITION").conditionExpression("inputs.entities.vip == true"),
                step(2, "RESPONSE").actionTarget("Welcome back").parentOrder(1).branchName("true"),
                step(3, "RESPONSE").actionTarget("Hello").parentOrder(1).branchName("false"),
                step(4, "RESPONSE").actionTarget("{{steps.2.output}}{{steps.3.output}}, {{inputs.entities.name}}")
                        .parentOrders(List.of(2, 3)));
    }

    @Test
    void aConditionRunsOnlyTheMatchingBranchAndBothRejoin() {
        Map<String, Object> vip = f.start(vipJourney(), entities("vip", true, "name", "Sara"));
        assertThat(trace(vip)).containsExactly("CONDITION:SUCCESS", "RESPONSE:SUCCESS", "RESPONSE:SUCCESS");
        assertThat(vip.get("message")).isEqualTo("Welcome back, Sara");

        Map<String, Object> regular = f.start(vipJourney(), entities("vip", false, "name", "Omar"));
        assertThat(regular.get("message")).isEqualTo("Hello, Omar");
        assertThat(context(regular).getStepResults()).doesNotContainKey(2);
    }

    /** A step under a branch that did not run is skipped, and so is everything under it. */
    @Test
    void theDescendantsOfASkippedBranchAreSkippedToo() {
        JourneyDefinition journey = journey("J",
                step(1, "CONDITION").conditionExpression("false"),
                step(2, "RECORD").parentOrder(1).branchName("true"),
                step(3, "RECORD").parentOrder(2),
                step(4, "RECORD"));

        f.start(journey, Map.of());

        assertThat(f.executed).containsExactly("s4");
    }

    private static JourneyDefinition planJourney() {
        return journey("PLAN",
                step(1, "SWITCH").conditionExpression("inputs.entities.plan"),
                step(2, "RESPONSE").actionTarget("gold desk").parentOrder(1).branchName("gold"),
                step(3, "RESPONSE").actionTarget("silver desk").parentOrder(1).branchName("silver"),
                step(4, "RESPONSE").actionTarget("general desk").parentOrder(1).branchName("DEFAULT"));
    }

    @Test
    void aSwitchRunsTheNamedCaseCaseInsensitivelyElseDefault() {
        assertThat(f.start(planJourney(), entities("plan", "GOLD")).get("message")).isEqualTo("gold desk");
        assertThat(f.start(planJourney(), entities("plan", "silver")).get("message")).isEqualTo("silver desk");
        assertThat(f.start(planJourney(), entities("plan", "bronze")).get("message")).isEqualTo("general desk");
    }

    // ───────────────────────── JUMP ─────────────────────────

    @Test
    void aForwardJumpSkipsTheStepsInBetween() {
        JourneyDefinition journey = journey("J", step(1, "RECORD"), step(2, "JUMP").actionTarget("4"),
                step(3, "RECORD"), step(4, "RECORD"));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(f.executed).containsExactly("s1", "s4");
        assertThat(result.get("status")).isEqualTo("FINISHED");
    }

    /** Replayed steps start clean: their outputs and the state they wrote are rolled back. */
    @Test
    void aBackwardJumpReplaysAndRollsBackWhatTheReplayedStepsWrote() {
        AtomicInteger passes = new AtomicInteger();
        f.with(stub("ONCE_BACK", (step, context) -> passes.incrementAndGet() == 1
                ? StepResult.jump(1, "again")
                : StepResult.success("through")));
        JourneyDefinition journey = journey("J",
                step(1, "STATE_STORE").apiConfig("{\"variable\":\"visits\",\"operation\":\"INCREMENT\"}"),
                step(2, "ONCE_BACK"),
                step(3, "RESPONSE").actionTarget("visits={{state.visits}}"));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(passes).hasValue(2);
        assertThat(result.get("message")).isEqualTo("visits=1");
        // No view for the JUMP itself; the replayed step appears once per pass.
        assertThat(trace(result)).containsExactly("STATE_STORE:SUCCESS", "STATE_STORE:SUCCESS", "ONCE_BACK:SUCCESS",
                "RESPONSE:SUCCESS");
    }

    /** An author's loop with no working exit ends as a localized ERROR, not a hung request thread. */
    @Test
    void anEndlessJumpLoopIsStopped() {
        JourneyDefinition journey = journey("J", step(1, "RECORD"), step(2, "JUMP").actionTarget("1"));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(result.get("status")).isEqualTo("ERROR");
        assertThat(result.get("message")).asString().startsWith("This request was stopped because it was repeating");
        assertThat(f.executed).hasSize(JourneyEngineImpl.MAX_JUMPS_PER_TURN + 1);
        assertThat(f.last().getStatus()).isEqualTo(RunStatus.ERROR);
    }

    // ───────────────────────── pause and resume ─────────────────────────

    private static JourneyDefinition cityJourney() {
        return journey("CITY",
                step(1, "USER_INPUT").message("Which city?"),
                step(2, "RESPONSE").actionTarget("Delivering to {{steps.1.output}}"));
    }

    @Test
    void aUserInputParksTheRunAndTheResumeContinuesFromThatStep() {
        JourneyDefinition journey = cityJourney();

        Map<String, Object> first = f.start(journey, Map.of());

        assertThat(first.get("status")).isEqualTo("WAITING");
        assertThat(first.get("message")).isEqualTo("Which city?");
        ExecutionContext parked = context(first);
        assertThat(parked.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
        assertThat(f.statuses()).containsExactly(RunStatus.RUNNING, RunStatus.WAITING);
        assertThat(f.last().getCompletedAt()).isNull();

        Map<String, Object> second = f.engine.resume(journey, parked, Map.of("answer", "Irbid"));

        assertThat(second.get("status")).isEqualTo("FINISHED");
        assertThat(second.get("message")).isEqualTo("Delivering to Irbid");
        assertThat(second.get("executionId")).isEqualTo(first.get("executionId"));
        assertThat(trace(second)).containsExactly("USER_INPUT:SUCCESS", "RESPONSE:SUCCESS");
        assertThat(f.statuses()).containsExactly(RunStatus.RUNNING, RunStatus.WAITING, RunStatus.COMPLETED);
        assertThat(f.last().getCompletedAt()).isNotNull();
        assertThat(f.last().getExecutionId()).isEqualTo(first.get("executionId"));
        assertThat(parked.getInternal("_pendingResumeInput")).isNull();
    }

    @Test
    void aResumeWithNoAnswerAsksAgain() {
        JourneyDefinition journey = cityJourney();
        ExecutionContext parked = context(f.start(journey, Map.of()));

        Map<String, Object> again = f.engine.resume(journey, parked, Map.of());

        assertThat(again.get("status")).isEqualTo("WAITING");
        assertThat(again.get("message")).isEqualTo("Which city?");
    }

    /** HANDOFF ends the run deliberately: nothing after it runs, and it is reported finished. */
    @Test
    void aHandoffEndsTheRunAsCompleted() {
        JourneyDefinition journey = journey("J", step(1, "HANDOFF").apiConfig("{\"queue\":\"support\"}"),
                step(2, "RECORD"));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(f.executed).isEmpty();
        assertThat(views(result).get(0)).containsEntry("handoff", true).containsEntry("queue", "support");
        assertThat(f.last().getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(f.last().getCompletedAt()).isNotNull();
    }

    // ───────────────────────── failures ─────────────────────────

    private void failing() {
        f.with(stub("FAIL", (step, context) -> StepResult.error("TEMPLATE_RENDER: '{{id}}' is not a template id")));
        f.with(stub("FAIL_POLITELY", (step, context) -> StepResult.error("rejected by approver", "Your request was declined.")));
        f.with(stub("THROW", (step, context) -> {
            throw new IllegalStateException("boom");
        }));
    }

    /** A step that fails stops the run; the user gets a sentence, not the diagnostic. */
    @Test
    void aFailingStepStopsTheRun() {
        failing();
        JourneyDefinition journey = journey("J", step(1, "RECORD"), step(2, "FAIL"), step(3, "RECORD"));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(f.executed).containsExactly("s1");
        assertThat(result.get("status")).isEqualTo("ERROR");
        assertThat(result.get("message")).isEqualTo("Something went wrong while handling your request. Please try again.");
        assertThat(views(result).get(1)).containsEntry("message", "TEMPLATE_RENDER: '{{id}}' is not a template id");
        assertThat(context(result).getStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(f.last().getStatus()).isEqualTo(RunStatus.ERROR);
        assertThat(f.last().getCompletedAt()).isNotNull();
        assertThat(f.last().getStepLogs()).hasSize(2);
    }

    @Test
    void aStepsOwnUserMessageIsWhatTheUserIsTold() {
        failing();
        Map<String, Object> result = f.start(journey("J", step(1, "FAIL_POLITELY")), Map.of());

        assertThat(result.get("message")).isEqualTo("Your request was declined.");
        assertThat(views(result).get(0)).containsEntry("detail", "rejected by approver");
        assertThat(f.last().getStepLogs().get(0).detail()).isEqualTo("rejected by approver");
    }

    @Test
    void continueOnErrorRecordsTheFailureAndCarriesOn() {
        failing();
        JourneyDefinition journey = journey("J", step(1, "RECORD"), step(2, "FAIL").continueOnError(true),
                step(3, "RESPONSE").actionTarget("step 2 said {{steps.2.output}}"));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(trace(result)).containsExactly("RECORD:SUCCESS", "FAIL:ERROR", "RESPONSE:SUCCESS");
        assertThat(result.get("message")).isEqualTo("step 2 said FAILED");
        assertThat(f.last().getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void aThrowingHandlerIsAFailedStepNotACrashedTurn() {
        failing();
        Map<String, Object> result = f.start(journey("J", step(1, "THROW")), Map.of());

        assertThat(result.get("status")).isEqualTo("ERROR");
        assertThat(views(result).get(0)).containsEntry("message", "Internal Handler Error: boom");
    }

    @Test
    void anUnknownStepTypeStopsTheRunUnlessContinueOnError() {
        Map<String, Object> stopped = f.start(journey("J", step(1, "NO_SUCH_TYPE"), step(2, "RECORD")), Map.of());
        assertThat(stopped.get("status")).isEqualTo("ERROR");
        assertThat(views(stopped).get(0)).containsEntry("message", "No handler found for type: NO_SUCH_TYPE");
        assertThat(f.executed).isEmpty();

        Map<String, Object> carried = f.start(
                journey("J", step(1, "NO_SUCH_TYPE").continueOnError(true), step(2, "RECORD")), Map.of());
        assertThat(carried.get("status")).isEqualTo("FINISHED");
        assertThat(f.executed).containsExactly("s2");
    }

    @Test
    void aCyclicStepGraphIsRefusedBeforeAnyStepRuns() {
        JourneyDefinition journey = journey("J", step(1, "RECORD").parentOrder(2), step(2, "RECORD").parentOrder(1));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(result.get("status")).isEqualTo("ERROR");
        assertThat(f.executed).isEmpty();
        assertThat(f.last().getStatus()).isEqualTo(RunStatus.ERROR);
    }

    @Test
    void aJourneyWithNoStepsFinishesAndSaysSo() {
        Map<String, Object> result = f.start(journey("J"), Map.of());

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(result.get("message")).isEqualTo("This journey has no steps configured.");
        assertThat(f.last().getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    // ───────────────────────── rehearsal ─────────────────────────

    /** A rehearsal runs every step but writes no run history, on any turn. */
    @Test
    void aSimulatedRunLeavesNoLifecycleTrace() {
        JourneyDefinition journey = cityJourney();

        Map<String, Object> first = f.start(journey, entities(Simulation.PARAM_SIMULATE, true));
        assertThat(first.get("status")).isEqualTo("WAITING");
        assertThat(first).containsEntry(Simulation.META_SIMULATED, true);
        assertThat(Simulation.isActive(context(first))).isTrue();

        Map<String, Object> second = f.engine.resume(journey, context(first), Map.of("answer", "Irbid"));
        assertThat(second.get("status")).isEqualTo("FINISHED");
        assertThat(second).containsEntry(Simulation.META_SIMULATED, true);

        assertThat(f.events).isEmpty();
    }
}
