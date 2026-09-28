package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/** SWITCH stores the evaluated value; the engine matches it against the children's branch names. */
class SwitchStepHandlerTest {

    private final SwitchStepHandler handler = new SwitchStepHandler(UTILS, VARIABLES, SCHEMAS);

    private static JourneyStep step(String expression) {
        return JourneyStep.builder().stepOrder(3).stepName("route").actionType("SWITCH")
                .conditionExpression(expression).build();
    }

    @Test
    void thePathsValueIsTheStepOutputWithItsType() {
        ExecutionContext context = run(Map.of("plan", "gold", "seats", 4));

        StepResult gold = handler.execute(step("inputs.entities.plan"), context);
        assertThat(gold.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(gold.getData()).isEqualTo("gold");
        assertThat(output(context, 3)).isEqualTo("gold");

        assertThat(handler.execute(step("{{inputs.entities.seats}}"), context).getData()).isEqualTo(4);
    }

    @Test
    void anExpressionIsEvaluatedNotJustResolved() {
        ExecutionContext context = run(Map.of("seats", 4));

        assertThat(handler.execute(step("inputs.entities.seats > 3 ? 'team' : 'solo'"), context).getData())
                .isEqualTo("team");
    }

    /** Null routes to the DEFAULT branch in the engine; the step itself still succeeds. */
    @Test
    void anUnresolvableValueIsNullAndStillSucceeds() {
        ExecutionContext context = run();

        StepResult result = handler.execute(step("inputs.entities.missing"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getData()).isNull();
        assertThat(context.getStepResults()).containsEntry(3, null);
    }
}
