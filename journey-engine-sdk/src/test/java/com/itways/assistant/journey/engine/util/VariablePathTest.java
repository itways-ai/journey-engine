package com.itways.assistant.journey.engine.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.util.VariablePath.Resolution;

class VariablePathTest {

    @Test
    void parseSplitsOnDotsAndBrackets() {
        assertThat(VariablePath.parse("steps.3.output.email")).containsExactly("steps", "3", "output", "email");
        assertThat(VariablePath.parse("items[0].name")).containsExactly("items", "0", "name");
        assertThat(VariablePath.parse(" inputs . text ")).containsExactly("inputs", "text");
    }

    @Test
    void parseUnquotesBracketKeys() {
        assertThat(VariablePath.parse("output['odd-key']")).containsExactly("output", "odd-key");
        assertThat(VariablePath.parse("output[\"a.b\"].c")).containsExactly("output", "a.b", "c");
        assertThat(VariablePath.parse("output['a]b']")).containsExactly("output", "a]b");
    }

    @Test
    void parseRejectsBlankAndMalformedPaths() {
        assertThat(VariablePath.parse(null)).isEmpty();
        assertThat(VariablePath.parse("   ")).isEmpty();
        assertThat(VariablePath.parse("items[0")).isEmpty();
        assertThat(VariablePath.parse("items[]")).isEmpty();
    }

    @Test
    void lookupWalksMapsListsAndArrays() {
        Map<String, Object> root = Map.of(
                "output", Map.of(
                        "items", List.of(Map.of("name", "tea"), Map.of("name", "cake")),
                        "tags", new String[] {"a", "b"}));

        assertThat(VariablePath.resolve(root, "output.items[1].name")).isEqualTo("cake");
        assertThat(VariablePath.resolve(root, "output.items.0.name")).isEqualTo("tea");
        assertThat(VariablePath.resolve(root, "output.tags[1]")).isEqualTo("b");
        assertThat(VariablePath.resolve(root, "output['items'][0]['name']")).isEqualTo("tea");
    }

    @Test
    void integerKeysMatchByTheirRenderedValue() {
        Map<Integer, Object> stepResults = Map.of(3, Map.of("email", "sara@example.com"));

        assertThat(VariablePath.resolve(Map.of("stepResults", stepResults), "stepResults.3.email"))
                .isEqualTo("sara@example.com");
    }

    @Test
    void presentButNullIsFoundWhileAbsentIsMissing() {
        Map<String, Object> output = new HashMap<>();
        output.put("empty", null);
        Map<String, Object> root = Map.of("output", output);

        Resolution present = VariablePath.lookup(root, "output.empty");
        assertThat(present.found()).isTrue();
        assertThat(present.value()).isNull();

        Resolution absent = VariablePath.lookup(root, "output.nope");
        assertThat(absent.found()).isFalse();
        assertThat(absent).isSameAs(Resolution.MISSING);
        assertThat(VariablePath.resolve(root, "output.nope")).isNull();
    }

    @Test
    void outOfRangeAndNonNumericIndexesAreMissing() {
        Map<String, Object> root = Map.of("items", List.of("a", "b"), "arr", new Object[] {"x"});

        assertThat(VariablePath.lookup(root, "items[2]").found()).isFalse();
        assertThat(VariablePath.lookup(root, "items[-1]").found()).isFalse();
        assertThat(VariablePath.lookup(root, "items.first").found()).isFalse();
        assertThat(VariablePath.lookup(root, "arr[1]").found()).isFalse();
        assertThat(VariablePath.lookup(root, "arr.x").found()).isFalse();
    }

    @Test
    void aScalarHasNoChildren() {
        Map<String, Object> root = Map.of("text", "hello");

        assertThat(VariablePath.lookup(root, "text.length").found()).isFalse();
        assertThat(VariablePath.lookup(null, "text").found()).isFalse();
    }

    @Test
    void anEmptyPathResolvesToNothing() {
        assertThat(VariablePath.lookup(Map.of("a", 1), "").found()).isFalse();
        assertThat(VariablePath.lookup(Map.of("a", 1), "[]").found()).isFalse();
    }
}
