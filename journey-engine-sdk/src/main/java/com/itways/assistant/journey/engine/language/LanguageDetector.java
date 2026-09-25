package com.itways.assistant.journey.engine.language;

import org.springframework.stereotype.Component;

/**
 * Decides what language a message is written in, by script.
 *
 * <p>
 * Counting Arabic-block codepoints against Latin ones is enough to separate the
 * two languages the product ships in, and it is the right tool rather than a
 * cheap stand-in for one: it is deterministic, costs nothing, and adds no
 * latency to a path that already spends seconds in intent classification. An
 * LLM call here would make every turn slower and occasionally answer
 * differently for the same input, to distinguish two scripts that do not
 * overlap in Unicode at all.
 *
 * <p>
 * The interesting behaviour is {@link #hasEnoughSignal}: most turns inside a
 * running journey are short answers — {@code "نعم"}, {@code "ok"}, {@code "3"},
 * a phone number, a URL, an emoji — and re-detecting on those is how a
 * conversation ends up switching language halfway through. Callers are expected
 * to keep the language already established unless this reports real signal.
 */
@Component
public class LanguageDetector {

    /**
     * Script-bearing characters required before a message may change the
     * conversation's language.
     *
     * <p>
     * Three is low enough that a genuine short sentence ({@code "شكرا"},
     * {@code "help"}) still counts, and high enough to reject the answers that
     * actually caused the flipping: single letters, digits, and punctuation.
     */
    private static final int MIN_SIGNIFICANT_CHARS = 3;

    /**
     * Share of script-bearing characters that must be Arabic for the message to
     * count as Arabic.
     *
     * <p>
     * Not 0.5. Arabic messages routinely carry Latin fragments — product codes,
     * URLs, brand names, "OK" — while an English message almost never carries
     * Arabic letters. The asymmetry is real, so the threshold sits below half:
     * any meaningful amount of Arabic means the user is writing Arabic.
     */
    private static final double ARABIC_SHARE_THRESHOLD = 0.3;

    /**
     * The language this text is written in, or null if it does not say.
     *
     * <p>
     * Null means "no opinion" — an empty message, digits, or a bare URL — and is
     * distinct from a confident English answer. Callers must not collapse the
     * two: doing so lets a numeric answer silently reset an Arabic conversation
     * to English.
     */
    public ConversationLanguage detect(String text) {
        Counts counts = count(text);
        if (counts.significant() < MIN_SIGNIFICANT_CHARS) {
            return null;
        }
        double arabicShare = (double) counts.arabic / counts.significant();
        return arabicShare >= ARABIC_SHARE_THRESHOLD ? ConversationLanguage.ARABIC : ConversationLanguage.ENGLISH;
    }

    /**
     * Whether this message carries enough script to be allowed to change the
     * conversation's language.
     */
    public boolean hasEnoughSignal(String text) {
        return count(text).significant() >= MIN_SIGNIFICANT_CHARS;
    }

    private Counts count(String text) {
        if (text == null || text.isBlank()) {
            return new Counts(0, 0);
        }

        int arabic = 0;
        int latin = 0;

        // Codepoint-wise, not char-wise: Arabic Presentation Forms and any
        // supplementary-plane character would otherwise be counted as two
        // surrogate halves, neither of which belongs to any script block.
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            if (!Character.isLetter(codePoint)) {
                // Digits, punctuation and emoji are script-neutral. Counting them
                // would let "12345" look like a confident answer in whichever
                // language happened to win the ratio.
                continue;
            }

            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.ARABIC) {
                arabic++;
            } else if (script == Character.UnicodeScript.LATIN) {
                latin++;
            }
            // Any other script is ignored rather than defaulted: a message in a
            // language we do not ship should fall through to the rest of the
            // precedence chain, not be forced onto whichever of the two it
            // resembles least.
        }

        return new Counts(arabic, latin);
    }

    private record Counts(int arabic, int latin) {
        int significant() {
            return arabic + latin;
        }
    }
}
