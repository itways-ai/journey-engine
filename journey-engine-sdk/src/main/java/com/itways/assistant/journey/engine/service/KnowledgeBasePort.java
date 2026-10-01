package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.model.EngineSearchResult;
import java.util.List;
import java.util.UUID;

/**
 * Port interface for knowledge base search.
 * Implemented by the host (conversation-service), which delegates to journey-service.
 * Kept here in the SDK so KnowledgeRetrievalStepHandler can depend on it
 * without knowing anything about JPA or PostgreSQL.
 *
 * <p>
 * <b>1.0.20.</b> The step calls {@link #search(KnowledgeQuery)}, which carries the
 * question's text, the step's threshold and its diversity, and reports a question
 * it could not answer through {@link #recordMiss(KnowledgeMiss)}. Both are default
 * methods, so an adapter written for 1.0.19 compiles and behaves as before: the
 * query goes to the positional {@code search}, and misses are dropped. An adapter
 * that wants hybrid search, the gate and gaps overrides both, and implements the
 * positional method by delegating to {@link #search(KnowledgeQuery)}.
 *
 * <p>
 * The positional method stays the single abstract one, so the port is still a
 * functional interface and a lambda still implements it.
 */
public interface KnowledgeBasePort {

    /**
     * Searches for the most similar chunks to the given query vector.
     *
     * @param accountId  the account that owns the knowledge base
     * @param assistantId the assistant the search runs for. An index name
     *                   resolves in its scope: the assistant's own index of that
     *                   name, else the shared one, never another assistant's.
     *                   Null searches shared indexes only
     * @param indexName  the named knowledge base to search (e.g. "products", "faq")
     * @param queryVector the embedding vector of the user's query
     * @param limit      maximum number of results to return
     * @param locale     the conversation's language, or null for no preference.
     *                   When the index holds chunks in this locale, only those
     *                   are searched; when it holds none, the whole index is
     *                   searched and answers may come back in another language.
     *                   Restricting to a locale that exists is what stops a
     *                   near-duplicate English chunk outscoring the correct
     *                   Arabic one on a cross-lingual embedding
     * @return list of matching text chunks ordered by similarity (most similar first)
     * @throws KnowledgeIndexMissingException when the index does not exist in the scope
     */
    List<EngineSearchResult> search(String accountId,
                                    UUID assistantId,
                                    String indexName,
                                    float[] queryVector,
                                    int limit,
                                    String locale);

    /**
     * Searches one index with the question's text, the step's threshold and its
     * diversity (1.0.20).
     *
     * <p>
     * An adapter that forwards all of it lets journey-service run the hybrid
     * search, gate each hit against {@link KnowledgeQuery#threshold()} and say why
     * it served it ({@link EngineSearchResult#reason()}). The default drops the new
     * fields and calls the positional method; its hits carry no reason, so the step
     * applies the threshold itself.
     *
     * @return hits in the order to serve them, best first
     * @throws KnowledgeIndexMissingException when the index does not exist in the scope
     */
    default List<EngineSearchResult> search(KnowledgeQuery query) {
        return search(query.accountId(), query.assistantId(), query.indexName(), query.queryVector(),
                query.limit(), query.locale());
    }

    /**
     * Searches several indexes, one {@link #search(KnowledgeQuery)} each, for a step
     * that names more than one ({@code apiConfig.indexNames}).
     *
     * <p>
     * One outcome per query, in the queries' order. A search that throws is an
     * outcome with its failure, never thrown from here: the step skips a missing
     * index with a warning and fails only when every one failed. The default
     * searches one after another on the calling thread; an adapter may run them in
     * parallel, carrying whatever thread-bound state its searches need (the
     * caller's credential, for one) to the threads it uses, and must still return
     * them in order.
     */
    default List<KnowledgeSearchOutcome> searchEach(List<KnowledgeQuery> queries) {
        List<KnowledgeSearchOutcome> outcomes = new java.util.ArrayList<>(queries.size());
        for (KnowledgeQuery query : queries) {
            try {
                outcomes.add(KnowledgeSearchOutcome.found(query.indexName(), search(query)));
            } catch (RuntimeException e) {
                outcomes.add(KnowledgeSearchOutcome.failed(query.indexName(), e));
            }
        }
        return outcomes;
    }

    /**
     * Reports a question the step could not answer, so it can be recorded as a
     * knowledge gap (1.0.20).
     *
     * <p>
     * Called on the run's thread: an implementation must not block on the write
     * (hand it to an executor) and must not throw. The step ignores any failure
     * here and still gives its "no answer" reply. Not called for a failed search,
     * a missing index, a model that failed to compose the answer
     * ({@link KnowledgeMiss#REASON_GENERATION_FAILED}), a miss on a question an
     * earlier knowledge step of the run failed to compose an answer to, a step
     * that could not search one of its indexes, a rehearsal (simulated) run or a
     * step whose {@code apiConfig.recordGaps} is false. A step that searched
     * several indexes reports one miss naming them all ({@link KnowledgeMiss#indexNames()}).
     * The default does nothing.
     */
    default void recordMiss(KnowledgeMiss miss) {
        // An adapter written before 1.0.20 records no gaps.
    }
}
