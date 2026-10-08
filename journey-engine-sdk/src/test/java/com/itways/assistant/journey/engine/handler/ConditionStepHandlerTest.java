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

/** CONDITION evaluates its expression and stores the boolean the engine branches on. */
class ConditionStepHandlerTest {

    private final ConditionStepHandler handler = new ConditionStepHandler(UTILS, VARIABLES, SCHEMAS);

    private static JourneyStep step(String expression) {
        return JourneyStep.builder().stepOrder(2).stepName("check").actionType("CONDITION")
                .conditionExpression(expression).message("checked").build();
    }

    @Test
    void aTrueExpressionStoresTrueAsTheStepOutput() {
        ExecutionContext context = run(Map.of("age", 30));

        StepResult result = handler.execute(step("inputs.entities.age >= 18"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getData()).isEqualTo(true);
        assertThat(result.getMessage()).isEqualTo("checked");
        assertThat(output(context, 2)).isEqualTo(true);
        assertThat(context.getStepResults()).containsEntry(2, true);
    }

    @Test
    void aFalseExpressionStoresFalse() {
        ExecutionContext context = run(Map.of("age", 12));

        StepResult result = handler.execute(step("{{inputs.entities.age}} >= 18"), context);

        assertThat(result.getData()).isEqualTo(false);
        assertThat(context.getStepResults()).containsEntry(2, false);
    }

    /** A condition that cannot be evaluated is false, never an error that halts the run. */
    @Test
    void aMissingVariableOrBlankExpressionReadsAsFalse() {
        ExecutionContext context = run();

        assertThat(handler.execute(step("inputs.entities.nothing == 'x'"), context).getData()).isEqualTo(false);
        assertThat(handler.execute(step(""), context).getData()).isEqualTo(false);
        assertThat(handler.execute(step(null), context).getStatus()).isEqualTo(StepStatus.SUCCESS);
    }

    /** SimpleEvaluationContext: an author-written condition cannot reach Java types. */
    @Test
    void anExpressionCannotReachJavaTypes() {
        ExecutionContext context = run();

        StepResult result = handler.execute(step("T(java.lang.System).exit(1) == null"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getData()).isEqualTo(false);
    }
}
