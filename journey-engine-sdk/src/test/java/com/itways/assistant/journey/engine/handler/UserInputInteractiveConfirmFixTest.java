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

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/**
 * Regression test: an INTERACTIVE answer confirmed with a plain "yes" stored
 * "yes" as the step's output, replacing the answer being confirmed.
 */
class UserInputInteractiveConfirmFixTest {

    private final UserInputStepHandler handler = new UserInputStepHandler(UTILS, VARIABLES, SCHEMAS, MESSAGES,
            decisionWords());

    private static JourneyStep step() {
        return JourneyStep.builder().stepOrder(2).stepName("city").actionType("USER_INPUT")
                .apiConfig(json(Map.of("inputMode", "INTERACTIVE"))).build();
    }

    @Test
    void aYesConfirmsTheAnswerRatherThanReplacingIt() {
        ExecutionContext context = run();
        answer(context, "Irbid");
        assertThat(handler.execute(step(), context).getStatus()).isEqualTo(StepStatus.WAITING);

        answer(context, "yes");
        StepResult confirmed = handler.execute(step(), context);

        assertThat(confirmed.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(confirmed.getData()).isEqualTo("Irbid");
        assertThat(output(context, 2)).isEqualTo("Irbid");
    }

    @Test
    void aNoAsksTheQuestionAgain() {
        ExecutionContext context = run();
        answer(context, "Irbid");
        handler.execute(step(), context);

        answer(context, "no");
        StepResult declined = handler.execute(step(), context);

        assertThat(declined.getStatus()).isEqualTo(StepStatus.WAITING);
        assertThat(declined.getMetadata()).doesNotContainEntry("subStatus", "CONFIRMATION_REQUIRED");

        answer(context, "Amman");
        assertThat(handler.execute(step(), context).getStatus()).isEqualTo(StepStatus.WAITING);
        answer(context, "yes");
        assertThat(handler.execute(step(), context).getData()).isEqualTo("Amman");
    }

    /** Anything that is not a yes or a no is a corrected answer, as before. */
    @Test
    void aCorrectionIsTakenAsTheAnswer() {
        ExecutionContext context = run();
        answer(context, "Irbd");
        handler.execute(step(), context);

        answer(context, "Irbid");
        StepResult corrected = handler.execute(step(), context);

        assertThat(corrected.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(output(context, 2)).isEqualTo("Irbid");
    }
}
