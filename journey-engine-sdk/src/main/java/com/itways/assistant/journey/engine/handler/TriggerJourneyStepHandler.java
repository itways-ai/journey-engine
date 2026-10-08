package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.engine.context.EndUserAuth;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.engine.language.LanguageParams;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.JourneyEngine;
import com.itways.assistant.journey.engine.service.JourneyLookupPort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyDefinition;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Runs another journey inline by trigger intent, then lets the parent continue.
 * Nested WAITING state is stored on the parent context under {@link #ACTIVE_TRIGGERED_JOURNEY}.
 * Child runs receive their own execution UUID with {@code parentExecutionId}/{@code rootExecutionId}
 * linkage; durable lifecycle persistence is handled by the engine lifecycle port.
 */
@Slf4j
@Component
public class TriggerJourneyStepHandler implements StepHandler {

    public static final String ACTIVE_TRIGGERED_JOURNEY = "_activeTriggeredJourney";
    public static final String TRIGGERED_JOURNEY_STACK = "_triggeredJourneyStack";
    public static final String PENDING_RESUME_INPUT = "_pendingResumeInput";
    /** Internal start-param: immediate parent run UUID for a triggered child. */
    public static final String PARENT_EXECUTION_ID = "_parentExecutionId";
    /** Internal start-param: top-level root run UUID for a triggered child. */
    public static final String ROOT_EXECUTION_ID = "_rootExecutionId";
    public static final String META_NESTED_STEP_RESULTS = "nestedStepResults";
    public static final String META_SKIP_SELF_VIEW = "skipSelfView";

    private static final int MAX_TRIGGER_DEPTH = 5;

    private final JourneyLookupPort journeyLookupPort;
    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final JourneyEngine journeyEngine;
    private final com.itways.assistant.journey.engine.language.EngineMessages engineMessages;

    public TriggerJourneyStepHandler(JourneyLookupPort journeyLookupPort, EngineUtils engineUtils,
                                     VariableContext variableContext, StepOutputSchemaHelper schemaHelper,
                                     @Lazy JourneyEngine journeyEngine,
                                     com.itways.assistant.journey.engine.language.EngineMessages engineMessages) {
        this.journeyLookupPort = journeyLookupPort;
        this.engineUtils = engineUtils;
        this.variableContext = variableContext;
        this.schemaHelper = schemaHelper;
        this.journeyEngine = journeyEngine;
        this.engineMessages = engineMessages;
    }

    @Override
    public String getType() {
        return "TRIGGER_JOURNEY";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.triggerJourneyDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("TRIGGER_JOURNEY", "Triggered Journey Result");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        Object activeRaw = context.getInternal(ACTIVE_TRIGGERED_JOURNEY);
        Map<String, Object> childResult;
        String intent;
        JourneyDefinition childJourney;

        if (activeRaw instanceof Map<?, ?> activeMap) {
            intent = String.valueOf(activeMap.get("intent"));
            ExecutionContext childContext = deserializeChildContext(activeMap.get("childContext"));
            if (childContext == null) {
                context.removeInternal(ACTIVE_TRIGGERED_JOURNEY);
                return StepResult.error("TRIGGER_JOURNEY: corrupt nested execution state");
            }

            // Resume against the version the child was parked on, never the
            // current definition — the whole point of pinning: a publish between
            // the child's question and the user's answer must not remap the
            // answer onto renumbered steps. No intent fallback; a pruned version
            // means the run is gracefully dead, not silently rewired.
            Object pinnedVersion = activeMap.get("versionId");
            childJourney = pinnedVersion instanceof Number pinned
                    ? journeyLookupPort.findByVersionId(context.getAccountId(), pinned.longValue())
                    : null;
            if (childJourney == null) {
                context.removeInternal(ACTIVE_TRIGGERED_JOURNEY);
                return StepResult.error(
                        "TRIGGER_JOURNEY: pinned version " + pinnedVersion + " gone for intent '" + intent + "'",
                        engineMessages.get(context.resolvedLanguage(), "run.versionGone"));
            }

            Map<String, Object> pending = context.getInternal(PENDING_RESUME_INPUT) instanceof Map<?, ?> p
                    ? new HashMap<>((Map<String, Object>) p)
                    : new HashMap<>();
            // The parent lifted this turn's token out of `pending` before storing
            // it, and hosts rotate tokens between turns, so hand the child the
            // parent's current one rather than letting it keep the value it
            // captured when it started.
            passUserToken(context, pending);
            log.info("Resuming triggered journey '{}' executionId={}", intent, childContext.getExecutionId());
            childResult = journeyEngine.resume(childJourney, childContext, pending);
            // This turn's answer was the reply to the child's question. Left in the
            // parent's inputs, the parent's next USER_INPUT took it as its own
            // answer and never asked.
            variableContext.getInputs(context).remove("answer");
        } else {
            intent = engineUtils.replacePlaceholders(step.getActionTarget(), context.getVariables());
            if (intent == null || intent.isBlank()) {
                return StepResult.error("TRIGGER_JOURNEY: actionTarget (journey intent) is required");
            }
            intent = intent.trim();

            List<String> stack = resolveTriggerStack(context);
            if (stack.contains(intent)) {
                return StepResult.error(
                        "TRIGGER_JOURNEY: cannot trigger '" + intent + "' — already on the call stack " + stack);
            }
            if (stack.size() >= MAX_TRIGGER_DEPTH) {
                return StepResult.error(
                        "TRIGGER_JOURNEY: max nesting depth (" + MAX_TRIGGER_DEPTH + ") exceeded");
            }

            childJourney = journeyLookupPort.findByTriggerIntent(context.getAccountId(), context.getAssistantId(), intent);
            if (childJourney == null) {
                return StepResult.error("TRIGGER_JOURNEY: journey not found for intent '" + intent + "'");
            }

            Map<String, Object> childParams = copyVariablesForChild(context.getVariables());
            List<String> childStack = new ArrayList<>(stack);
            childStack.add(intent);
            // Start-params are the only channel into a child run, so the stack and
            // lineage ids ride along here and are lifted into `internal` by start().
            childParams.put(TRIGGERED_JOURNEY_STACK, childStack);
            // Link child run identity to this parent.
            childParams.put(PARENT_EXECUTION_ID, context.getExecutionId());
            String rootId = context.getRootExecutionId() != null ? context.getRootExecutionId() : context.getExecutionId();
            childParams.put(ROOT_EXECUTION_ID, rootId);
            // Without this the child runs anonymously: the token lives in the
            // parent's `internal`, which copyVariablesForChild deliberately does
            // not copy, so every `{{auth.userToken}}` in the child resolved to
            // nothing and its first host call came back 401.
            passUserToken(context, childParams);
            // Without this the child re-resolves from scratch and answers in the
            // account default while its parent is mid-conversation in Arabic.
            LanguageParams.inherit(childParams, context);
            // Same channel, same limits: a child asked to show a form on a phone
            // call would otherwise refuse the caller's spoken answer.
            com.itways.assistant.journey.engine.context.ChannelCapabilities.inherit(childParams, context);

            log.info("Starting triggered journey '{}' parentExecutionId={} rootExecutionId={}",
                    intent, context.getExecutionId(), rootId);
            // The child inherits the parent's product scope, so a shared journey
            // triggered inside one product cannot reach another product's flows.
            childResult = journeyEngine.start(childJourney, context.getAccountId(), context.getAssistantId(),
                    childParams);
        }

        String childStatus = (String) childResult.get("status");
        List<Map<String, Object>> childSteps = childResult.get("stepResults") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        ExecutionContext childCtx = childResult.get("context") instanceof ExecutionContext c ? c : null;

        if ("WAITING".equals(childStatus)) {
            Map<String, Object> active = new HashMap<>();
            active.put("intent", intent);
            // The pin. Every runtime-loaded journey carries the published
            // version it came from; a versionless one (possible only in embeddings
            // that bypass publishing) parks unresumably and dies as versionGone.
            if (childJourney.getVersionId() != null) {
                active.put("versionId", childJourney.getVersionId());
            }
            if (childCtx != null && childCtx.getJourneyId() != null) {
                active.put("journeyId", childCtx.getJourneyId());
            }
            if (childCtx != null && childCtx.getExecutionId() != null) {
                active.put("executionId", childCtx.getExecutionId());
            }
            active.put("childContext", serializeChildContext(childCtx));
            context.setInternal(ACTIVE_TRIGGERED_JOURNEY, active);
            context.setStatus(ExecutionStatus.WAITING_FOR_INPUT);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(META_NESTED_STEP_RESULTS, childSteps);
            metadata.put(META_SKIP_SELF_VIEW, true);

            String message = childResult.get("message") != null
                    ? String.valueOf(childResult.get("message"))
                    : null;
            return StepResult.waiting(message, metadata);
        }

        context.removeInternal(ACTIVE_TRIGGERED_JOURNEY);

        if ("ERROR".equals(childStatus)) {
            String errMsg = childResult.get("message") != null
                    ? String.valueOf(childResult.get("message"))
                    : "Triggered journey '" + intent + "' failed";
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(META_NESTED_STEP_RESULTS, childSteps);
            return StepResult.builder()
                    .status(StepStatus.ERROR)
                    .message(errMsg)
                    .metadata(metadata)
                    .build();
        }

        // FINISHED — this step's output is whatever the child journey last produced.
        // Previously read the flat `lastStep` variable, which only REDIRECT ever
        // wrote, so every other child journey silently yielded just the intent name.
        Object outputData = lastChildOutput(childCtx);
        if (outputData == null) {
            outputData = intent;
        }

        String message = step.getMessage() != null
                ? engineUtils.replacePlaceholders(step.getMessage(), context.getVariables())
                : "Triggered journey: " + intent;

        variableContext.storeOutput(context, step, outputData);
        context.setStatus(ExecutionStatus.RUNNING);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(META_NESTED_STEP_RESULTS, childSteps);

        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(outputData)
                .message(message)
                .actionTarget(intent)
                .metadata(metadata)
                .build();
    }

    /**
     * Hands the end user's token down to a child run.
     *
     * <p>
     * Start-params are the only channel into a child, and the token cannot ride
     * the variable map it inherits — {@link EndUserAuth} exists precisely to
     * keep it out of anything a journey author or the run history can see. So
     * it travels as the same reserved param the channel layer uses, and the
     * child's own {@code start}/{@code resume} lifts it straight back into
     * internals before its first step executes.
     */
    private static void passUserToken(ExecutionContext parent, Map<String, Object> childParams) {
        String token = EndUserAuth.token(parent);
        if (token != null) {
            childParams.put(EndUserAuth.PARAM_USER_TOKEN, token);
        }
    }

    private List<String> resolveTriggerStack(ExecutionContext context) {
        List<String> stack = new ArrayList<>();
        Object raw = context.getInternal(TRIGGERED_JOURNEY_STACK);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    stack.add(String.valueOf(item));
                }
            }
        }
        return stack;
    }

    /** The output of the highest-ordered step the child journey completed. */
    private static Object lastChildOutput(ExecutionContext childCtx) {
        if (childCtx == null || childCtx.getStepResults() == null || childCtx.getStepResults().isEmpty()) {
            return null;
        }
        return childCtx.getStepResults().entrySet().stream()
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * Builds an isolated variable map for a triggered child journey: fresh
     * {@code steps}/{@code state}/{@code runtime}, and independent copies of
     * {@code channel}/{@code inputs} (without {@code answer}) so child writes
     * cannot overwrite parent step buckets.
     */
    private Map<String, Object> copyVariablesForChild(Map<String, Object> parentVars) {
        Map<String, Object> copy = new HashMap<>();
        copy.put("steps", new HashMap<String, Object>());
        copy.put("state", new HashMap<String, Object>());
        copy.put("runtime", new HashMap<String, Object>());

        if (parentVars == null) {
            copy.put("inputs", new HashMap<String, Object>());
            copy.put("channel", new HashMap<String, Object>());
            return copy;
        }

        copy.put("channel", copyMapOneLevel(parentVars.get("channel")));
        Map<String, Object> inputs = copyMapOneLevel(parentVars.get("inputs"));
        inputs.remove("answer");
        copy.put("inputs", inputs);

        for (Map.Entry<String, Object> e : parentVars.entrySet()) {
            String key = e.getKey();
            if (ACTIVE_TRIGGERED_JOURNEY.equals(key)
                    || PENDING_RESUME_INPUT.equals(key)
                    || TRIGGERED_JOURNEY_STACK.equals(key)
                    || "steps".equals(key)
                    || "state".equals(key)
                    || "runtime".equals(key)
                    || "inputs".equals(key)
                    || "channel".equals(key)) {
                continue;
            }
            copy.put(key, e.getValue());
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMapOneLevel(Object raw) {
        Map<String, Object> out = new HashMap<>();
        if (!(raw instanceof Map<?, ?> source)) {
            return out;
        }
        for (Map.Entry<?, ?> e : source.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            Object val = e.getValue();
            if (val instanceof Map<?, ?> nested) {
                out.put(String.valueOf(e.getKey()), new HashMap<>((Map<String, Object>) nested));
            } else if (val instanceof List<?> list) {
                out.put(String.valueOf(e.getKey()), new ArrayList<>(list));
            } else {
                out.put(String.valueOf(e.getKey()), val);
            }
        }
        return out;
    }

    private Map<String, Object> serializeChildContext(ExecutionContext childCtx) {
        if (childCtx == null) {
            return Map.of();
        }
        Map<String, Object> map = new HashMap<>();
        map.put("journeyId", childCtx.getJourneyId());
        map.put("accountId", childCtx.getAccountId());
        map.put("executionId", childCtx.getExecutionId());
        map.put("parentExecutionId", childCtx.getParentExecutionId());
        map.put("rootExecutionId", childCtx.getRootExecutionId());
        map.put("currentStepIndex", childCtx.getCurrentStepIndex());
        map.put("status", childCtx.getStatus() != null ? childCtx.getStatus().name() : ExecutionStatus.WAITING_FOR_INPUT.name());
        map.put("variables", childCtx.getVariables() != null ? new HashMap<>(childCtx.getVariables()) : new HashMap<>());
        map.put("stepResults", childCtx.getStepResults() != null ? new HashMap<>(childCtx.getStepResults()) : new HashMap<>());
        // A paused grandchild lives in the child's internal map; drop it and the
        // nesting chain cannot be resumed.
        map.put("internal", childCtx.getInternal() != null ? new HashMap<>(childCtx.getInternal()) : new HashMap<>());
        // Field-by-field, so anything not named here is lost when a nested run
        // parks. A paused child that came back speaking the account default was
        // exactly that bug for the language.
        map.put("language", childCtx.resolvedLanguage().code());
        map.put("startedAt", childCtx.getStartedAt());
        return map;
    }

    @SuppressWarnings("unchecked")
    private ExecutionContext deserializeChildContext(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            Map<String, Object> variables = map.get("variables") instanceof Map<?, ?> v
                    ? new HashMap<>((Map<String, Object>) v)
                    : new HashMap<>();

            Map<String, Object> internal = map.get("internal") instanceof Map<?, ?> i
                    ? new HashMap<>((Map<String, Object>) i)
                    : new HashMap<>();

            Map<Integer, Object> stepResults = new HashMap<>();
            Object rawResults = map.get("stepResults");
            if (rawResults instanceof Map<?, ?> rm) {
                for (Map.Entry<?, ?> entry : rm.entrySet()) {
                    stepResults.put(Integer.valueOf(String.valueOf(entry.getKey())), entry.getValue());
                }
            }

            Long journeyId = map.get("journeyId") != null ? Long.valueOf(String.valueOf(map.get("journeyId"))) : null;
            String statusName = map.get("status") != null ? String.valueOf(map.get("status")) : ExecutionStatus.WAITING_FOR_INPUT.name();
            int currentStepIndex = -1;
            if (map.get("currentStepIndex") instanceof Number n) {
                currentStepIndex = n.intValue();
            } else if (map.get("currentStepIndex") != null) {
                currentStepIndex = Integer.parseInt(String.valueOf(map.get("currentStepIndex")));
            }

            Date startedAt = null;
            if (map.get("startedAt") instanceof Date d) {
                startedAt = d;
            } else if (map.get("startedAt") instanceof Number n) {
                startedAt = new Date(n.longValue());
            }

            String executionId = map.get("executionId") != null ? String.valueOf(map.get("executionId")) : null;
            String parentExecutionId = map.get("parentExecutionId") != null ? String.valueOf(map.get("parentExecutionId")) : null;
            String rootExecutionId = map.get("rootExecutionId") != null ? String.valueOf(map.get("rootExecutionId")) : executionId;

            ConversationLanguage language = ConversationLanguage.parse(
                    map.get("language") != null ? String.valueOf(map.get("language")) : null);

            return ExecutionContext.builder()
                    .executionId(executionId)
                    .parentExecutionId(parentExecutionId)
                    .rootExecutionId(rootExecutionId)
                    .journeyId(journeyId)
                    .accountId(map.get("accountId") != null ? String.valueOf(map.get("accountId")) : null)
                    .currentStepIndex(currentStepIndex)
                    .status(ExecutionStatus.valueOf(statusName))
                    .variables(variables)
                    .stepResults(stepResults)
                    .internal(internal)
                    .startedAt(startedAt)
                    .language(language)
                    .build();
        } catch (Exception e) {
            log.error("Failed to deserialize nested journey context", e);
            return null;
        }
    }
}
