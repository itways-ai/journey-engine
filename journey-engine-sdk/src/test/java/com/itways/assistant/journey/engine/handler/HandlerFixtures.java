package com.itways.assistant.journey.engine.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.DecisionWords;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.ExecutionStatus;
import java.util.HashMap;
import java.util.Map;

/**
 * The collaborators every handler test needs, built by hand: no Spring context,
 * no network. Handlers are plain classes, so a test constructs exactly the one it
 * exercises with these.
 */
final class HandlerFixtures {

    static final ObjectMapper JSON = new ObjectMapper();
    static final EngineUtils UTILS = new EngineUtils(JSON);
    static final VariableContext VARIABLES = new VariableContext();
    static final StepOutputSchemaHelper SCHEMAS = new StepOutputSchemaHelper(JSON);
    static final EngineMessages MESSAGES = new EngineMessages();

    private HandlerFixtures() {
    }

    /** Loaded by hand: {@code @PostConstruct} does not run outside a container. */
    static DecisionWords decisionWords() {
        DecisionWords words = new DecisionWords(MESSAGES);
        words.load();
        return words;
    }

    /** A running context with the standard variable buckets and the given entities. */
    static ExecutionContext run(Map<String, Object> entities) {
        ExecutionContext context = ExecutionContext.builder()
                .executionId("exec-1")
                .accountId("acc-1")
                .status(ExecutionStatus.RUNNING)
                .variables(new HashMap<>())
                .build();
        VARIABLES.ensureStructure(context);
        VARIABLES.getInputs(context).put("entities", new HashMap<>(entities));
        return context;
    }

    static ExecutionContext run() {
        return run(Map.of());
    }

    /** Puts {@code answer} where a resumed turn's reply lands: {@code inputs.answer}. */
    static void answer(ExecutionContext context, Object answer) {
        VARIABLES.getInputs(context).put("answer", answer);
    }

    /** {@code steps.<order>.<field>} as a handler wrote it. */
    @SuppressWarnings("unchecked")
    static Object stepField(ExecutionContext context, int order, String field) {
        Map<String, Object> steps = (Map<String, Object>) context.getVariables().get("steps");
        Object bucket = steps.get(String.valueOf(order));
        return bucket instanceof Map<?, ?> map ? map.get(field) : null;
    }

    static Object output(ExecutionContext context, int order) {
        return stepField(context, order, "output");
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
