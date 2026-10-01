package com.itways.assistant.journey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One knowledge base hit, as journey-service returns it and the engine's
 * KNOWLEDGE_RETRIEVAL step consumes it.
 *
 * <p>
 * Since 1.0.20 a hit also says which passage it is, where that passage came from
 * and why it was served, so a reply can cite it. Those fields are trailing and
 * nullable: a journey-service that predates them sends only the first three, and
 * the three-argument constructor keeps every existing caller compiling. Unknown
 * fields are ignored, so a newer journey-service can add more. The question-vector
 * evidence ({@code questionScore}, {@code vectorMatch}) came last, with its own
 * fourteen-argument constructor for the callers written before it.
 *
 * @param answer       the stored answer, verbatim
 * @param similarity   cosine similarity to the query vector, 0..1 (the vector score; for a
 *                     Q&amp;A row with a question vector, the greater of its two vectors)
 * @param locale       the language the answer is stored in, or null when the
 *                     chunk was never tagged. Null is meaningfully different from
 *                     a tagged locale: an untagged corpus is assumed usable in any
 *                     language and is left alone, while a chunk tagged in the
 *                     wrong language is a candidate for translation on output
 * @param id           the passage (row) id; null from a journey-service before 1.0.20
 * @param indexName    the index the passage belongs to
 * @param question     the passage's question, or its text for a document passage
 * @param sourceId     the source (sheet, document, website, typed Q&amp;A) it came from
 * @param sourceName   that source's name, e.g. the file name
 * @param sourceKind   SHEET, DOCUMENT, WEBSITE or QA
 * @param rowNumber    the sheet row, when it came from a sheet
 * @param url          the page, when it came from a website
 * @param lexicalScore full-text rank, 0..1 (0 when the full-text search did not find it)
 * @param fusedScore   reciprocal-rank fusion of both rankings; orders hits, never admits one
 * @param reason       why journey-service served it: {@link #REASON_VECTOR} or
 *                     {@link #REASON_LEXICAL_CORROBORATED}; to a recall search also
 *                     {@link #REASON_LEXICAL_RECALL}. Null when journey-service
 *                     applied no gate (an older journey-service, or a search sent
 *                     without a threshold); the engine then applies the step's
 *                     threshold itself
 * @param questionScore the cosine to the Q&amp;A row's question vector alone; null for a passage
 *                      without one (a document or web passage), or from a journey-service that
 *                      predates it
 * @param vectorMatch  which vector gave {@code similarity}: {@link #VECTOR_MATCH_QUESTION} when the
 *                     question vector is strictly nearer, else {@link #VECTOR_MATCH_ANSWER}; null from
 *                     a journey-service that predates it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineSearchResult(
        String answer,
        double similarity,
        String locale,
        Long id,
        String indexName,
        String question,
        Long sourceId,
        String sourceName,
        String sourceKind,
        Integer rowNumber,
        String url,
        Double lexicalScore,
        Double fusedScore,
        String reason,
        Double questionScore,
        String vectorMatch) {

    /** Served: the vector similarity reaches the threshold. */
    public static final String REASON_VECTOR = "VECTOR";

    /** Served: slightly below the threshold, with full-text evidence. */
    public static final String REASON_LEXICAL_CORROBORATED = "LEXICAL_CORROBORATED";

    /**
     * Served to a recall search only, i.e. to a composing step that hands its candidates to a
     * model: below the floor, on strong lexical evidence (the passage covers most of the
     * question's terms, or contains one of its rare terms). Never served as a stored answer.
     */
    public static final String REASON_LEXICAL_RECALL = "LEXICAL_RECALL";

    /** Not served: below the threshold. Never expected in a search result; refused if seen. */
    public static final String REASON_BELOW_THRESHOLD = "BELOW_THRESHOLD";

    /** {@link #similarity} came from the passage's answer (question and answer) vector. */
    public static final String VECTOR_MATCH_ANSWER = "ANSWER";

    /** {@link #similarity} came from the Q&amp;A row's question-only vector, which was strictly nearer. */
    public static final String VECTOR_MATCH_QUESTION = "QUESTION";

    /** The 1.0.19 shape: an answer and its score, with nothing to cite. */
    public EngineSearchResult(String answer, double similarity, String locale) {
        this(answer, similarity, locale, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** The first 1.0.20 shape: the citation fields and the reason, without the question-vector evidence. */
    public EngineSearchResult(String answer, double similarity, String locale, Long id, String indexName,
            String question, Long sourceId, String sourceName, String sourceKind, Integer rowNumber, String url,
            Double lexicalScore, Double fusedScore, String reason) {
        this(answer, similarity, locale, id, indexName, question, sourceId, sourceName, sourceKind, rowNumber, url,
                lexicalScore, fusedScore, reason, null, null);
    }

    /**
     * Whether journey-service gated this hit against the threshold it was sent,
     * i.e. whether it says why the hit was served.
     */
    public boolean gated() {
        return reason != null && !reason.isBlank();
    }
}
