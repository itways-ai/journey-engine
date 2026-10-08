package com.itways.assistant.journey.engine.handler;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether a composed knowledge answer is the model saying that the passages it
 * was shown do not answer the question (1.0.20).
 *
 * <p>
 * The compose prompt tells the model to reply with exactly {@value #TOKEN} and
 * nothing else in that case. That is the primary signal, read leniently: case,
 * surrounding whitespace, quotes, brackets, markdown and punctuation do not
 * matter, and a reply that starts with the token (the model adding an
 * explanation after it) or ends with it (the explanation before it) counts. The
 * token inside a sentence does not: "the call status NO_ANSWER means…" is an
 * answer. The spaced spellings ("No answer.") count only as the whole reply, since
 * "No answer is logged as a missed call" is an answer too.
 *
 * <p>
 * Secondary, and behind a flag: a short reply that opens with one of a few
 * common natural-language abstentions ("The sources do not contain…",
 * "لا تحتوي المصادر…"). Each phrase names the passages themselves, or is a
 * first-person "I don't know", so an answer that happens to start with a
 * negation ("The basic plan does not include analytics", "لا يوجد رسوم…") is not
 * mistaken for one. A reply longer than {@value #MAX_PHRASE_REPLY} characters,
 * or one that goes on with "but…"/"لكن…", carries an answer and is kept.
 */
final class KnowledgeAbstention {

    /** What the compose prompt tells the model to reply, and nothing else, when the passages do not answer. */
    static final String TOKEN = "NO_ANSWER";

    /** The longest reply the phrase heuristic reads: an abstention is one short sentence. */
    static final int MAX_PHRASE_REPLY = 200;

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** A model's thinking, if a provider left it in the content. */
    private static final Pattern THINKING = Pattern.compile("(?is)<think>.*?</think>");

    /** What a model may wrap the token in: whitespace, ASCII punctuation, markdown, quotes and brackets. */
    private static final String WRAP = "[\\s\\p{Punct}\\p{Pi}\\p{Pf}\\p{Ps}\\p{Pe}«»“”‘’،؛؟]*";

    /** Not part of the same word: the token is not "NO_ANSWERS". */
    private static final String END_OF_WORD = "(?![\\p{L}\\p{N}_])";
    /** Not followed by a letter: a whole word, in either script. */
    private static final String WORD_END = "(?![\\p{L}])";

    private static final Pattern LEADING_TOKEN = Pattern.compile("^" + WRAP + "no_?answer" + END_OF_WORD, FLAGS);
    /** The token as the reply's last sentence or line, after an explanation; not "The status is NO_ANSWER." */
    private static final Pattern TRAILING_TOKEN = Pattern.compile(
            "(?:^|[\\n.!?؟])[\\s\\p{Pi}\\p{Ps}\"'*`_]*no_answer" + WRAP + "$", FLAGS);
    private static final Pattern WHOLE_SPACED = Pattern.compile("^" + WRAP + "no[\\s-]answer" + WRAP + "$", FLAGS);

    /** A reply that goes on to say something: whatever it opened with, it is not an abstention. */
    private static final Pattern CONTRAST = Pattern.compile(
            "(?<![\\p{L}])(?:but|however|although|though)(?![\\p{L}])|لكن|الا\\s+ان|غير\\s+ان", FLAGS);

    private static final String EN_NEGATION = "(?:do|does|did)(?:\\s+not|n't)";
    private static final String EN_VERB = "(?:contain|answer|mention|include|provide|specify|say|cover|address|state)";
    private static final String EN_MATERIAL = "(?:(?:(?:provided|given|supplied|above)\\s+)?"
            + "(?:sources?|passages?|reference\\s+material|context)"
            + "|(?:provided|given|supplied)\\s+(?:information|text|material|documents?)"
            + "|(?:information|text|material|documents?)\\s+(?:provided|given|supplied))";

    private static final String AR_VERB = "(?:تحتو(?:ي)?|يحتو(?:ي)?|تتضمن|يتضمن|تذكر|يذكر|تجب|تجيب|يجيب|تقدم|يقدم"
            + "|توفر|يوفر|تحدد|يحدد)";
    private static final String AR_MATERIAL = "(?:ال)?(?:مصادر|نصوص|مقاطع|مراجع|مقتطفات|نص|معلومات)";
    private static final String AR_QUALIFIER = "(?:\\s+(?:المقدم[هة]|المتاح[هة]|المعطا[هة]|المرفق[هة]|المذكور[هة]))?";

    /**
     * The phrases, matched at the start of the reply after {@link #normalized}:
     * lower case, Arabic without diacritics or tatweel, and alef forms folded.
     */
    private static final List<Pattern> PHRASES = List.of(
            // "The sources do not contain…", "The provided passages don't answer…", "The information provided does not specify…"
            Pattern.compile("^(?:(?:unfortunately|sorry),?\\s+)?(?:the\\s+)?" + EN_MATERIAL
                    + "(?:\\s+(?:provided|given|supplied|above))?\\s+" + EN_NEGATION + "\\s+" + EN_VERB
                    + WORD_END, FLAGS),
            // "There is no information about…", "There's no mention of…", "There is no information."
            Pattern.compile("^there(?:\\s+is|'s)\\s+no\\s+(?:information|mention)"
                    + "(?:\\s+(?:about|of|on|regarding|in)" + WORD_END + "|\\s*[.!]?$)", FLAGS),
            // "I don't know", "I cannot find…", "I'm unable to answer…"
            Pattern.compile("^i(?:\\s+(?:cannot|can't|could\\s+not|couldn't|do\\s+not|don't|am\\s+unable\\s+to)"
                    + "|'m\\s+(?:unable|not\\s+able)\\s+to)\\s+(?:find|answer|know|have\\s+(?:enough\\s+|any\\s+)?"
                    + "information)" + WORD_END, FLAGS),
            // "لا تحتوي المصادر…", "لم تذكر النصوص…"
            Pattern.compile("^(?:للاسف[،,]?\\s*)?(?:لا|لم)\\s+" + AR_VERB + "\\s+" + AR_MATERIAL + WORD_END),
            // "المصادر المقدمة لا تتضمن…"
            Pattern.compile("^(?:للاسف[،,]?\\s*)?" + AR_MATERIAL + AR_QUALIFIER + "\\s+(?:لا|لم)\\s+" + AR_VERB
                    + WORD_END),
            // "لا توجد معلومات حول…", "لا توجد إجابة." (and not "لا توجد معلومات شخصية مطلوبة")
            Pattern.compile("^(?:للاسف[،,]?\\s*)?لا\\s+(?:توجد|يوجد|تتوفر|يتوفر)\\s+(?:اي\\s+)?(?:معلومات|اجاب[هة]|ذكر)"
                    + "(?:\\s+(?:(?:في|حول|عن|بخصوص|بشان|تتعلق|متعلق[هة])" + WORD_END + "|ل)|\\s*[.!؟]?$)"),
            // "لا أعرف", "لم أجد…", "لا أستطيع الإجابة…", "ليس لدي معلومات…"
            Pattern.compile("^(?:للاسف[،,]?\\s*)?(?:لا\\s+اعرف|لم\\s+اجد|لا\\s+استطيع\\s+(?:الاجاب[هة]|العثور)"
                    + "|ليس\\s+لدي\\s+(?:معلومات|اجاب[هة]))"));

    private static final Pattern ARABIC_MARKS = Pattern.compile("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u0640]");
    private static final Pattern LEADING_WRAP = Pattern.compile("^" + WRAP);

    private KnowledgeAbstention() {
    }

    /**
     * Whether the reply is an abstention: the {@value #TOKEN} signal, or (when
     * {@code phrases} is on) one of the natural-language ones.
     */
    static boolean isAbstention(String reply, boolean phrases) {
        String text = withoutThinking(reply).trim();
        if (text.isEmpty()) {
            return false;
        }
        if (LEADING_TOKEN.matcher(text).find() || TRAILING_TOKEN.matcher(text).find()
                || WHOLE_SPACED.matcher(text).matches()) {
            return true;
        }
        return phrases && isPhrase(text);
    }

    /** Whether the reply is one of the natural-language abstentions (the secondary heuristic). */
    static boolean isPhrase(String reply) {
        String text = normalized(withoutThinking(reply).trim());
        if (text.isEmpty() || text.length() > MAX_PHRASE_REPLY || CONTRAST.matcher(text).find()) {
            return false;
        }
        return PHRASES.stream().anyMatch(phrase -> phrase.matcher(text).find());
    }

    /** The reply without any {@code <think>…</think>} block; empty for null. */
    static String withoutThinking(String reply) {
        return reply == null ? "" : THINKING.matcher(reply).replaceAll("");
    }

    /** Lower case, typographic apostrophes as ASCII, Arabic without marks and with one alef. */
    private static String normalized(String text) {
        String folded = ARABIC_MARKS.matcher(text).replaceAll("")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                .replace('’', '\'').replace('‘', '\'')
                .toLowerCase(Locale.ROOT);
        return LEADING_WRAP.matcher(folded).replaceFirst("").replaceAll("\\s+", " ");
    }
}
