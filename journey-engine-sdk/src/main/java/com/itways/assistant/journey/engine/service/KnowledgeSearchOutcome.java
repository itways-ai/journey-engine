package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.model.EngineSearchResult;
import java.util.List;

/**
 * What one index's search came to, when a KNOWLEDGE_RETRIEVAL step searches
 * several ({@link KnowledgeBasePort#searchEach(List)}): its hits, or why there
 * are none.
 *
 * <p>
 * A failure is carried, not thrown, so that one index that is missing (or one
 * search that fails) does not cost the answers of the others: the step skips it
 * with a warning, and fails only when every index failed.
 *
 * @param indexName the index searched
 * @param hits      its hits, best first; empty (never null) when it failed
 * @param failure   why the search failed, or null when it did not
 */
public record KnowledgeSearchOutcome(String indexName, List<EngineSearchResult> hits, RuntimeException failure) {

    public KnowledgeSearchOutcome {
        hits = hits == null ? List.of() : hits;
    }

    /** A search that came back (possibly with nothing). */
    public static KnowledgeSearchOutcome found(String indexName, List<EngineSearchResult> hits) {
        return new KnowledgeSearchOutcome(indexName, hits, null);
    }

    /** A search that failed. */
    public static KnowledgeSearchOutcome failed(String indexName, RuntimeException failure) {
        return new KnowledgeSearchOutcome(indexName, List.of(), failure);
    }

    /** Whether the search came back. */
    public boolean succeeded() {
        return failure == null;
    }

    /**
     * Whether the index does not exist in the run's scope: the failure is (or is
     * caused by) a {@link KnowledgeIndexMissingException}.
     */
    public boolean missing() {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof KnowledgeIndexMissingException) {
                return true;
            }
        }
        return false;
    }
}
