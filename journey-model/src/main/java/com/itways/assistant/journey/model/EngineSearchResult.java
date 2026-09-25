package com.itways.assistant.journey.model;

/**
 * One knowledge base hit, as journey-service returns it and the engine's
 * KNOWLEDGE_RETRIEVAL step consumes it.
 *
 * @param answer     the stored answer, verbatim
 * @param similarity cosine similarity to the query vector, 0..1
 * @param locale     the language the answer is stored in, or null when the
 *                   chunk was never tagged. Null is meaningfully different from
 *                   a tagged locale: an untagged corpus is assumed usable in any
 *                   language and is left alone, while a chunk tagged in the
 *                   wrong language is a candidate for translation on output
 */
public record EngineSearchResult(
        String answer,
        double similarity,
        String locale
) {
}
