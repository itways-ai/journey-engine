package com.itways.assistant.journey.engine.context;

import com.itways.assistant.journey.engine.language.LanguageParams;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.engine.util.Placeholders;
import com.itways.assistant.journey.engine.util.VariablePath;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class VariableContext {

    private static final Set<String> RESERVED_KEYS = Set.of(
            "text", "files", "entities", "answer", "forceIntent", "channel",
            // Lifted onto the context by LanguageParams; an entities copy would
            // look authoritative to an author while changing nothing.
            LanguageParams.PARAM_LANGUAGE,
            // Stripped by the caller before the engine runs, so this is defence
            // rather than the primary guard. It matters because the value is a
            // memory partition key: an author who could write it could point the
            // next turn at another conversation's history.
            ConversationParams.PARAM_CONVERSATION_ID,
            // Lifted into internals by ChannelCapabilities; a journey must not be
            // able to read what its channel cannot do.
            ChannelCapabilities.PARAM_CAPABILITIES,
            // The end user's bearer token. Copied into entities it became a
            // variable, and variables are persisted with the parked run and the
            // run history and shown to CODE_SCRIPT and DATA_MAP — every path
            // EndUserAuth exists to keep it off.
            EndUserAuth.PARAM_USER_TOKEN,
            // The rehearsal flag. Merged before Simulation.lift runs, so without
            // this a journey could read {{inputs.entities.simulate}} and behave
            // differently under test.
            Simulation.PARAM_SIMULATE);

    public void ensureStructure(ExecutionContext context) {
        Map<String, Object> vars = context.getVariables();
        if (vars == null) {
            vars = new HashMap<>();
            context.setVariables(vars);
        }
        vars.putIfAbsent("inputs", new HashMap<String, Object>());
        vars.putIfAbsent("steps", new HashMap<String, Object>());
        vars.putIfAbsent("state", new HashMap<String, Object>());
        vars.putIfAbsent("runtime", new HashMap<String, Object>());
        vars.putIfAbsent("channel", new HashMap<String, Object>());
    }

    @SuppressWarnings("unchecked")
    public void mergeInputs(ExecutionContext context, Map<String, Object> flatParams) {
        ensureStructure(context);
        if (flatParams == null || flatParams.isEmpty()) {
            return;
        }

        Map<String, Object> inputs = (Map<String, Object>) context.getVariables().get("inputs");

        if (flatParams.containsKey("text")) {
            inputs.put("text", flatParams.get("text"));
        }
        if (flatParams.containsKey("files")) {
            inputs.put("files", flatParams.get("files"));
        }
        if (flatParams.containsKey("answer")) {
            inputs.put("answer", flatParams.get("answer"));
        }
        if (flatParams.containsKey("entities")) {
            mergeEntities(inputs, flatParams.get("entities"));
        }
        if (flatParams.containsKey("channel") && flatParams.get("channel") instanceof Map) {
            mergeChannel(context, (Map<String, Object>) flatParams.get("channel"));
        }

        Map<String, Object> entities = getOrCreateEntities(inputs);
        for (Map.Entry<String, Object> entry : flatParams.entrySet()) {
            String key = entry.getKey();
            if (!RESERVED_KEYS.contains(key)) {
                entities.put(key, entry.getValue());
            }
        }

        if (flatParams.containsKey("forceIntent")) {
            context.getVariables().put("forceIntent", flatParams.get("forceIntent"));
        }
    }

    @SuppressWarnings("unchecked")
    public void mergeChannel(ExecutionContext context, Map<String, Object> channelData) {
        ensureStructure(context);
        if (channelData == null || channelData.isEmpty()) {
            return;
        }
        Map<String, Object> channel = (Map<String, Object>) context.getVariables().get("channel");
        channel.putAll(channelData);
    }

    @SuppressWarnings("unchecked")
    private void mergeEntities(Map<String, Object> inputs, Object entitiesObj) {
        Map<String, Object> entities = getOrCreateEntities(inputs);
        if (entitiesObj instanceof Map) {
            entities.putAll((Map<String, Object>) entitiesObj);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOrCreateEntities(Map<String, Object> inputs) {
        Object existing = inputs.get("entities");
        if (existing instanceof Map) {
            return (Map<String, Object>) existing;
        }
        Map<String, Object> entities = new HashMap<>();
        inputs.put("entities", entities);
        return entities;
    }

    @SuppressWarnings("unchecked")
    public void writeStepOutput(ExecutionContext context, JourneyStep step, Object output) {
        writeStepField(context, step, "output", output);
    }

    public void storeOutput(ExecutionContext context, JourneyStep step, Object output) {
        writeStepOutput(context, step, output);
        context.addStepResult(step.getStepOrder(), output);
    }

    @SuppressWarnings("unchecked")
    public void writeStepField(ExecutionContext context, JourneyStep step, String field, Object value) {
        ensureStructure(context);
        Map<String, Object> steps = (Map<String, Object>) context.getVariables().get("steps");
        String orderKey = String.valueOf(step.getStepOrder());
        Map<String, Object> stepBucket = (Map<String, Object>) steps.computeIfAbsent(orderKey, k -> new HashMap<>());
        stepBucket.put(field, value);
    }

    /** Engine-internal key recording which step wrote each state entry. */
    private static final String STATE_PROVENANCE = "_stateWrites";

    /**
     * Writes state and records the writing step, so a JUMP backwards can roll
     * back exactly the entries produced by the steps it is about to replay.
     */
    @SuppressWarnings("unchecked")
    public void writeState(ExecutionContext context, JourneyStep step, String key, Object value) {
        ensureStructure(context);
        Map<String, Object> state = (Map<String, Object>) context.getVariables().get("state");
        state.put(key, value);
        stateProvenance(context).put(key, step.getStepOrder());
    }

    /** Removes state entries written by steps at or after {@code fromOrder}. */
    public void clearStateWrittenFrom(ExecutionContext context, int fromOrder) {
        ensureStructure(context);
        Map<String, Integer> provenance = stateProvenance(context);
        if (provenance.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) context.getVariables().get("state");
        provenance.entrySet().removeIf(entry -> {
            if (entry.getValue() == null || entry.getValue() < fromOrder) {
                return false;
            }
            state.remove(entry.getKey());
            return true;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> stateProvenance(ExecutionContext context) {
        Object existing = context.getInternal(STATE_PROVENANCE);
        if (existing instanceof Map) {
            return (Map<String, Integer>) existing;
        }
        Map<String, Integer> provenance = new HashMap<>();
        context.setInternal(STATE_PROVENANCE, provenance);
        return provenance;
    }

    public Object read(ExecutionContext context, String dotPath) {
        return VariablePath.resolve(context.getVariables(), dotPath);
    }

    public String resolveForTemplate(ExecutionContext context, String text) {
        return Placeholders.replace(text, context.getVariables());
    }

    @SuppressWarnings("unchecked")
    public void clearStepOutputsFromOrder(ExecutionContext context, int fromOrder) {
        ensureStructure(context);
        Map<String, Object> steps = (Map<String, Object>) context.getVariables().get("steps");
        steps.keySet().removeIf(k -> {
            try {
                return Integer.parseInt(k) >= fromOrder;
            } catch (NumberFormatException e) {
                return false;
            }
        });

        context.getStepResults().keySet().removeIf(order -> order >= fromOrder);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getInputs(ExecutionContext context) {
        ensureStructure(context);
        return (Map<String, Object>) context.getVariables().get("inputs");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getChannel(ExecutionContext context) {
        ensureStructure(context);
        return (Map<String, Object>) context.getVariables().get("channel");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getState(ExecutionContext context) {
        ensureStructure(context);
        return (Map<String, Object>) context.getVariables().get("state");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getRuntime(ExecutionContext context) {
        ensureStructure(context);
        return (Map<String, Object>) context.getVariables().get("runtime");
    }

    public void writeRuntime(ExecutionContext context, String key, Object value) {
        getRuntime(context).put(key, value);
    }
}
