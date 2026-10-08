package com.itways.assistant.journey.engine.impl;

import static com.itways.assistant.journey.engine.impl.EngineFixture.context;
import static com.itways.assistant.journey.engine.impl.EngineFixture.journey;
import static com.itways.assistant.journey.engine.impl.EngineFixture.step;
import static com.itways.assistant.journey.engine.impl.EngineFixture.stub;
import static com.itways.assistant.journey.engine.impl.EngineFixture.trace;
import static com.itways.assistant.journey.engine.impl.EngineFixture.views;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyDefinition;
import com.itways.assistant.journey.model.RunStatus;
import com.itways.assistant.journey.model.StepStatus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The error branch (1.1.0): a failed step with a child on the "error" branch
 * hands the run to that child instead of stopping, and its other children are
 * skipped. Steps without an error child, continueOnError and the CONDITION /
 * SWITCH routing behave exactly as before.
 */
class JourneyEngineErrorBranchTest {

    private final EngineFixture f = new EngineFixture();
    private final AtomicInteger flakyPasses = new AtomicInteger();

    JourneyEngineErrorBranchTest() {
        f.with(stub("FAIL_TYPED", (step, context) -> StepResult.builder().status(StepStatus.ERROR)
                .message("bank said no")
                .metadata(Map.of(StepResult.META_ERROR_CODE, "ACCOUNT_NOT_FOUND", StepResult.META_RETRYABLE, false,
                        StepResult.META_HTTP_STATUS, 404))
                .build()));
        f.with(stub("FAIL_CONTINUE", (step, context) -> StepResult.builder().status(StepStatus.ERROR)
                .message("timed out")
                .metadata(Map.of(StepResult.META_CONTINUE_ON_ERROR, true, StepResult.META_ERROR_CODE,
                        "CONNECTOR_TIMEOUT", StepResult.META_RETRYABLE, true))
                .build()));
        f.with(stub("PASS", (step, context) -> StepResult.success("paid", "paid")));
        f.with(stub("FLAKY", (step, context) -> flakyPasses.incrementAndGet() == 1
                ? StepResult.builder().status(StepStatus.ERROR).message("busy")
                        .metadata(Map.of(StepResult.META_ERROR_CODE, "BUSY", StepResult.META_RETRYABLE, true)).build()
                : StepResult.success("ok", "ok")));
    }

