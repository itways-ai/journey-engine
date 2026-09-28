package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.MESSAGES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.answer;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.decisionWords;
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
 * HUMAN_APPROVAL parks until a clear approve or reject. A rejection, or a
 * deadline that passes with no decision, halts the run: nothing is approved by
 * a "no", an unclear reply or inaction.
 */
class HumanApprovalStepHandlerTest {

    private static final String KEY = HumanApprovalStepHandler.DEADLINE_PREFIX + 4;

    private final HumanApprovalStepHandler handler = new HumanApprovalStepHandler(decisionWords(), UTILS, VARIABLES,
            SCHEMAS, MESSAGES);

    private static JourneyStep step(String apiConfig, String message) {
        return JourneyStep.builder().stepOrder(4).stepName("approve").actionType("HUMAN_APPROVAL")
                .apiConfig(apiConfig).message(message).build();
    }

    private static JourneyStep selfConfirm() {
        return step("{\"timeout\":2}", null);
    }

    @Test
    void theFirstPassParksAndOpensTheTimeoutWindow() {
        ExecutionContext context = run();
        Instant before = Instant.now();

        StepResult result = handler.execute(selfConfirm(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(context.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
        assertThat(result.getMessage()).isEqualTo("Please confirm to continue. Reply approve or reject.");
        assertThat(result.getMetadata()).containsEntry("type", "HUMAN_GOVERNANCE")
                .containsEntry("approvalMode", "SELF_CONFIRM").containsEntry("awaiting", true)
                .doesNotContainKey("stakeholders");
        assertThat(Instant.parse((String) context.getInternal(KEY)))
                .isCloseTo(before.plus(Duration.ofHours(2)), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void stakeholderModeNamesTheApproversWithPlaceholdersResolved() {
        ExecutionContext context = run(Map.of("manager", "Omar"));

        StepResult result = handler.execute(step(
                "{\"approvalMode\":\"stakeholder\",\"stakeholders\":\"{{inputs.entities.manager}}\","
                        + "\"instruction\":\"Check {{inputs.entities.manager}}'s budget\"}",
                null), context);

        assertThat(result.getMessage()).isEqualTo("Awaiting approval from: Omar. Reply approve or reject.");
        assertThat(result.getMetadata()).containsEntry("approvalMode", "STAKEHOLDER")
                .containsEntry("stakeholders", "Omar").containsEntry("instruction", "Check Omar's budget");
    }

    @Test
    void theAuthorsMessageIsUsedWhenGiven() {
        ExecutionContext context = run(Map.of("amount", 250));

        StepResult result = handler.execute(step(null, "Refund {{inputs.entities.amount}}?"), context);

        assertThat(result.getMessage()).isEqualTo("Refund 250?");
    }

    @Test
    void anApprovalLetsTheRunContinue() {
        for (Object reply : new Object[] { "approve", "Yes!", Boolean.TRUE }) {
            ExecutionContext context = run();
            handler.execute(selfConfirm(), context);
            answer(context, reply);

            StepResult result = handler.execute(selfConfirm(), context);

            assertThat(result.getStatus()).as(String.valueOf(reply)).isEqualTo(StepStatus.SUCCESS);
            assertThat(result.getData()).isEqualTo(true);
            assertThat(result.getMessage()).isEqualTo("The request was approved.");
            assertThat(output(context, 4)).isEqualTo(true);
            assertThat(context.getInternal(KEY)).isNull();
            assertThat(VARIABLES.getInputs(context)).doesNotContainKey("answer");
        }
    }

    /** "no" must never approve: the run halts, and the decision is still recorded. */
    @Test
    void aRejectionHaltsTheRun() {
        ExecutionContext context = run();
        handler.execute(selfConfirm(), context);
        answer(context, "no");

        StepResult result = handler.execute(selfConfirm(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("Human approval rejected.");
        assertThat(result.getUserMessage()).isEqualTo("The request was rejected.");
        assertThat(output(context, 4)).isEqualTo(false);
    }

    @Test
    void anUnclearReplyAsksAgainAndKeepsTheDeadline() {
        ExecutionContext context = run();
        handler.execute(selfConfirm(), context);
        Object deadline = context.getInternal(KEY);
        answer(context, "maybe later");

        StepResult result = handler.execute(selfConfirm(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(result.getMessage()).startsWith("I could not tell whether that was an approval");
        assertThat(context.getInternal(KEY)).isEqualTo(deadline);
        assertThat(VARIABLES.getInputs(context)).doesNotContainKey("answer");
        assertThat(output(context, 4)).isNull();
    }

    @Test
    void anElapsedDeadlineRejectsAutomatically() {
        ExecutionContext context = run();
        context.setInternal(KEY, Instant.now().minusSeconds(1).toString());

        StepResult result = handler.execute(selfConfirm(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getUserMessage()).isEqualTo("The approval request expired and was automatically rejected.");
        assertThat(output(context, 4)).isEqualTo(false);
        assertThat(context.getInternal(KEY)).isNull();
    }
}
