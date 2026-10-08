package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** RESPONSE interpolates its text and says it: the text is both the output and the message. */
class ResponseStepHandlerTest {

    private final ResponseStepHandler handler = new ResponseStepHandler(UTILS, VARIABLES, SCHEMAS);

    private static JourneyStep step(String text) {
        return JourneyStep.builder().stepOrder(5).actionType("RESPONSE").actionTarget(text).build();
    }

    @Test
    void placeholdersAreResolvedIntoTheReply() {
        ExecutionContext context = run(Map.of("name", "Sara"));
        VARIABLES.writeStepOutput(context, JourneyStep.builder().stepOrder(2).build(), Map.of("total", 42));

        StepResult result = handler.execute(step("Hi {{inputs.entities.name}}, your total is {{steps.2.output.total}}."),
                context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMessage()).isEqualTo("Hi Sara, your total is 42.");
        assertThat(result.getData()).isEqualTo("Hi Sara, your total is 42.");
        assertThat(output(context, 5)).isEqualTo("Hi Sara, your total is 42.");
    }

    /** An absent path renders as nothing rather than as the raw braces. */
    @Test
    void aMissingVariableRendersEmpty() {
        StepResult result = handler.execute(step("Order {{inputs.entities.orderId}} confirmed"), run());

        assertThat(result.getMessage()).isEqualTo("Order  confirmed");
    }

    @Test
    void plainTextPassesThroughAndNoTextIsNull() {
        assertThat(handler.execute(step("Thanks!"), run()).getMessage()).isEqualTo("Thanks!");
        StepResult empty = handler.execute(step(null), run());
        assertThat(empty.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(empty.getMessage()).isNull();
    }
}
