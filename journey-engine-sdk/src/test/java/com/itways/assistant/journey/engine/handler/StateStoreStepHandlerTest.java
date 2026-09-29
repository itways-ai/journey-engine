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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** STATE_STORE writes {@code state.<variable>} with SET, APPEND or INCREMENT. */
class StateStoreStepHandlerTest {

    private final StateStoreStepHandler handler = new StateStoreStepHandler(UTILS, VARIABLES, SCHEMAS);

    private static JourneyStep step(int order, String variable, String operation, String source) {
        String config = "{\"variable\":" + (variable == null ? "null" : "\"" + variable + "\"")
                + ",\"operation\":" + (operation == null ? "null" : "\"" + operation + "\"")
                + ",\"source\":" + (source == null ? "null" : "\"" + source + "\"") + "}";
        return JourneyStep.builder().stepOrder(order).actionType("STATE_STORE").apiConfig(config).build();
    }

    private static Object state(ExecutionContext context, String key) {
        return VARIABLES.getState(context).get(key);
    }

    /** A lone placeholder keeps its type: 8 is stored, not "8". */
    @Test
    void setStoresTheResolvedValueWithItsType() {
        ExecutionContext context = run(Map.of("score", 8));

        StepResult result = handler.execute(step(2, "score", null, "{{inputs.entities.score}}"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(state(context, "score")).isEqualTo(8);
        assertThat(output(context, 2)).isEqualTo(8);
    }

    @Test
    void aMixedTemplateIsStoredAsText() {
        ExecutionContext context = run(Map.of("name", "Sara"));

        handler.execute(step(2, "greeting", "set", "Hi {{inputs.entities.name}}"), context);

        assertThat(state(context, "greeting")).isEqualTo("Hi Sara");
    }

    @Test
    void appendGrowsAListAcrossSteps() {
        ExecutionContext context = run();

        handler.execute(step(2, "items", "APPEND", "apple"), context);
        handler.execute(step(3, "items", "APPEND", "pear"), context);

        assertThat(state(context, "items")).isEqualTo(List.of("apple", "pear"));
    }

    @Test
    void incrementAddsTheSourceOrOne() {
        ExecutionContext context = run();

        handler.execute(step(2, "count", "INCREMENT", null), context);
        handler.execute(step(3, "count", "INCREMENT", "5"), context);
        handler.execute(step(4, "count", "INCREMENT", "not a number"), context);

        assertThat(state(context, "count")).isEqualTo(7);
    }

    @Test
    void aStepWithoutAVariableNameFails() {
        ExecutionContext context = run();

        StepResult result = handler.execute(step(2, null, "SET", "x"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("State variable name is missing");
        assertThat(VARIABLES.getState(context)).isEmpty();
    }

    /** Provenance is recorded so a backward JUMP can roll back exactly what the replayed steps wrote. */
    @Test
    void writesFromAReplayedStepAreRolledBack() {
        ExecutionContext context = run();
        handler.execute(step(2, "kept", "SET", "a"), context);
        handler.execute(step(4, "dropped", "SET", "b"), context);

        VARIABLES.clearStateWrittenFrom(context, 3);

        assertThat(VARIABLES.getState(context)).containsOnlyKeys("kept");
    }
}
