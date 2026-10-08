package com.itways.assistant.journey.connector;

import static com.itways.assistant.journey.connector.MockBankDescriptors.node;
import static com.itways.assistant.journey.connector.MockBankDescriptors.object;
import static com.itways.assistant.journey.connector.MockBankDescriptors.operation;
import static com.itways.assistant.journey.connector.MockBankDescriptors.ordered;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.RiskLevel;
import com.itways.assistant.journey.model.connector.SchemaNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Inputs arrive rendered as text and leave typed and placed; everything the
 * schema forbids is reported, all problems at once.
 */
class InputBinderTest {

    private static final ConnectorOperation SEARCH = operation("search", "POST", "/contacts/{tenant}/search",
            RiskLevel.LOW, true, null,
            object(ordered(
                    "tenant", node("string").in(SchemaNode.IN_PATH).build(),
                    "phone", node("string").in(SchemaNode.IN_QUERY).as("$filter")
                            .template("mobilephone eq '{value}'").build(),
                    "top", node("integer").in(SchemaNode.IN_QUERY).as("$top").range(1, 50).defaultValue(5).build(),
                    "lang", node("string").in(SchemaNode.IN_HEADER).as("Accept-Language").enumValues(List.of("en", "ar"))
                            .build(),
                    "amount", node("number").build(),
                    "urgent", node("boolean").build(),
                    "code", node("string").pattern("^[A-Z]{3}$").length(3, 3).build()),
                    List.of("tenant", "phone")),
            object(Map.of(), List.of()), null, null);

    @Test
    void placesCoercesRenamesAndWraps() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("tenant", "acme");
        inputs.put("phone", "+962790000000");
        inputs.put("top", "7");
        inputs.put("lang", "ar");
        inputs.put("amount", "12.50");
        inputs.put("urgent", "true");
        inputs.put("code", "JOD");

        InputBinder.BoundInputs bound = InputBinder.bind(SEARCH, inputs);

        assertThat(bound.path()).containsExactly(Map.entry("tenant", "acme"));
        assertThat(bound.query()).containsOnly(
                Map.entry("$filter", "mobilephone eq '+962790000000'"),
                Map.entry("$top", "7"));
        assertThat(bound.headers()).containsExactly(Map.entry("Accept-Language", "ar"));
        assertThat(bound.body()).containsOnlyKeys("amount", "urgent", "code");
        assertThat(bound.body().get("amount")).isInstanceOf(Number.class);
        assertThat(((Number) bound.body().get("amount")).doubleValue()).isEqualTo(12.5);
        assertThat(bound.body().get("urgent")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void appliesTheDefaultWhenNothingIsMapped() {
        InputBinder.BoundInputs bound = InputBinder.bind(SEARCH, Map.of("tenant", "acme", "phone", "1"));

        assertThat(bound.query()).containsEntry("$top", "5");
    }

    @Test
    void reportsEveryProblemTogether() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("top", "0");
        inputs.put("lang", "fr");
        inputs.put("amount", "lots");
        inputs.put("urgent", "maybe");
        inputs.put("code", "jod1");
        inputs.put("extra", "x");

        assertThatThrownBy(() -> InputBinder.bind(SEARCH, inputs))
                .isInstanceOfSatisfying(ConnectorException.class, e -> {
                    assertThat(e.code()).isEqualTo(ConnectorErrorCodes.INPUT_INVALID);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.details()).contains(
                            "tenant: required",
                            "phone: required",
                            "top: must be 1 or more",
                            "lang: must be one of [en, ar]",
                            "amount: must be a number",
                            "urgent: must be a boolean",
                            "code: does not match the expected format",
                            "code: must be at most 3 characters",
                            "extra: not an input of operation search");
                    assertThat(e.getMessage()).startsWith("inputs do not satisfy the operation: ");
                });
    }

    @Test
    void coercesIntegerTextAndRefusesFractions() {
        assertThat(InputBinder.bind(SEARCH, Map.of("tenant", "t", "phone", "p", "top", "12")).query())
                .containsEntry("$top", "12");
        assertThat(InputBinder.problems(SEARCH, Map.of("tenant", "t", "phone", "p", "top", "1.5")))
                .containsExactly("top: must be an integer");
        assertThat(InputBinder.problems(SEARCH, Map.of("tenant", "t", "phone", "p", "top", 60)))
                .containsExactly("top: must be 50 or less");
    }

    @Test
    void anEmptyStringCountsAsAbsent() {
        assertThat(InputBinder.problems(SEARCH, Map.of("tenant", "", "phone", "p")))
                .containsExactly("tenant: required");
    }

    @Test
    void problemsIsEmptyWhenTheInputsBind() {
        assertThat(InputBinder.problems(SEARCH, Map.of("tenant", "t", "phone", "p"))).isEmpty();
        assertThat(InputBinder.bind(MockBankDescriptors.PING, null)).isEqualTo(InputBinder.BoundInputs.NONE);
    }
}
