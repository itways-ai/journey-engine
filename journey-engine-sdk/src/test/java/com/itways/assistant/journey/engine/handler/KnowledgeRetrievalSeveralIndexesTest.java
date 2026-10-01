package com.itways.assistant.journey.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.KnowledgeIndexMissingException;
import com.itways.assistant.journey.engine.service.KnowledgeMiss;
import com.itways.assistant.journey.engine.service.KnowledgeQuery;
import com.itways.assistant.journey.engine.service.KnowledgeSearchOutcome;
import com.itways.assistant.journey.engine.service.TextTranslator;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.model.EngineSearchResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * One KNOWLEDGE_RETRIEVAL step that searches several indexes ({@code apiConfig.indexNames}):
 * each searched through the port, the hits merged and de-duplicated, the same candidate
 * selection, one composition call, one gap for a miss, and a missing index skipped.
 */
class KnowledgeRetrievalSeveralIndexesTest {

    private static final String QUESTION = "how do I reset the feeder?";

    /** Hits per index; some indexes missing, some failing. Uses the port's default {@code searchEach}. */
    private static final class Indexes implements KnowledgeBasePort {
        final Map<String, List<EngineSearchResult>> hits = new HashMap<>();
        final Set<String> missing = new HashSet<>();
        final Set<String> failing = new HashSet<>();
        final List<KnowledgeQuery> queries = new ArrayList<>();
        final List<KnowledgeMiss> misses = new ArrayList<>();
        int searchEachCalls;

        @Override
        public List<EngineSearchResult> search(String accountId, UUID assistantId, String indexName,
                float[] queryVector, int limit, String locale) {
            throw new AssertionError("the step must call search(KnowledgeQuery)");
        }

        @Override
        public List<EngineSearchResult> search(KnowledgeQuery query) {
            queries.add(query);
            if (missing.contains(query.indexName())) {
                throw new RuntimeException("wrapped", new KnowledgeIndexMissingException(query.indexName()));
            }
            if (failing.contains(query.indexName())) {
                throw new IllegalStateException("500 from journey-service");
            }
            return hits.getOrDefault(query.indexName(), List.of());
        }

        @Override
        public List<KnowledgeSearchOutcome> searchEach(List<KnowledgeQuery> queries) {
            searchEachCalls++;
            return KnowledgeBasePort.super.searchEach(queries);
        }

        @Override
        public void recordMiss(KnowledgeMiss miss) {
            misses.add(miss);
        }
    }

    private static final class FixedEmbedding extends LocalEmbeddingEngine {
        FixedEmbedding() {
            super("http://localhost:1");
        }

        @Override
        public float[] embed(String text) {
            return new float[] { 1f };
        }
    }

    private static final class Model extends AiService {
        final List<AiChatRequest> requests = new ArrayList<>();
        final String reply;

        Model(String reply) {
            super(Map.of());
            this.reply = reply;
        }

        @Override
        public AiResponse chat(AiChatRequest request) {
            requests.add(request);
            return AiResponse.builder().content(reply).build();
        }

        String prompt() {
            return requests.get(0).getMessages().get(1).getContent();
        }
    }

    private static KnowledgeRetrievalStepHandler handler(KnowledgeBasePort port, AiService ai) {
        return new KnowledgeRetrievalStepHandler(new EngineUtils(new ObjectMapper()), new VariableContext(), null,
                new FixedEmbedding(), port, new EngineMessages(), TextTranslator.NONE, ai,
                ai == null ? null : accountId -> null);
    }

    private static JourneyStep step(String config) {
        return JourneyStep.builder().stepOrder(1).actionType("KNOWLEDGE_RETRIEVAL").apiConfig(config).build();
    }

    private static JourneyStep compose(String... indexes) {
        return step("{\"indexNames\":" + names(indexes) + ",\"answerMode\":\"COMPOSE\"}");
    }

    private static String names(String... indexes) {
        return Arrays.stream(indexes).map(name -> "\"" + name + "\"").toList().toString();
    }

