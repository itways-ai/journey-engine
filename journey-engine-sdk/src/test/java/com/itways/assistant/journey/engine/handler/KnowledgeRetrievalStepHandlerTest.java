package com.itways.assistant.journey.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.context.Simulation;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.AiConfigProvider;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.KnowledgeIndexMissingException;
import com.itways.assistant.journey.engine.service.KnowledgeMiss;
import com.itways.assistant.journey.engine.service.KnowledgeQuery;
import com.itways.assistant.journey.engine.service.TextTranslator;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.EngineSearchResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import com.itways.assistant.journey.model.catalog.OutputField;
import com.itways.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The KNOWLEDGE_RETRIEVAL step: scope, threshold, limit, diversity, gaps,
 * citations and what it logs.
 *
 * <p>
 * A knowledge step searches in the run's assistant scope: the index name means
 * that assistant's own index, else the shared one. A shared journey run for one
 * assistant must never be handed another assistant's index of the same name.
 */
class KnowledgeRetrievalStepHandlerTest {

    /** What the port was asked, and one passage to answer with. A 1.0.19 adapter: positional search only. */
    private static final class RecordingPort implements KnowledgeBasePort {
        final List<Object[]> calls = new ArrayList<>();

        @Override
        public List<EngineSearchResult> search(String accountId, UUID assistantId, String indexName,
                float[] queryVector, int limit, String locale) {
            calls.add(new Object[] { accountId, assistantId, indexName });
            return List.of(new EngineSearchResult("We deliver to Irbid.", 0.9, null));
        }
    }

    /** A 1.0.20 adapter: takes the whole query, answers with scripted hits, keeps the misses. */
    private static final class ScriptedPort implements KnowledgeBasePort {
        final List<KnowledgeQuery> queries = new ArrayList<>();
        final List<KnowledgeMiss> misses = new ArrayList<>();
        List<EngineSearchResult> hits = List.of();
        RuntimeException failure;
        RuntimeException missFailure;

        @Override
        public List<EngineSearchResult> search(String accountId, UUID assistantId, String indexName,
                float[] queryVector, int limit, String locale) {
            throw new AssertionError("a 1.0.20 step must call search(KnowledgeQuery)");
        }

        @Override
        public List<EngineSearchResult> search(KnowledgeQuery query) {
            queries.add(query);
            if (failure != null) {
                throw failure;
            }
            return hits;
        }

        @Override
        public void recordMiss(KnowledgeMiss miss) {
            misses.add(miss);
            if (missFailure != null) {
                throw missFailure;
            }
        }
    }

    /** No Ollama: every text gets the same vector. */
    private static final class FixedEmbedding extends LocalEmbeddingEngine {
        FixedEmbedding() {
            super("http://localhost:1");
        }

        @Override
        public float[] embed(String text) {
            return new float[] { 1f };
        }
    }

    /** A model that answers with a script, or fails, and keeps what it was asked. */
    private static final class ScriptedAi extends AiService {
        final List<AiChatRequest> requests = new ArrayList<>();
        final String reply;
        final boolean fail;

        private ScriptedAi(String reply, boolean fail) {
            super(Map.of());
            this.reply = reply;
            this.fail = fail;
        }

        static ScriptedAi replying(String reply) {
            return new ScriptedAi(reply, false);
        }

        static ScriptedAi failing() {
            return new ScriptedAi(null, true);
        }

        @Override
        public AiResponse chat(AiChatRequest request) {
            requests.add(request);
            if (fail) {
                return AiResponse.failure(AiError.builder().kind(AiError.Kind.RATE_LIMITED).provider("OLLAMA")
                        .providerStatus(429).message("busy").build());
            }
            return AiResponse.builder().content(reply).build();
        }

        String system() {
            return requests.get(0).getMessages().get(0).getContent();
        }

        String prompt() {
            return requests.get(0).getMessages().get(1).getContent();
        }
    }

    private static final String QUESTION = "do you deliver to Irbid?";

    private static final String UNAVAILABLE_TEXT = "I cannot answer that right now. Please try again in a moment.";

    private final RecordingPort port = new RecordingPort();
    private final KnowledgeRetrievalStepHandler handler = handler(port);

    private static KnowledgeRetrievalStepHandler handler(KnowledgeBasePort port) {
        return new KnowledgeRetrievalStepHandler(new EngineUtils(new ObjectMapper()), new VariableContext(), null,
                new FixedEmbedding(), port, new EngineMessages(), TextTranslator.NONE, null, null);
    }

    private static JourneyStep step() {
        return step("");
    }

    private static KnowledgeRetrievalStepHandler composing(KnowledgeBasePort port, AiService ai) {
        return composing(port, ai, accountId -> null);
    }

    private static KnowledgeRetrievalStepHandler composing(KnowledgeBasePort port, AiService ai,
                                                           AiConfigProvider configs) {
        return new KnowledgeRetrievalStepHandler(new EngineUtils(new ObjectMapper()), new VariableContext(), null,
                new FixedEmbedding(), port, new EngineMessages(), TextTranslator.NONE, ai, configs);
    }

    /** What conversation-service's config provider throws for an account with no AI provider (ACC-08). */
    private static BusinessException notConfigured() {
        return new BusinessException("No AI provider is set up for this account.",
                AiConfigProvider.NOT_CONFIGURED, 422);
    }

    /** A COMPOSE step on index {@code faq}, with extra apiConfig members. */
    private static JourneyStep composeStep(String extraConfig) {
        return JourneyStep.builder().stepOrder(1).actionType("KNOWLEDGE_RETRIEVAL")
                .apiConfig("{\"indexName\":\"faq\",\"answerMode\":\"COMPOSE\"" + extraConfig + "}").build();
    }

    /** A later COMPOSE step of a chain (FAQ, then guides), on index {@code guides}. */
    private static JourneyStep guidesStep() {
        return JourneyStep.builder().stepOrder(3).actionType("KNOWLEDGE_RETRIEVAL")
                .apiConfig("{\"indexName\":\"guides\",\"answerMode\":\"COMPOSE\"}").build();
    }

