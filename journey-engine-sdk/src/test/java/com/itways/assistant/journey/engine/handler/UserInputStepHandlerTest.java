package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.MESSAGES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.answer;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.decisionWords;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.json;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.context.ChannelCapabilities;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/**
 * USER_INPUT parks the run on a question and, on the resumed turn, validates
 * and stores the answer: re-asking on a bad one (a bounded number of times),
 * offering an already-known value for a yes/no, confirming an INTERACTIVE
 * answer once, and asking a form one field at a time where the channel cannot
 * show a form.
 */
class UserInputStepHandlerTest {

    private final UserInputStepHandler handler = new UserInputStepHandler(UTILS, VARIABLES, SCHEMAS, MESSAGES,
            decisionWords());

    private static JourneyStep step(Map<String, Object> apiConfig, String message) {
        return JourneyStep.builder().stepOrder(2).stepName("city").actionType("USER_INPUT")
                .apiConfig(apiConfig == null ? null : json(apiConfig)).message(message).build();
    }

    private static Map<String, Object> field(String name, String label, String type, Map<String, Object> validations) {
        Map<String, Object> field = new HashMap<>();
        field.put("name", name);
        field.put("label", label);
        field.put("type", type);
        field.put("validations", validations);
        return field;
    }

    // ───────────────────────── free text ─────────────────────────

