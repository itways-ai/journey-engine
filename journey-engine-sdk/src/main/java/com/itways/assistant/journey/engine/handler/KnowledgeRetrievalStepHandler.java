package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiMessage;
import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.context.Simulation;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.language.LanguageDetector;
import com.itways.assistant.journey.engine.model.*;
import com.itways.assistant.journey.engine.service.AiConfigProvider;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.KnowledgeIndexMissingException;
import com.itways.assistant.journey.engine.service.KnowledgeMiss;
import com.itways.assistant.journey.engine.service.KnowledgeQuery;
import com.itways.assistant.journey.engine.service.KnowledgeSearchOutcome;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.TextTranslator;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.EngineSearchResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalStepHandler implements StepHandler {

    /** Hits served when the step does not say ({@code apiConfig.limit}). */
    static final int DEFAULT_LIMIT = 5;
    /** The most hits a step may ask for. */
    static final int MAX_LIMIT = 20;

    /**
     * The cosine similarity a hit needs when the step does not say
     * ({@code apiConfig.threshold}): a "sure match" on the knowledge base's own
     * embedding model ({@code snowflake-arctic-embed2}, the embedding switch).
     * It was 0.70 on {@code granite-embedding:278m}, which scores everything
     * higher; 0.70 was also the fixed floor before 1.0.20, when the step's own
     * setting was ignored (B01).
     */
    static final double DEFAULT_THRESHOLD = 0.38;
    /** Below this every loosely related passage would answer. */
    static final double MIN_THRESHOLD = 0.30;
    /** Above this even a verbatim paraphrase misses. */
    static final double MAX_THRESHOLD = 0.95;

    /**
     * The similarity a passage needs to be shown to the model, for a step that
     * composes, when the step does not say ({@code apiConfig.recallThreshold}).
     *
     * <p>
     * Lower than {@link #DEFAULT_THRESHOLD} on purpose: no fixed threshold
     * separates every real paraphrase from every unrelated row, so a composing
     * step fetches generously and lets the model, which reads the passages, be
     * the precision filter ({@code NO_ANSWER}). 0.34 on the knowledge base's own
     * embedding model ({@code snowflake-arctic-embed2}); it was 0.55 on
     * {@code granite-embedding:278m}, where a question that names the product
     * scored 0.70–0.83 against unrelated rows.
     */
    static final double DEFAULT_RECALL_THRESHOLD = 0.34;

    /**
     * How many candidates are shown to the model when the platform does not say
     * ({@code journey.knowledge.synthesis.max-chunks}). 8 since the recall round
     * (it was 5, and 3 before that): with question vectors and lexical recall a
     * composing step gets more real candidates back, and the model, not the
     * similarity score, is what tells the right one from its neighbours.
     */
    static final int DEFAULT_SYNTHESIS_MAX_CHUNKS = 8;

    /**
     * How many of the candidates shown are picked by vector similarity alone when
     * the platform does not say ({@code journey.knowledge.synthesis.vector-first}).
     *
     * <p>
     * The port's order is fused (vector and full-text rank) and spread over
     * different passages (MMR). That order is right for the rest of the list, and
     * wrong for its head: a passage that matches the question almost word for word
     * can be pushed down by a diverse neighbour or by one the full-text leg ranks
     * higher, and then fall off the end of what the model is shown. So the best
     * few by similarity always make it, and the remaining slots follow the port.
     */
    static final int DEFAULT_SYNTHESIS_VECTOR_FIRST = 4;

    /**
     * The similarity a stored entry needs to be served as the answer when the model
     * was asked and failed, when the platform does not say
     * ({@code journey.knowledge.synthesis.sure-match-threshold}).
     *
     * <p>
     * Much higher than the step's threshold on purpose. A composing step searches on
     * a recall floor and counts on the model to throw out the near misses; when the
     * model is not there to do that, only a candidate that is almost certainly the
     * answer may go out unread. 0.85 on the knowledge base's own embedding model
     * ({@code snowflake-arctic-embed2}), and only on the stored question
     * ({@link #nearIdenticalQuestion}): a near-identical wording of a stored
     * question. It was 0.60, which a question merely sharing a stored one's topic
     * reached ("free parking for the night shift?" served the shift's start time at
     * 0.72). Clamped to 0.30..0.95, as a threshold is.
     */
    static final double DEFAULT_SURE_MATCH_THRESHOLD = 0.85;

    /** The reply to a question that went unanswered: the knowledge base has nothing on it. */
    static final String MESSAGE_NO_ANSWER = "step.knowledge.noAnswer";

    /**
     * The reply to a question that went unanswered because the model composing the
     * answer failed (a provider error, an exception, an empty reply): a temporary
     * fault, so the user is asked to try again rather than told the knowledge base
     * knows nothing about it.
     */
    static final String MESSAGE_UNAVAILABLE = "step.knowledge.unavailable";

    /**
     * Where a step that failed to generate marks the run
     * ({@link ExecutionContext#getInternal(String)}), so that a later knowledge
     * step of the chain that misses replies with {@link #MESSAGE_UNAVAILABLE} too,
     * and records no gap, whatever its own miss reason.
     *
     * <p>
     * An engine internal rather than a {@code runtime} variable: it is bookkeeping
     * between two steps, not a fact a journey author should branch on, and
     * internals never reach run history, CODE_SCRIPT or DATA_MAP. Its value is the
     * question's {@link #fingerprint(String) fingerprint} (its length and a short
     * hash, never the text), and only a step asked the same question honours it:
     * a parked run resumed with a new question, or a loop that jumps back to ask
     * the next one, must not carry one failure into every later miss.
     */
    static final String GENERATION_FAILED_MARK = "knowledgeGenerationFailed";

    /** The system instruction for a question that is not Arabic: the reply's language follows the question. */
    static final String SYSTEM_NEUTRAL = "You answer questions strictly from the passages you are given."
            + " Answer in the same language as the question. If the passages do not contain the answer, reply"
            + " with exactly " + KnowledgeAbstention.TOKEN + " and nothing else.";

    /** The system instruction for an Arabic question: the same rules, in Arabic, so the reply stays Arabic. */
    static final String SYSTEM_ARABIC = "أنت تجيب عن الأسئلة حصرًا من المقاطع المرفقة."
            + " اكتب إجابتك باللغة العربية، بلغة السؤال نفسها."
            + " إذا لم تتضمن المقاطع إجابة السؤال، فاكتب " + KnowledgeAbstention.TOKEN + " فقط، بالضبط، ولا شيء غيرها.";

    /** Stateless: which script the question is written in. */
    private static final LanguageDetector LANGUAGES = new LanguageDetector();

    /** The reasons journey-service gives a hit it served; any other reason is a drop. */
    static final Set<String> SERVED_REASONS = Set.of(EngineSearchResult.REASON_VECTOR,
            EngineSearchResult.REASON_LEXICAL_CORROBORATED);

    /**
     * The reasons a composing step takes a hit as a candidate for the model: the
     * served ones, and {@link EngineSearchResult#REASON_LEXICAL_RECALL}, a passage
     * below the floor that journey-service handed to a recall search on strong
     * full-text evidence (most of the question's terms, or one of its rare ones).
     * Such a hit is only ever read by the model: it is never the answer as stored.
     */
    static final Set<String> RECALL_REASONS = Set.of(EngineSearchResult.REASON_VECTOR,
            EngineSearchResult.REASON_LEXICAL_CORROBORATED, EngineSearchResult.REASON_LEXICAL_RECALL);

    /**
     * The step message when the search itself fails. Generic on purpose: the
     * exception text can carry the knowledge service's internal address and
     * response body, and step messages are stored in run history (JRN-18).
     */
    static final String SEARCH_UNAVAILABLE = "The knowledge base could not be searched right now";

    /**
     * The step message when the step's index does not exist in the run's scope
     * (B08). A configuration fault: the step fails, and no gap is recorded, since
     * no knowledge added to a nonexistent index could ever answer it.
     */
    static final String INDEX_MISSING = "KNOWLEDGE_INDEX_MISSING";

    private final EngineUtils        engineUtils;
    private final VariableContext    variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final LocalEmbeddingEngine embeddingEngine;
    private final KnowledgeBasePort  knowledgeBasePort;
    private final EngineMessages messages;
    private final TextTranslator translator;
    private final com.itways.assistant.ai.service.AiService aiService;
    private final AiConfigProvider aiConfigProvider;

    /**
     * Whether several served chunks are composed into one answer.
     *
     * <p>
     * Off restores the previous behaviour exactly: the single best-scoring
     * chunk, returned as stored.
     */
    @org.springframework.beans.factory.annotation.Value("${journey.knowledge.synthesis.enabled:true}")
    boolean synthesisEnabled = true;

    /**
     * How many candidates are shown to the model when composing (see
     * {@link #shown(List, int, int)} for which). Every one shown is cited, and only
     * those: a citation the model never saw is not a source of its answer. A
     * composing step also asks the port for twice as many
     * ({@link #recallLimit(int, int)}).
     */
    @org.springframework.beans.factory.annotation.Value("${journey.knowledge.synthesis.max-chunks:8}")
    int synthesisMaxChunks = DEFAULT_SYNTHESIS_MAX_CHUNKS;

    /**
     * How many of the candidates shown are the best by vector similarity, whatever
     * the port's order; the rest follow the port's order.
     */
    @org.springframework.beans.factory.annotation.Value("${journey.knowledge.synthesis.vector-first:4}")
    int synthesisVectorFirst = DEFAULT_SYNTHESIS_VECTOR_FIRST;

    /**
     * The similarity a stored entry needs to be served when the model failed
     * ({@link #DEFAULT_SURE_MATCH_THRESHOLD}); never below the step's own
     * threshold, plus {@code journey.knowledge.recall.arabic-offset} for an
     * Arabic run.
     */
    @org.springframework.beans.factory.annotation.Value("${journey.knowledge.synthesis.sure-match-threshold:0.85}")
    double sureMatchThreshold = DEFAULT_SURE_MATCH_THRESHOLD;

    /**
     * Whether a short natural-language abstention ("The sources do not contain…",
     * "لا تحتوي المصادر…") counts as {@code NO_ANSWER} too. The token is the
     * primary signal; this catches a model that says it in words instead.
     */
    @org.springframework.beans.factory.annotation.Value("${journey.knowledge.synthesis.abstention-phrases:true}")
    boolean abstentionPhrases = true;

    /**
     * How much more than the step's threshold a candidate of an Arabic run needs
     * to be served without the model, when the search ran on the recall floor
     * (a single sure match, or no model to compose). A recall search asks
     * journey-service for the floor as sent, without the Arabic offset its gate
     * otherwise applies, so the step applies it here; mirrors journey-service's
     * {@code journey.knowledge.search.arabic-vector-offset}. 0 (off) by default
     * since the knowledge base moved to its own embedding model
     * ({@code snowflake-arctic-embed2}); it was 0.05 on {@code granite-embedding:278m}.
     */
    @org.springframework.beans.factory.annotation.Value("${journey.knowledge.recall.arabic-offset:0}")
    double recallArabicOffset = 0;

    /** What the model made of the candidates it was shown. */
    enum Outcome {
        /** It answered from them. */
        ANSWERED,
        /** It said they do not answer the question ({@code NO_ANSWER}). */
        ABSTAINED,
        /**
         * There is no model to ask (no AI service, no configuration provider, or an
         * account with no AI provider set up): the step answers as it would without
         * composing, from what reaches its threshold.
         */
        NO_MODEL,
        /**
         * It was asked and failed: a provider error, an exception, an empty reply.
         * A temporary fault, not a verdict on the candidates.
         */
        FAILED
    }

    /** The model's answer ({@link Outcome#ANSWERED} only), or why there is none. */
    record Synthesis(Outcome outcome, String answer) {
        static final Synthesis ABSTAINED = new Synthesis(Outcome.ABSTAINED, null);
        static final Synthesis NO_MODEL = new Synthesis(Outcome.NO_MODEL, null);
        static final Synthesis FAILED = new Synthesis(Outcome.FAILED, null);
    }

    /**
     * Asks the model to answer from the candidates shown, or to say
     * {@code NO_ANSWER} when they do not answer the question.
     *
     * <p>
     * Retrieval used to be extractive: the top row won and was returned word for
     * word. That is right when one chunk plainly answers the question and wrong
     * the moment the answer is split across two — "what is the refund window"
     * and "what does it exclude" living in separate rows meant the user got half
     * an answer with no sign the other half existed. It is also the precision
     * filter a similarity threshold cannot be: on the platform's embedding model a
     * question naming the product scores as high against unrelated rows as a real
     * paraphrase does against the right one.
     *
     * <p>
     * No model to ask is {@link Outcome#NO_MODEL}: the step answers as one that
     * does not compose would. That includes an account with no AI provider set up
     * ({@link AiConfigProvider#NOT_CONFIGURED}):
     * a setup gap that no retry fixes, so never "try again in a moment". Any other
     * failure to resolve the configuration is {@link Outcome#FAILED}. A model that was asked and gave nothing usable (a
     * provider error, an exception, an empty reply) is {@link Outcome#FAILED}, and
     * different: the candidates were fetched on a recall floor for a model to sift,
     * so serving them unread would put near misses in front of the user. Never the
     * provider's text as the answer, in either case.
     */
    private Synthesis synthesize(String query, List<EngineSearchResult> shown, ExecutionContext context) {
        if (aiService == null || aiConfigProvider == null) {
            return Synthesis.NO_MODEL;
        }
        AiRequestConfig config;
        try {
            config = aiConfigProvider.getConfig(context.getAccountId());
        } catch (Exception e) {
            if (AiConfigProvider.isNotConfigured(e)) {
                // The account has no AI provider: a setup gap no retry fixes, so the
                // step answers as one that does not compose, not "try again".
                log.info("Knowledge synthesis skipped: the account has no AI provider; answering from stored entries");
                return Synthesis.NO_MODEL;
            }
            log.warn("Knowledge synthesis could not resolve the AI configuration: {}", e.getMessage());
            return Synthesis.FAILED;
        }
        try {
            AiResponse response = aiService.chat(AiChatRequest.builder()
                    .messages(composeMessages(query, shown, questionIsArabic(query, context)))
                    .config(config)
                    .build());
            if (response != null && response.isError()) {
                log.warn("Knowledge synthesis got no answer ({})", response.getError().summary());
                return Synthesis.FAILED;
            }
            String reply = response == null ? null : response.getContent();
            if (reply == null || reply.isBlank()) {
                log.warn("Knowledge synthesis got an empty reply");
                return Synthesis.FAILED;
            }
            if (KnowledgeAbstention.isAbstention(reply, abstentionPhrases)) {
                return Synthesis.ABSTAINED;
            }
            String answer = KnowledgeAbstention.withoutThinking(reply).trim();
            if (answer.isEmpty()) {
                log.warn("Knowledge synthesis got a reply with nothing but its reasoning");
                return Synthesis.FAILED;
            }
            log.info("🧩 Knowledge answer composed from {} passage(s)", shown.size());
            return new Synthesis(Outcome.ANSWERED, answer);
        } catch (Exception e) {
            log.warn("Knowledge synthesis failed: {}", e.getMessage());
            return Synthesis.FAILED;
        }
    }

    /**
     * The compose prompt: a system instruction (Arabic for an Arabic question,
     * language-neutral otherwise) and the question with the passages shown,
     * numbered in the order they are cited.
     *
     * <p>
     * The reply's language follows the question, not the run: a user who asks in
     * Arabic in an English conversation reads the answer in Arabic. The model
     * replies {@value KnowledgeAbstention#TOKEN} and nothing else when the passages
     * do not answer; the step then misses as a retrieval miss does.
     */
    static List<AiMessage> composeMessages(String query, List<EngineSearchResult> shown, boolean arabicQuestion) {
        StringBuilder prompt = new StringBuilder()
                .append("Answer the question using only the passages below.\n")
                .append("Rules:\n")
                .append("- Use only what the passages say; never add facts of your own.\n")
                .append("- If the passages do not contain the answer to the question, reply with exactly ")
                .append(KnowledgeAbstention.TOKEN).append(" and nothing else.\n")
                .append("- Answer in the same language as the question.\n")
                .append("- Be brief. Do not mention the passages, their numbers or these rules.\n\n")
                .append("Question: ").append(query).append("\n\nPassages:\n");
        for (int i = 0; i < shown.size(); i++) {
            prompt.append('[').append(i + 1).append("] ").append(text(shown.get(i))).append('\n');
        }
        return List.of(AiMessage.system(arabicQuestion ? SYSTEM_ARABIC : SYSTEM_NEUTRAL),
                AiMessage.user(prompt.toString()));
    }

    /**
     * Whether the question is Arabic: by its script, or when it has too few
     * letters to tell (a code, a number), by the run's language.
     */
    static boolean questionIsArabic(String query, ExecutionContext context) {
        ConversationLanguage written = LANGUAGES.detect(query);
        if (written != null) {
            return written == ConversationLanguage.ARABIC;
        }
        return context != null && context.resolvedLanguage() == ConversationLanguage.ARABIC;
    }

    /**
     * Whether this step is allowed to combine several entries into one answer.
     *
     * <p>
     * The step decides, and only falls back to the platform default when its
     * author expressed no preference. That order matters: an assistant
     * legitimately needs both behaviours at once — a refund window explained
     * across two entries should read as one answer, while the clause underneath
     * it must come back in its approved wording, unmerged and unreworded. A
     * single global switch cannot express that, and getting it wrong on a legal
     * or pricing answer is not a cosmetic mistake.
     *
     * <p>
     * An unrecognised value falls through to the default rather than failing the
     * step: a typo in one step's config should not take a knowledge base offline.
     *
     * <p>
     * A step that composes also searches on its recall floor
     * ({@code recallThreshold}) and lets the model decide which candidates answer;
     * one that does not keeps its threshold and answers with the stored entry.
     */
    private boolean mayCompose(ApiConfig config) {
        String mode = config == null || config.getAnswerMode() == null
                ? null
                : config.getAnswerMode().trim();
        if (mode == null || mode.isEmpty() || "AUTO".equalsIgnoreCase(mode)) {
            return synthesisEnabled;
        }
        if ("SINGLE".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("COMPOSE".equalsIgnoreCase(mode)) {
            return true;
        }
        log.warn("KNOWLEDGE_RETRIEVAL: unrecognised answerMode '{}' — using the platform default", mode);
        return synthesisEnabled;
    }

    @Override
    public String getType() {
        return "KNOWLEDGE_RETRIEVAL";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.knowledgeDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.knowledgeRetrievalSchema();
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        List<String> indexNames = List.of();
        try {
            ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
            String accountId = context.getAccountId();

            // 1. Query = user's message (context variable "text").
            //    Optionally overridable via apiConfig.query with {{placeholder}} syntax,
            //    but for standard FAQ flows the user's input IS the query.
            String rawQuery = (config.getQuery() != null && !config.getQuery().isBlank())
                    ? config.getQuery()
                    : "{{inputs.text}}";

            String query = engineUtils.replacePlaceholders(rawQuery, context.getVariables());

            if (query == null || query.isBlank()) {
                return StepResult.error("KNOWLEDGE_RETRIEVAL: query is empty");
            }

            indexNames = indexNames(config);
            if (indexNames.isEmpty()) {
                return StepResult.error("KNOWLEDGE_RETRIEVAL: indexName is required");
            }

            int limit = limit(config.getLimit());
            double threshold = threshold(config.getThreshold());
            Double diversity = diversity(config.getDiversity());
            // The run's language: a ranking preference in the search (same-language
            // passages first), not a filter; the port bridges a question written in
            // another language than the index (cross-language search).
            String locale = context.resolvedLanguage().code();
            // A step that composes fetches on the recall floor and lets the model
            // decide; one that answers with the stored entry keeps its threshold.
            boolean composing = mayCompose(config);
            Double recallThreshold = composing ? recallThreshold(config.getRecallThreshold(), threshold) : null;
            double searchThreshold = composing ? recallThreshold : threshold;
            // A composing step asks for more than it can show, so that neither the
            // port's limit nor its diversification cuts a strong vector match before
            // the step picks what the model reads.
            int searchLimit = composing ? recallLimit(limit, synthesisMaxChunks) : limit;

            // B20: what a person asked is personal data. INFO carries its length and
            // a short hash (enough to follow one question through the logs), the
            // text itself only DEBUG.
            log.info("🔍 Knowledge retrieval: step={}, index(es)={}, query {}, limit={}, searchLimit={}, threshold={}, "
                    + "recallThreshold={}, diversity={}", step.getStepOrder(), indexNames, fingerprint(query), limit,
                    searchLimit, threshold, recallThreshold, diversity);
            log.debug("Knowledge retrieval query for step {}: '{}'", step.getStepOrder(), query);

            float[] queryVector = embeddingEngine.embed(query);
            String embeddingModel = embeddingEngine.modelId();

            // In the run's assistant scope: the name means that assistant's own index,
            // else the shared one. A shared journey therefore reads each assistant's
            // own "faq" where it has one, and no run ever reads another assistant's.
            // The query text, threshold and diversity let journey-service run the
            // hybrid search, gate the hits and spread them (an adapter from before
            // 1.0.20 drops them and the threshold is applied here instead). A recall
            // search asks journey-service to apply the floor as sent.
            List<KnowledgeQuery> queries = indexNames.stream()
                    .map(index -> new KnowledgeQuery(accountId, context.getAssistantId(), index, query, queryVector,
                            searchLimit, locale, searchThreshold, diversity, embeddingModel, composing))
                    .toList();

            Search search;
            List<EngineSearchResult> results;
            List<EngineSearchResult> served;
            if (queries.size() == 1) {
                // One index: exactly as before indexNames existed. A missing index or
                // a failed search throws, and fails the step below.
                results = knowledgeBasePort.search(queries.get(0));
                if (results == null) {
                    results = List.of();
                }
                search = new Search(indexNames, List.of(), List.of(), query, queryVector, embeddingModel, limit,
                        threshold, recallThreshold, recordGaps(config));
                served = served(results, searchThreshold, searchLimit, composing);
            } else {
                Searched searched = searchEach(step, queries);
                if (searched.searched().isEmpty()) {
                    return searched.missing().size() == queries.size()
                            ? indexMissing(step, indexNames, searched.missing())
                            : StepResult.error(SEARCH_UNAVAILABLE);
                }
                results = searched.merged();
                search = new Search(searched.searched(), searched.missing(), searched.failed(), query, queryVector,
                        embeddingModel, limit, threshold, recallThreshold, recordGaps(config));
                served = servedAcross(results, searchThreshold, composing ? results.size() : limit, composing);
            }

            if (served.isEmpty()) {
                EngineSearchResult best = best(results);
                return miss(step, context, search, results,
                        best == null ? KnowledgeMiss.REASON_NO_RESULTS : KnowledgeMiss.REASON_BELOW_THRESHOLD,
                        searchThreshold, Map.of());
            }

            EngineSearchResult best = served.get(0);
            log.info("🎯 Knowledge retrieval: step={}, {} of {} hit(s) served, best score {} (passage {}, {})",
                    step.getStepOrder(), served.size(), results.size(), score(best.similarity()), best.id(),
                    reason(best));
            return composing
                    ? compose(step, context, search, results, served)
                    : answer(step, context, search, answerInRunLanguage(best, context), served, false);

        } catch (Exception e) {
            if (indexMissing(e)) {
                // B08: a typo in the step's index name, or an index deleted under a
                // published journey. Not a gap: nothing added to the knowledge base
                // under another name could answer it.
                return indexMissing(step, indexNames, indexNames);
            }
            // The detail (an internal URL, a response body) stays in the log: the
            // step's message is kept in run history and shown to journey authors.
            log.error("❌ Knowledge Retrieval failed for step {}: {}", step.getStepOrder(), e.getMessage(), e);
            return StepResult.error(SEARCH_UNAVAILABLE);
        }
    }

    /**
     * The step's indexes: {@code apiConfig.indexNames} when it names at least one,
     * else {@code apiConfig.indexName}. Trimmed, blanks dropped, each once, in the
     * order given. Empty when the step names none.
     */
    static List<String> indexNames(ApiConfig config) {
        Set<String> names = new LinkedHashSet<>();
        if (config != null && config.getIndexNames() != null) {
            for (String name : config.getIndexNames()) {
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
        }
        if (names.isEmpty() && config != null && config.getIndexName() != null
                && !config.getIndexName().isBlank()) {
            names.add(config.getIndexName().trim());
        }
        return List.copyOf(names);
    }

    /**
     * B08: the step's index (or every one of its indexes) does not exist in the
     * run's scope. A configuration fault, not a gap: the step fails with
     * {@value #INDEX_MISSING} and records nothing.
     */
    private StepResult indexMissing(JourneyStep step, List<String> indexNames, List<String> missing) {
        log.warn("KNOWLEDGE_RETRIEVAL step {}: index(es) {} do not exist in the run's scope",
                step.getStepOrder(), missing);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("errorCode", KnowledgeIndexMissingException.ERROR_CODE);
        metadata.put("indexName", indexNames.isEmpty() ? null : indexNames.get(0));
        metadata.put("indexNames", indexNames);
        metadata.put("missingIndexNames", missing);
        return StepResult.builder()
                .status(StepStatus.ERROR)
                .message(INDEX_MISSING)
                .metadata(metadata)
                .build();
    }

    /**
     * The searches of a step that names several indexes, merged.
     *
     * @param merged   every hit of the indexes that were searched ({@link #merge})
     * @param searched the indexes that were searched, in the step's order
     * @param missing  the indexes that do not exist in the run's scope (skipped)
     * @param failed   the indexes whose search failed otherwise (skipped)
     */
    private record Searched(List<EngineSearchResult> merged, List<String> searched, List<String> missing,
            List<String> failed) {
    }

    /**
     * Searches every index through the port ({@link KnowledgeBasePort#searchEach},
     * which may run them in parallel) and merges what came back. An index that is
     * missing, or whose search fails, is skipped with a warning; the caller fails
     * the step only when none was searched.
     */
    private Searched searchEach(JourneyStep step, List<KnowledgeQuery> queries) {
        List<KnowledgeSearchOutcome> outcomes = knowledgeBasePort.searchEach(queries);
        if (outcomes == null) {
            outcomes = List.of();
        }
        List<String> searched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<KnowledgeSearchOutcome> found = new ArrayList<>();
        for (KnowledgeSearchOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            if (outcome.succeeded()) {
                searched.add(outcome.indexName());
                found.add(outcome);
            } else if (outcome.missing()) {
                log.warn("KNOWLEDGE_RETRIEVAL step {}: index '{}' does not exist in the run's scope; skipped",
                        step.getStepOrder(), outcome.indexName());
                missing.add(outcome.indexName());
            } else {
                log.warn("KNOWLEDGE_RETRIEVAL step {}: index '{}' could not be searched ({}); skipped",
                        step.getStepOrder(), outcome.indexName(), outcome.failure().getMessage());
                failed.add(outcome.indexName());
            }
        }
        return new Searched(merge(found), List.copyOf(searched), List.copyOf(missing), List.copyOf(failed));
    }

    /**
     * The hits of several indexes as one list (one embedding model, so their
     * scores compare).
     *
     * <p>
     * Each hit says which index it came from (filled in from its search when the
     * port did not say), so a citation keeps it. The order interleaves the
     * indexes' own orders by rank (each index's first hit, then each one's
     * second, ...): every index ranked only its own hits, fused and spread, and no
     * index's tail outranks another's head. A passage that came back twice (by id,
     * or by the same text in two indexes) is kept once, the copy with the higher
     * similarity, at the place the first copy had.
     */
    static List<EngineSearchResult> merge(List<KnowledgeSearchOutcome> outcomes) {
        List<List<EngineSearchResult>> lists = new ArrayList<>();
        for (KnowledgeSearchOutcome outcome : outcomes) {
            lists.add(outcome.hits().stream()
                    .filter(Objects::nonNull)
                    .map(hit -> withIndex(hit, outcome.indexName()))
                    .toList());
        }
        List<EngineSearchResult> merged = new ArrayList<>();
        Map<Object, Integer> places = new HashMap<>();
        int longest = lists.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < longest; rank++) {
            for (List<EngineSearchResult> list : lists) {
                if (rank >= list.size()) {
                    continue;
                }
                EngineSearchResult hit = list.get(rank);
                Integer place = null;
                for (Object key : keys(hit)) {
                    place = places.get(key);
                    if (place != null) {
                        break;
                    }
                }
                if (place == null) {
                    for (Object key : keys(hit)) {
                        places.put(key, merged.size());
                    }
                    merged.add(hit);
                } else if (hit.similarity() > merged.get(place).similarity()) {
                    for (Object key : keys(hit)) {
                        places.putIfAbsent(key, place);
                    }
                    merged.set(place, hit);
                }
            }
        }
        return List.copyOf(merged);
    }

    /** What makes two hits the same passage: its id, and its text (whitespace and case aside). */
    private static List<Object> keys(EngineSearchResult hit) {
        List<Object> keys = new ArrayList<>(2);
        if (hit.id() != null) {
            keys.add(hit.id());
        }
        String text = text(hit);
        if (text != null) {
            keys.add("text:" + text.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT));
        }
        if (keys.isEmpty()) {
            keys.add(new Object());
        }
        return keys;
    }

    /** The hit, saying which index it came from when the port did not. */
    private static EngineSearchResult withIndex(EngineSearchResult hit, String indexName) {
        if (hit.indexName() != null || indexName == null) {
            return hit;
        }
        return new EngineSearchResult(hit.answer(), hit.similarity(), hit.locale(), hit.id(), indexName,
                hit.question(), hit.sourceId(), hit.sourceName(), hit.sourceKind(), hit.rowNumber(), hit.url(),
                hit.lexicalScore(), hit.fusedScore(), hit.reason(), hit.questionScore(),
                hit.vectorMatch());
    }

    /**
     * {@link #served(List, double, int, boolean)} over the merged hits of several
     * indexes. A composing step keeps them all, in the merged order, and picks
     * what the model reads as it always does ({@link #shown}: the best by meaning
     * first, then the rest). One that does not compose serves the best hit across
     * the indexes: by similarity, best first, at most {@code limit}.
     */
    static List<EngineSearchResult> servedAcross(List<EngineSearchResult> merged, double threshold, int limit,
                                                 boolean recall) {
        List<EngineSearchResult> served = served(merged, threshold, Integer.MAX_VALUE, recall);
        if (recall) {
            return served.stream().limit(Math.max(limit, 1)).toList();
        }
        return served.stream()
                .sorted(Comparator.comparingDouble(EngineSearchResult::similarity).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * The hits this step serves, in the order the port returned them, at most
     * {@code limit}.
     *
     * <p>
     * journey-service gates each hit against the threshold it was sent and says
     * why it served it ({@link EngineSearchResult#reason()}); a hit whose reason is
     * {@link EngineSearchResult#REASON_VECTOR} or
     * {@link EngineSearchResult#REASON_LEXICAL_CORROBORATED} is taken as served,
     * even a little under the threshold, where full-text evidence corroborated
     * it. Any other reason ({@code BELOW_THRESHOLD}, {@code BELOW_LOCALE_THRESHOLD},
     * or one this build does not know) is a drop: only the reasons that mean
     * "served" are trusted. A hit without a reason comes from an adapter or a
     * journey-service that predates the gate, and must reach the threshold here.
     * A hit with no text to answer with is never served.
     */
    static List<EngineSearchResult> served(List<EngineSearchResult> results, double threshold, int limit) {
        return served(results, threshold, limit, false);
    }

    /**
     * {@link #served(List, double, int)}, for a recall search too: a composing
     * step ({@code recall}) also takes {@link EngineSearchResult#REASON_LEXICAL_RECALL}
     * hits, as candidates for the model only ({@link #RECALL_REASONS}), and its
     * {@code limit} is the {@link #recallLimit(int, int) recall limit} it searched
     * with, not the step's.
     */
    static List<EngineSearchResult> served(List<EngineSearchResult> results, double threshold, int limit,
                                           boolean recall) {
        Set<String> reasons = recall ? RECALL_REASONS : SERVED_REASONS;
        return results.stream()
                .filter(Objects::nonNull)
                .filter(hit -> hit.gated()
                        ? reasons.contains(hit.reason())
                        : hit.similarity() >= threshold)
                .filter(hit -> text(hit) != null)
                .limit(limit)
                .toList();
    }

    /**
     * Whether a hit may be served as the answer, as stored: one journey-service
     * served (or one the step gated itself), never a
     * {@link EngineSearchResult#REASON_LEXICAL_RECALL} hit, which is below the floor
     * and only fit for a model to read.
     */
    static boolean storable(EngineSearchResult hit) {
        return SERVED_REASONS.contains(reason(hit));
    }

    /**
     * How many hits a composing step asks the port for:
     * {@code min(20, max(limit, 2 * maxChunks))}. Twice what the model can be
     * shown, so that the port's limit and its diversification (MMR) leave room
     * for the step's own pick ({@link #shown(List, int, int)}); never fewer than
     * the step's limit, never more than {@link #MAX_LIMIT}.
     */
    static int recallLimit(int limit, int maxChunks) {
        return Math.min(MAX_LIMIT, Math.max(limit, 2 * Math.max(maxChunks, 1)));
    }

    /**
     * The candidates shown to the model, in the order they are numbered and cited:
     * the {@code vectorFirst} best by similarity (ties in the port's order), then
     * the rest in the port's order (fused and diversified), at most
     * {@code maxChunks}. A passage the port returned twice is shown once (by id;
     * a hit without one only when it is the same hit).
     *
     * <p>
     * See {@link #DEFAULT_SYNTHESIS_VECTOR_FIRST} for why the head is picked by
     * similarity and the tail is not.
     */
    static List<EngineSearchResult> shown(List<EngineSearchResult> served, int vectorFirst, int maxChunks) {
        int cap = Math.max(maxChunks, 1);
        Set<Long> ids = new HashSet<>();
        Set<EngineSearchResult> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<EngineSearchResult> distinct = served.stream()
                .filter(hit -> hit.id() != null ? ids.add(hit.id()) : seen.add(hit))
                .toList();

        // A stable sort: hits of equal similarity keep the port's order.
        List<EngineSearchResult> shown = new ArrayList<>(distinct.stream()
                .sorted(Comparator.comparingDouble(EngineSearchResult::similarity).reversed())
                .limit(Math.max(0, Math.min(vectorFirst, cap)))
                .toList());
        Set<EngineSearchResult> picked = Collections.newSetFromMap(new IdentityHashMap<>());
        picked.addAll(shown);
        for (EngineSearchResult hit : distinct) {
            if (shown.size() >= cap) {
                break;
            }
            if (picked.add(hit)) {
                shown.add(hit);
            }
        }
        return List.copyOf(shown);
    }

    /**
     * The text a hit answers with: its stored answer, or for a document or web
     * passage (which has no separate answer) the passage itself.
     */
    static String text(EngineSearchResult hit) {
        if (hit.answer() != null && !hit.answer().isBlank()) {
            return hit.answer();
        }
        if (hit.question() != null && !hit.question().isBlank()) {
            return hit.question();
        }
        return null;
    }

    /** {@code apiConfig.limit}, 1..20; absent (or not positive) is 5. */
    static int limit(Integer configured) {
        if (configured == null || configured < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(configured, MAX_LIMIT);
    }

    /** {@code apiConfig.threshold}, 0.30..0.95; absent is 0.38. */
    static double threshold(Double configured) {
        if (configured == null || configured.isNaN()) {
            return DEFAULT_THRESHOLD;
        }
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, configured));
    }

    /**
     * {@code apiConfig.recallThreshold}, 0.30..0.95, and never above the step's
     * threshold (a recall floor is lower by definition); absent is 0.34.
     */
    static double recallThreshold(Double configured, double threshold) {
        double floor = configured == null || configured.isNaN()
                ? DEFAULT_RECALL_THRESHOLD
                : Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, configured));
        return Math.min(floor, threshold);
    }

    /**
     * {@code journey.knowledge.synthesis.sure-match-threshold}, 0.30..0.95, as a
     * threshold is; not a number is 0.85.
     */
    static double sureMatchThreshold(double configured) {
        if (Double.isNaN(configured)) {
            return DEFAULT_SURE_MATCH_THRESHOLD;
        }
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, configured));
    }

    /** {@code apiConfig.diversity}, 0..1; absent stays null, leaving the default to journey-service. */
    static Double diversity(Double configured) {
        if (configured == null || configured.isNaN()) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, configured));
    }

    /**
     * {@code apiConfig.recordGaps}: whether a miss is reported as a knowledge gap.
     * Only an explicit false turns it off; absent keeps the behaviour of every
     * step saved before the setting existed.
     */
    static boolean recordGaps(ApiConfig config) {
        return config == null || !Boolean.FALSE.equals(config.getRecordGaps());
    }

    /**
     * What a log line may say about a question: its length and the first eight hex
     * digits of its SHA-256. Enough to tell whether two lines are about the same
     * question, not what it was.
     */
    static String fingerprint(String text) {
        if (text == null) {
            return "len=0";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "len=" + text.length() + " sha256=" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            return "len=" + text.length();
        }
    }

    /** Whether the port said the index does not exist, directly or as the cause of what it threw. */
    private static boolean indexMissing(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof KnowledgeIndexMissingException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The matched answer, translated if it is stored in another language.
     *
     * <p>
     * Only reached when the index had nothing in the run's language -- the search
     * itself prefers matching-locale chunks, so a properly tagged bilingual
     * corpus never gets here and never pays for a translation.
     *
     * <p>
     * An untagged chunk (null locale) is returned as stored. Guessing its
     * language and translating on that guess risks mangling an answer that was
     * already correct, and untagged corpora are the ones most likely to be mixed.
     */
    private String answerInRunLanguage(EngineSearchResult match, ExecutionContext context) {
        String answer = text(match);
        ConversationLanguage stored = ConversationLanguage.parse(match.locale());
        ConversationLanguage target = context.resolvedLanguage();

        if (answer == null || answer.isBlank() || stored == null || stored == target) {
            return answer;
        }

        try {
            String translated = translator.translate(context.getAccountId(), answer, stored, target);
            if (translated != null && !translated.isBlank()) {
                log.info("Translated knowledge answer from {} to {}", stored.code(), target.code());
                return translated;
            }
        } catch (Exception e) {
            log.warn("Could not translate knowledge answer: {}", e.getMessage());
        }

        // A correct answer in the wrong language beats no answer at all.
        return answer;
    }

    /**
     * What one execution searched for and against which bars: carried to the
     * answer and to the miss.
     *
     * @param limit           the step's limit: at most this many stored entries are cited
     * @param threshold       the step's threshold: the bar for SINGLE, and for a composing
     *                        step the bar a candidate served without the model must reach
     * @param recallThreshold the floor a composing step searched on; null for SINGLE
     * @param indexNames      the indexes searched, in the step's order (never empty)
     * @param missing         the step's indexes that do not exist in the run's scope, skipped
     * @param failed          the step's indexes whose search failed, skipped
     */
    private record Search(List<String> indexNames, List<String> missing, List<String> failed, String query,
            float[] queryVector, String embeddingModel, int limit, double threshold, Double recallThreshold,
            boolean recordGaps) {

        /** The first index searched: the step's {@code indexName}, as reported before {@code indexNames}. */
        String indexName() {
            return indexNames.get(0);
        }

        /** Whether the step searched several indexes and merged their hits. */
        boolean several() {
            return indexNames.size() + missing.size() + failed.size() > 1;
        }

        /** The step view's metadata about which indexes were searched, and which were skipped and why. */
        void describe(Map<String, Object> metadata) {
            metadata.put("indexName", indexName());
            metadata.put("indexNames", indexNames);
            if (!missing.isEmpty() || !failed.isEmpty()) {
                List<String> warnings = new ArrayList<>();
                missing.forEach(name -> warnings.add("Knowledge index '" + name
                        + "' does not exist in the run's scope; it was skipped"));
                failed.forEach(name -> warnings.add("Knowledge index '" + name
                        + "' could not be searched right now; it was skipped"));
                metadata.put("warnings", warnings);
            }
            if (!missing.isEmpty()) {
                metadata.put("missingIndexNames", missing);
            }
            if (!failed.isEmpty()) {
                metadata.put("failedIndexNames", failed);
            }
        }
    }

    /**
     * A composing step's answer from its candidates (1.0.20).
     *
     * <ul>
     * <li>A single candidate that reaches the step's threshold (with the Arabic
     * offset for an Arabic run) <em>is</em> the answer, as stored: sending it to
     * a model to be rephrased costs a call and risks paraphrasing a carefully
     * worded policy for no gain. Not a {@code LEXICAL_RECALL} candidate, which
     * is below the floor and goes to the model however it scores.
     * <li>Otherwise the model is shown {@link #shown(List, int, int) at most
     * {@code journey.knowledge.synthesis.max-chunks}} candidates, and they are
     * cited, in that order, only they.
     * <li>The model saying they do not answer ({@code NO_ANSWER}) is a miss
     * ({@code MODEL_ABSTAINED}), exactly as a retrieval miss: the "no answer"
     * text, {@code found} false, no citations, a gap unless {@code recordGaps}
     * is off. A journey that tries its next knowledge step on
     * {@code found == false} goes on to it.
     * <li>No model to ask (none configured): the candidates that reach the
     * step's threshold are served as stored, as if the step had searched on it;
     * none is a {@code BELOW_THRESHOLD} miss.
     * <li>The model failed (a provider error, an exception, an empty reply): see
     * {@link #generationFailed}.
     * </ul>
     */
    private StepResult compose(JourneyStep step, ExecutionContext context, Search search,
                               List<EngineSearchResult> results, List<EngineSearchResult> served) {
        double sureBar = sureBar(search.threshold(), context);
        if (served.size() == 1 && storable(served.get(0)) && served.get(0).similarity() >= sureBar) {
            return answer(step, context, search, answerInRunLanguage(served.get(0), context), served, false);
        }

        List<EngineSearchResult> shown = shown(served, synthesisVectorFirst, synthesisMaxChunks);
        Synthesis synthesis = synthesize(search.query(), shown, context);
        switch (synthesis.outcome()) {
            case ANSWERED:
                return answer(step, context, search, synthesis.answer(), shown, true);
            case ABSTAINED:
                log.info("Knowledge retrieval: step={}, the model found no answer in the {} passage(s) shown",
                        step.getStepOrder(), shown.size());
                return miss(step, context, search, shown, KnowledgeMiss.REASON_MODEL_ABSTAINED,
                        search.recallThreshold(), Map.of("candidateCount", shown.size()));
            case FAILED:
                return generationFailed(step, context, search, served, shown);
            default:
                List<EngineSearchResult> candidates = served;
                if (search.several()) {
                    // Several indexes: the best across them first, as a step that does not compose serves.
                    candidates = served.stream()
                            .sorted(Comparator.comparingDouble(EngineSearchResult::similarity).reversed())
                            .toList();
                }
                List<EngineSearchResult> sure = candidates.stream()
                        .filter(KnowledgeRetrievalStepHandler::storable)
                        .filter(hit -> hit.similarity() >= sureBar)
                        .limit(search.limit())
                        .toList();
                if (sure.isEmpty()) {
                    return miss(step, context, search, results, KnowledgeMiss.REASON_BELOW_THRESHOLD,
                            search.threshold(), Map.of());
                }
                return answer(step, context, search, answerInRunLanguage(sure.get(0), context), sure, false);
        }
    }

    /**
     * The model was asked and failed: a provider error, an exception, an empty
     * reply.
     *
     * <p>
     * The candidates were fetched on the recall floor for the model to sift, so
     * they are not served unread. Only a stored entry that is almost certainly the
     * answer goes out as stored: the best of this step's candidates by similarity,
     * if it reaches {@code journey.knowledge.synthesis.sure-match-threshold} (never
     * less than the step's own threshold, plus the Arabic offset for an Arabic
     * run), is not a {@code LEXICAL_RECALL} hit, and matched on its stored question
     * ({@link #nearIdenticalQuestion}). It is cited alone.
     *
     * <p>
     * Otherwise the question went unanswered for a reason that is nobody's fault
     * but the provider's, and is likely to pass: the "try again in a moment" text
     * ({@value #MESSAGE_UNAVAILABLE}), {@code found} false, no citations, and
     * {@code missReason} {@code GENERATION_FAILED}. Never a gap, whatever
     * {@code recordGaps} says: the knowledge base may well hold the answer, and a
     * reviewer would only be sent looking for one that exists. The run is marked
     * ({@link #GENERATION_FAILED_MARK}) so that a later knowledge step of the chain
     * that misses the same question says the same, and records no gap either.
     */
    private StepResult generationFailed(JourneyStep step, ExecutionContext context, Search search,
                                        List<EngineSearchResult> served, List<EngineSearchResult> shown) {
        double bar = sureBar(Math.max(sureMatchThreshold(sureMatchThreshold), search.threshold()), context);
        EngineSearchResult sure = served.stream()
                .filter(KnowledgeRetrievalStepHandler::storable)
                .filter(hit -> hit.similarity() >= bar)
                .filter(hit -> nearIdenticalQuestion(hit, bar))
                .max(Comparator.comparingDouble(EngineSearchResult::similarity))
                .orElse(null);
        if (sure != null) {
            log.info("Knowledge retrieval: step={}, the model failed; serving the sure match as stored "
                    + "(passage {}, score {})", step.getStepOrder(), sure.id(), score(sure.similarity()));
            return answer(step, context, search, answerInRunLanguage(sure, context), List.of(sure), false);
        }

        EngineSearchResult best = best(shown);
        log.info("Knowledge retrieval: step={}, the model failed and no candidate reaches {} (best score {}); "
                + "no answer this time, no gap", step.getStepOrder(), score(bar),
                best == null ? "none" : score(best.similarity()));
        context.setInternal(GENERATION_FAILED_MARK, fingerprint(search.query()));
        return unanswered(step, context, search, messages.get(context.resolvedLanguage(), MESSAGE_UNAVAILABLE),
                KnowledgeMiss.REASON_GENERATION_FAILED, false, best,
                Map.of("candidateCount", shown.size()));
    }

    /**
     * Whether a hit is a near-identical wording of a stored question, the only kind
     * served unread when the model failed: journey-service served it on its vector
     * ({@code VECTOR}) and the Q&amp;A row's question-only vector is what matched
     * ({@code vectorMatch} {@code QUESTION}), or its question-only score reaches the
     * bar by itself. A passage that only scores high on its answer (question and
     * answer) vector shares the question's topic, not its question; and a hit
     * without the question evidence (a document passage, an older journey-service)
     * never qualifies.
     */
    static boolean nearIdenticalQuestion(EngineSearchResult hit, double bar) {
        if (EngineSearchResult.REASON_VECTOR.equals(hit.reason())
                && EngineSearchResult.VECTOR_MATCH_QUESTION.equals(hit.vectorMatch())) {
            return true;
        }
        return hit.questionScore() != null && hit.questionScore() >= bar;
    }

    /**
     * Whether an earlier knowledge step of this run failed to generate an answer to
     * the same question ({@link #GENERATION_FAILED_MARK}).
     */
    private static boolean generationFailedEarlier(ExecutionContext context, Search search) {
        Object mark = context.getInternal(GENERATION_FAILED_MARK);
        return mark != null && mark.equals(fingerprint(search.query()));
    }

    /**
     * The similarity a candidate of a recall search needs to be served without
     * the model: the step's threshold, plus {@code journey.knowledge.recall.arabic-offset}
     * for an Arabic run (the bar journey-service's gate would have applied).
     */
    private double sureBar(double threshold, ExecutionContext context) {
        return context.resolvedLanguage() == ConversationLanguage.ARABIC
                ? threshold + Math.max(0, recallArabicOffset)
                : threshold;
    }

    /**
     * A hit: the answer, {@code found} true, and a citation for every passage it
     * stands on — the stored entries served, or the passages the model was shown.
     *
     * <p>
     * The citations ({@code sources}) go to the step's variables, for the journey,
     * and to the step view's metadata, from which conversation-service lifts them
     * onto the reply (and so into the stored chat message) — so a client can cite
     * them, and support can see which passages produced an answer someone disputes.
     */
    private StepResult answer(JourneyStep step, ExecutionContext context, Search search, String answer,
                              List<EngineSearchResult> cited, boolean composed) {
        List<Map<String, Object>> sources = cited.stream().map(hit -> citation(hit, search.indexName())).toList();

        storeKnowledgeOutput(step, context, answer, true);
        variableContext.writeStepField(context, step, "sources", sources);
        variableContext.writeStepField(context, step, "composed", composed);

        // Also on the step view, not just in the variables: the simulator's
        // trace is where an author checks whether an answer was merged or came
        // back as stored, and that is the whole point of the setting.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("found", true);
        metadata.put("composed", composed);
        metadata.put("sourceCount", sources.size());
        metadata.put("sources", sources);
        search.describe(metadata);
        metadata.put("threshold", search.threshold());
        if (search.recallThreshold() != null) {
            metadata.put("recallThreshold", search.recallThreshold());
        }
        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(answer)
                .message(step.getMessage())
                .metadata(metadata)
                .build();
    }

    /**
     * One citation: which passage, where it came from and why it was served.
     * Fields journey-service did not send are left out rather than sent as null.
     * {@code vectorScore} and {@code reason} are always present; a hit the step
     * gated itself was served on its vector score.
     */
    static Map<String, Object> citation(EngineSearchResult hit, String stepIndexName) {
        Map<String, Object> citation = new LinkedHashMap<>();
        putIfPresent(citation, "id", hit.id());
        putIfPresent(citation, "indexName", hit.indexName() != null ? hit.indexName() : stepIndexName);
        putIfPresent(citation, "question", hit.question());
        putIfPresent(citation, "sourceId", hit.sourceId());
        putIfPresent(citation, "sourceName", hit.sourceName());
        putIfPresent(citation, "sourceKind", hit.sourceKind());
        putIfPresent(citation, "rowNumber", hit.rowNumber());
        putIfPresent(citation, "url", hit.url());
        citation.put("vectorScore", hit.similarity());
        putIfPresent(citation, "lexicalScore", hit.lexicalScore());
        putIfPresent(citation, "fusedScore", hit.fusedScore());
        citation.put("reason", reason(hit));
        return citation;
    }

    private static String reason(EngineSearchResult hit) {
        return hit.gated() ? hit.reason() : EngineSearchResult.REASON_VECTOR;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String score(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /**
     * No answer: nothing came back ({@code NO_RESULTS}), nothing reached the
     * threshold ({@code BELOW_THRESHOLD}), or the model said the passages it was
     * shown do not answer ({@code MODEL_ABSTAINED}). The "no answer" reply, and
     * the question reported to the port as a knowledge gap (B07).
     *
     * <p>
     * All three look the same to the journey — the "no answer" text,
     * {@code found} false, no {@code sources}, {@code composed} false — so a
     * chain of knowledge steps goes on to its next step whichever it was. So
     * does a failed generation ({@link #generationFailed}), but for its text and
     * its gap, which it never records.
     *
     * <p>
     * A rehearsal records nothing: an author trying questions in the simulator
     * would otherwise fill the gaps queue with their own tests. Nor does a step
     * with {@code recordGaps} off: in a chain of knowledge steps a later one may
     * still answer, and only the last step's miss is a real gap. The port is told
     * the best score that did come back, so a reviewer can tell a question the
     * knowledge base nearly answered from one it knows nothing about.
     *
     * <p>
     * The reply is the "no answer" text, unless an earlier knowledge step of the
     * run failed to generate an answer to the same question
     * ({@link #generationFailed}): then the knowledge base may hold the answer after
     * all, and the user is asked to try again in a moment instead. The miss is
     * still a miss, but never a gap, whatever this step's own reason and its
     * {@code recordGaps}: "can't answer right now" is not something a reviewer
     * should go looking for knowledge to close.
     *
     * <p>
     * A step that searched several indexes records one gap naming all of them.
     * When one of its searches failed (rather than finding nothing) it records
     * none: the index it could not search may hold the answer.
     *
     * @param considered the candidates the miss is about: every result, or the passages the model was shown
     * @param threshold  the bar the miss was judged against, reported with the gap
     * @param extra      further metadata for the step view
     */
    private StepResult miss(JourneyStep step, ExecutionContext context, Search search,
                            List<EngineSearchResult> considered, String reason, double threshold,
                            Map<String, Object> extra) {
        EngineSearchResult best = best(considered);
        Double bestScore = best == null ? null : best.similarity();
        log.info("Knowledge retrieval: step={}, no answer in {} ({}, best score {}, threshold {})",
                step.getStepOrder(), search.indexNames(), reason, bestScore == null ? "none" : score(bestScore),
                threshold);

        boolean generationFailedEarlier = generationFailedEarlier(context, search);
        boolean reported = false;
        if (generationFailedEarlier) {
            // An earlier step of the chain failed to generate an answer to this very
            // question: the knowledge base may well hold it, so this miss, whatever its
            // own reason, is "can't answer right now", never a gap.
            log.debug("Knowledge retrieval: step={}, an earlier step failed to generate an answer to this "
                    + "question, the miss is not reported", step.getStepOrder());
        } else if (!search.recordGaps()) {
            log.debug("Knowledge retrieval: step={}, recordGaps is off, the miss is not reported",
                    step.getStepOrder());
        } else if (!search.failed().isEmpty()) {
            // One of the indexes could not be searched: it may hold the answer. A fault, not a gap.
            log.debug("Knowledge retrieval: step={}, index(es) {} could not be searched, the miss is not reported",
                    step.getStepOrder(), search.failed());
        } else if (!Simulation.isActive(context)) {
            try {
                // One gap for the question, naming every index searched.
                knowledgeBasePort.recordMiss(new KnowledgeMiss(context.getAccountId(), context.getAssistantId(),
                        search.indexName(), search.query(), context.resolvedLanguage().code(), channelType(context),
                        search.queryVector(), search.embeddingModel(), bestScore, best == null ? null : text(best),
                        threshold, reason, context.getJourneyId(), context.getExecutionId(), search.indexNames()));
                reported = true;
            } catch (Exception e) {
                // A gap that fails to record costs nothing; the reply must still go out.
                log.warn("Knowledge retrieval: step={}, the miss could not be reported: {}",
                        step.getStepOrder(), e.getMessage());
            }
        }

        String key = generationFailedEarlier ? MESSAGE_UNAVAILABLE : MESSAGE_NO_ANSWER;
        return unanswered(step, context, search, messages.get(context.resolvedLanguage(), key), reason, reported,
                best, extra);
    }

    /**
     * What every unanswered question looks like to the journey, whatever the
     * reason: {@code reply} as the output, {@code found} false, no {@code sources},
     * {@code composed} false. The step view's metadata says why
     * ({@code missReason}) and whether a gap was recorded.
     */
    private StepResult unanswered(JourneyStep step, ExecutionContext context, Search search, String reply,
                                  String reason, boolean reported, EngineSearchResult best,
                                  Map<String, Object> extra) {
        Double bestScore = best == null ? null : best.similarity();
        storeKnowledgeOutput(step, context, reply, false);
        variableContext.writeStepField(context, step, "sources", List.of());
        variableContext.writeStepField(context, step, "composed", false);

        Map<String, Object> metadata = new HashMap<>(extra);
        metadata.put("found", false);
        metadata.put("composed", false);
        metadata.put("sourceCount", 0);
        metadata.put("sources", List.of());
        search.describe(metadata);
        metadata.put("threshold", search.threshold());
        if (search.recallThreshold() != null) {
            metadata.put("recallThreshold", search.recallThreshold());
        }
        metadata.put("missReason", reason);
        metadata.put("gapReported", reported);
        if (bestScore != null) {
            metadata.put("bestScore", bestScore);
        }
        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(reply)
                .message(step.getMessage())
                .metadata(metadata)
                .build();
    }

    /** The candidate with the highest similarity, or null when there is none. */
    private static EngineSearchResult best(List<EngineSearchResult> candidates) {
        return candidates.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparingDouble(EngineSearchResult::similarity))
                .orElse(null);
    }

    /** The run's channel type ({@code channel.type}), or null. */
    private static String channelType(ExecutionContext context) {
        Map<String, Object> variables = context.getVariables();
        Object channel = variables == null ? null : variables.get("channel");
        if (channel instanceof Map<?, ?> map && map.get("type") != null) {
            String type = String.valueOf(map.get("type"));
            return type.isBlank() ? null : type;
        }
        return null;
    }

    private void storeKnowledgeOutput(JourneyStep step, ExecutionContext context, String answer, boolean found) {
        // Namespaced only: {{steps.<order>.output}} and {{steps.<order>.found}}.
        // The flat knowledge_found / step<N>_found / <name>_found flags are gone.
        variableContext.storeOutput(context, step, answer);
        variableContext.writeStepField(context, step, "found", found);
    }
}
