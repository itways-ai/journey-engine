package com.itways.assistant.journey.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Whether a composed knowledge answer is the model abstaining: the
 * {@code NO_ANSWER} token read leniently, and a few natural-language
 * abstentions behind a flag.
 */
class KnowledgeAbstentionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "NO_ANSWER",
            "  NO_ANSWER \n",
            "no_answer",
            "No_Answer.",
            "\"NO_ANSWER\"",
            "'NO_ANSWER'",
            "«NO_ANSWER»",
            "“NO_ANSWER”",
            "**NO_ANSWER**",
            "`NO_ANSWER`",
            "(NO_ANSWER)",
            "[NO_ANSWER]",
            "NO_ANSWER.",
            "NOANSWER",
            "NO_ANSWER - the passages do not say how much the premium plan costs.",
            "NO_ANSWER\n\nThe passages only cover incident priorities.",
            "NO_ANSWER لا تحتوي المقاطع على السعر",
            "The passages only cover incident priorities.\nNO_ANSWER",
            "لا تذكر المقاطع السعر. NO_ANSWER",
            "<think>\nThe passages mention priorities only.\n</think>\n\nNO_ANSWER",
            "No answer.",
            "NO ANSWER",
            "no-answer" })
    void theTokenIsAnAbstentionHoweverItIsWrapped(String reply) {
        assertThat(KnowledgeAbstention.isAbstention(reply, false)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Priority 3 - Medium",
            "الأولوية الافتراضية هي 3 - متوسطة.",
            "No answer is logged as a missed call after 30 seconds.",
            "The call status NO_ANSWER means the customer did not pick up.",
            "The status is NO_ANSWER.",
            "NO_ANSWERS are retried twice.",
            "Nothing",
            "",
            "   " })
    void anAnswerIsNotAnAbstention(String reply) {
        assertThat(KnowledgeAbstention.isAbstention(reply, true)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The sources do not answer the question.",
            "The provided passages don't contain information about the premium plan.",
            "The passages provided do not mention a price.",
            "The information provided does not specify the price.",
            "Unfortunately, the sources do not cover this.",
            "The given text doesn’t say.",
            "There is no information about the premium plan.",
            "There's no mention of pricing in the passages.",
            "I don't know.",
            "I cannot find the answer in the passages.",
            "I'm unable to answer that from the passages.",
            "لا تحتوي المصادر على إجابة لهذا السؤال.",
            "لا تحتوي المصادر المقدمة على هذه المعلومة.",
            "المصادر المقدمة لا تتضمن معلومات عن السعر.",
            "النصوص لا تذكر سعر الباقة.",
            "لم تذكر المصادر سعر الباقة.",
            "لا توجد معلومات حول الباقة المميزة.",
            "لا توجد إجابة لهذا السؤال في المقاطع.",
            "لا توجد إجابة.",
            "للأسف، لا تحتوي النصوص على هذه المعلومة.",
            "لا أعرف.",
            "لم أجد الإجابة في المقاطع.",
            "لا أستطيع الإجابة من المقاطع المتاحة.",
            "لَا تَحْتَوِي المَصَادِرُ على الإجابة." })
    void aNaturalLanguageAbstentionCountsWhenThePhrasesAreOn(String reply) {
        assertThat(KnowledgeAbstention.isAbstention(reply, true)).isTrue();
        assertThat(KnowledgeAbstention.isAbstention(reply, false)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Answers that open with a negation: nothing about the passages.
            "The basic plan does not include analytics.",
            "Information does not leave the EU.",
            "Deliveries do not include Fridays.",
            "I can find your order under My Orders.",
            "لا يوجد رسوم على التوصيل.",
            "لا تذكر كلمة المرور لأي شخص.",
            "لا تحتوي الباقة الأساسية على التحليلات.",
            "لا توجد معلومات شخصية مطلوبة للتسجيل.",
            "لا يحدد نصف المبلغ إلا المدير.",
            // A caveat followed by an answer is an answer.
            "The sources do not mention the price, but delivery takes two days.",
            "The passages do not say when; however, refunds take 5 working days.",
            "لا تذكر المصادر السعر، لكن التوصيل يستغرق يومين.",
            // An abstention phrase buried in a longer answer.
            "Delivery takes two days. The sources do not mention weekends." })
    void anAnswerThatOnlyLooksLikeOneIsKept(String reply) {
        assertThat(KnowledgeAbstention.isAbstention(reply, true)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void aLongReplyIsNeverAPhraseAbstention() {
        String longReply = "The sources do not mention the premium plan by name. " + "x".repeat(200);

        assertThat(KnowledgeAbstention.isPhrase(longReply)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void thinkingIsRemoved() {
        assertThat(KnowledgeAbstention.withoutThinking("<think>hm</think>\nPriority 3")).isEqualTo("\nPriority 3");
        assertThat(KnowledgeAbstention.withoutThinking(null)).isEmpty();
    }
}