    @Test
    void withoutAnAnswerTheStepAsksAndParks() {
        ExecutionContext context = run(Map.of("name", "Sara"));

        StepResult result = handler.execute(step(null, "Which city, {{inputs.entities.name}}?"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(context.getStatus()).isEqualTo(ExecutionStatus.WAITING_FOR_INPUT);
        assertThat(result.getMessage()).isEqualTo("Which city, Sara?");
        assertThat(result.getMetadata()).containsEntry("stepName", "city").containsEntry("inputMode", "FREE_TEXT")
                .doesNotContainKey("subStatus");
    }

    @Test
    void withoutAMessageTheDefaultPromptNamesTheStep() {
        StepResult result = handler.execute(step(null, null), run());

        assertThat(result.getMessage()).isEqualTo("Waiting for input: city");
    }

    @Test
    void aStructuredStepAsksForTheWholeForm() {
        StepResult result = handler.execute(step(Map.of("inputMode", "STRUCTURED"), null), run());

        assertThat(result.getMetadata()).containsEntry("subStatus", "DIRECT_FORM");
    }

    @Test
    void anAnswerIsStoredAndConsumed() {
        ExecutionContext context = run();
        answer(context, "Irbid");

        StepResult result = handler.execute(step(null, "Got it: {{steps.2.output}}"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getData()).isEqualTo("Irbid");
        assertThat(result.getMessage()).isEqualTo("Got it: Irbid");
        assertThat(output(context, 2)).isEqualTo("Irbid");
        assertThat(VARIABLES.getInputs(context)).doesNotContainKey("answer");
    }

    // ───────────────────────── validation ─────────────────────────

    @Test
    void anInvalidAnswerIsRefusedWithTheFieldsError() {
        Map<String, Object> config = Map.of("fields",
                List.of(field("email", "Email", "text", Map.of("required", true, "email", true))));
        ExecutionContext context = run();
        answer(context, "not-an-email");

        StepResult result = handler.execute(step(config, null), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(result.getMessage()).isEqualTo("Please check the following: Email: Enter a valid email address.");
        assertThat(result.getMetadata()).containsEntry("subStatus", "VALIDATION_FAILED")
                .containsEntry("attemptsRemaining", 2);
        assertThat(output(context, 2)).isNull();
        assertThat(VARIABLES.getInputs(context)).doesNotContainKey("answer");

        answer(context, "sara@example.com");
        StepResult fixed = handler.execute(step(config, null), context);
        assertThat(fixed.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(output(context, 2)).isEqualTo("sara@example.com");
    }

    /** Bounded: a question nobody can answer ends as a failed run, not an endless loop. */
    @Test
    void theThirdBadAnswerHaltsTheRun() {
        ExecutionContext context = run();
        StepResult result = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            answer(context, "   ");
            result = handler.execute(step(null, null), context);
            if (attempt < 3) {
                assertThat(result.getStatus()).isEqualTo(StepStatus.WAITING);
                assertThat(result.getMessage()).isEqualTo("I did not catch that. Could you answer the question?");
            }
        }

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).startsWith("USER_INPUT step 'city' gave up after 3 invalid answers");
        assertThat(result.getUserMessage()).startsWith("I could not get a valid answer");
        assertThat(output(context, 2)).isNull();
    }

    // ───────────────────────── pre-fill ─────────────────────────

    @Test
    void aKnownEntityIsOfferedAndAYesStoresTheOfferedValue() {
        ExecutionContext context = run(Map.of("task", "board deck"));
        JourneyStep step = step(Map.of("fillFrom", "task"), "Working on {{steps.2.output}}");

        StepResult offer = handler.execute(step, context);
        assertThat(offer.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(offer.getMessage()).isEqualTo("Use “board deck”? Reply yes to go ahead, or no to choose something else.");
        assertThat(offer.getMetadata()).containsEntry("subStatus", "PREFILL_CONFIRMATION")
                .containsEntry("prefill", "board deck");

        answer(context, "yes");
        StepResult accepted = handler.execute(step, context);

        assertThat(accepted.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(accepted.getData()).isEqualTo("board deck");
        assertThat(output(context, 2)).isEqualTo("board deck");
        assertThat(accepted.getMessage()).isEqualTo("Working on board deck");
    }

    @Test
    void aNoAsksTheOriginalQuestionWithoutAnOffer() {
        ExecutionContext context = run(Map.of("task", "board deck"));
        JourneyStep step = step(Map.of("fillFrom", "task"), "Which task?");
        handler.execute(step, context);

        answer(context, "no");
        StepResult declined = handler.execute(step, context);
        assertThat(declined.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(declined.getMessage()).isEqualTo("Which task?");
        assertThat(declined.getMetadata()).doesNotContainKey("prefill");

        answer(context, "quarterly report");
        assertThat(handler.execute(step, context).getData()).isEqualTo("quarterly report");
    }

    /** Told "Use 'board deck'?", a user may simply type what they meant: that is the answer. */
    @Test
    void aReplyThatIsNeitherYesNorNoIsTheAnswer() {
        ExecutionContext context = run(Map.of("task", "board deck"));
        JourneyStep step = step(Map.of("fillFrom", "task"), null);
        handler.execute(step, context);

        answer(context, "the budget sheet");
        StepResult result = handler.execute(step, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(output(context, 2)).isEqualTo("the budget sheet");
    }

    // ───────────────────────── INTERACTIVE ─────────────────────────

    @Test
    void anInteractiveAnswerIsConfirmedOnceThenAccepted() {
        ExecutionContext context = run();
        JourneyStep step = step(Map.of("inputMode", "INTERACTIVE"), null);
        answer(context, "Sara, Irbid");

        StepResult confirm = handler.execute(step, context);
        assertThat(confirm.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(confirm.getMessage()).startsWith("I have read your input");
        assertThat(confirm.getMetadata()).containsEntry("subStatus", "CONFIRMATION_REQUIRED")
                .containsEntry("parsedData", "Sara, Irbid");

        // The widget confirms by submitting the (possibly corrected) form.
        Map<String, Object> corrected = Map.of("name", "Sara", "city", "Irbid");
        answer(context, corrected);
        StepResult accepted = handler.execute(step, context);

        assertThat(accepted.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(output(context, 2)).isEqualTo(corrected);
        assertThat(context.getInternal(UserInputStepHandler.AWAITING_CONFIRM_PREFIX + 2)).isNull();
    }

    // ───────────────────────── field by field ─────────────────────────

    @Test
    void aFormIsAskedOneFieldAtATimeWhereTheChannelCannotShowIt() {
        Map<String, Object> plan = field("plan", "Plan", "select", Map.of());
        plan.put("options", List.of(Map.of("label", "Gold", "value", "gold"), Map.of("label", "Silver", "value", "silver")));
        Map<String, Object> config = Map.of("inputMode", "STRUCTURED",
                "fields", List.of(field("email", "Email", "email", Map.of("required", true)), plan));
        JourneyStep step = step(config, "Let's sign you up.");
        ExecutionContext context = run();
        context.setInternal(ChannelCapabilities.INTERNAL_CAPABILITIES, Map.of(ChannelCapabilities.FORM, false));

        StepResult first = handler.execute(step, context);
        assertThat(first.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(first.getMessage()).isEqualTo("Let's sign you up. 1 of 2: what is the Email?");
        assertThat(first.getMetadata()).containsEntry("subStatus", "FIELD_BY_FIELD").containsEntry("fieldIndex", 0);

        answer(context, "nope");
        StepResult again = handler.execute(step, context);
        assertThat(again.getMessage()).isEqualTo(
                "Please check the following: Email: Enter a valid email address. 1 of 2: what is the Email?");

        answer(context, "sara@example.com");
        StepResult second = handler.execute(step, context);
        assertThat(second.getMessage()).isEqualTo("2 of 2: what is the Plan? The options are: Gold, Silver.");

        answer(context, "GOLD");
        StepResult done = handler.execute(step, context);
        assertThat(done.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(output(context, 2)).isEqualTo(Map.of("email", "sara@example.com", "plan", "gold"));
    }

    @Test
    void aFileFieldCannotBeAskedOnAChannelWithoutForms() {
        Map<String, Object> config = Map.of("inputMode", "STRUCTURED",
                "fields", List.of(field("name", "Name", "text", Map.of()), field("cv", "CV", "file", Map.of())));
        JourneyStep step = step(config, null);
        ExecutionContext context = run();
        context.setInternal(ChannelCapabilities.INTERNAL_CAPABILITIES, Map.of(ChannelCapabilities.FORM, false));
        handler.execute(step, context);

        answer(context, "Sara");
        StepResult result = handler.execute(step, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getUserMessage()).isEqualTo("This step needs a file, which cannot be sent on this channel.");
    }
}
