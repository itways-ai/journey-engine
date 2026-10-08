package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.language.ConversationLanguage;

/**
 * Translates one piece of user-facing text into the run's language.
 *
 * <p>
 * The gap-filler behind every authored source of text: {@code
 * journey_step_translations} for step wording, locale-tagged chunks for
 * knowledge base answers. Authored content is better in every way —
 * deterministic, reviewable, free at runtime — but it only exists once somebody
 * writes it, and until then the alternative is answering an Arabic user in
 * English. So the two work together: authored content wins whenever it exists,
 * and this covers the rest while coverage grows.
 *
 * <p>
 * Implementations must never throw and must return null rather than a
 * best-effort mangling. The caller falls back to the authored text on null,
 * which is the behaviour without this port at all.
 */
public interface TextTranslator {

    /**
     * @return the translated text, or null when translation was not possible or
     *         would not be safe (a lost placeholder, an empty model response)
     */
    String translate(String accountId, String text, ConversationLanguage from, ConversationLanguage to);

    /** Never translates. Callers fall back to authored text. */
    TextTranslator NONE = (accountId, text, from, to) -> null;
}
