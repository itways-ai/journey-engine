package com.itways.assistant.journey.engine.impl;

import static com.itways.assistant.journey.engine.impl.EngineFixture.journey;
import static com.itways.assistant.journey.engine.impl.EngineFixture.step;
import static com.itways.assistant.journey.engine.impl.EngineFixture.trace;
import static com.itways.assistant.journey.engine.impl.EngineFixture.views;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.handler.KnowledgeRetrievalStepHandler;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.KnowledgeMiss;
import com.itways.assistant.journey.engine.service.KnowledgeQuery;
import com.itways.assistant.journey.engine.service.TextTranslator;
import com.itways.assistant.journey.model.EngineSearchResult;
import com.itways.assistant.journey.model.JourneyDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A chain of knowledge steps (FAQ, then guides), run by the real engine: the
 * model saying the FAQ's passages do not answer ({@code NO_ANSWER}) sends the
 * run on to the guides exactly as a retrieval miss does, and only the last
 * step's miss is a gap. Defect 1: an abstention used to count as found and
 * stopped the chain at its first step, with citations.
 *
 * <p>
 * A model that fails on the FAQ (with nothing there sure enough to serve as
 * stored) sends the run on too, records no gap, and makes a later miss of the
 * same question say "try again in a moment" rather than "no information".
 */
class KnowledgeChainTest {

    private static final String FAQ_PASSAGE = "Nibras supports email, web chat and WhatsApp.";
    private static final String GUIDE_PASSAGE = "A new incident gets priority 3 - Medium.";
    private static final String UNAVAILABLE = "I cannot answer that right now. Please try again in a moment.";

    /** Candidates per index; keeps what it was asked and the gaps. */
    private static final class Indexes implements KnowledgeBasePort {
        final Map<String, List<EngineSearchResult>> hits;
        final List<String> searched = new ArrayList<>();
        final List<KnowledgeMiss> misses = new ArrayList<>();

        Indexes(Map<String, List<EngineSearchResult>> hits) {
            this.hits = hits;
        }

        @Override
        public List<EngineSearchResult> search(String accountId, UUID assistantId, String indexName,
                float[] queryVector, int limit, String locale) {
            throw new AssertionError("a 1.0.20 step must call search(KnowledgeQuery)");
        }

        @Override
        public List<EngineSearchResult> search(KnowledgeQuery query) {
            searched.add(query.indexName());
            return hits.getOrDefault(query.indexName(), List.of());
        }

        @Override
        public void recordMiss(KnowledgeMiss miss) {
            misses.add(miss);
        }
    }

    /** Answers from the guide's passage, and says NO_ANSWER to anything else; or fails on the FAQ's passage. */
    private static final class Model extends AiService {
        final boolean failOnFaq;
        int calls;

        Model() {
            this(false);
        }

        Model(boolean failOnFaq) {
            super(Map.of());
            this.failOnFaq = failOnFaq;
        }

