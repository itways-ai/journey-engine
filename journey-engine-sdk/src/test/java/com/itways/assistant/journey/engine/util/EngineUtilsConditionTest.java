package com.itways.assistant.journey.engine.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class EngineUtilsConditionTest {

    private final EngineUtils utils = new EngineUtils(new ObjectMapper());

    private final Map<String, Object> vars = Map.of(
            "steps", Map.of(
                    "2", Map.of("output", "Alice"),
                    "3", Map.of("output", Map.of("status", "OK", "count", 4, "valid", true))),
            "inputs", Map.of("age", 30));

    /** Comparisons on a step's output: these used to fail to parse and read as false. */
    @Test
    void comparisonsOnStepOutputsEvaluate() {
        assertThat(utils.evaluateCondition("steps.2.output == 'Alice'", vars)).isTrue();
        assertThat(utils.evaluateCondition("steps.2.output == 'Bob'", vars)).isFalse();
        assertThat(utils.evaluateCondition("steps.3.output.status == 'OK' and steps.3.output.count > 3", vars)).isTrue();
        assertThat(utils.evaluateCondition("{{steps.3.output.count}} >= 4", vars)).isFalse(); // placeholders only as the whole expression
    }

    @Test
    void pathOnlyAndNonStepExpressionsStillWork() {
        assertThat(utils.evaluateCondition("steps.3.output.valid", vars)).isTrue();
        assertThat(utils.evaluateCondition("{{steps.3.output.valid}}", vars)).isTrue();
        assertThat(utils.evaluateCondition("inputs.age >= 18", vars)).isTrue();
        assertThat(utils.evaluateCondition("mysteps.3 == null", vars)).isFalse();
    }
}
