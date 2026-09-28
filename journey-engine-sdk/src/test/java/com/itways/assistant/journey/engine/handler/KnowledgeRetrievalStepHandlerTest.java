package com.itways.assistant.journey.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.ai.service.impl.LocalEmbeddingEngine;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.KnowledgeBasePort;
import com.itways.assistant.journey.engine.service.TextTranslator;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.model.EngineSearchResult;
import com.itways.assistant.journey.model.JourneyStep;

/**
 * A knowledge step searches in the run's assistant scope: the index name means
 * that assistant's own index, else the shared one. A shared journey run for one
 * assistant must never be handed another assistant's index of the same name.
 */
class KnowledgeRetrievalStepHandlerTest {

    /** What the port was asked, and one passage to answer with. */
    private static final class RecordingPort implements KnowledgeBasePort {
        final List<Object[]> calls = new ArrayList<>();

        @Override
        public List<EngineSearchResult> search(String accountId, UUID assistantId, String indexName,
                float[] queryVector, int limit, String locale) {
            calls.add(new Object[] { accountId, assistantId, indexName });
            return List.of(new EngineSearchResult("We deliver to Irbid.", 0.9, null));
        }
    }

    /** No Ollama: every text gets the same vector. */
    private static final class FixedEmbedding extends LocalEmbeddingEngine {
        FixedEmbedding() {
            super("http://localhost:1");
        }

        @Override
        public float[] embed(String text) {
            return new float[] { 1f };
        }
    }

    private final RecordingPort port = new RecordingPort();
    private final KnowledgeRetrievalStepHandler handler = new KnowledgeRetrievalStepHandler(
            new EngineUtils(new ObjectMapper()), new VariableContext(), null, new FixedEmbedding(), port,
            new EngineMessages(), TextTranslator.NONE, null, null);

    private static JourneyStep step() {
        return JourneyStep.builder().stepOrder(1).actionType("KNOWLEDGE_RETRIEVAL")
                .apiConfig("{\"indexName\":\"faq\",\"answerMode\":\"SINGLE\"}").build();
    }

    private static ExecutionContext run(UUID assistantId) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("text", "do you deliver to Irbid?");
        Map<String, Object> variables = new HashMap<>();
        variables.put("inputs", inputs);
        return ExecutionContext.builder().accountId("acc-1").assistantId(assistantId).variables(variables).build();
    }

    @Test
    void theSearchRunsInTheRunsAssistantScope() {
        UUID assistant = UUID.randomUUID();

        StepResult result = handler.execute(step(), run(assistant));

        assertThat(result.getData()).isEqualTo("We deliver to Irbid.");
        assertThat(port.calls).hasSize(1);
        assertThat(port.calls.get(0)).containsExactly("acc-1", assistant, "faq");
    }

    @Test
    void aRunWithoutAnAssistantSearchesSharedIndexesOnly() {
        handler.execute(step(), run(null));

        assertThat(port.calls.get(0)).containsExactly("acc-1", null, "faq");
    }

    /** JRN-18: the step message goes to run history, so no internal address or body. */
    @Test
    void aFailedSearchStoresAGenericMessageNotTheInternalDetail() {
        KnowledgeBasePort failing = (accountId, assistantId, indexName, vector, limit, locale) -> {
            throw new IllegalStateException(
                    "500 on POST http://172.19.0.5:8085/internal/knowledge/search: {\"trace\":\"secret\"}");
        };
        KnowledgeRetrievalStepHandler failingHandler = new KnowledgeRetrievalStepHandler(
                new EngineUtils(new ObjectMapper()), new VariableContext(), null, new FixedEmbedding(), failing,
                new EngineMessages(), TextTranslator.NONE, null, null);

        StepResult result = failingHandler.execute(step(), run(UUID.randomUUID()));

        assertThat(result.getStatus()).isEqualTo(com.itways.assistant.journey.model.StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("The knowledge base could not be searched right now");
        assertThat(result.getMessage()).doesNotContain("172.19", "internal", "trace");
    }

    /** LIB-10: a provider failure while composing must never become the answer; the stored one is used. */
    @Test
    void aFailedCompositionFallsBackToTheStoredAnswerNotTheProviderError() {
        KnowledgeBasePort twoPassages = (accountId, assistantId, indexName, vector, limit, locale) -> List.of(
                new EngineSearchResult("We deliver to Irbid.", 0.92, null),
                new EngineSearchResult("Delivery takes two days.", 0.90, null));
        com.itways.assistant.ai.service.AiService failingAi = new com.itways.assistant.ai.service.AiService(Map.of()) {
            @Override
            public com.itways.assistant.ai.dto.AiResponse chat(com.itways.assistant.ai.dto.AiChatRequest request) {
                return com.itways.assistant.ai.dto.AiResponse.failure(com.itways.assistant.ai.dto.AiError.builder()
                        .kind(com.itways.assistant.ai.dto.AiError.Kind.RATE_LIMITED).provider("GROQ")
                        .providerStatus(429).message("Rate limit reached").build());
            }
        };
        KnowledgeRetrievalStepHandler composing = new KnowledgeRetrievalStepHandler(
                new EngineUtils(new ObjectMapper()), new VariableContext(), null, new FixedEmbedding(), twoPassages,
                new EngineMessages(), TextTranslator.NONE, failingAi, accountId -> null);
        JourneyStep step = JourneyStep.builder().stepOrder(1).actionType("KNOWLEDGE_RETRIEVAL")
                .apiConfig("{\"indexName\":\"faq\",\"answerMode\":\"COMPOSE\"}").build();

        StepResult result = composing.execute(step, run(UUID.randomUUID()));

        assertThat(result.getStatus()).isEqualTo(com.itways.assistant.journey.model.StepStatus.SUCCESS);
        assertThat(result.getData()).isEqualTo("We deliver to Irbid.");
        assertThat(result.getMetadata()).containsEntry("composed", false);
    }
}
