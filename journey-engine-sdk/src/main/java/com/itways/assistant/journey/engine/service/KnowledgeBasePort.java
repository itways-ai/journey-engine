package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.model.EngineSearchResult;

import java.util.List;
import java.util.UUID;

/**
 * Port interface for knowledge base vector search.
 * Implemented by journey-service using pgvector.
 * Kept here in the SDK so KnowledgeRetrievalStepHandler can depend on it
 * without knowing anything about JPA or PostgreSQL.
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
     */
    List<EngineSearchResult> search(String accountId,
                                    UUID assistantId,
                                    String indexName,
                                    float[] queryVector,
                                    int limit,
                                    String locale);
}
