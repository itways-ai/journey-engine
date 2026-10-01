package com.itways.assistant.journey.engine.service;

import java.util.List;
import java.util.UUID;

/**
 * A question a KNOWLEDGE_RETRIEVAL step could not answer (1.0.20): what the
 * adapter needs to record it as a knowledge gap.
 *
 * <p>
 * Handed to {@link KnowledgeBasePort#recordMiss(KnowledgeMiss)} when the search
 * came back empty, nothing reached the step's threshold, or the model composing
 * the answer said the passages it was shown do not answer the question. Not for
 * a failed search, a missing index or a model that failed to compose an answer
 * ({@link #REASON_GENERATION_FAILED}): those are faults, not gaps.
 *
 * @param accountId      the account that owns the knowledge base
 * @param assistantId    the assistant the run is for; null when the run has none
 * @param indexName      the index that was searched; for a step that searched several, the
 *                       first of them (what an adapter written before {@code indexNames} records)
 * @param query          the question as asked. Personal data: scrub it before storing and
 *                       never log it above DEBUG
 * @param locale         the run's language code
 * @param channelType    the run's channel type ({@code channel.type}), or null
 * @param queryVector    the question's embedding, so the gap needs no second embedding call
 * @param embeddingModel the model that made {@code queryVector}, or null when unknown
 * @param bestScore      the best similarity that came back, or null when nothing did
 * @param bestPassage    the best answer that came back (below the threshold, or one the model
 *                       did not answer from), or null
 * @param threshold      the threshold the miss was judged against: the step's, or for a step that
 *                       composes, the recall floor its search applied
 * @param reason         {@link #REASON_NO_RESULTS}, {@link #REASON_BELOW_THRESHOLD} or
 *                       {@link #REASON_MODEL_ABSTAINED}
 * @param journeyId      the journey the step belongs to, or null
 * @param executionId    the run, or null
 * @param indexNames     every index the step searched, in its order: one gap names them all.
 *                       Never null; {@code [indexName]} for a step that searched one
 */
public record KnowledgeMiss(
        String accountId,
        UUID assistantId,
        String indexName,
        String query,
        String locale,
        String channelType,
        float[] queryVector,
        String embeddingModel,
        Double bestScore,
        String bestPassage,
        double threshold,
        String reason,
        Long journeyId,
        String executionId,
        List<String> indexNames) {

    /** Never a null list; an empty or missing one is {@code [indexName]}. */
    public KnowledgeMiss {
        indexNames = indexNames == null || indexNames.isEmpty()
                ? (indexName == null ? List.of() : List.of(indexName))
                : List.copyOf(indexNames);
    }

    /** A miss in one index: the shape before a step could search several. */
    public KnowledgeMiss(String accountId, UUID assistantId, String indexName, String query, String locale,
            String channelType, float[] queryVector, String embeddingModel, Double bestScore, String bestPassage,
            double threshold, String reason, Long journeyId, String executionId) {
        this(accountId, assistantId, indexName, query, locale, channelType, queryVector, embeddingModel, bestScore,
                bestPassage, threshold, reason, journeyId, executionId, null);
    }

    /** The search returned no hit at all. */
    public static final String REASON_NO_RESULTS = "NO_RESULTS";

    /** Hits came back, none reached the threshold. */
    public static final String REASON_BELOW_THRESHOLD = "BELOW_THRESHOLD";

    /**
     * Candidates came back and were shown to the model composing the answer, which
     * said they do not answer the question ({@code NO_ANSWER}).
     */
    public static final String REASON_MODEL_ABSTAINED = "MODEL_ABSTAINED";

    /**
     * The model composing the answer was asked and failed (a provider error, an
     * exception, an empty reply), and no candidate was sure enough to serve as
     * stored. The step view's {@code missReason} only: never a gap, so never in a
     * {@code KnowledgeMiss} sent to the port. The knowledge base may well hold the
     * answer; the user is asked to try again in a moment.
     */
    public static final String REASON_GENERATION_FAILED = "GENERATION_FAILED";

    /** Never the query text or the vector: a record's generated toString would log both. */
    @Override
    public String toString() {
        return "KnowledgeMiss[indexes=" + indexNames + ", assistant=" + assistantId + ", reason=" + reason
                + ", bestScore=" + bestScore + ", threshold=" + threshold + ", execution=" + executionId
                + ", queryLength=" + (query == null ? 0 : query.length()) + "]";
    }
}
