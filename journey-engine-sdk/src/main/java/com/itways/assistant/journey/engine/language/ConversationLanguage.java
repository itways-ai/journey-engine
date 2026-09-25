package com.itways.assistant.journey.engine.language;

import java.util.Locale;

/**
 * The language one conversation is being conducted in.
 *
 * <p>
 * Deliberately a small closed enum rather than a raw BCP-47 string. Every
 * consumer of this value has to <em>do</em> something per language — pick a
 * resource bundle, choose a text direction, name the language to a model — and
 * an open string type pushes an "unknown tag" branch into all of them. Adding a
 * language is a product decision that touches translations anyway, so it costs
 * one entry here and nothing else.
 *
 * @see LanguageDetector for how an inbound message is mapped onto one of these
 */
public enum ConversationLanguage {

    ENGLISH("en", "English", Direction.LTR),
    ARABIC("ar", "Arabic", Direction.RTL);

    /** Used when nothing in the precedence chain yields a language. */
    public static final ConversationLanguage DEFAULT = ENGLISH;

    public enum Direction {
        LTR, RTL;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final String code;
    private final String englishName;
    private final Direction direction;

    ConversationLanguage(String code, String englishName, Direction direction) {
        this.code = code;
        this.englishName = englishName;
        this.direction = direction;
    }

    /** ISO 639-1 code, e.g. {@code "ar"}. */
    public String code() {
        return code;
    }

    /**
     * The language's name in English, for naming it to a model.
     *
     * <p>
     * English names beat native ones in a system prompt: instructions are
     * written in English and a model follows "Reply in Arabic" more reliably
     * than "Reply in العربية", which reads as content rather than instruction.
     */
    public String englishName() {
        return englishName;
    }

    public Direction direction() {
        return direction;
    }

    public boolean isRightToLeft() {
        return direction == Direction.RTL;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(code);
    }

    /**
     * Parses a language tag onto a supported language, or null.
     *
     * <p>
     * Only the primary subtag is considered, so {@code ar-SA}, {@code ar_EG} and
     * {@code ar} all land on Arabic. Regional variants do not currently change
     * any wording, and treating {@code ar-SA} as unknown would silently drop the
     * clearest signal a channel ever gives us.
     *
     * <p>
     * Returns null rather than the default on purpose: "no opinion" and
     * "explicitly English" are different inputs to the precedence chain, and
     * collapsing them makes a missing hint outrank a real one further down.
     */
    public static ConversationLanguage parse(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String normalised = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalised.equals("auto")) {
            return null;
        }
        int separator = normalised.indexOf('-');
        String primary = separator > 0 ? normalised.substring(0, separator) : normalised;
        for (ConversationLanguage language : values()) {
            if (language.code.equals(primary)) {
                return language;
            }
        }
        return null;
    }

    /** Parses a tag, falling back to {@link #DEFAULT} when it names nothing supported. */
    public static ConversationLanguage parseOrDefault(String tag) {
        ConversationLanguage parsed = parse(tag);
        return parsed != null ? parsed : DEFAULT;
    }
}