    private static ExecutionContext run() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("text", QUESTION);
        Map<String, Object> variables = new HashMap<>();
        variables.put("inputs", inputs);
        return ExecutionContext.builder().accountId("acc-1").assistantId(UUID.randomUUID()).journeyId(7L)
                .executionId("exec-1").variables(variables).build();
    }

    /** A hit journey-service served, from {@code index}. */
    private static EngineSearchResult hit(long id, String index, String text, double score) {
        return new EngineSearchResult(text, score, "en", id, index, "Q" + id, 10L + id, index + ".xlsx", "SHEET",
                (int) id, null, 0.1, 0.03, EngineSearchResult.REASON_VECTOR);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sources(StepResult result) {
        return (List<Map<String, Object>>) result.getMetadata().get("sources");
    }

    private static ApiConfig config(String indexName, List<String> indexNames) {
        ApiConfig config = new ApiConfig();
        config.setIndexName(indexName);
        config.setIndexNames(indexNames);
        return config;
    }

    @Test
    void indexNamesWinsOverIndexNameAndIsCleanedUp() {
        assertThat(KnowledgeRetrievalStepHandler.indexNames(config("faq", Arrays.asList(" guides ", "", null,
                "web", "guides")))).containsExactly("guides", "web");
        assertThat(KnowledgeRetrievalStepHandler.indexNames(config("faq", List.of()))).containsExactly("faq");
        assertThat(KnowledgeRetrievalStepHandler.indexNames(config("faq", null))).containsExactly("faq");
        assertThat(KnowledgeRetrievalStepHandler.indexNames(config(" ", List.of(" ")))).isEmpty();
    }

    @Test
    void aSingleStringIndexNamesIsReadAsAOneNameList() {
        ApiConfig config = new EngineUtils(new ObjectMapper()).parseApiConfig("{\"indexNames\":\"faq\"}");

        assertThat(config.getIndexNames()).containsExactly("faq");
    }

    /** One name in indexNames: exactly today's step, one search through search(KnowledgeQuery). */
    @Test
    void oneNameInIndexNamesBehavesAsIndexName() {
        Indexes indexes = new Indexes();
        indexes.hits.put("faq", List.of(hit(1, "faq", "Hold the button for 5 seconds.", 0.9)));

        StepResult result = handler(indexes, null).execute(step("{\"indexNames\":[\"faq\"],\"answerMode\":\"SINGLE\"}"),
                run());

        assertThat(indexes.searchEachCalls).isZero();
        assertThat(indexes.queries).extracting(KnowledgeQuery::indexName).containsExactly("faq");
        assertThat(result.getData()).isEqualTo("Hold the button for 5 seconds.");
        assertThat(result.getMetadata()).containsEntry("indexName", "faq")
                .containsEntry("indexNames", List.of("faq")).doesNotContainKey("warnings");
    }

    @Test
    void everyIndexIsSearchedWithTheSameQueryAndOneModelCallComposesTheAnswer() {
        Indexes indexes = new Indexes();
        indexes.hits.put("faq", List.of(hit(1, "faq", "The feeder has a reset button.", 0.62)));
        indexes.hits.put("guides", List.of(hit(2, "guides", "Hold it for 5 seconds.", 0.58)));
        indexes.hits.put("web", List.of(hit(3, "web", "Unplug it first.", 0.51)));
        Model model = new Model("Unplug the feeder, then hold its reset button for 5 seconds.");

        StepResult result = handler(indexes, model).execute(compose("faq", "guides", "web"), run());

        assertThat(indexes.searchEachCalls).isEqualTo(1);
        assertThat(indexes.queries).extracting(KnowledgeQuery::indexName).containsExactly("faq", "guides", "web");
        assertThat(indexes.queries).allSatisfy(query -> {
            assertThat(query.recall()).isTrue();
            assertThat(query.limit()).isEqualTo(16);
            assertThat(query.threshold()).isEqualTo(0.34);
            assertThat(query.query()).isEqualTo(QUESTION);
        });
        assertThat(model.requests).hasSize(1);
        assertThat(model.prompt()).contains("The feeder has a reset button.", "Hold it for 5 seconds.",
                "Unplug it first.");
        assertThat(result.getData()).isEqualTo("Unplug the feeder, then hold its reset button for 5 seconds.");
        assertThat(result.getMetadata()).containsEntry("found", true).containsEntry("composed", true)
                .containsEntry("indexName", "faq").containsEntry("indexNames", List.of("faq", "guides", "web"));
        assertThat(sources(result)).extracting(source -> source.get("indexName"))
                .containsExactly("faq", "guides", "web");
    }

    /** The best 4 by similarity across the indexes first, then the rest in the merged order, up to 8. */
    @Test
    void theCandidatesAreTheBestFourByMeaningThenTheRestUpToEight() {
        Indexes indexes = new Indexes();
        indexes.hits.put("faq", List.of(hit(1, "faq", "F1", 0.50), hit(2, "faq", "F2", 0.49),
                hit(3, "faq", "F3", 0.48), hit(4, "faq", "F4", 0.47), hit(5, "faq", "F5", 0.46),
                hit(6, "faq", "F6", 0.45)));
        indexes.hits.put("guides", List.of(hit(11, "guides", "G1", 0.40), hit(12, "guides", "G2", 0.80),
                hit(13, "guides", "G3", 0.79), hit(14, "guides", "G4", 0.39), hit(15, "guides", "G5", 0.38),
                hit(16, "guides", "G6", 0.37)));
        Model model = new Model("An answer.");

        StepResult result = handler(indexes, model).execute(compose("faq", "guides"), run());

        // Head: G2, G3, F1, F2 by similarity. Tail in the merged (interleaved by rank) order:
        // F1, G1, F2, G2, F3, G3, F4, G4 ... less those already shown.
        assertThat(sources(result)).extracting(source -> source.get("id"))
                .containsExactly(12L, 13L, 1L, 2L, 11L, 3L, 4L, 14L);
    }

    @Test
    void aPassageFoundInTwoIndexesIsShownOnceTheBetterCopy() {
        Indexes indexes = new Indexes();
        indexes.hits.put("faq", List.of(hit(1, "faq", "Hold the reset button  for 5 seconds.", 0.55),
                hit(2, "faq", "Feeding times are set in the app.", 0.50)));
        indexes.hits.put("web", List.of(hit(7, "web", "hold the reset button for 5 seconds.", 0.61)));
        Model model = new Model("Hold the reset button for 5 seconds.");

        StepResult result = handler(indexes, model).execute(compose("faq", "web"), run());

        assertThat(sources(result)).extracting(source -> source.get("id")).containsExactly(7L, 2L);
        assertThat(sources(result).get(0)).containsEntry("indexName", "web");
    }

    /** SINGLE: the best hit across the indexes, as stored; no model. */
    @Test
    void singleServesTheBestHitAcrossTheIndexes() {
        Indexes indexes = new Indexes();
        indexes.hits.put("faq", List.of(hit(1, "faq", "Weak FAQ answer.", 0.55)));
        indexes.hits.put("guides", List.of(hit(2, "guides", "The guide's answer.", 0.81)));
        Model model = new Model("unused");

        StepResult result = handler(indexes, model).execute(
                step("{\"indexNames\":[\"faq\",\"guides\"],\"answerMode\":\"SINGLE\",\"limit\":1}"), run());

        assertThat(model.requests).isEmpty();
        assertThat(result.getData()).isEqualTo("The guide's answer.");
        assertThat(sources(result)).singleElement().satisfies(source ->
                assertThat(source).containsEntry("indexName", "guides"));
    }

    @Test
    void aMissRecordsOneGapNamingEveryIndex() {
        Indexes indexes = new Indexes();
        indexes.hits.put("faq", List.of(hit(1, "faq", "Feeding times.", 0.50)));
        indexes.hits.put("guides", List.of(hit(2, "guides", "Cleaning the bowl.", 0.45)));
        Model model = new Model("NO_ANSWER");

        StepResult result = handler(indexes, model).execute(compose("faq", "guides", "web"), run());

        assertThat(result.getMetadata()).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED")
                .containsEntry("gapReported", true).containsEntry("indexNames", List.of("faq", "guides", "web"));
        assertThat(indexes.misses).singleElement().satisfies(miss -> {
            assertThat(miss.indexNames()).containsExactly("faq", "guides", "web");
            assertThat(miss.indexName()).isEqualTo("faq");
            assertThat(miss.bestScore()).isEqualTo(0.50);
        });
    }

    @Test
    void aMissingIndexAmongSeveralIsSkippedWithAWarning() {
        Indexes indexes = new Indexes();
        indexes.missing.add("guides");
        indexes.hits.put("faq", List.of(hit(1, "faq", "Feeding times.", 0.50)));
        indexes.hits.put("web", List.of(hit(3, "web", "Cleaning the bowl.", 0.45)));
        Model model = new Model("NO_ANSWER");

        StepResult result = handler(indexes, model).execute(compose("faq", "guides", "web"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMetadata()).containsEntry("missingIndexNames", List.of("guides"))
                .containsEntry("indexNames", List.of("faq", "web")).containsEntry("gapReported", true);
        assertThat((List<?>) result.getMetadata().get("warnings")).singleElement().asString().contains("guides");
        // The gap names the indexes that were searched: nothing added to a missing one could answer.
        assertThat(indexes.misses).singleElement()
                .satisfies(miss -> assertThat(miss.indexNames()).containsExactly("faq", "web"));
    }

    @Test
    void anAnswerStillComesFromTheOtherIndexesWhenOneIsMissing() {
        Indexes indexes = new Indexes();
        indexes.missing.add("faq");
        indexes.hits.put("web", List.of(hit(3, "web", "Hold it for 5 seconds.", 0.90)));

        StepResult result = handler(indexes, null).execute(
                step("{\"indexNames\":[\"faq\",\"web\"],\"answerMode\":\"SINGLE\"}"), run());

        assertThat(result.getData()).isEqualTo("Hold it for 5 seconds.");
        assertThat(result.getMetadata()).containsEntry("found", true).containsEntry("indexName", "web")
                .containsEntry("missingIndexNames", List.of("faq"));
    }

    @Test
    void theStepFailsOnlyWhenEveryIndexIsMissing() {
        Indexes indexes = new Indexes();
        indexes.missing.addAll(List.of("faq", "guides"));

        StepResult result = handler(indexes, new Model("unused")).execute(compose("faq", "guides"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("KNOWLEDGE_INDEX_MISSING");
        assertThat(result.getMetadata()).containsEntry("errorCode", "INDEX_NOT_FOUND")
                .containsEntry("missingIndexNames", List.of("faq", "guides"));
        assertThat(indexes.misses).isEmpty();
    }

    /** A search that failed (not a missing index) may have held the answer: skipped, and no gap. */
    @Test
    void aFailedSearchAmongSeveralIsSkippedAndTheMissIsNoGap() {
        Indexes indexes = new Indexes();
        indexes.failing.add("guides");
        indexes.hits.put("faq", List.of(hit(1, "faq", "Feeding times.", 0.50), hit(2, "faq", "Cleaning.", 0.45)));

        StepResult result = handler(indexes, new Model("NO_ANSWER")).execute(compose("faq", "guides"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMetadata()).containsEntry("found", false).containsEntry("gapReported", false)
                .containsEntry("failedIndexNames", List.of("guides"));
        assertThat(indexes.misses).isEmpty();
    }

    @Test
    void everySearchFailingFailsTheStepWithTheGenericMessage() {
        Indexes indexes = new Indexes();
        indexes.failing.addAll(List.of("faq", "guides"));

        StepResult result = handler(indexes, null).execute(compose("faq", "guides"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("The knowledge base could not be searched right now");
    }

    /** An adapter that does not say which index a hit came from: the citation still says it. */
    @Test
    void aHitWithoutAnIndexIsCitedWithTheIndexItWasSearchedIn() {
        Indexes indexes = new Indexes();
        indexes.hits.put("faq", List.of(new EngineSearchResult("FAQ answer.", 0.70, null)));
        indexes.hits.put("guides", List.of(new EngineSearchResult("Guide answer.", 0.66, null)));

        StepResult result = handler(indexes, new Model("Both.")).execute(compose("faq", "guides"), run());

        assertThat(sources(result)).extracting(source -> source.get("indexName")).containsExactly("faq", "guides");
    }

    @Test
    void thePortsDefaultSearchEachKeepsOrderAndCarriesFailures() {
        Indexes indexes = new Indexes();
        indexes.missing.add("guides");
        indexes.hits.put("faq", List.of(hit(1, "faq", "A.", 0.5)));
        List<KnowledgeQuery> queries = List.of("faq", "guides", "web").stream()
                .map(index -> new KnowledgeQuery("acc-1", null, index, "q", new float[] { 1f }, 5, "en", 0.38,
                        null, null))
                .toList();

        List<KnowledgeSearchOutcome> outcomes = indexes.searchEach(queries);

        assertThat(outcomes).extracting(KnowledgeSearchOutcome::indexName).containsExactly("faq", "guides", "web");
        assertThat(outcomes.get(0).succeeded()).isTrue();
        assertThat(outcomes.get(0).hits()).hasSize(1);
        assertThat(outcomes.get(1).missing()).isTrue();
        assertThat(outcomes.get(2).succeeded()).isTrue();
        assertThat(outcomes.get(2).hits()).isEmpty();
    }

    @Test
    void aMissInOneIndexKeepsTheOldShape() {
        KnowledgeMiss miss = new KnowledgeMiss("acc-1", null, "faq", "q", "en", null, null, null, null, null, 0.38,
                KnowledgeMiss.REASON_NO_RESULTS, null, null);

        assertThat(miss.indexNames()).containsExactly("faq");
        assertThat(miss.toString()).contains("indexes=[faq]");
    }
}
