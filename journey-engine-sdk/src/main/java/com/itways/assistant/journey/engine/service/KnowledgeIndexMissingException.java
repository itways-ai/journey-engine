package com.itways.assistant.journey.engine.service;

/**
 * Thrown by a {@link KnowledgeBasePort} when the index a step names does not
 * exist in the run's scope (journey-service answers 404 {@code INDEX_NOT_FOUND}).
 *
 * <p>
 * A configuration fault, not a gap: the KNOWLEDGE_RETRIEVAL step fails with
 * {@code KNOWLEDGE_INDEX_MISSING} and records no miss. Before 1.0.20 journey-service
 * answered an empty list for a missing index and the step said "no answer",
 * which hid a typo in the step's index name behind a gap that no new knowledge
 * could ever close.
 */
public class KnowledgeIndexMissingException extends RuntimeException {

    /** The journey-service error code this exception stands for. */
    public static final String ERROR_CODE = "INDEX_NOT_FOUND";

    private final String indexName;

    public KnowledgeIndexMissingException(String indexName) {
        this(indexName, null);
    }

    public KnowledgeIndexMissingException(String indexName, Throwable cause) {
        super("Knowledge index '" + indexName + "' does not exist", cause);
        this.indexName = indexName;
    }

    public String getIndexName() {
        return indexName;
    }
}
