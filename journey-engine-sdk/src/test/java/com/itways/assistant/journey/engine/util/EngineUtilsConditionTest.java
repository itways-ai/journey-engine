package com.itways.assistant.journey.engine.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
    }

    @Test
    void pathOnlyAndNonStepExpressionsStillWork() {
        assertThat(utils.evaluateCondition("steps.3.output.valid", vars)).isTrue();
        assertThat(utils.evaluateCondition("{{steps.3.output.valid}}", vars)).isTrue();
        assertThat(utils.evaluateCondition("inputs.age >= 18", vars)).isTrue();
        assertThat(utils.evaluateCondition("mysteps.3 == null", vars)).isFalse();
    }

    /**
     * JRN-20: braces around a path inside a comparison used to make SpEL read a
     * nested list, so the condition was always false. They are now read as the path.
     */
    @Test
    void placeholderBracesInsideAComparisonAreReadAsThePath() {
        assertThat(utils.evaluateCondition("{{steps.3.output.count}} >= 4", vars)).isTrue();
        assertThat(utils.evaluateCondition("{{ steps.3.output.count }} > 4", vars)).isFalse();
        assertThat(utils.evaluateCondition("{{steps.3.output.status}} == 'OK' and {{inputs.age}} >= 18", vars)).isTrue();
    }

    @Test
    void bracesInQuotedTextAndSpelInlineListsAreLeftAlone() {
        assertThat(EngineUtils.stripPlaceholderBraces("inputs.text == '{{name}}'"))
                .isEqualTo("inputs.text == '{{name}}'");
        assertThat(EngineUtils.stripPlaceholderBraces("{1,2,3}.contains(inputs.age)"))
                .isEqualTo("{1,2,3}.contains(inputs.age)");
        assertThat(EngineUtils.stripPlaceholderBraces("{{steps.3.output.count}} >= 4"))
                .isEqualTo("steps.3.output.count >= 4");
    }
}
