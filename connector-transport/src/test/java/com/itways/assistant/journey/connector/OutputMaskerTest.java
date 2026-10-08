package com.itways.assistant.journey.connector;

import static com.itways.assistant.journey.connector.MockBankDescriptors.node;
import static com.itways.assistant.journey.connector.MockBankDescriptors.object;
import static com.itways.assistant.journey.connector.MockBankDescriptors.ordered;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.model.connector.SchemaNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A sensitive output never reaches the run context, however deep the schema nests it. */
class OutputMaskerTest {

    private static final SchemaNode SCHEMA = object(ordered(
            "balance", node("number").build(),
            "iban", node("string").sensitive().build(),
            "owner", node("object").properties(ordered(
                    "name", node("string").build(),
                    "nationalId", node("string").sensitive().build()), List.of()).build(),
            "cards", node("array").items(node("object").properties(ordered(
                    "last4", node("string").build(),
                    "pan", node("string").sensitive().build()), List.of()).build()).build()),
            List.of());

    private static Map<String, Object> body() {
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("name", "Lina");
        owner.put("nationalId", "9901234567");
        owner.put("segment", "gold");
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("last4", "4242");
        card.put("pan", "4242424242424242");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("balance", 120.5);
        body.put("iban", "JO94CBJO0010000000000131000302");
        body.put("owner", owner);
        body.put("cards", List.of(card));
        body.put("internalRef", "x-1");
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void maskHidesNestedSensitiveValuesAndKeepsUndeclaredOnes() {
        Map<String, Object> masked = (Map<String, Object>) OutputMasker.mask(SCHEMA, body());

        assertThat(masked).containsEntry("balance", 120.5).containsEntry("iban", OutputMasker.MASK)
                .containsEntry("internalRef", "x-1");
        Map<String, Object> owner = (Map<String, Object>) masked.get("owner");
        assertThat(owner).containsEntry("name", "Lina").containsEntry("nationalId", OutputMasker.MASK)
                .containsEntry("segment", "gold");
        List<Map<String, Object>> cards = (List<Map<String, Object>>) masked.get("cards");
        assertThat(cards).singleElement().satisfies(card -> assertThat(card).containsEntry("last4", "4242")
                .containsEntry("pan", OutputMasker.MASK));
    }

    @Test
    @SuppressWarnings("unchecked")
    void maskAndLimitDropsUndeclaredPropertiesAtEveryLevel() {
        Map<String, Object> shaped = (Map<String, Object>) OutputMasker.maskAndLimit(SCHEMA, body());

        assertThat(shaped).containsOnlyKeys("balance", "iban", "owner", "cards");
        assertThat((Map<String, Object>) shaped.get("owner")).containsOnlyKeys("name", "nationalId")
                .containsEntry("nationalId", OutputMasker.MASK);
        assertThat((List<Map<String, Object>>) shaped.get("cards")).singleElement()
                .satisfies(card -> assertThat(card).containsOnlyKeys("last4", "pan"));
    }

    @Test
    void scalarsNullsAndSchemalessBodiesPassThrough() {
        assertThat(OutputMasker.mask(SCHEMA, null)).isNull();
        assertThat(OutputMasker.maskAndLimit(SCHEMA, "plain text")).isEqualTo("plain text");
        assertThat(OutputMasker.mask(null, Map.of("a", 1))).isEqualTo(Map.of("a", 1));
        assertThat(OutputMasker.maskAndLimit(SchemaNode.EMPTY_OBJECT, Map.of("a", 1))).isEqualTo(Map.of());
    }

    @Test
    void sensitivePathsAreDottedAndIncludeArrayItems() {
        assertThat(OutputMasker.sensitivePaths(SCHEMA)).containsExactly("iban", "owner.nationalId", "cards.pan");
        assertThat(OutputMasker.sensitivePaths(MockBankDescriptors.GET_ACCOUNT_BALANCE.output()))
                .containsExactly("iban");
    }
}
