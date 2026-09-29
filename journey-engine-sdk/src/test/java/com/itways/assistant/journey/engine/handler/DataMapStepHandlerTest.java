package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.JSON;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.itways.assistant.ai.dto.AiChatRequest;
import com.itways.assistant.ai.dto.AiError;
import com.itways.assistant.ai.dto.AiResponse;
import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

import freemarker.template.Configuration;

/**
 * DATA_MAP asks the model to fill the author's JSON shape from what the user
 * said. It strips a markdown fence, unflattens dotted keys, re-asks once for
 * declared fields left blank, and reports a provider error as one.
 */
class DataMapStepHandlerTest {

    /** Answers each chat call with the next scripted reply and records the prompts. */
    private static final class ScriptedAi extends AiService {
        final List<AiChatRequest> requests = new ArrayList<>();
        private final List<Object> replies;

        /** Each reply is the content of an answer, an {@link AiResponse} as it is, or null for a throw. */
        ScriptedAi(Object... replies) {
            super(Map.of());
            this.replies = new ArrayList<>(java.util.Arrays.asList(replies));
        }

        @Override
        public AiResponse chat(AiChatRequest request) {
            requests.add(request);
            Object next = replies.size() > 1 ? replies.remove(0) : replies.get(0);
            if (next == null) {
                throw new IllegalStateException("provider unreachable");
            }
            if (next instanceof AiResponse scripted) {
                return scripted;
            }
            AiResponse response = new AiResponse();
            response.setContent((String) next);
            return response;
        }

        String lastPrompt() {
            AiChatRequest last = requests.get(requests.size() - 1);
            return last.getMessages().get(last.getMessages().size() - 1).getContent();
        }
    }

    private static DataMapStepHandler handler(AiService ai) {
        TemplateRender render = new TemplateRender(new Configuration(Configuration.VERSION_2_3_32),
                new DefaultResourceLoader());
        return new DataMapStepHandler(ai, JSON, UTILS, VARIABLES, SCHEMAS, render, accountId -> null);
    }

    private static JourneyStep step(String template) {
        return JourneyStep.builder().stepOrder(3).stepName("map").actionType("DATA_MAP").actionTarget(template)
                .message("mapped").build();
    }

    private static ExecutionContext said(String text) {
        ExecutionContext context = run(Map.of("city", "Irbid"));
        VARIABLES.getInputs(context).put("text", text);
        return context;
    }

    @Test
    void theModelsJsonBecomesTheOutputAndThePromptCarriesTheInputs() {
        ScriptedAi ai = new ScriptedAi("```json\n{\"name\":\"Sara\",\"address.city\":\"Irbid\"}\n```");
        ExecutionContext context = said("I am Sara from Irbid");

        StepResult result = handler(ai).execute(step("{\"name\":\"\",\"address\":\"{{inputs.entities.city}}\"}"),
                context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMessage()).isEqualTo("mapped");
        assertThat(result.getData()).isEqualTo(Map.of("name", "Sara", "address", Map.of("city", "Irbid")));
        assertThat(output(context, 3)).isEqualTo(result.getData());
        assertThat(ai.requests).hasSize(1);
        // The template's placeholders are resolved before the model sees it.
        assertThat(ai.lastPrompt()).contains("I am Sara from Irbid").contains("\"address\":\"Irbid\"");
    }

    @Test
    void conversationalTextAroundTheJsonIsDropped() {
        ScriptedAi ai = new ScriptedAi("Sure! Here it is: {\"name\":\"Sara\"} Hope that helps.");

        StepResult result = handler(ai).execute(step("{\"name\":\"\"}"), said("Sara"));

        assertThat(result.getData()).isEqualTo(Map.of("name", "Sara"));
    }

    @Test
    void aBlankDeclaredFieldIsAskedForOnceMore() {
        ScriptedAi ai = new ScriptedAi("{\"name\":\"Sara\",\"briefing\":\"\"}",
                "{\"name\":\"Other\",\"briefing\":\"Needs a refund\"}");

        StepResult result = handler(ai).execute(step("{\"name\":\"\",\"briefing\":\"\"}"), said("refund please"));

        assertThat(ai.requests).hasSize(2);
        assertThat(ai.lastPrompt()).contains("left these fields empty or missing: briefing");
        // Only the gap is taken from the retry; what the first answer got right stands.
        assertThat(result.getData()).isEqualTo(Map.of("name", "Sara", "briefing", "Needs a refund"));
    }

    /** A provider failure fails the step with its kind and status, never with the provider's text. */
    @Test
    void aProviderErrorFailsTheStepCleanly() {
        ScriptedAi ai = new ScriptedAi(AiResponse.failure(AiError.builder().kind(AiError.Kind.BAD_REQUEST)
                .provider("GROQ").providerStatus(413).message("Request too large for model gsk_secretkey123").build()));
        ExecutionContext context = said("hi");

        StepResult result = handler(ai).execute(step("{\"name\":\"\"}"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("Data Mapping Failed: AI provider error BAD_REQUEST (GROQ 413)");
        assertThat(output(context, 3)).isNull();
    }

    @Test
    void aRateLimitedProviderFailsTheStepWithoutStoringAnOutput() {
        ScriptedAi ai = new ScriptedAi(AiResponse.failure(AiError.builder().kind(AiError.Kind.RATE_LIMITED)
                .provider("CLAUDE").providerStatus(429).message("slow down").build()));
        ExecutionContext context = said("hi");

        StepResult result = handler(ai).execute(step("{\"name\":\"\"}"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).doesNotContain("slow down").contains("RATE_LIMITED (CLAUDE 429)");
        assertThat(result.getData()).isNull();
        assertThat(output(context, 3)).isNull();
    }

    @Test
    void aFailedRepairKeepsTheFirstAnswer() {
        ScriptedAi ai = new ScriptedAi("{\"name\":\"Sara\",\"briefing\":\"\"}",
                AiResponse.failure(AiError.builder().kind(AiError.Kind.REFUSED).provider("CLAUDE").build()));

        StepResult result = handler(ai).execute(step("{\"name\":\"\",\"briefing\":\"\"}"), said("refund please"));

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getData()).isEqualTo(Map.of("name", "Sara", "briefing", ""));
    }

    @Test
    void anEmptyAnswerOrAnUnreachableProviderFailsTheStep() {
        assertThat(handler(new ScriptedAi("  ")).execute(step("{}"), said("hi")).getStatus())
                .isEqualTo(StepStatus.ERROR);

        StepResult unreachable = handler(new ScriptedAi((String) null)).execute(step("{}"), said("hi"));
        assertThat(unreachable.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(unreachable.getMessage()).isEqualTo("Data Mapping Failed: provider unreachable");
    }

    /** Over budget, older step outputs are dropped first and the prompt says which. */
    @Test
    void anOversizedContextDropsTheOldestStepOutputs() throws Exception {
        Map<String, Object> steps = new HashMap<>();
        steps.put("1", Map.of("output", "x".repeat(6000)));
        steps.put("2", Map.of("output", "y".repeat(6000)));
        Map<String, Object> variables = new HashMap<>();
        variables.put("steps", steps);
        variables.put("inputs", Map.of("text", "hi"));

        String bounded = handler(new ScriptedAi("{}")).boundedContext(variables, step("{}"));

        assertThat(bounded).contains("yyyy").doesNotContain("xxxx")
                .contains("Older step outputs were omitted to fit: steps 1.");
    }
}