    /** {@code count} candidates journey-service served on the recall floor, best first. */
    private static List<EngineSearchResult> candidates(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> cited(i + 1, "Passage " + (i + 1) + ".", 0.80 - i * 0.03, EngineSearchResult.REASON_VECTOR))
                .toList();
    }


    /** A SINGLE-answer step on index {@code faq}, with extra apiConfig members (e.g. {@code ,"limit":2}). */
    private static JourneyStep step(String extraConfig) {
        return JourneyStep.builder().stepOrder(1).actionType("KNOWLEDGE_RETRIEVAL")
                .apiConfig("{\"indexName\":\"faq\",\"answerMode\":\"SINGLE\"" + extraConfig + "}").build();
    }

    private static ExecutionContext run(UUID assistantId) {
        return run(assistantId, QUESTION);
    }

    private static ExecutionContext run(UUID assistantId, String text) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("text", text);
        Map<String, Object> channel = new HashMap<>();
        channel.put("type", "WEB");
        Map<String, Object> variables = new HashMap<>();
        variables.put("inputs", inputs);
        variables.put("channel", channel);
        return ExecutionContext.builder().accountId("acc-1").assistantId(assistantId).journeyId(7L)
                .executionId("exec-1").variables(variables).build();
    }

    /** A hit journey-service gated and cited (1.0.20). */
    private static EngineSearchResult cited(long id, String answer, double vectorScore, String reason) {
        return new EngineSearchResult(answer, vectorScore, "en", id, "faq", "Question " + id, 40L + id,
                "prices.xlsx", "SHEET", (int) id + 1, null, 0.25, 0.031, reason);
    }

    /**
     * A Q&A row served on its vector, with the question-vector evidence: {@code onQuestion} when its
     * question-only vector matched best (score {@code questionScore}), else its answer vector did.
     */
    private static EngineSearchResult matched(long id, String answer, double similarity, String reason,
                                              Double questionScore, boolean onQuestion) {
        return new EngineSearchResult(answer, similarity, "en", id, "faq", "Question " + id, 40L + id,
                "prices.xlsx", "SHEET", (int) id + 1, null, 0.25, 0.031, reason, questionScore,
                onQuestion ? EngineSearchResult.VECTOR_MATCH_QUESTION : EngineSearchResult.VECTOR_MATCH_ANSWER);
    }

    /** A Q&A row whose stored question matched the question at {@code score} ({@code VECTOR}, {@code QUESTION}). */
    private static EngineSearchResult onQuestion(long id, String answer, double score) {
        return matched(id, answer, score, EngineSearchResult.REASON_VECTOR, score, true);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sources(StepResult result) {
        return (List<Map<String, Object>>) result.getMetadata().get("sources");
    }

    @Test
    void theSearchRunsInTheRunsAssistantScope() {
        UUID assistant = UUID.randomUUID();

        StepResult result = handler.execute(step(), run(assistant));

        assertThat(result.getData()).isEqualTo("We deliver to Irbid.");
        assertThat(port.calls).hasSize(1);
        assertThat(port.calls.get(0)).containsExactly("acc-1", assistant, "faq");
    }

    @Test
    void aRunWithoutAnAssistantSearchesSharedIndexesOnly() {
        handler.execute(step(), run(null));

        assertThat(port.calls.get(0)).containsExactly("acc-1", null, "faq");
    }

    /** JRN-18: the step message goes to run history, so no internal address or body. */
    @Test
    void aFailedSearchStoresAGenericMessageNotTheInternalDetail() {
        KnowledgeBasePort failing = (accountId, assistantId, indexName, vector, limit, locale) -> {
            throw new IllegalStateException(
                    "500 on POST http://172.19.0.5:8085/internal/knowledge/search: {\"trace\":\"secret\"}");
        };

        StepResult result = handler(failing).execute(step(), run(UUID.randomUUID()));

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("The knowledge base could not be searched right now");
        assertThat(result.getMessage()).doesNotContain("172.19", "internal", "trace");
    }

    /**
     * LIB-10: a provider failure while composing must never become the answer. A stored entry is used
     * only when it is a sure match (journey.knowledge.synthesis.sure-match-threshold, 0.85, on the stored
     * question); otherwise the step asks the user to try again, and records no gap: the answer may well be there.
     */
    @Test
    void aFailedCompositionFallsBackToTheStoredAnswerNotTheProviderError() {
        AiService failingAi = new AiService(Map.of()) {
            @Override
            public AiResponse chat(AiChatRequest request) {
                return AiResponse.failure(AiError.builder().kind(AiError.Kind.RATE_LIMITED).provider("GROQ")
                        .providerStatus(429).message("Rate limit reached").build());
            }
        };
        ScriptedPort sure = new ScriptedPort();
        sure.hits = List.of(onQuestion(1, "We deliver to Irbid.", 0.91),
                cited(2, "Delivery takes two days.", 0.50, "VECTOR"));
        ScriptedPort unsure = new ScriptedPort();
        unsure.hits = List.of(onQuestion(1, "We deliver to Irbid.", 0.70),
                cited(2, "Delivery takes two days.", 0.40, "VECTOR"));

        StepResult served = composing(sure, failingAi).execute(composeStep(""), run(UUID.randomUUID()));
        StepResult notServed = composing(unsure, failingAi).execute(composeStep(""), run(UUID.randomUUID()));

        assertThat(served.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(served.getData()).isEqualTo("We deliver to Irbid.");
        assertThat(served.getMetadata()).containsEntry("found", true).containsEntry("composed", false)
                .containsEntry("sourceCount", 1);
        assertThat(notServed.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(notServed.getData()).isEqualTo("I cannot answer that right now. Please try again in a moment.");
        assertThat(notServed.getMetadata()).containsEntry("found", false).containsEntry("composed", false)
                .containsEntry("missReason", "GENERATION_FAILED").containsEntry("gapReported", false);
        assertThat(sure.misses).isEmpty();
        assertThat(unsure.misses).isEmpty();
    }

    /** B01: the step's threshold is honoured, clamped to 0.30..0.95; absent is 0.38 (0.70 before the embedding switch). */
    @Nested
    class Threshold {

        /** An adapter from before 1.0.20: hits carry no reason, so the step gates them itself. */
        private StepResult runWithHit(double vectorScore, String extraConfig) {
            KnowledgeBasePort old = (accountId, assistantId, indexName, vector, limit, locale) -> List.of(
                    new EngineSearchResult("We open at 9.", vectorScore, null));
            return handler(old).execute(step(extraConfig), run(UUID.randomUUID()));
        }

        @Test
        void aThresholdOf099MissesAHitThatALowerThresholdServes() {
            StepResult strict = runWithHit(0.93, ",\"threshold\":0.99");
            StepResult lenient = runWithHit(0.93, ",\"threshold\":0.60");

            // 0.99 is clamped to 0.95, which 0.93 does not reach.
            assertThat(strict.getData()).isEqualTo("I do not have information about this.");
            assertThat(strict.getMetadata()).containsEntry("found", false).containsEntry("threshold", 0.95);
            assertThat(lenient.getData()).isEqualTo("We open at 9.");
            assertThat(lenient.getMetadata()).containsEntry("found", true);
        }

        @Test
        void aStepAt060ServesA065HitThatAStepAt080Refuses() {
            assertThat(runWithHit(0.65, ",\"threshold\":0.60").getData()).isEqualTo("We open at 9.");
            assertThat(runWithHit(0.65, ",\"threshold\":0.80").getData())
                    .isEqualTo("I do not have information about this.");
        }

        /** The knowledge base's own embedding model's "sure match" (the embedding switch; 0.70 before). */
        @Test
        void withoutAThresholdTheFloorIs038() {
            assertThat(runWithHit(0.39, "").getData()).isEqualTo("We open at 9.");
            assertThat(runWithHit(0.37, "").getData()).isEqualTo("I do not have information about this.");
        }

        @Test
        void theClampedThresholdIsSentToThePort() {
            ScriptedPort scripted = new ScriptedPort();
            KnowledgeRetrievalStepHandler h = handler(scripted);

            h.execute(step(",\"threshold\":0.99"), run(null));
            h.execute(step(",\"threshold\":0.1"), run(null));
            h.execute(step(",\"threshold\":0.82"), run(null));
            h.execute(step(), run(null));

            assertThat(scripted.queries).extracting(KnowledgeQuery::threshold)
                    .containsExactly(0.95, 0.30, 0.82, 0.38);
        }

        /** journey-service's gate admitted it (full-text evidence within the relax): the step trusts it. */
        @Test
        void aHitJourneyServiceGatedIsServedSlightlyUnderTheThreshold() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "We open at 9.", 0.67, EngineSearchResult.REASON_LEXICAL_CORROBORATED));

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(result.getData()).isEqualTo("We open at 9.");
            assertThat(sources(result).get(0)).containsEntry("reason", "LEXICAL_CORROBORATED");
            assertThat(scripted.misses).isEmpty();
        }

        @Test
        void aHitMarkedBelowThresholdIsNeverServed() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "We open at 9.", 0.9, EngineSearchResult.REASON_BELOW_THRESHOLD));

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(result.getMetadata()).containsEntry("found", false);
        }

        /**
         * R9: only VECTOR and LEXICAL_CORROBORATED mean "served". Any other reason
         * (journey-service's BELOW_LOCALE_THRESHOLD, or one this build does not
         * know) is a drop, whatever the score; a hit without a reason still has to
         * reach the step's threshold here.
         */
        @Test
        void onlyTheServedReasonsAreTrusted() {
            List<EngineSearchResult> hits = List.of(
                    cited(1, "Vector.", 0.60, EngineSearchResult.REASON_VECTOR),
                    cited(2, "Corroborated.", 0.66, EngineSearchResult.REASON_LEXICAL_CORROBORATED),
                    cited(3, "Below.", 0.95, EngineSearchResult.REASON_BELOW_THRESHOLD),
                    cited(4, "Below its language's bar.", 0.95, "BELOW_LOCALE_THRESHOLD"),
                    cited(5, "Unknown reason.", 0.95, "SOMETHING_NEW"),
                    cited(6, "Lower case.", 0.95, "vector"),
                    new EngineSearchResult("No reason, above.", 0.71, "en"),
                    new EngineSearchResult("No reason, below.", 0.69, "en"),
                    cited(9, "Blank reason, below.", 0.69, " "));

            assertThat(KnowledgeRetrievalStepHandler.served(hits, 0.70, 20))
                    .extracting(EngineSearchResult::answer)
                    .containsExactly("Vector.", "Corroborated.", "No reason, above.");
        }

        /** LEXICAL_RECALL is for a recall search (a composing step) only; a SINGLE step drops it, whatever it scores. */
        @Test
        void aLexicalRecallHitIsServedToARecallSearchOnly() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "Enable FLAG_SECURE.", 0.90, EngineSearchResult.REASON_LEXICAL_RECALL));
            List<EngineSearchResult> hits = List.of(cited(1, "Recall.", 0.25, EngineSearchResult.REASON_LEXICAL_RECALL),
                    cited(2, "Vector.", 0.50, EngineSearchResult.REASON_VECTOR));

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(result.getMetadata()).containsEntry("found", false);
            assertThat(KnowledgeRetrievalStepHandler.served(hits, 0.34, 20)).extracting(EngineSearchResult::id)
                    .containsExactly(2L);
            assertThat(KnowledgeRetrievalStepHandler.served(hits, 0.34, 20, true)).extracting(EngineSearchResult::id)
                    .containsExactly(1L, 2L);
            assertThat(KnowledgeRetrievalStepHandler.storable(hits.get(0))).isFalse();
            assertThat(KnowledgeRetrievalStepHandler.storable(hits.get(1))).isTrue();
            assertThat(KnowledgeRetrievalStepHandler.storable(new EngineSearchResult("Ungated.", 0.5, null))).isTrue();
        }

        @Test
        void aHitWithAnUnknownReasonIsNotServedEvenAboveTheThreshold() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "We open at 9.", 0.99, "BELOW_LOCALE_THRESHOLD"));

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(result.getMetadata()).containsEntry("found", false);
        }
    }

    /** E04: the step's limit is sent to the port and never exceeded; 1..20, absent is 5. */
    @Nested
    class Limit {

        @Test
        void theLimitIsSentToThePortAndRespected() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = IntStream.range(0, 6)
                    .mapToObj(i -> cited(i, "Answer " + i, 0.9 - i * 0.01, EngineSearchResult.REASON_VECTOR))
                    .toList();

            StepResult result = handler(scripted).execute(step(",\"limit\":2"), run(null));

            assertThat(scripted.queries.get(0).limit()).isEqualTo(2);
            assertThat(sources(result)).hasSize(2);
            assertThat(result.getMetadata()).containsEntry("sourceCount", 2);
        }

        @Test
        void aLimitOfOneCitesOnePassage() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "A", 0.9, "VECTOR"), cited(2, "B", 0.88, "VECTOR"));

            StepResult result = handler(scripted).execute(step(",\"limit\":1"), run(null));

            assertThat(sources(result)).hasSize(1);
        }

        @Test
        void theLimitIsClampedToOneToTwenty() {
            ScriptedPort scripted = new ScriptedPort();
            KnowledgeRetrievalStepHandler h = handler(scripted);

            h.execute(step(",\"limit\":50"), run(null));
            h.execute(step(",\"limit\":0"), run(null));
            h.execute(step(), run(null));

            assertThat(scripted.queries).extracting(KnowledgeQuery::limit).containsExactly(20, 5, 5);
        }

        /** An adapter from before 1.0.20 gets the limit too, positionally. */
        @Test
        void anOldAdapterGetsTheLimitPositionally() {
            List<Integer> limits = new ArrayList<>();
            KnowledgeBasePort old = (accountId, assistantId, indexName, vector, limit, locale) -> {
                limits.add(limit);
                return List.of();
            };

            handler(old).execute(step(",\"limit\":3"), run(null));

            assertThat(limits).containsExactly(3);
        }
    }

    /** E04: diversity is journey-service's MMR; the step sends it clamped and keeps the order it gets back. */
    @Nested
    class Diversity {

        @Test
        void diversityIsSentClampedAndAbsentLeavesTheDefaultToJourneyService() {
            ScriptedPort scripted = new ScriptedPort();
            KnowledgeRetrievalStepHandler h = handler(scripted);

            h.execute(step(",\"diversity\":1.5"), run(null));
            h.execute(step(",\"diversity\":-0.2"), run(null));
            h.execute(step(",\"diversity\":0.4"), run(null));
            h.execute(step(), run(null));

            assertThat(scripted.queries).extracting(KnowledgeQuery::diversity).containsExactly(1.0, 0.0, 0.4, null);
        }

        /** MMR may put a less similar but different passage second; the step must not re-sort by score. */
        @Test
        void theDiversifiedOrderIsKept() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(
                    cited(1, "We open at 9.", 0.90, "VECTOR"),
                    cited(3, "Parking is free.", 0.74, "VECTOR"),
                    cited(2, "We open at nine.", 0.89, "VECTOR"));

            StepResult result = handler(scripted).execute(step(",\"diversity\":0.8"), run(null));

            assertThat(sources(result)).extracting(source -> source.get("id")).containsExactly(1L, 3L, 2L);
        }
    }

    /** B07: a miss reports the question to the port as a gap; B08: a missing index is a fault, not a gap. */
    @Nested
    class Misses {

        @Test
        void aMissRecordsAGapWithTheQuestion() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(new EngineSearchResult("We open at 9.", 0.66, "en"));
            UUID assistant = UUID.randomUUID();

            StepResult result = handler(scripted).execute(step(",\"threshold\":0.8"), run(assistant));

            assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
            assertThat(result.getData()).isEqualTo("I do not have information about this.");
            assertThat(result.getMetadata()).containsEntry("found", false).containsEntry("gapReported", true)
                    .containsEntry("missReason", "BELOW_THRESHOLD").containsEntry("bestScore", 0.66);
            assertThat(scripted.misses).hasSize(1);
            KnowledgeMiss miss = scripted.misses.get(0);
            assertThat(miss.query()).isEqualTo(QUESTION);
            assertThat(miss.accountId()).isEqualTo("acc-1");
            assertThat(miss.assistantId()).isEqualTo(assistant);
            assertThat(miss.indexName()).isEqualTo("faq");
            assertThat(miss.reason()).isEqualTo(KnowledgeMiss.REASON_BELOW_THRESHOLD);
            assertThat(miss.bestScore()).isEqualTo(0.66);
            assertThat(miss.bestPassage()).isEqualTo("We open at 9.");
            assertThat(miss.threshold()).isEqualTo(0.8);
            assertThat(miss.channelType()).isEqualTo("WEB");
            assertThat(miss.locale()).isEqualTo("en");
            assertThat(miss.queryVector()).containsExactly(1f);
            assertThat(miss.embeddingModel()).isEqualTo(LocalEmbeddingEngine.DEFAULT_MODEL);
            assertThat(miss.journeyId()).isEqualTo(7L);
            assertThat(miss.executionId()).isEqualTo("exec-1");
        }

        @Test
        void anEmptySearchIsAMissWithNoBestScore() {
            ScriptedPort scripted = new ScriptedPort();

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(result.getMetadata()).containsEntry("missReason", "NO_RESULTS").doesNotContainKey("bestScore");
            assertThat(scripted.misses).singleElement().satisfies(miss -> {
                assertThat(miss.reason()).isEqualTo(KnowledgeMiss.REASON_NO_RESULTS);
                assertThat(miss.bestScore()).isNull();
                assertThat(miss.bestPassage()).isNull();
            });
        }

        @Test
        void aMissPublishesNoSources() {
            ScriptedPort scripted = new ScriptedPort();
            ExecutionContext context = run(null);

            StepResult result = handler(scripted).execute(step(), context);

            assertThat(sources(result)).isEmpty();
            assertThat(HandlerFixtures.stepField(context, 1, "found")).isEqualTo(false);
            assertThat(HandlerFixtures.stepField(context, 1, "sources")).isEqualTo(List.of());
            assertThat(HandlerFixtures.stepField(context, 1, "composed")).isEqualTo(false);
        }

        @Test
        void aRehearsalRecordsNoGap() {
            ScriptedPort scripted = new ScriptedPort();
            ExecutionContext context = run(null);
            context.setInternal(Simulation.INTERNAL_SIMULATE, true);

            StepResult result = handler(scripted).execute(step(), context);

            assertThat(result.getData()).isEqualTo("I do not have information about this.");
            assertThat(result.getMetadata()).containsEntry("gapReported", false);
            assertThat(scripted.misses).isEmpty();
        }

        /** An earlier step of a chain (FAQ, then guides): a later step may still answer, so no gap. */
        @Test
        void aStepWithRecordGapsOffMissesWithoutRecordingAGap() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(new EngineSearchResult("We open at 9.", 0.66, "en"));
            ExecutionContext context = run(UUID.randomUUID());

            StepResult result = handler(scripted).execute(step(",\"threshold\":0.8,\"recordGaps\":false"), context);

            assertThat(scripted.misses).isEmpty();
            assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
            assertThat(result.getData()).isEqualTo("I do not have information about this.");
            assertThat(result.getMetadata()).containsEntry("found", false).containsEntry("gapReported", false)
                    .containsEntry("missReason", "BELOW_THRESHOLD").containsEntry("bestScore", 0.66);
            assertThat(HandlerFixtures.stepField(context, 1, "found")).isEqualTo(false);
        }

        @Test
        void recordGapsOffStillReportsTheMissReasonOfAnEmptySearch() {
            ScriptedPort scripted = new ScriptedPort();

            StepResult result = handler(scripted).execute(step(",\"recordGaps\":false"), run(null));

            assertThat(scripted.misses).isEmpty();
            assertThat(result.getMetadata()).containsEntry("gapReported", false)
                    .containsEntry("missReason", "NO_RESULTS").doesNotContainKey("bestScore");
        }

        @Test
        void recordGapsTrueRecordsTheGapAsWhenAbsent() {
            ScriptedPort scripted = new ScriptedPort();

            StepResult result = handler(scripted).execute(step(",\"recordGaps\":true"), run(null));

            assertThat(result.getMetadata()).containsEntry("gapReported", true).containsEntry("missReason", "NO_RESULTS");
            assertThat(scripted.misses).singleElement()
                    .satisfies(miss -> assertThat(miss.reason()).isEqualTo(KnowledgeMiss.REASON_NO_RESULTS));
        }

        @Test
        void recordGapsOffDoesNotChangeAHit() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "We deliver to Irbid.", 0.9, EngineSearchResult.REASON_VECTOR));

            StepResult result = handler(scripted).execute(step(",\"recordGaps\":false"), run(null));

            assertThat(result.getData()).isEqualTo("We deliver to Irbid.");
            assertThat(result.getMetadata()).containsEntry("found", true).doesNotContainKey("gapReported");
            assertThat(scripted.misses).isEmpty();
        }

        /** Only an explicit false turns it off: every step saved before the setting keeps recording gaps. */
        @Test
        void recordGapsIsOnUnlessExplicitlyFalse() {
            assertThat(KnowledgeRetrievalStepHandler.recordGaps(null)).isTrue();
            assertThat(KnowledgeRetrievalStepHandler.recordGaps(new ApiConfig())).isTrue();
            assertThat(KnowledgeRetrievalStepHandler.recordGaps(ApiConfig.builder().recordGaps(true).build())).isTrue();
            assertThat(KnowledgeRetrievalStepHandler.recordGaps(ApiConfig.builder().recordGaps(false).build())).isFalse();
        }

        @Test
        void aGapThatCannotBeRecordedStillGivesTheNoAnswerReply() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.missFailure = new IllegalStateException("journey-service down");

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
            assertThat(result.getData()).isEqualTo("I do not have information about this.");
            assertThat(result.getMetadata()).containsEntry("gapReported", false);
        }

        /** A 1.0.19 adapter has no recordMiss of its own: the default does nothing and the step answers. */
        @Test
        void anOldAdapterMissesQuietly() {
            KnowledgeBasePort old = (accountId, assistantId, indexName, vector, limit, locale) -> List.of();

            StepResult result = handler(old).execute(step(), run(null));

            assertThat(result.getData()).isEqualTo("I do not have information about this.");
        }

        @Test
        void aMissingIndexFailsTheStepAndRecordsNoGap() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.failure = new KnowledgeIndexMissingException("nope");

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
            assertThat(result.getMessage()).isEqualTo("KNOWLEDGE_INDEX_MISSING");
            assertThat(result.getMetadata()).containsEntry("errorCode", "INDEX_NOT_FOUND")
                    .containsEntry("indexName", "faq");
            assertThat(scripted.misses).isEmpty();
        }

        /** A resilience wrapper may rethrow it as the cause of its own exception. */
        @Test
        void aWrappedMissingIndexIsStillReportedAsSuch() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.failure = new IllegalStateException("breaker",
                    new KnowledgeIndexMissingException("nope"));

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(result.getMessage()).isEqualTo("KNOWLEDGE_INDEX_MISSING");
            assertThat(scripted.misses).isEmpty();
        }
    }

    /** E06: every served passage is cited, in the step's variables and in its metadata. */
    @Nested
    class Citations {

        @Test
        void sourcesCarryWhereEachPassageCameFromAndItsScores() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(11, "We open at 9.", 0.91, "VECTOR"),
                    cited(12, "Closed on Fridays.", 0.84, "LEXICAL_CORROBORATED"));
            ExecutionContext context = run(null);

            StepResult result = handler(scripted).execute(step(), context);

            assertThat(result.getData()).isEqualTo("We open at 9.");
            assertThat(result.getMetadata()).containsEntry("found", true).containsEntry("sourceCount", 2)
                    .containsEntry("composed", false).containsEntry("indexName", "faq");
            List<Map<String, Object>> sources = sources(result);
            assertThat(sources).hasSize(2);
            assertThat(sources.get(0)).containsEntry("id", 11L).containsEntry("indexName", "faq")
                    .containsEntry("question", "Question 11").containsEntry("sourceId", 51L)
                    .containsEntry("sourceName", "prices.xlsx").containsEntry("sourceKind", "SHEET")
                    .containsEntry("rowNumber", 12).containsEntry("vectorScore", 0.91)
                    .containsEntry("lexicalScore", 0.25).containsEntry("fusedScore", 0.031)
                    .containsEntry("reason", "VECTOR")
                    .doesNotContainKey("url");
            assertThat(HandlerFixtures.stepField(context, 1, "sources")).isEqualTo(sources);
            assertThat(HandlerFixtures.stepField(context, 1, "found")).isEqualTo(true);
            assertThat(HandlerFixtures.stepField(context, 1, "composed")).isEqualTo(false);
        }

        /** They must survive the reply's JSON (conversation-service stores it in the chat message). */
        @Test
        void sourcesSerialiseAsPlainJson() throws Exception {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(11, "We open at 9.", 0.91, "VECTOR"));

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(HandlerFixtures.json(sources(result)))
                    .contains("\"id\":11", "\"sourceName\":\"prices.xlsx\"", "\"vectorScore\":0.91");
        }

        /** An adapter from before 1.0.20 sends only the answer and its score: cited with what there is. */
        @Test
        void anOldAdaptersHitIsCitedWithItsScoreAndTheStepsIndex() {
            StepResult result = handler.execute(step(), run(null));

            assertThat(sources(result)).singleElement().isEqualTo(
                    Map.of("indexName", "faq", "vectorScore", 0.9, "reason", "VECTOR"));
        }

        /** A document or web passage has no separate answer: its text is the answer. */
        @Test
        void aDocumentPassageAnswersWithItsText() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(new EngineSearchResult(null, 0.9, null, 5L, "faq",
                    "Opening hours are 9 to 5, Sunday to Thursday.", 3L, "hours.pdf", "DOCUMENT", null, null,
                    0.0, 0.016, "VECTOR"));

            StepResult result = handler(scripted).execute(step(), run(null));

            assertThat(result.getData()).isEqualTo("Opening hours are 9 to 5, Sunday to Thursday.");
            assertThat(sources(result).get(0)).containsEntry("sourceKind", "DOCUMENT");
        }

        /** The builder's catalog calls it "Knowledge answer", as the portal does; the stored type is unchanged. */
        @Test
        void theCatalogCallsTheStepKnowledgeAnswer() {
            KnowledgeRetrievalStepHandler described = new KnowledgeRetrievalStepHandler(
                    new EngineUtils(new ObjectMapper()), new VariableContext(),
                    new StepOutputSchemaHelper(new ObjectMapper()), new FixedEmbedding(), port, new EngineMessages(),
                    TextTranslator.NONE, null, null);

            assertThat(described.getType()).isEqualTo("KNOWLEDGE_RETRIEVAL");
            assertThat(described.describe().getType()).isEqualTo("KNOWLEDGE_RETRIEVAL");
            assertThat(described.describe().getLabel()).isEqualTo("Knowledge answer");
        }

        @Test
        void theOutputSchemaDeclaresSourcesAndComposed() {
            List<OutputField> fields = new StepOutputSchemaHelper(new ObjectMapper()).knowledgeRetrievalSchema()
                    .getFields();

            assertThat(fields).extracting(OutputField::getPath).containsExactly("output", "found", "sources",
                    "composed");
            assertThat(fields).extracting(OutputField::getType).containsExactly("string", "boolean", "array",
                    "boolean");
        }
    }

    /**
     * 1.0.20: a step that composes searches on its recall floor (asking for twice
     * what it can show), shows the model at most {@code max-chunks} candidates (the
     * best {@code vector-first} by similarity, then the port's order, with
     * {@code LEXICAL_RECALL} hits for the model only), cites exactly those, and
     * treats the model's {@code NO_ANSWER} as a miss. A model that fails serves only
     * a sure match, else asks the user to try again without recording a gap.
     * SINGLE is unchanged.
     */
    @Nested
    class Composing {

        @Test
        void aNoAnswerReplyIsAMissWithoutCitationsAndRecordsAGap() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(5);
            ScriptedAi ai = ScriptedAi.replying("NO_ANSWER");
            UUID assistant = UUID.randomUUID();
            ExecutionContext context = run(assistant);

            StepResult result = composing(scripted, ai).execute(composeStep(""), context);

            assertThat(ai.requests).hasSize(1);
            assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
            assertThat(result.getData()).isEqualTo("I do not have information about this.");
            assertThat(result.getMetadata()).containsEntry("found", false).containsEntry("composed", false)
                    .containsEntry("sourceCount", 0).containsEntry("missReason", "MODEL_ABSTAINED")
                    .containsEntry("gapReported", true).containsEntry("candidateCount", 5)
                    .containsEntry("bestScore", 0.80).containsEntry("threshold", 0.38)
                    .containsEntry("recallThreshold", 0.34);
            assertThat(sources(result)).isEmpty();
            assertThat(HandlerFixtures.stepField(context, 1, "found")).isEqualTo(false);
            assertThat(HandlerFixtures.stepField(context, 1, "sources")).isEqualTo(List.of());
            assertThat(HandlerFixtures.stepField(context, 1, "composed")).isEqualTo(false);
            assertThat(scripted.misses).singleElement().satisfies(miss -> {
                assertThat(miss.reason()).isEqualTo(KnowledgeMiss.REASON_MODEL_ABSTAINED);
                assertThat(miss.query()).isEqualTo(QUESTION);
                assertThat(miss.assistantId()).isEqualTo(assistant);
                assertThat(miss.bestScore()).isEqualTo(0.80);
                assertThat(miss.bestPassage()).isEqualTo("Passage 1.");
                assertThat(miss.threshold()).isEqualTo(0.34);
            });
        }

        /** The same "no answer" a retrieval miss gives: a chain goes on to its next step. */
        @Test
        void anAbstentionLooksToTheJourneyExactlyLikeARetrievalMiss() {
            ScriptedPort abstaining = new ScriptedPort();
            abstaining.hits = candidates(3);
            ScriptedPort empty = new ScriptedPort();
            ExecutionContext abstained = run(null);
            ExecutionContext missed = run(null);

            StepResult a = composing(abstaining, ScriptedAi.replying("NO_ANSWER")).execute(composeStep(""), abstained);
            StepResult m = composing(empty, ScriptedAi.replying("unused")).execute(composeStep(""), missed);

            assertThat(a.getData()).isEqualTo(m.getData());
            assertThat(a.getStatus()).isEqualTo(m.getStatus());
            for (String field : List.of("output", "found", "sources", "composed")) {
                assertThat(HandlerFixtures.stepField(abstained, 1, field))
                        .isEqualTo(HandlerFixtures.stepField(missed, 1, field));
            }
        }

        @Test
        void anAbstentionInAnEarlierStepOfAChainRecordsNoGap() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(3);

            StepResult result = composing(scripted, ScriptedAi.replying("no_answer."))
                    .execute(composeStep(",\"recordGaps\":false"), run(null));

            assertThat(result.getMetadata()).containsEntry("found", false).containsEntry("gapReported", false)
                    .containsEntry("missReason", "MODEL_ABSTAINED");
            assertThat(scripted.misses).isEmpty();
        }

        @Test
        void aRehearsedAbstentionRecordsNoGap() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(3);
            ExecutionContext context = run(null);
            context.setInternal(Simulation.INTERNAL_SIMULATE, true);

            StepResult result = composing(scripted, ScriptedAi.replying("NO_ANSWER")).execute(composeStep(""), context);

            assertThat(result.getMetadata()).containsEntry("missReason", "MODEL_ABSTAINED")
                    .containsEntry("gapReported", false);
            assertThat(scripted.misses).isEmpty();
        }

        @Test
        void aNaturalLanguageAbstentionIsAMissOnlyWhileThePhrasesAreOn() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(3);
            String reply = "The sources do not answer the question.";

            StepResult on = composing(scripted, ScriptedAi.replying(reply)).execute(composeStep(""), run(null));
            KnowledgeRetrievalStepHandler literal = composing(scripted, ScriptedAi.replying(reply));
            literal.abstentionPhrases = false;
            StepResult off = literal.execute(composeStep(""), run(null));

            assertThat(on.getMetadata()).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED");
            assertThat(off.getMetadata()).containsEntry("found", true).containsEntry("composed", true);
            assertThat(off.getData()).isEqualTo(reply);
        }

        @Test
        void anArabicAbstentionIsAMiss() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(3);

            StepResult result = composing(scripted, ScriptedAi.replying("لا تحتوي المصادر على إجابة لهذا السؤال."))
                    .execute(composeStep(""), run(null, "كم سعر الباقة المميزة؟"));

            assertThat(result.getMetadata()).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED");
        }

        /** Defect 2: a reply cited 5 passages while the model was shown 3. */
        @Test
        void theCitationsAreExactlyThePassagesTheModelWasShownInOrder() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(10);
            ScriptedAi ai = ScriptedAi.replying("Priority 3 - Medium");
            ExecutionContext context = run(null);

            StepResult result = composing(scripted, ai).execute(composeStep(",\"limit\":10"), context);

            assertThat(result.getData()).isEqualTo("Priority 3 - Medium");
            assertThat(result.getMetadata()).containsEntry("found", true).containsEntry("composed", true)
                    .containsEntry("sourceCount", 8);
            assertThat(sources(result)).extracting(source -> source.get("id"))
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
            assertThat(HandlerFixtures.stepField(context, 1, "sources")).isEqualTo(sources(result));
            assertThat(ai.prompt()).contains("[1] Passage 1.", "[8] Passage 8.").doesNotContain("Passage 9.",
                    "Passage 10.");
        }

        /**
         * The best {@code vector-first} (4) by similarity lead, ties in the port's order, so a strong
         * vector match the fused and diversified order pushed down is still read; the rest follow the port.
         */
        @Test
        void theModelSeesTheBestVectorMatchesFirstThenThePortsOrder() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(
                    cited(1, "One.", 0.50, "VECTOR"),
                    cited(2, "Two.", 0.70, "VECTOR"),
                    cited(3, "Three.", 0.40, "LEXICAL_CORROBORATED"),
                    cited(4, "Four.", 0.80, "VECTOR"),
                    cited(5, "Five.", 0.45, "VECTOR"),
                    cited(6, "Six.", 0.70, "VECTOR"),
                    cited(7, "Seven.", 0.60, "VECTOR"),
                    cited(8, "Eight.", 0.42, "VECTOR"),
                    cited(9, "Nine.", 0.41, "VECTOR"),
                    cited(10, "Ten.", 0.39, "VECTOR"));
            ScriptedAi ai = ScriptedAi.replying("Answer.");

            StepResult result = composing(scripted, ai).execute(composeStep(""), run(null));

            assertThat(sources(result)).extracting(source -> source.get("id"))
                    .containsExactly(4L, 2L, 6L, 7L, 1L, 3L, 5L, 8L);
            assertThat(ai.prompt()).contains("[1] Four.", "[2] Two.", "[3] Six.", "[4] Seven.", "[5] One.",
                    "[6] Three.", "[8] Eight.").doesNotContain("Nine.", "Ten.");
        }

        @Test
        void aPassageReturnedTwiceIsShownOnce() {
            EngineSearchResult unidentified = new EngineSearchResult("No id.", 0.50, "en");
            List<EngineSearchResult> served = List.of(cited(1, "A.", 0.70, "VECTOR"),
                    cited(1, "A again.", 0.69, "VECTOR"), unidentified, unidentified,
                    new EngineSearchResult("No id.", 0.50, "en"), cited(2, "B.", 0.40, "VECTOR"));

            // By id; a hit without one only when it is the very same hit (an equal record is another passage).
            assertThat(KnowledgeRetrievalStepHandler.shown(served, 4, 8)).extracting(EngineSearchResult::answer)
                    .containsExactly("A.", "No id.", "No id.", "B.");
        }

        @Test
        void vectorFirstNeverExceedsWhatIsShown() {
            assertThat(KnowledgeRetrievalStepHandler.shown(candidates(5), 10, 3)).extracting(EngineSearchResult::id)
                    .containsExactly(1L, 2L, 3L);
            assertThat(KnowledgeRetrievalStepHandler.shown(candidates(5), 0, 0)).extracting(EngineSearchResult::id)
                    .containsExactly(1L);
            assertThat(KnowledgeRetrievalStepHandler.shown(List.of(), 4, 8)).isEmpty();
        }

        /** A composing step asks for twice what it can show, so neither the port's limit nor MMR cuts a strong match. */
        @Test
        void aComposingStepAsksThePortForItsRecallLimit() {
            ScriptedPort scripted = new ScriptedPort();
            KnowledgeRetrievalStepHandler h = composing(scripted, null);

            h.execute(composeStep(""), run(null));
            h.execute(composeStep(",\"limit\":18"), run(null));
            h.execute(step(), run(null));
            h.synthesisMaxChunks = 3;
            h.execute(composeStep(""), run(null));
            h.synthesisMaxChunks = 12;
            h.execute(composeStep(""), run(null));

            assertThat(scripted.queries).extracting(KnowledgeQuery::limit).containsExactly(16, 18, 5, 6, 20);
        }

        @Test
        void theRecallLimitIsTwiceMaxChunksNeverUnderTheStepsLimitNorOverTwenty() {
            assertThat(KnowledgeRetrievalStepHandler.recallLimit(5, 8)).isEqualTo(16);
            assertThat(KnowledgeRetrievalStepHandler.recallLimit(18, 8)).isEqualTo(18);
            assertThat(KnowledgeRetrievalStepHandler.recallLimit(5, 12)).isEqualTo(20);
            assertThat(KnowledgeRetrievalStepHandler.recallLimit(5, 0)).isEqualTo(5);
        }

        /** The step's limit does not cut a composing step's candidates: its recall limit does. */
        @Test
        void aComposingStepsCandidatesAreBoundedByTheRecallLimitNotTheStepsLimit() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(12);
            ScriptedAi ai = ScriptedAi.replying("Answer.");

            StepResult result = composing(scripted, ai).execute(composeStep(",\"limit\":2"), run(null));

            assertThat(scripted.queries.get(0).limit()).isEqualTo(16);
            assertThat(sources(result)).hasSize(8);
        }

        /** LEXICAL_RECALL: below the floor on strong full-text evidence; the model reads it and it is cited. */
        @Test
        void aLexicalRecallHitIsACandidateForTheModel() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(
                    cited(1, "Enable FLAG_SECURE in the manifest.", 0.24, EngineSearchResult.REASON_LEXICAL_RECALL),
                    cited(2, "Screenshots are blocked on banking screens.", 0.41, EngineSearchResult.REASON_VECTOR));
            ScriptedAi ai = ScriptedAi.replying("Set FLAG_SECURE in the manifest.");

            StepResult result = composing(scripted, ai).execute(composeStep(""), run(null));

            assertThat(ai.prompt()).contains("Enable FLAG_SECURE in the manifest.");
            assertThat(result.getMetadata()).containsEntry("found", true).containsEntry("composed", true);
            assertThat(sources(result)).extracting(source -> source.get("reason"))
                    .containsExactly("VECTOR", "LEXICAL_RECALL");
        }

        /** Not the one-candidate sure match, not the fallback of a failed or absent model: whatever it scores. */
        @Test
        void aLexicalRecallHitIsNeverTheAnswerAsStored() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(
                    cited(1, "Enable FLAG_SECURE in the manifest.", 0.90, EngineSearchResult.REASON_LEXICAL_RECALL));
            ScriptedAi abstaining = ScriptedAi.replying("NO_ANSWER");

            StepResult judged = composing(scripted, abstaining).execute(composeStep(""), run(null));
            StepResult failed = composing(scripted, ScriptedAi.failing()).execute(composeStep(""), run(null));
            StepResult noModel = composing(scripted, null).execute(composeStep(""), run(null));

            assertThat(abstaining.requests).hasSize(1);
            assertThat(judged.getMetadata()).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED");
            assertThat(failed.getMetadata()).containsEntry("found", false)
                    .containsEntry("missReason", "GENERATION_FAILED");
            assertThat(noModel.getMetadata()).containsEntry("found", false)
                    .containsEntry("missReason", "BELOW_THRESHOLD");
        }

        @Test
        void howManyPassagesAreShownIsConfigurable() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(5);
            ScriptedAi ai = ScriptedAi.replying("Priority 3 - Medium");
            KnowledgeRetrievalStepHandler h = composing(scripted, ai);
            h.synthesisMaxChunks = 2;

            StepResult result = h.execute(composeStep(""), run(null));

            assertThat(sources(result)).extracting(source -> source.get("id")).containsExactly(1L, 2L);
            assertThat(ai.prompt()).contains("[2] Passage 2.").doesNotContain("Passage 3.");
        }

        @Test
        void theDefaultsShowEightPassagesTheBestFourByVectorAndServeASureMatchAt085() {
            KnowledgeRetrievalStepHandler h = composing(new ScriptedPort(), null);

            assertThat(h.synthesisMaxChunks).isEqualTo(8);
            assertThat(h.synthesisVectorFirst).isEqualTo(4);
            assertThat(h.sureMatchThreshold).isEqualTo(0.85);
        }

        @Test
        void theModelIsToldToAbstainWithTheTokenAndToAnswerInTheQuestionsLanguage() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(2);
            ScriptedAi english = ScriptedAi.replying("Yes.");
            ScriptedAi arabic = ScriptedAi.replying("نعم.");

            composing(scripted, english).execute(composeStep(""), run(null));
            // An Arabic question in a run whose language is English still gets the Arabic instruction.
            composing(scripted, arabic).execute(composeStep(""), run(null, "هل توصلون إلى إربد؟"));

            assertThat(english.system()).isEqualTo(KnowledgeRetrievalStepHandler.SYSTEM_NEUTRAL)
                    .contains("same language as the question", "exactly NO_ANSWER and nothing else");
            assertThat(arabic.system()).isEqualTo(KnowledgeRetrievalStepHandler.SYSTEM_ARABIC).contains("NO_ANSWER");
            assertThat(english.prompt()).contains("reply with exactly NO_ANSWER and nothing else",
                    "Answer in the same language as the question", "Question: " + QUESTION);
            assertThat(arabic.prompt()).contains("Question: هل توصلون إلى إربد؟");
        }

        @Test
        void aQuestionWithoutLettersFollowsTheRunsLanguage() {
            ExecutionContext arabicRun = run(null, "12345");
            arabicRun.setLanguage(ConversationLanguage.ARABIC);

            assertThat(KnowledgeRetrievalStepHandler.questionIsArabic("12345", arabicRun)).isTrue();
            assertThat(KnowledgeRetrievalStepHandler.questionIsArabic("12345", run(null))).isFalse();
            assertThat(KnowledgeRetrievalStepHandler.questionIsArabic("what is the price?", arabicRun)).isFalse();
        }

        /** Defect 3: a composing step fetches on the recall floor and says it is a recall search. */
        @Test
        void aComposingStepSearchesOnTheRecallFloorAsARecallSearch() {
            ScriptedPort scripted = new ScriptedPort();
            KnowledgeRetrievalStepHandler h = composing(scripted, null);

            h.execute(composeStep(""), run(null));
            h.execute(composeStep(",\"recallThreshold\":0.36"), run(null));
            h.execute(composeStep(",\"recallThreshold\":0.1"), run(null));
            h.execute(composeStep(",\"recallThreshold\":0.99"), run(null));
            h.execute(composeStep(",\"threshold\":0.32"), run(null));
            h.execute(step(",\"recallThreshold\":0.36"), run(null));

            // Defaults 0.34 and 0.38; the floor is clamped to 0.30..0.95 and never above the threshold.
            assertThat(scripted.queries).extracting(KnowledgeQuery::threshold)
                    .containsExactly(0.34, 0.36, 0.30, 0.38, 0.32, 0.38);
            assertThat(scripted.queries).extracting(KnowledgeQuery::recall)
                    .containsExactly(true, true, true, true, true, false);
        }

        /** AUTO follows the platform default: composing (the default) is a recall search, off is not. */
        @Test
        void anAutoStepSearchesOnTheRecallFloorOnlyWhileComposingIsOn() {
            ScriptedPort scripted = new ScriptedPort();
            KnowledgeRetrievalStepHandler h = composing(scripted, null);
            JourneyStep auto = JourneyStep.builder().stepOrder(1).actionType("KNOWLEDGE_RETRIEVAL")
                    .apiConfig("{\"indexName\":\"faq\"}").build();

            h.execute(auto, run(null));
            h.synthesisEnabled = false;
            h.execute(auto, run(null));

            assertThat(scripted.queries).extracting(KnowledgeQuery::recall).containsExactly(true, false);
            assertThat(scripted.queries).extracting(KnowledgeQuery::threshold).containsExactly(0.34, 0.38);
        }

        @Test
        void theRecallThresholdIsClampedAndNeverAboveTheThreshold() {
            assertThat(KnowledgeRetrievalStepHandler.recallThreshold(null, 0.38)).isEqualTo(0.34);
            assertThat(KnowledgeRetrievalStepHandler.recallThreshold(Double.NaN, 0.70)).isEqualTo(0.34);
            assertThat(KnowledgeRetrievalStepHandler.recallThreshold(null, 0.32)).as("the default, under the threshold")
                    .isEqualTo(0.32);
            assertThat(KnowledgeRetrievalStepHandler.recallThreshold(0.1, 0.70)).isEqualTo(0.30);
            assertThat(KnowledgeRetrievalStepHandler.recallThreshold(0.62, 0.70)).isEqualTo(0.62);
            assertThat(KnowledgeRetrievalStepHandler.recallThreshold(0.99, 0.95)).isEqualTo(0.95);
            assertThat(KnowledgeRetrievalStepHandler.recallThreshold(0.60, 0.50)).isEqualTo(0.50);
        }

        /** An adapter from before 1.0.20: the step gates its hits on the recall floor itself. */
        @Test
        void anOldAdaptersCandidatesAreGatedOnTheRecallFloor() {
            KnowledgeBasePort old = (accountId, assistantId, indexName, vector, limit, locale) -> List.of(
                    new EngineSearchResult("Priority 3 - Medium is the default.", 0.37, null),
                    new EngineSearchResult("Major incidents are escalated.", 0.35, null),
                    new EngineSearchResult("Unrelated.", 0.30, null));
            ScriptedAi ai = ScriptedAi.replying("3 - Medium");

            StepResult composed = composing(old, ai).execute(composeStep(""), run(null));
            StepResult single = composing(old, ai).execute(step(), run(null));

            assertThat(composed.getData()).isEqualTo("3 - Medium");
            assertThat(sources(composed)).hasSize(2);
            assertThat(single.getMetadata()).containsEntry("found", false);
            assertThat(ai.requests).hasSize(1);
        }

        @Test
        void aSingleSureCandidateIsTheAnswerAsStoredWithoutTheModel() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "We deliver to Irbid.", 0.86, "VECTOR"));
            ScriptedAi ai = ScriptedAi.replying("NO_ANSWER");

            StepResult result = composing(scripted, ai).execute(composeStep(""), run(null));

            assertThat(ai.requests).isEmpty();
            assertThat(result.getData()).isEqualTo("We deliver to Irbid.");
            assertThat(result.getMetadata()).containsEntry("found", true).containsEntry("composed", false)
                    .containsEntry("sourceCount", 1);
        }

        @Test
        void aSingleCandidateUnderTheThresholdIsJudgedByTheModel() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "Nibras supports WhatsApp.", 0.36, "VECTOR"));
            ScriptedAi ai = ScriptedAi.replying("NO_ANSWER");

            StepResult result = composing(scripted, ai).execute(composeStep(""), run(null));

            assertThat(ai.requests).hasSize(1);
            assertThat(result.getMetadata()).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED");
        }

        /**
         * journey-service applies no Arabic offset to a recall search; the step's sure bar does, when
         * {@code journey.knowledge.recall.arabic-offset} is set (0.05 here; the default is 0 since the
         * embedding switch).
         */
        @Test
        void anArabicRunsSingleCandidateNeedsTheArabicOffsetToSkipTheModel() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "نعم، نوصل إلى إربد.", 0.40, "VECTOR"));
            ScriptedAi ai = ScriptedAi.replying("NO_ANSWER");
            ExecutionContext arabic = run(null, "هل توصلون إلى إربد؟");
            arabic.setLanguage(ConversationLanguage.ARABIC);
            ExecutionContext english = run(null);

            KnowledgeRetrievalStepHandler arabicStep = composing(scripted, ai);
            arabicStep.recallArabicOffset = 0.05;
            KnowledgeRetrievalStepHandler englishStep = composing(scripted, ScriptedAi.replying("NO_ANSWER"));
            englishStep.recallArabicOffset = 0.05;
            StepResult ar = arabicStep.execute(composeStep(""), arabic);
            StepResult en = englishStep.execute(composeStep(""), english);

            assertThat(ai.requests).hasSize(1);
            assertThat(ar.getMetadata()).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED");
            assertThat(en.getMetadata()).containsEntry("found", true).containsEntry("composed", false);
        }

        /** By default (offset 0) an Arabic run's single sure candidate skips the model like any other. */
        @Test
        void byDefaultAnArabicRunHasNoExtraBar() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "نعم، نوصل إلى إربد.", 0.40, "VECTOR"));
            ScriptedAi ai = ScriptedAi.replying("NO_ANSWER");
            ExecutionContext arabic = run(null, "هل توصلون إلى إربد؟");
            arabic.setLanguage(ConversationLanguage.ARABIC);

            KnowledgeRetrievalStepHandler h = composing(scripted, ai);
            assertThat(h.recallArabicOffset).isZero();
            StepResult ar = h.execute(composeStep(""), arabic);

            assertThat(ai.requests).isEmpty();
            assertThat(ar.getMetadata()).containsEntry("found", true).containsEntry("composed", false);
        }

        /** The model failed: the best sure match of this step's candidates goes out as stored, cited alone. */
        @Test
        void aFailedCompositionServesTheBestSureMatchAloneAsStored() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(onQuestion(1, "Delivery takes two days.", 0.86),
                    onQuestion(2, "We deliver to Irbid.", 0.93),
                    cited(3, "Nibras supports WhatsApp.", 0.36, "VECTOR"));

            StepResult result = composing(scripted, ScriptedAi.failing()).execute(composeStep(""), run(null));

            assertThat(result.getData()).isEqualTo("We deliver to Irbid.");
            assertThat(result.getMetadata()).containsEntry("found", true).containsEntry("composed", false)
                    .containsEntry("sourceCount", 1);
            assertThat(sources(result)).extracting(source -> source.get("id")).containsExactly(2L);
            assertThat(scripted.misses).isEmpty();
        }

        /** A provider error and no sure match: "try again", found false, and no gap even with recordGaps on. */
        @Test
        void aFailedCompositionWithNoSureMatchAsksToTryAgainAndRecordsNoGap() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "Nibras supports WhatsApp.", 0.45, "VECTOR"),
                    cited(2, "Major incidents are escalated.", 0.41, "VECTOR"));
            ExecutionContext context = run(UUID.randomUUID());

            StepResult result = composing(scripted, ScriptedAi.failing())
                    .execute(composeStep(",\"recordGaps\":true"), context);

            assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
            assertThat(result.getData()).isEqualTo("I cannot answer that right now. Please try again in a moment.");
            assertThat(result.getMetadata()).containsEntry("found", false).containsEntry("composed", false)
                    .containsEntry("sourceCount", 0).containsEntry("missReason", "GENERATION_FAILED")
                    .containsEntry("gapReported", false).containsEntry("candidateCount", 2)
                    .containsEntry("bestScore", 0.45).containsEntry("recallThreshold", 0.34);
            assertThat(sources(result)).isEmpty();
            assertThat(HandlerFixtures.stepField(context, 1, "output")).isEqualTo(result.getData());
            assertThat(HandlerFixtures.stepField(context, 1, "found")).isEqualTo(false);
            assertThat(HandlerFixtures.stepField(context, 1, "sources")).isEqualTo(List.of());
            assertThat(HandlerFixtures.stepField(context, 1, "composed")).isEqualTo(false);
            assertThat(scripted.misses).isEmpty();
        }

        @Test
        void anArabicRunIsAskedToTryAgainInArabic() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "نعم، نوصل إلى إربد.", 0.45, "VECTOR"),
                    cited(2, "التوصيل خلال يومين.", 0.40, "VECTOR"));
            ExecutionContext arabic = run(null, "هل توصلون إلى إربد؟");
            arabic.setLanguage(ConversationLanguage.ARABIC);

            StepResult result = composing(scripted, ScriptedAi.failing()).execute(composeStep(""), arabic);

            assertThat(result.getData()).isEqualTo("لا أستطيع الإجابة الآن، يرجى المحاولة مرة أخرى بعد قليل.");
            assertThat(result.getMetadata()).containsEntry("missReason", "GENERATION_FAILED")
                    .containsEntry("gapReported", false);
            assertThat(scripted.misses).isEmpty();
        }

        /** Asked and failed, however it failed: an exception, a blank reply, a reply of nothing but reasoning. */
        @Test
        void anExceptionAndAnEmptyReplyAreFailedGenerationsToo() {
            AiService throwing = new AiService(Map.of()) {
                @Override
                public AiResponse chat(AiChatRequest request) {
                    throw new IllegalStateException("connection reset");
                }
            };
            for (AiService ai : List.of(throwing, ScriptedAi.replying("  "), ScriptedAi.replying("<think>hm</think>"))) {
                ScriptedPort scripted = new ScriptedPort();
                scripted.hits = List.of(cited(1, "Nibras supports WhatsApp.", 0.45, "VECTOR"),
                        cited(2, "Major incidents are escalated.", 0.41, "VECTOR"));

                StepResult result = composing(scripted, ai).execute(composeStep(""), run(null));

                assertThat(result.getMetadata()).containsEntry("missReason", "GENERATION_FAILED");
                assertThat(scripted.misses).isEmpty();
            }
        }

        /** No model configured: the old fallback, what the step's threshold alone would serve; none is a miss. */
        @Test
        void withoutAModelTheCandidatesThatReachTheThresholdAreServedAsBefore() {
            ScriptedPort sure = new ScriptedPort();
            sure.hits = List.of(cited(1, "We deliver to Irbid.", 0.42, "VECTOR"),
                    cited(2, "Nibras supports WhatsApp.", 0.36, "VECTOR"));
            ScriptedPort unsure = new ScriptedPort();
            unsure.hits = List.of(cited(1, "Nibras supports WhatsApp.", 0.37, "VECTOR"),
                    cited(2, "Major incidents are escalated.", 0.35, "VECTOR"));

            StepResult served = composing(sure, null).execute(composeStep(""), run(null));
            StepResult missed = composing(unsure, null).execute(composeStep(""), run(null));

            // 0.42 reaches the step's threshold (0.38): no sure-match bar without a model.
            assertThat(served.getData()).isEqualTo("We deliver to Irbid.");
            assertThat(served.getMetadata()).containsEntry("found", true).containsEntry("composed", false);
            assertThat(sources(served)).extracting(source -> source.get("id")).containsExactly(1L);
            assertThat(missed.getData()).isEqualTo("I do not have information about this.");
            assertThat(missed.getMetadata()).containsEntry("found", false)
                    .containsEntry("missReason", "BELOW_THRESHOLD").containsEntry("bestScore", 0.37);
            assertThat(unsure.misses).singleElement()
                    .satisfies(miss -> assertThat(miss.threshold()).isEqualTo(0.38));
        }

        /**
         * The probe: "Is there free parking for the night shift?" scored 0.721 against the shift's start time
         * when the model failed, and 0.60 served it. A passage that only shares the question's topic is never
         * served unread: the bar is 0.85, on the stored question.
         */
        @Test
        void aFailedCompositionDoesNotServeATopicNeighbourBelowTheSureMatchBar() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(onQuestion(1, "The night shift starts at 22:00.", 0.721),
                    cited(2, "Staff parking is behind building B.", 0.55, "VECTOR"));

            StepResult result = composing(scripted, ScriptedAi.failing()).execute(composeStep(""), run(null));

            assertThat(result.getMetadata()).containsEntry("found", false)
                    .containsEntry("missReason", "GENERATION_FAILED").containsEntry("gapReported", false);
            assertThat(scripted.misses).isEmpty();
        }

        /**
         * Above the bar is not enough: the hit must be a near-identical stored question. One that matched on
         * its answer vector, one without question evidence (a document passage, an older journey-service) or
         * an old adapter's ungated hit is not served unread; a corroborated hit whose question-only score
         * reaches the bar is.
         */
        @Test
        void aFailedCompositionServesOnlyANearIdenticalStoredQuestion() {
            List<List<EngineSearchResult>> notServed = List.of(
                    List.of(matched(1, "We deliver to Irbid.", 0.92, "VECTOR", 0.70, false)),
                    List.of(cited(1, "We deliver to Irbid.", 0.92, "VECTOR")),
                    List.of(new EngineSearchResult("We deliver to Irbid.", 0.95, null)),
                    List.of(matched(1, "We deliver to Irbid.", 0.92, "VECTOR", 0.84, false)));
            // Each with a second candidate, so the model is asked (a single sure candidate never is).
            EngineSearchResult other = cited(9, "Delivery takes two days.", 0.40, "VECTOR");
            for (List<EngineSearchResult> hits : notServed) {
                ScriptedPort scripted = new ScriptedPort();
                scripted.hits = List.of(hits.get(0), other);

                StepResult result = composing(scripted, ScriptedAi.failing()).execute(composeStep(""), run(null));

                assertThat(result.getMetadata()).as("%s", hits).containsEntry("missReason", "GENERATION_FAILED");
            }

            ScriptedPort corroborated = new ScriptedPort();
            corroborated.hits = List.of(
                    matched(1, "We deliver to Irbid.", 0.88, EngineSearchResult.REASON_LEXICAL_CORROBORATED, 0.88,
                            false), other);
            StepResult served = composing(corroborated, ScriptedAi.failing()).execute(composeStep(""), run(null));

            assertThat(served.getData()).isEqualTo("We deliver to Irbid.");
            assertThat(served.getMetadata()).containsEntry("found", true).containsEntry("composed", false);
        }

        @Test
        void aNearIdenticalQuestionIsAVectorQuestionMatchOrAQuestionScoreAtTheBar() {
            assertThat(KnowledgeRetrievalStepHandler.nearIdenticalQuestion(onQuestion(1, "A", 0.86), 0.85)).isTrue();
            assertThat(KnowledgeRetrievalStepHandler.nearIdenticalQuestion(
                    matched(1, "A", 0.90, "VECTOR", 0.85, false), 0.85)).isTrue();
            assertThat(KnowledgeRetrievalStepHandler.nearIdenticalQuestion(
                    matched(1, "A", 0.90, "VECTOR", 0.80, false), 0.85)).isFalse();
            assertThat(KnowledgeRetrievalStepHandler.nearIdenticalQuestion(
                    matched(1, "A", 0.90, EngineSearchResult.REASON_LEXICAL_CORROBORATED, 0.80, true), 0.85))
                    .isFalse();
            assertThat(KnowledgeRetrievalStepHandler.nearIdenticalQuestion(cited(1, "A", 0.99, "VECTOR"), 0.85))
                    .isFalse();
        }

        /**
         * An account with no AI provider is not a failed generation: "try again in a moment" would never come
         * true. The step answers as a SINGLE step does, from the best stored entry at the step's threshold,
         * and the model is never called.
         */
        @Test
        void anAccountWithoutAnAiProviderAnswersFromTheStoredEntryAtTheStepsThreshold() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "We deliver to Irbid.", 0.42, "VECTOR"),
                    cited(2, "Nibras supports WhatsApp.", 0.36, "VECTOR"));
            ScriptedAi ai = ScriptedAi.replying("Composed.");

            StepResult result = composing(scripted, ai, accountId -> {
                throw notConfigured();
            }).execute(composeStep(""), run(null));

            assertThat(ai.requests).isEmpty();
            assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
            assertThat(result.getData()).isEqualTo("We deliver to Irbid.");
            assertThat(result.getMetadata()).containsEntry("found", true).containsEntry("composed", false)
                    .doesNotContainKey("missReason");
            assertThat(sources(result)).extracting(source -> source.get("id")).containsExactly(1L);
            assertThat(scripted.misses).isEmpty();
        }

        /** No AI provider and nothing at the step's threshold: an ordinary miss, with a gap, not "try again". */
        @Test
        void anAccountWithoutAnAiProviderAndNoStoredAnswerIsAnOrdinaryMissWithAGap() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "Nibras supports WhatsApp.", 0.37, "VECTOR"),
                    cited(2, "Major incidents are escalated.", 0.35, "VECTOR"));
            ExecutionContext context = run(UUID.randomUUID());

            // Wrapped, as a proxy or an adapter may throw it.
            StepResult result = composing(scripted, ScriptedAi.replying("Composed."), accountId -> {
                throw new IllegalStateException("config lookup failed", notConfigured());
            }).execute(composeStep(""), context);

            assertThat(result.getData()).isEqualTo("I do not have information about this.");
            assertThat(result.getMetadata()).containsEntry("found", false)
                    .containsEntry("missReason", "BELOW_THRESHOLD").containsEntry("bestScore", 0.37);
            assertThat(scripted.misses).singleElement()
                    .satisfies(miss -> assertThat(miss.threshold()).isEqualTo(0.38));
            assertThat(context.getInternal(KnowledgeRetrievalStepHandler.GENERATION_FAILED_MARK)).isNull();
        }

        /** Any other failure to resolve the configuration (account-service down) is a failed generation. */
        @Test
        void anUnreachableAiConfigurationIsAFailedGeneration() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "We deliver to Irbid.", 0.42, "VECTOR"),
                    cited(2, "Nibras supports WhatsApp.", 0.36, "VECTOR"));

            StepResult result = composing(scripted, ScriptedAi.replying("Composed."), accountId -> {
                throw new BusinessException("AI Configuration Service is currently unavailable",
                        "AI_CONFIG_UNAVAILABLE", 503);
            }).execute(composeStep(""), run(null));

            assertThat(result.getMetadata()).containsEntry("found", false)
                    .containsEntry("missReason", "GENERATION_FAILED").containsEntry("gapReported", false);
            assertThat(scripted.misses).isEmpty();
        }

        @Test
        void onlyTheNotConfiguredCodeMeansNoProvider() {
            assertThat(AiConfigProvider.isNotConfigured(notConfigured())).isTrue();
            assertThat(AiConfigProvider.isNotConfigured(new RuntimeException("x", notConfigured()))).isTrue();
            assertThat(AiConfigProvider.isNotConfigured(new BusinessException("x", "AI_CONFIG_UNAVAILABLE", 503)))
                    .isFalse();
            assertThat(AiConfigProvider.isNotConfigured(new IllegalArgumentException("Account Id is null")))
                    .isFalse();
            assertThat(AiConfigProvider.isNotConfigured(null)).isFalse();
        }

        @Test
        void theSureMatchBarIsConfigurableClampedAndNeverUnderTheStepsThreshold() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(onQuestion(1, "We deliver to Irbid.", 0.90),
                    cited(2, "Delivery takes two days.", 0.50, "VECTOR"));
            KnowledgeRetrievalStepHandler stricter = composing(scripted, ScriptedAi.failing());
            stricter.sureMatchThreshold = 0.92;

            StepResult underTheSetting = stricter.execute(composeStep(""), run(null));
            StepResult underTheStep = composing(scripted, ScriptedAi.failing())
                    .execute(composeStep(",\"threshold\":0.93"), run(null));
            StepResult served = composing(scripted, ScriptedAi.failing()).execute(composeStep(""), run(null));

            assertThat(underTheSetting.getMetadata()).containsEntry("missReason", "GENERATION_FAILED");
            assertThat(underTheStep.getMetadata()).containsEntry("missReason", "GENERATION_FAILED");
            assertThat(served.getMetadata()).containsEntry("found", true);
            assertThat(KnowledgeRetrievalStepHandler.sureMatchThreshold(0.1)).isEqualTo(0.30);
            assertThat(KnowledgeRetrievalStepHandler.sureMatchThreshold(0.99)).isEqualTo(0.95);
            assertThat(KnowledgeRetrievalStepHandler.sureMatchThreshold(Double.NaN)).isEqualTo(0.85);
            assertThat(KnowledgeRetrievalStepHandler.sureMatchThreshold(0.72)).isEqualTo(0.72);
        }

        @Test
        void anArabicRunsSureMatchNeedsTheArabicOffsetToo() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(onQuestion(1, "نعم، نوصل إلى إربد.", 0.87),
                    cited(2, "التوصيل خلال يومين.", 0.50, "VECTOR"));
            ExecutionContext arabic = run(null, "هل توصلون إلى إربد؟");
            arabic.setLanguage(ConversationLanguage.ARABIC);
            KnowledgeRetrievalStepHandler arabicStep = composing(scripted, ScriptedAi.failing());
            arabicStep.recallArabicOffset = 0.05;
            KnowledgeRetrievalStepHandler englishStep = composing(scripted, ScriptedAi.failing());
            englishStep.recallArabicOffset = 0.05;

            StepResult ar = arabicStep.execute(composeStep(""), arabic);
            StepResult en = englishStep.execute(composeStep(""), run(null));

            assertThat(ar.getMetadata()).containsEntry("missReason", "GENERATION_FAILED");
            assertThat(en.getMetadata()).containsEntry("found", true).containsEntry("composed", false);
        }

        /**
         * A chain: after a failed generation a later step's miss of the same question says "try again" too, and
         * is never a gap: "can't answer right now" does not belong in the gap review.
         */
        @Test
        void aLaterMissOfTheSameQuestionAfterAFailedGenerationAsksToTryAgainAndIsNoGap() {
            ScriptedPort faq = new ScriptedPort();
            faq.hits = List.of(cited(1, "Nibras supports WhatsApp.", 0.45, "VECTOR"),
                    cited(2, "Major incidents are escalated.", 0.41, "VECTOR"));
            ScriptedPort guides = new ScriptedPort();
            ExecutionContext context = run(null);

            composing(faq, ScriptedAi.failing()).execute(composeStep(",\"recordGaps\":false"), context);
            StepResult later = composing(guides, ScriptedAi.replying("unused")).execute(guidesStep(), context);

            assertThat(later.getData()).isEqualTo("I cannot answer that right now. Please try again in a moment.");
            assertThat(later.getMetadata()).containsEntry("found", false).containsEntry("missReason", "NO_RESULTS")
                    .containsEntry("gapReported", false);
            assertThat(guides.misses).isEmpty();
            assertThat(faq.misses).isEmpty();
        }

        /** Whatever the later step's own miss reason: below its threshold, or its model abstaining. */
        @Test
        void aLaterMissOfTheSameQuestionIsNoGapWhateverItsOwnReason() {
            ScriptedPort faq = new ScriptedPort();
            faq.hits = List.of(cited(1, "Nibras supports WhatsApp.", 0.45, "VECTOR"),
                    cited(2, "Major incidents are escalated.", 0.41, "VECTOR"));
            ExecutionContext context = run(UUID.randomUUID());
            composing(faq, ScriptedAi.failing()).execute(composeStep(""), context);

            ScriptedPort below = new ScriptedPort();
            below.hits = List.of(new EngineSearchResult("Closed on Fridays.", 0.20, null));
            StepResult belowThreshold = handler(below).execute(JourneyStep.builder().stepOrder(3)
                    .actionType("KNOWLEDGE_RETRIEVAL")
                    .apiConfig("{\"indexName\":\"guides\",\"answerMode\":\"SINGLE\",\"recordGaps\":true}").build(),
                    context);
            ScriptedPort abstained = new ScriptedPort();
            abstained.hits = candidates(3);
            StepResult modelAbstained = composing(abstained, ScriptedAi.replying("NO_ANSWER"))
                    .execute(guidesStep(), context);

            assertThat(belowThreshold.getMetadata()).containsEntry("missReason", "BELOW_THRESHOLD")
                    .containsEntry("gapReported", false);
            assertThat(modelAbstained.getMetadata()).containsEntry("missReason", "MODEL_ABSTAINED")
                    .containsEntry("gapReported", false);
            assertThat(belowThreshold.getData()).isEqualTo(UNAVAILABLE_TEXT);
            assertThat(modelAbstained.getData()).isEqualTo(UNAVAILABLE_TEXT);
            assertThat(faq.misses).isEmpty();
            assertThat(below.misses).isEmpty();
            assertThat(abstained.misses).isEmpty();
        }

        /**
         * The mark belongs to the question: a parked run resumed with another question, or a loop that
         * asks the next one, misses with the ordinary text. And it carries no question text.
         */
        @Test
        @SuppressWarnings("unchecked")
        void aMissOfAnotherQuestionInTheSameRunIsAnOrdinaryMiss() {
            ScriptedPort faq = new ScriptedPort();
            faq.hits = List.of(cited(1, "Nibras supports WhatsApp.", 0.45, "VECTOR"),
                    cited(2, "Major incidents are escalated.", 0.41, "VECTOR"));
            ExecutionContext context = run(null);

            composing(faq, ScriptedAi.failing()).execute(composeStep(""), context);
            ((Map<String, Object>) context.getVariables().get("inputs")).put("text", "what are your opening hours?");
            StepResult later = composing(new ScriptedPort(), ScriptedAi.replying("unused")).execute(guidesStep(),
                    context);

            assertThat(later.getData()).isEqualTo("I do not have information about this.");
            assertThat(later.getMetadata()).containsEntry("gapReported", true);
            assertThat(context.getInternal(KnowledgeRetrievalStepHandler.GENERATION_FAILED_MARK))
                    .isEqualTo(KnowledgeRetrievalStepHandler.fingerprint(QUESTION));
        }

        /** A later step that answers is the reply, whatever an earlier step's failure said. */
        @Test
        void aLaterAnswerIsNotAffectedByAnEarlierFailedGeneration() {
            ScriptedPort faq = new ScriptedPort();
            faq.hits = List.of(cited(1, "Nibras supports WhatsApp.", 0.45, "VECTOR"),
                    cited(2, "Major incidents are escalated.", 0.41, "VECTOR"));
            ScriptedPort guides = new ScriptedPort();
            guides.hits = candidates(3);
            ExecutionContext context = run(null);

            composing(faq, ScriptedAi.failing()).execute(composeStep(""), context);
            StepResult later = composing(guides, ScriptedAi.replying("We deliver to Irbid."))
                    .execute(guidesStep(), context);

            assertThat(later.getData()).isEqualTo("We deliver to Irbid.");
            assertThat(later.getMetadata()).containsEntry("found", true).containsEntry("composed", true);
        }

        @Test
        void singleNeverAsksTheModelAndCitesEveryServedHitAsBefore() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = candidates(3);
            ScriptedAi ai = ScriptedAi.replying("NO_ANSWER");

            StepResult result = composing(scripted, ai).execute(step(), run(null));

            assertThat(ai.requests).isEmpty();
            assertThat(result.getData()).isEqualTo("Passage 1.");
            assertThat(result.getMetadata()).containsEntry("found", true).containsEntry("sourceCount", 3)
                    .doesNotContainKey("recallThreshold");
            assertThat(scripted.queries.get(0).recall()).isFalse();
            assertThat(scripted.queries.get(0).threshold()).isEqualTo(0.38);
        }

        @Test
        void aQueryPrintsWhetherItIsARecallSearch() {
            KnowledgeQuery query = new KnowledgeQuery("acc-1", null, "faq", "q", new float[] { 1f }, 5, "en", 0.55,
                    null, null, true);

            assertThat(query.toString()).contains("recall=true", "threshold=0.55");
            assertThat(new KnowledgeQuery("acc-1", null, "faq", "q", new float[] { 1f }, 5, "en", 0.7, null, null)
                    .recall()).isFalse();
        }
    }

    /** B20: what a person asked never reaches the log above DEBUG. */
    @Nested
    class Logging {

        private static final String SECRET = "SECRET-QA-TOKEN what are your opening hours";

        private List<ILoggingEvent> capture(Runnable body) {
            Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeRetrievalStepHandler.class);
            Level previous = logger.getLevel();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.DEBUG);
            try {
                body.run();
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(previous);
            }
            return appender.list;
        }

        private void assertNoQueryAboveDebug(List<ILoggingEvent> events) {
            assertThat(events).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("SECRET-QA-TOKEN"));
        }

        @Test
        void aServedQuestionIsLoggedByLengthAndHashOnly() {
            ScriptedPort scripted = new ScriptedPort();
            scripted.hits = List.of(cited(1, "We open at 9.", 0.9, "VECTOR"));

            List<ILoggingEvent> events = capture(() -> handler(scripted).execute(step(), run(null, SECRET)));

            assertNoQueryAboveDebug(events);
            assertThat(events).filteredOn(event -> event.getLevel() == Level.INFO)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("len=" + SECRET.length(),
                                    KnowledgeRetrievalStepHandler.fingerprint(SECRET)));
            // The text is still there for whoever turns DEBUG on: the capture sees it.
            assertThat(events).filteredOn(event -> event.getLevel() == Level.DEBUG)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("SECRET-QA-TOKEN"));
        }

        @Test
        void aMissIsLoggedWithoutTheQuestion() {
            ScriptedPort scripted = new ScriptedPort();

            List<ILoggingEvent> events = capture(() -> handler(scripted).execute(step(), run(null, SECRET)));

            assertNoQueryAboveDebug(events);
            assertThat(scripted.misses).singleElement()
                    .satisfies(miss -> assertThat(miss.toString()).doesNotContain("SECRET-QA-TOKEN"));
        }

        @Test
        void theFingerprintIsTheLengthAndEightHexDigits() {
            assertThat(KnowledgeRetrievalStepHandler.fingerprint("hello"))
                    .isEqualTo("len=5 sha256=2cf24dba");
            assertThat(KnowledgeRetrievalStepHandler.fingerprint(null)).isEqualTo("len=0");
        }

        @Test
        void aQueryPrintsWithoutItsText() {
            KnowledgeQuery query = new KnowledgeQuery("acc-1", null, "faq", SECRET, new float[] { 1f }, 5, "en",
                    0.7, null, null);

            assertThat(query.toString()).doesNotContain("SECRET-QA-TOKEN").contains("queryLength=" + SECRET.length());
        }
    }
}