    /** RECORD, then the given step type with a success child (3) and an error child (4). */
    private static JourneyDefinition forkJourney(String type) {
        return journey("PAY",
                step(1, "RECORD"),
                step(2, type),
                step(3, "RESPONSE").actionTarget("ok").parentOrder(2),
                step(4, "RESPONSE").actionTarget("fallback {{steps.2.error.code}}").parentOrder(2).branchName("error"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bucket(Map<String, Object> result, int order) {
        Map<String, Object> steps = (Map<String, Object>) context(result).getVariables().get("steps");
        return (Map<String, Object>) steps.get(String.valueOf(order));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> result, int order) {
        Map<String, Object> bucket = bucket(result, order);
        return bucket != null ? (Map<String, Object>) bucket.get("error") : null;
    }

    // ───────────────────────── the error branch ─────────────────────────

    /** A failed step with an error child runs that child and the run finishes. */
    @Test
    void aFailedStepTakesItsErrorChild() {
        Map<String, Object> result = f.start(forkJourney("FAIL_TYPED"), Map.of());

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(trace(result)).containsExactly("RECORD:SUCCESS", "FAIL_TYPED:ERROR", "RESPONSE:SUCCESS");
        assertThat(result.get("message")).isEqualTo("fallback ACCOUNT_NOT_FOUND");
        assertThat(context(result).getStepResults()).doesNotContainKey(3);
        assertThat(context(result).getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(bucket(result, 2)).containsEntry("output", "FAILED");
        assertThat(error(result, 2))
                .containsEntry("code", "ACCOUNT_NOT_FOUND")
                .containsEntry("message", "bank said no")
                .containsEntry("retryable", false)
                .containsEntry("httpStatus", 404);
        assertThat(f.statuses()).containsExactly(RunStatus.RUNNING, RunStatus.COMPLETED);
        assertThat(views(result).get(1)).containsEntry("errorCode", "ACCOUNT_NOT_FOUND")
                .containsEntry("retryable", false);
    }

    /** When the step succeeds, its success child runs and its error child does not. */
    @Test
    void aSucceedingStepSkipsItsErrorChild() {
        Map<String, Object> result = f.start(forkJourney("PASS"), Map.of());

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(result.get("message")).isEqualTo("ok");
        assertThat(context(result).getStepResults()).containsKey(3).doesNotContainKey(4);
        assertThat(error(result, 2)).isNull();
    }

    /** The error child's descendants run; the skipped success child's descendants do not. */
    @Test
    void descendantsFollowTheBranchTheirAncestorWasOn() {
        JourneyDefinition journey = journey("PAY",
                step(1, "RECORD"),
                step(2, "FAIL_TYPED"),
                step(3, "RECORD").parentOrder(2),
                step(4, "RECORD").parentOrder(2).branchName("error"),
                step(5, "RECORD").parentOrder(3),
                step(6, "RECORD").parentOrder(4));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(f.executed).containsExactly("s1", "s4", "s6");
    }

    /** A child named "success" is the success side of the fork, like an unnamed one. */
    @Test
    void aSuccessNamedChildIsSkippedOnFailure() {
        JourneyDefinition journey = journey("PAY",
                step(1, "FAIL_TYPED"),
                step(2, "RECORD").parentOrder(1).branchName("success"),
                step(3, "RECORD").parentOrder(1).branchName("error"));

        f.start(journey, Map.of());

        assertThat(f.executed).containsExactly("s3");
    }

    // ───────────────────────── unchanged behaviour ─────────────────────────

    /** Without an error child a failure stops the run exactly as before. */
    @Test
    void withoutAnErrorChildAFailureStillStopsTheRun() {
        JourneyDefinition journey = journey("J", step(1, "RECORD"), step(2, "FAIL_TYPED"),
                step(3, "RECORD").parentOrder(2));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(f.executed).containsExactly("s1");
        assertThat(result.get("status")).isEqualTo("ERROR");
        assertThat(result.get("message")).isEqualTo("Something went wrong while handling your request. Please try again.");
        assertThat(context(result).getStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(f.last().status()).isEqualTo(RunStatus.ERROR);
    }

    /** continueOnError still writes only FAILED, with no error field. */
    @Test
    void continueOnErrorIsUnchanged() {
        JourneyDefinition journey = journey("J", step(1, "FAIL_TYPED").continueOnError(true),
                step(2, "RECORD").parentOrder(1));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(f.executed).containsExactly("s2");
        assertThat(bucket(result, 1)).containsEntry("output", "FAILED").doesNotContainKey("error");
    }

    /** A continueOnError step that also has an error child routes to the error child. */
    @Test
    void anErrorChildWinsOverContinueOnError() {
        JourneyDefinition journey = journey("J", step(1, "FAIL_TYPED").continueOnError(true),
                step(2, "RECORD").parentOrder(1),
                step(3, "RECORD").parentOrder(1).branchName("error"));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(f.executed).containsExactly("s3");
    }

    /** onError CONTINUE arrives as result metadata: the run carries on and the error is recorded. */
    @Test
    void continueFromResultMetadataCarriesOnWithTheError() {
        JourneyDefinition journey = journey("J", step(1, "FAIL_CONTINUE"), step(2, "RECORD").parentOrder(1));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(f.executed).containsExactly("s2");
        assertThat(bucket(result, 1)).containsEntry("output", "FAILED");
        assertThat(error(result, 1))
                .containsEntry("code", "CONNECTOR_TIMEOUT")
                .containsEntry("retryable", true)
                .doesNotContainKey("httpStatus");
    }

    // ───────────────────────── JUMP, WAITING, SWITCH, CONDITION ─────────────────────────

    /** A backward jump clears the failure, so the replayed step that succeeds takes the success path. */
    @Test
    void aReplayedStepThatSucceedsTakesTheSuccessPath() {
        JourneyDefinition journey = journey("RETRY",
                step(1, "FLAKY"),
                step(2, "RESPONSE").actionTarget("retrying after {{steps.1.error.code}}").parentOrder(1).branchName("error"),
                step(3, "JUMP").actionTarget("1").parentOrder(2),
                step(4, "RESPONSE").actionTarget("done").parentOrder(1));

        Map<String, Object> result = f.start(journey, Map.of());

        assertThat(flakyPasses).hasValue(2);
        assertThat(trace(result)).containsExactly("FLAKY:ERROR", "RESPONSE:SUCCESS", "FLAKY:SUCCESS",
                "RESPONSE:SUCCESS");
        assertThat(views(result).get(1)).containsEntry("message", "retrying after BUSY");
        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(result.get("message")).isEqualTo("done");
        assertThat(context(result).getStepResults()).doesNotContainKey(2).doesNotContainKey(3);
        assertThat(error(result, 1)).isNull();
    }

    /** WAITING is not a failure: neither child runs while parked, and the answer takes the success path. */
    @Test
    void aWaitingStepDoesNotOpenItsErrorBranch() {
        JourneyDefinition journey = journey("ASK",
                step(1, "USER_INPUT").message("Account number?"),
                step(2, "RECORD").parentOrder(1).branchName("error"),
                step(3, "RECORD").parentOrder(1));

        Map<String, Object> first = f.start(journey, Map.of());

        assertThat(first.get("status")).isEqualTo("WAITING");
        assertThat(f.executed).isEmpty();

        ExecutionContext parked = context(first);
        Map<String, Object> second = f.engine.resume(journey, parked, Map.of("answer", "12345"));

        assertThat(second.get("status")).isEqualTo("FINISHED");
        assertThat(f.executed).containsExactly("s3");
    }

    /** "error" under a SWITCH is not a case name: the matching case runs and DEFAULT does not. */
    @Test
    void anErrorChildUnderASwitchIsNotACase() {
        JourneyDefinition journey = journey("PLAN",
                step(1, "SWITCH").conditionExpression("inputs.entities.plan"),
                step(2, "RECORD").parentOrder(1).branchName("gold"),
                step(3, "RECORD").parentOrder(1).branchName("DEFAULT"),
                step(4, "RECORD").parentOrder(1).branchName("error"));

        Map<String, Object> result = f.start(journey, Map.of("plan", "gold"));

        assertThat(result.get("status")).isEqualTo("FINISHED");
        assertThat(f.executed).containsExactly("s2");
    }

    /** CONDITION true/false routing is untouched when no error child exists. */
    @Test
    void conditionRoutingIsUnchanged() {
        JourneyDefinition journey = journey("VIP",
                step(1, "CONDITION").conditionExpression("inputs.entities.vip == true"),
                step(2, "RECORD").parentOrder(1).branchName("true"),
                step(3, "RECORD").parentOrder(1).branchName("false"));

        f.start(journey, Map.of("vip", false));

        assertThat(f.executed).isEqualTo(List.of("s3"));
    }
}
