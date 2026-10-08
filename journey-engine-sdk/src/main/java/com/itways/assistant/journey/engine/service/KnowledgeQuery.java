package com.itways.assistant.journey.engine.service;

import java.util.UUID;

/**
 * One knowledge search, as a KNOWLEDGE_RETRIEVAL step asks for it (1.0.20).
 *
 * <p>
 * Carries what the positional {@link KnowledgeBasePort#search(String, UUID, String, float[], int, String)}
 * cannot: the question's text (for the full-text leg of a hybrid search), the
 * step's similarity threshold and its diversity. An adapter sends all of it to
 * journey-service, which gates and diversifies the hits.
 *
 * @param accountId      the account that owns the knowledge base
 * @param assistantId    the assistant the search runs for; the index name resolves in its
 *                       scope (its own index, else the shared one). Null searches shared
 *                       indexes only
 * @param indexName      the index to search
 * @param query          the question as asked. Personal data: never log it above DEBUG
 * @param queryVector    the question's embedding
 * @param limit          at most this many hits, 1..20
 * @param locale         the run's language code, or null for no preference. A ranking preference
 *                       (same-language passages rank a little higher), never a filter: the adapter
 *                       also tells journey-service when the question is in another language than
 *                       the index, and sends it translated (cross-language search)
 * @param threshold      the cosine similarity a hit needs, 0.30..0.95. Never null from the
 *                       engine: the step's setting, or 0.70
 * @param diversity      0..1, how much the hits are spread over different passages (MMR);
 *                       null when the step did not say, which leaves the default to
 *                       journey-service
 * @param embeddingModel the model that made {@code queryVector}, or null when unknown
 * @param recall         true when this is a recall search: a step that composes its answer asks
 *                       for candidates on a lower floor ({@code recallThreshold}) and lets the
 *                       model decide which of them answer. The adapter tells journey-service to
 *                       apply {@code threshold} as sent, without the extra bar an Arabic question
 *                       otherwise needs. False for every other search, as before
 */
public record KnowledgeQuery(
        String accountId,
        UUID assistantId,
        String indexName,
        String query,
        float[] queryVector,
        int limit,
        String locale,
        double threshold,
        Double diversity,
        String embeddingModel,
        boolean recall) {

    /** A search gated on {@code threshold} as the step's own bar: not a recall search. */
    public KnowledgeQuery(String accountId, UUID assistantId, String indexName, String query, float[] queryVector,
            int limit, String locale, double threshold, Double diversity, String embeddingModel) {
        this(accountId, assistantId, indexName, query, queryVector, limit, locale, threshold, diversity,
                embeddingModel, false);
    }

    /** Never the query text: a record's generated toString would put it in any log that prints this. */
    @Override
    public String toString() {
        return "KnowledgeQuery[index=" + indexName + ", assistant=" + assistantId + ", limit=" + limit
                + ", locale=" + locale + ", threshold=" + threshold + ", diversity=" + diversity
                + ", recall=" + recall + ", queryLength=" + (query == null ? 0 : query.length()) + "]";
    }
}
