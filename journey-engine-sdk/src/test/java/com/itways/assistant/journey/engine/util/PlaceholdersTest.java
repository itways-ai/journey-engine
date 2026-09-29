package com.itways.assistant.journey.engine.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    private final Map<String, Object> root = variables();

    private static Map<String, Object> variables() {
        Map<String, Object> output = new HashMap<>();
        output.put("score", 8);
        output.put("items", List.of(Map.of("name", "tea"), Map.of("name", "cake")));
        output.put("nothing", null);
        return Map.of(
                "inputs", Map.of("text", "hello", "price", "$5 \\ off"),
                "steps", Map.of("2", Map.of("output", output)));
    }

    @AfterEach
    void clearDiagnostics() {
        VariableDiagnostics.reset();
    }

    @Test
    void onlyDoubleBracesArePlaceholders() {
        assertThat(Placeholders.contains(null)).isFalse();
        assertThat(Placeholders.contains("plain text")).isFalse();
        assertThat(Placeholders.contains("Hi {{inputs.text}}")).isTrue();

        assertThat(Placeholders.replace("{inputs.text} ${inputs.text} <%inputs.text%>", root))
                .isEqualTo("{inputs.text} ${inputs.text} <%inputs.text%>");
    }

    @Test
    void textWithoutPlaceholdersComesBackUnchanged() {
        String text = "no variables here";
        assertThat(Placeholders.replace(text, root)).isSameAs(text);
        assertThat(Placeholders.replace(null, root)).isNull();
        assertThat(Placeholders.resolve(null, root)).isNull();
    }

    @Test
    void replaceSubstitutesEveryPlaceholderAndTrimsTheInnerPath() {
        assertThat(Placeholders.replace("Say {{inputs.text}}, score {{ steps.2.output.score }}.", root))
                .isEqualTo("Say hello, score 8.");
    }

    @Test
    void theInnerPathMayIndexIntoLists() {
        assertThat(Placeholders.replace("{{steps.2.output.items[1].name}} and {{steps.2.output.items.0.name}}", root))
                .isEqualTo("cake and tea");
    }

    @Test
    void replacementValuesAreInsertedLiterally() {
        assertThat(Placeholders.replace("Now {{inputs.price}}", root)).isEqualTo("Now $5 \\ off");
    }

    @Test
    void anAbsentPathRendersEmptyAndIsReported() {
        VariableDiagnostics.open();

        assertThat(Placeholders.replace("[{{inputs.missing}}]", root)).isEqualTo("[]");

        assertThat(VariableDiagnostics.close()).containsExactly("inputs.missing");
    }

    @Test
    void aPresentNullRendersEmptyAndIsNotReported() {
        VariableDiagnostics.open();

        assertThat(Placeholders.replace("[{{steps.2.output.nothing}}]", root)).isEqualTo("[]");

        assertThat(VariableDiagnostics.close()).isEmpty();
    }

    @Test
    void withNoOpenFrameAnAbsentPathIsNotAnError() {
        assertThat(Placeholders.replace("[{{inputs.missing}}]", root)).isEqualTo("[]");
        assertThat(VariableDiagnostics.close()).isEmpty();
    }

    @Test
    void aLonePlaceholderKeepsTheValuesType() {
        assertThat(Placeholders.resolve("{{steps.2.output.score}}", root)).isEqualTo(8);
        assertThat(Placeholders.resolve("  {{ steps.2.output.items }} ", root)).isInstanceOf(List.class);
        assertThat(Placeholders.resolve("{{steps.2.output.score}}/10", root)).isEqualTo("8/10");
        assertThat(Placeholders.resolve("plain", root)).isEqualTo("plain");
    }

    @Test
    void aLoneAbsentPlaceholderResolvesToNullAndIsReported() {
        VariableDiagnostics.open();

        assertThat(Placeholders.resolve("{{inputs.missing}}", root)).isNull();

        assertThat(VariableDiagnostics.close()).containsExactly("inputs.missing");
    }

    @Test
    void referencedPathsAreDistinctAndInOrder() {
        assertThat(Placeholders.referencedPaths("{{b}} {{a}} {{ b }} {{c.d}}")).containsExactly("b", "a", "c.d");
        assertThat(Placeholders.referencedPaths("none")).isEmpty();
        assertThat(Placeholders.referencedPaths(null)).isEmpty();
    }

    @Test
    void unresolvedPathsListsOnlyThePathsThatDoNotExist() {
        assertThat(Placeholders.unresolvedPaths(
                "{{inputs.text}} {{inputs.nope}} {{steps.2.output.nothing}} {{steps.9.output}}", root))
                .containsExactly("inputs.nope", "steps.9.output");
    }
}