        @Override
        public AiResponse chat(AiChatRequest request) {
            calls++;
            String prompt = request.getMessages().get(1).getContent();
            if (failOnFaq && prompt.contains(FAQ_PASSAGE)) {
                return AiResponse.failure(AiError.builder().kind(AiError.Kind.RATE_LIMITED).provider("GROQ")
                        .providerStatus(429).message("Rate limit reached").build());
            }
            return AiResponse.builder().content(prompt.contains(GUIDE_PASSAGE) ? "3 - Medium" : "NO_ANSWER").build();
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

    private static EngineSearchResult passage(long id, String index, String text, double score) {
        return new EngineSearchResult(text, score, "en", id, index, "Q" + id, 10L + id, index + ".xlsx", "SHEET",
                (int) id, null, 0.1, 0.03, EngineSearchResult.REASON_VECTOR);
    }

    private static EngineFixture engine(Indexes indexes, Model model) {
        EngineFixture f = new EngineFixture();
        return f.with(new KnowledgeRetrievalStepHandler(f.utils, f.variables, f.schemas, new FixedEmbedding(),
                indexes, f.messages, TextTranslator.NONE, model, accountId -> null));
    }

    /** FAQ (no gaps), then on {@code found == false} the guides (the last step, gaps on). */
    private static JourneyDefinition chain() {
        return journey("ASK",
                step(1, "KNOWLEDGE_RETRIEVAL")
                        .apiConfig("{\"indexName\":\"faq\",\"answerMode\":\"COMPOSE\",\"recordGaps\":false}"),
                step(2, "CONDITION").conditionExpression("steps.1.found == false"),
                step(3, "KNOWLEDGE_RETRIEVAL").parentOrder(2).branchName("true")
                        .apiConfig("{\"indexName\":\"guides\",\"answerMode\":\"COMPOSE\"}"));
    }

    private static final Map<String, Object> QUESTION = Map.of("text", "What priority does a new incident get?");

    @Test
    void anAbstentionInTheFirstStepSendsTheRunOnToTheNextOne() {
        Indexes indexes = new Indexes(Map.of(
                "faq", List.of(passage(1, "faq", FAQ_PASSAGE, 0.78), passage(2, "faq", "Password resets.", 0.74)),
                "guides", List.of(passage(3, "guides", GUIDE_PASSAGE, 0.71),
                        passage(4, "guides", "Major incidents are escalated.", 0.66))));
        Model model = new Model();

        Map<String, Object> result = engine(indexes, model).start(chain(), QUESTION);

        assertThat(trace(result)).containsExactly("KNOWLEDGE_RETRIEVAL:SUCCESS", "CONDITION:SUCCESS",
                "KNOWLEDGE_RETRIEVAL:SUCCESS");
        assertThat(indexes.searched).containsExactly("faq", "guides");
        assertThat(model.calls).isEqualTo(2);
        Map<String, Object> faq = views(result).get(0);
        Map<String, Object> guides = views(result).get(2);
        assertThat(faq).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED")
                .containsEntry("sources", List.of()).containsEntry("gapReported", false);
        assertThat(guides).containsEntry("found", true).containsEntry("composed", true).containsEntry("data",
                "3 - Medium");
        assertThat((List<?>) guides.get("sources")).hasSize(2);
        // The FAQ step's recordGaps is off, and the guides answered: no gap at all.
        assertThat(indexes.misses).isEmpty();
    }

    @Test
    void theChainTakesTheSamePathAfterAnAbstentionAsAfterARetrievalMiss() {
        Indexes abstaining = new Indexes(Map.of(
                "faq", List.of(passage(1, "faq", FAQ_PASSAGE, 0.78), passage(2, "faq", "Password resets.", 0.74)),
                "guides", List.of(passage(3, "guides", GUIDE_PASSAGE, 0.71), passage(4, "guides", "Other.", 0.60))));
        Indexes empty = new Indexes(Map.of(
                "guides", List.of(passage(3, "guides", GUIDE_PASSAGE, 0.71), passage(4, "guides", "Other.", 0.60))));

        Map<String, Object> afterAbstention = engine(abstaining, new Model()).start(chain(), QUESTION);
        Map<String, Object> afterMiss = engine(empty, new Model()).start(chain(), QUESTION);

        assertThat(trace(afterAbstention)).isEqualTo(trace(afterMiss));
        assertThat(views(afterAbstention).get(2).get("data")).isEqualTo(views(afterMiss).get(2).get("data"));
        assertThat(views(afterMiss).get(0)).containsEntry("missReason", "NO_RESULTS");
    }

    @Test
    void whenTheLastStepAbstainsTooTheGapIsRecordedOnceByIt() {
        Indexes indexes = new Indexes(Map.of(
                "faq", List.of(passage(1, "faq", FAQ_PASSAGE, 0.78), passage(2, "faq", "Password resets.", 0.74)),
                "guides", List.of(passage(4, "guides", "Major incidents are escalated.", 0.66),
                        passage(5, "guides", "Closed after 7 days.", 0.61))));

        Map<String, Object> result = engine(indexes, new Model()).start(chain(), QUESTION);

        assertThat(views(result).get(2)).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED")
                .containsEntry("gapReported", true).containsEntry("sources", List.of());
        assertThat(indexes.misses).singleElement().satisfies(miss -> {
            assertThat(miss.indexName()).isEqualTo("guides");
            assertThat(miss.reason()).isEqualTo(KnowledgeMiss.REASON_MODEL_ABSTAINED);
        });
    }

    @Test
    void anAnswerInTheFirstStepEndsTheChainThere() {
        Indexes indexes = new Indexes(Map.of(
                "faq", List.of(passage(1, "faq", GUIDE_PASSAGE, 0.78), passage(2, "faq", "Password resets.", 0.74))));

        Map<String, Object> result = engine(indexes, new Model()).start(chain(), QUESTION);

        assertThat(trace(result)).containsExactly("KNOWLEDGE_RETRIEVAL:SUCCESS", "CONDITION:SUCCESS");
        assertThat(views(result).get(0)).containsEntry("found", true).containsEntry("data", "3 - Medium");
        assertThat(indexes.searched).containsExactly("faq");
    }

    /** The FAQ's model fails and nothing there is a sure match: the run goes on, and the guides' answer is the reply. */
    @Test
    void aFailedGenerationInTheFirstStepSendsTheRunOnAndALaterAnswerIsTheReply() {
        Indexes indexes = new Indexes(Map.of(
                "faq", List.of(passage(1, "faq", FAQ_PASSAGE, 0.45), passage(2, "faq", "Password resets.", 0.42)),
                "guides", List.of(passage(3, "guides", GUIDE_PASSAGE, 0.71),
                        passage(4, "guides", "Major incidents are escalated.", 0.66))));
        Model model = new Model(true);

        Map<String, Object> result = engine(indexes, model).start(chain(), QUESTION);

        assertThat(trace(result)).containsExactly("KNOWLEDGE_RETRIEVAL:SUCCESS", "CONDITION:SUCCESS",
                "KNOWLEDGE_RETRIEVAL:SUCCESS");
        assertThat(indexes.searched).containsExactly("faq", "guides");
        assertThat(model.calls).isEqualTo(2);
        assertThat(views(result).get(0)).containsEntry("found", false).containsEntry("missReason", "GENERATION_FAILED")
                .containsEntry("gapReported", false).containsEntry("sources", List.of())
                .containsEntry("data", UNAVAILABLE);
        assertThat(views(result).get(2)).containsEntry("found", true).containsEntry("composed", true)
                .containsEntry("data", "3 - Medium");
        // The FAQ never records a gap for a failed generation, and the guides answered: no gap at all.
        assertThat(indexes.misses).isEmpty();
    }

    /**
     * The FAQ's model fails, the guides do not have it: the reply asks to try again, and no gap is recorded:
     * the FAQ may well hold the answer, so "can't answer right now" is not a gap.
     */
    @Test
    void aFailedGenerationFollowedByAMissRepliesWithTheTryAgainText() {
        Indexes indexes = new Indexes(Map.of(
                "faq", List.of(passage(1, "faq", FAQ_PASSAGE, 0.45), passage(2, "faq", "Password resets.", 0.42)),
                "guides", List.of(passage(4, "guides", "Major incidents are escalated.", 0.66),
                        passage(5, "guides", "Closed after 7 days.", 0.61))));

        Map<String, Object> result = engine(indexes, new Model(true)).start(chain(), QUESTION);

        assertThat(trace(result)).containsExactly("KNOWLEDGE_RETRIEVAL:SUCCESS", "CONDITION:SUCCESS",
                "KNOWLEDGE_RETRIEVAL:SUCCESS");
        assertThat(views(result).get(0)).containsEntry("missReason", "GENERATION_FAILED");
        assertThat(views(result).get(2)).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED")
                .containsEntry("gapReported", false).containsEntry("data", UNAVAILABLE);
        assertThat(indexes.misses).isEmpty();
    }

    /** The chain as one step: both indexes searched, one model call, citations from both. */
    @Test
    void oneStepSearchingBothIndexesAnswersWithOneModelCall() {
        Indexes indexes = new Indexes(Map.of(
                "faq", List.of(passage(1, "faq", FAQ_PASSAGE, 0.78), passage(2, "faq", "Password resets.", 0.74)),
                "guides", List.of(passage(3, "guides", GUIDE_PASSAGE, 0.71))));
        Model model = new Model();

        Map<String, Object> result = engine(indexes, model).start(journey("ASK", step(1, "KNOWLEDGE_RETRIEVAL")
                .apiConfig("{\"indexNames\":[\"faq\",\"guides\"],\"answerMode\":\"COMPOSE\"}")), QUESTION);

        assertThat(trace(result)).containsExactly("KNOWLEDGE_RETRIEVAL:SUCCESS");
        assertThat(indexes.searched).containsExactly("faq", "guides");
        assertThat(model.calls).isEqualTo(1);
        assertThat(views(result).get(0)).containsEntry("found", true).containsEntry("data", "3 - Medium");
        assertThat((List<Map<String, Object>>) views(result).get(0).get("sources"))
                .extracting(source -> source.get("indexName")).containsExactlyInAnyOrder("faq", "faq", "guides");
        assertThat(indexes.misses).isEmpty();
    }

    /** The chain as one step that misses: one gap, naming both indexes. */
    @Test
    void oneStepSearchingBothIndexesRecordsOneGapForAMiss() {
        Indexes indexes = new Indexes(Map.of(
                "faq", List.of(passage(1, "faq", FAQ_PASSAGE, 0.78), passage(2, "faq", "Password resets.", 0.74)),
                "guides", List.of(passage(4, "guides", "Major incidents are escalated.", 0.66))));

        Map<String, Object> result = engine(indexes, new Model()).start(journey("ASK", step(1, "KNOWLEDGE_RETRIEVAL")
                .apiConfig("{\"indexNames\":[\"faq\",\"guides\"],\"answerMode\":\"COMPOSE\"}")), QUESTION);

        assertThat(views(result).get(0)).containsEntry("found", false).containsEntry("missReason", "MODEL_ABSTAINED")
                .containsEntry("gapReported", true);
        assertThat(indexes.misses).singleElement()
                .satisfies(miss -> assertThat(miss.indexNames()).containsExactly("faq", "guides"));
    }
}
