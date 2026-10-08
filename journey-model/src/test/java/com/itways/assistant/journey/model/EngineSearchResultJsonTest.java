package com.itways.assistant.journey.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * A hit reads the same whichever journey-service sent it: one from before 1.0.20
 * (answer, similarity, locale), one from 1.0.20 (with the citation fields), or a
 * later one with fields this version does not know.
 */
class EngineSearchResultJsonTest {

    /** Strict on purpose: unknown fields must be ignored by the record itself, not by the caller's mapper. */
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theOldShapeReadsWithTheNewFieldsEmpty() throws Exception {
        EngineSearchResult hit = json.readValue("{\"answer\":\"We open at 9.\",\"similarity\":0.82,\"locale\":\"en\"}",
                EngineSearchResult.class);

        assertThat(hit).isEqualTo(new EngineSearchResult("We open at 9.", 0.82, "en"));
        assertThat(hit.id()).isNull();
        assertThat(hit.reason()).isNull();
        assertThat(hit.gated()).isFalse();
    }

    @Test
    void theNewShapeReadsEveryFieldAndIgnoresUnknownOnes() throws Exception {
        EngineSearchResult hit = json.readValue("""
                {"answer":"We open at 9.","similarity":0.66,"locale":"en","id":12,"indexName":"faq",
                 "question":"When do you open?","sourceId":3,"sourceName":"hours.xlsx","sourceKind":"SHEET",
                 "rowNumber":4,"url":null,"lexicalScore":0.4,"fusedScore":0.032,"reason":"LEXICAL_CORROBORATED",
                 "contentHash":"abc","somethingLater":true}
                """, EngineSearchResult.class);

        assertThat(hit).isEqualTo(new EngineSearchResult("We open at 9.", 0.66, "en", 12L, "faq",
                "When do you open?", 3L, "hours.xlsx", "SHEET", 4, null, 0.4, 0.032, "LEXICAL_CORROBORATED"));
        assertThat(hit.gated()).isTrue();
    }

    @Test
    void itWritesTheCitationFieldsAndNoHelperProperties() throws Exception {
        String written = json.writeValueAsString(new EngineSearchResult("A", 0.9, null, 1L, "faq", "Q", 2L, "s",
                "QA", null, null, 0.0, 0.016, EngineSearchResult.REASON_VECTOR));

        assertThat(written).contains("\"id\":1", "\"sourceKind\":\"QA\"", "\"reason\":\"VECTOR\"")
                .doesNotContain("gated");
    }

    @Test
    void theQuestionVectorEvidenceReadsAndIsNullWhenAbsent() throws Exception {
        EngineSearchResult hit = json.readValue("""
                {"answer":"We open at 9.","similarity":0.91,"locale":"en","id":12,"reason":"VECTOR",
                 "questionScore":0.91,"vectorMatch":"QUESTION"}
                """, EngineSearchResult.class);
        EngineSearchResult older = json.readValue("{\"answer\":\"A\",\"similarity\":0.5,\"reason\":\"VECTOR\"}",
                EngineSearchResult.class);

        assertThat(hit.questionScore()).isEqualTo(0.91);
        assertThat(hit.vectorMatch()).isEqualTo(EngineSearchResult.VECTOR_MATCH_QUESTION);
        assertThat(older.questionScore()).isNull();
        assertThat(older.vectorMatch()).isNull();
    }
}
