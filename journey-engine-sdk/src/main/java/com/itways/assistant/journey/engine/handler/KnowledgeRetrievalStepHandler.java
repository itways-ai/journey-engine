package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.model.StepStatus;
import com.itways.assistant.journey.model.EngineSearchResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.*;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.TextTranslator;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalStepHandler implements StepHandler {

    private static final int    DEFAULT_LIMIT     = 5;
    private static final double MIN_ABSOLUTE_THRESHOLD = 0.70;
    private static final double MIN_RELATIVE_GAP       = 0.04;

    private static final double SURE_MATCH_THRESHOLD = 0.85;

    /**
     * The step message when the search itself fails. Generic on purpose: the
     * exception text can carry the knowledge service's internal address and
     * response body, and step messages are stored in run history (JRN-18).
     */
    static final String SEARCH_UNAVAILABLE = "The knowledge base could not be searched right now";

    private final EngineUtils        engineUtils;
    private final VariableContext    variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final LocalEmbeddingEngine embeddingEngine;
    private final KnowledgeBasePort  knowledgeBasePort;
    private final EngineMessages messages;
    private final TextTranslator translator;
    private final com.itways.assistant.ai.service.AiService aiService;
    private final com.itways.assistant.journey.engine.service.AiConfigProvider aiConfigProvider;

    /**
     * Whether several qualifying chunks are composed into one answer.
     *
     * <p>
     * Off restores the previous behaviour exactly: the single best-scoring
     * chunk, returned as stored.
     */
    @org.springframework.beans.factory.annotation.Value("${nibras.knowledge.synthesis.enabled:true}")
    private boolean synthesisEnabled = true;

    /** How many chunks may be quoted to the model when composing. */
    @org.springframework.beans.factory.annotation.Value("${nibras.knowledge.synthesis.max-chunks:3}")
    private int synthesisMaxChunks = 3;

    /**
     * Composes one answer from the chunks that cleared the floor, or null to use
     * the stored text as-is.
     *
     * <p>
     * Retrieval used to be extractive: the top row won and was returned word for
     * word. That is right when one chunk plainly answers the question and wrong
     * the moment the answer is split across two — "what is the refund window"
     * and "what does it exclude" living in separate rows meant the user got half
     * an answer with no sign the other half existed.
     *
     * <p>
     * Only runs when at least two chunks qualify. A single chunk over the bar
     * <em>is</em> the answer; sending it to a model to be rephrased costs a call
     * and risks paraphrasing a carefully worded policy for no gain.
     *
     * <p>
     * Every failure returns null and the caller falls back to the stored answer.
     * A knowledge step that cannot reach a provider must still answer.
     */
    private String synthesize(String query, List<EngineSearchResult> qualifying, ExecutionContext context,
                              ApiConfig config) {
        if (!mayCompose(config) || qualifying.size() < 2) {
            return null;
        }
        List<EngineSearchResult> sources = qualifying.subList(0, Math.min(synthesisMaxChunks, qualifying.size()));

        StringBuilder prompt = new StringBuilder();
        prompt.append("Answer the question using only the sources below.\n")
                .append("Rules: use only what the sources say; never add facts of your own; ")
                .append("if the sources do not answer the question, say so plainly; ")
                .append("answer in ").append(context.resolvedLanguage().englishName())
                .append("; be brief and do not mention the word source or the numbering.\n\n")
                .append("Question: ").append(query).append("\n\nSources:\n");
        for (int i = 0; i < sources.size(); i++) {
            prompt.append(i + 1).append(". ").append(sources.get(i).answer()).append('\n');
        }

        try {
            com.itways.assistant.ai.dto.AiResponse response = aiService.chat(
                    com.itways.assistant.ai.dto.AiChatRequest.builder()
                            .messages(List.of(
                                    com.itways.assistant.ai.dto.AiMessage.system(
                                            "You answer strictly from supplied reference material."),
                                    com.itways.assistant.ai.dto.AiMessage.user(prompt.toString())))
                            .config(aiConfigProvider.getConfig(context.getAccountId()))
                            .build());
            if (response != null && response.isError()) {
                // Never the provider's text as the answer: the stored one is used.
                log.warn("Knowledge synthesis got no answer ({}), using the stored answer",
                        response.getError().summary());
                return null;
            }
            String answer = response == null ? null : response.getContent();
            if (answer == null || answer.isBlank()) {
                return null;
            }
            log.info("🧩 Knowledge answer composed from {} sources", sources.size());
            return answer.trim();
        } catch (Exception e) {
            log.warn("Knowledge synthesis failed, using the stored answer: {}", e.getMessage());
            return null;
        }
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

    /** The chunks worth composing from: everything over the answering floor. */
    private List<EngineSearchResult> qualifying(List<EngineSearchResult> results) {
        return results.stream()
                .filter(result -> result.similarity() >= MIN_ABSOLUTE_THRESHOLD)
                .filter(result -> result.answer() != null && !result.answer().isBlank())
                .toList();
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

            if (query.isBlank()) {
                return StepResult.error("KNOWLEDGE_RETRIEVAL: query is empty");
            }

            String indexName = config.getIndexName();
            if (indexName == null || indexName.isBlank()) {
                return StepResult.error("KNOWLEDGE_RETRIEVAL: indexName is required");
            }

            int    limit     = config.getLimit()     != null ? config.getLimit()     : DEFAULT_LIMIT;

            log.info("🔍 Knowledge Scored Retrieval: index='{}', query='{}'", indexName, query);

            float[] queryVector = embeddingEngine.embed(query);

            // Vector search via port (implemented in journey-service)
            // Locale-scoped when the index has content in this language. Without it a
            // near-duplicate English chunk can outscore the correct Arabic one, since
            // the embedding space is shared across languages.
            // In the run's assistant scope: the name means that assistant's own index,
            // else the shared one. A shared journey therefore reads each assistant's
            // own "faq" where it has one, and no run ever reads another assistant's.
            List<EngineSearchResult> results = knowledgeBasePort.search(
                    accountId, context.getAssistantId(), indexName, queryVector, limit,
                    context.resolvedLanguage().code());

            String fallbackMsg = messages.get(context.resolvedLanguage(), "step.knowledge.noAnswer");

            if (results.isEmpty()) {
                log.warn("⚠️ Database query returned 0 rows for index: '{}'", indexName);
                return triggerFallback(step,context,fallbackMsg);
            }

            EngineSearchResult bestMatch = results.get(0);
            log.info("🎯 Evaluated top match score: {} | Text: '{}'", bestMatch.similarity(), bestMatch.answer());

            double bestScore = bestMatch.similarity();
            double secondScore = results.size() > 1 ? results.get(1).similarity() : 0.0;
            double actualGap = bestScore - secondScore;

            log.info(
                    "Top score={}, Second score={}, Gap={}",
                    bestScore,
                    secondScore,
                    actualGap
            );

            log.info("📊 Confidence gap check -> Best: {}, Second: {}, Computed Gap: {}", bestScore, secondScore, actualGap);
            // GUARD 1: Absolute Floor (Protects against total hallucinations)
            if(bestScore < MIN_ABSOLUTE_THRESHOLD) {
                log.warn("❌ Top score {} rejected below absolute requirement of {}", bestMatch.similarity(), MIN_ABSOLUTE_THRESHOLD);
                return triggerFallback(step,context,fallbackMsg);
            }

            // GUARD 2: The "Sure Match" Bypass (For exact Arabic-to-Arabic matches)
            if(bestScore >= SURE_MATCH_THRESHOLD){
                log.info("🌟 Sure Match bypassed gap check! Score: {}", bestScore);
                return respond(step, context, query, results, bestMatch, config);
            }

//            // Guard 2
//            if (bestScore < SURE_MATCH_THRESHOLD && actualGap < MIN_RELATIVE_GAP) {
//                log.warn("⚠️ Ambiguous result cluster detected. Actual gap of {} is less than required {}. Forcing fallback to protect domain accuracy.", actualGap, MIN_RELATIVE_GAP);
//                return triggerFallback(step,context,fallbackMsg);
//            }

            // GUARD 3: The "Soft Match" / Cross-Lingual Zone
            if(actualGap < MIN_RELATIVE_GAP) {
                log.warn("⚠️ Ambiguous cluster detected (Gap {} < {}). Returning best scored answer.", actualGap, MIN_RELATIVE_GAP);
                return respond(step, context, query, results, bestMatch, config);
            }

            log.info("✅ Knowledge Retrieval complete (Direct Answer).");
            return respond(step, context, query, results, bestMatch, config);

        } catch (Exception e) {
            // The detail (an internal URL, a response body) stays in the log: the
            // step's message is kept in run history and shown to journey authors.
            log.error("❌ Knowledge Retrieval failed for step {}: {}", step.getStepOrder(), e.getMessage(), e);
            return StepResult.error(SEARCH_UNAVAILABLE);
        }
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
        String answer = match.answer();
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
     * The answer this step returns: composed where composing helps, the stored
     * text otherwise.
     *
     * <p>
     * Every accepting path funnels through here so the three score-based routes
     * — sure match, ambiguous cluster, clear winner — cannot drift apart in what
     * they actually publish. Synthesis is gated on there being a second
     * qualifying chunk rather than on which route got here: one chunk over the
     * floor is already the answer whatever its score.
     */
    private StepResult respond(JourneyStep step, ExecutionContext context, String query,
                               List<EngineSearchResult> results, EngineSearchResult bestMatch,
                               ApiConfig config) {
        List<EngineSearchResult> qualifying = qualifying(results);
        String composed = synthesize(query, qualifying, context, config);
        // A composed answer is already in the run's language by instruction;
        // only a stored one may need translating out of its own.
        String answer = composed != null ? composed : answerInRunLanguage(bestMatch, context);

        storeKnowledgeOutput(step, context, answer, true);
        // Sources travel with the output so a client can cite them and support
        // can see which chunks produced an answer someone disputes.
        variableContext.writeStepField(context, step, "sources",
                qualifying.stream().map(EngineSearchResult::answer).toList());
        variableContext.writeStepField(context, step, "composed", composed != null);

        // Also on the step view, not just in the variables: the simulator's
        // trace is where an author checks whether an answer was merged or came
        // back as stored, and that is the whole point of the setting.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("composed", composed != null);
        metadata.put("sourceCount", qualifying.size());
        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(answer)
                .message(step.getMessage())
                .metadata(metadata)
                .build();
    }

    private StepResult triggerFallback(JourneyStep step, ExecutionContext context, String fallbackMsg) {
        storeKnowledgeOutput(step, context, fallbackMsg, false);
        return StepResult.success(fallbackMsg, step.getMessage());
    }

    private void storeKnowledgeOutput(JourneyStep step, ExecutionContext context, String answer, boolean found) {
        // Namespaced only: {{steps.<order>.output}} and {{steps.<order>.found}}.
        // The flat knowledge_found / step<N>_found / <name>_found flags are gone.
        variableContext.storeOutput(context, step, answer);
        variableContext.writeStepField(context, step, "found", found);
    }
}
