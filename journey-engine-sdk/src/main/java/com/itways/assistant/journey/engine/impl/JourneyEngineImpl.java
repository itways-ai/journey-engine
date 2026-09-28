package com.itways.assistant.journey.engine.impl;

import com.itways.assistant.journey.model.RunStepLog;
import com.itways.assistant.journey.model.RunStatus;
import com.itways.assistant.journey.model.StepStatus;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyDefinition;
import com.itways.assistant.journey.model.JourneyStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.EndUserAuth;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.language.StepLocalizer;
import com.itways.assistant.journey.model.StepText;
import com.itways.assistant.journey.engine.language.LanguageParams;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.handler.TriggerJourneyStepHandler;
import com.itways.assistant.journey.engine.model.*;
import com.itways.assistant.journey.engine.service.JourneyEngine;
import com.itways.assistant.journey.engine.service.JourneyRunLifecyclePort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.StepObserver;
import com.itways.assistant.journey.engine.service.StepHandlerRegistry;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.JourneyStepGraph;
import com.itways.assistant.journey.engine.util.VariableDiagnostics;
import com.itways.assistant.journey.engine.util.VariablePath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
public class JourneyEngineImpl implements JourneyEngine {

    /**
     * Hard bounds on one turn's work. A backward JUMP rolls back the state
     * written by the steps it replays, so a journey author cannot build a loop
     * counter that survives its own loop — which means an author-written
     * CONDITION→JUMP loop with a bad exit condition is an infinite loop, and it
     * runs on the request thread. These caps turn "hangs forever" into a
     * localized ERROR. Sized far above any legitimate journey: the largest demo
     * journey executes ~30 steps per turn.
     */
    static final int MAX_JUMPS_PER_TURN = 50;
    static final int MAX_EXECUTED_STEPS_PER_TURN = 2000;

    private final StepHandlerRegistry handlerRegistry;
    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final ObjectMapper objectMapper;
    private final List<JourneyRunLifecyclePort> lifecyclePorts;
    private final EngineMessages engineMessages;
    private final StepLocalizer stepLocalizer;

    public JourneyEngineImpl(StepHandlerRegistry handlerRegistry, EngineUtils engineUtils,
                             VariableContext variableContext, ObjectMapper objectMapper,
                             List<JourneyRunLifecyclePort> lifecyclePorts, EngineMessages engineMessages,
                             StepLocalizer stepLocalizer) {
        this.handlerRegistry = handlerRegistry;
        this.engineUtils = engineUtils;
        this.variableContext = variableContext;
        this.objectMapper = objectMapper;
        this.lifecyclePorts = lifecyclePorts != null ? lifecyclePorts : List.of();
        this.engineMessages = engineMessages;
        this.stepLocalizer = stepLocalizer;
    }

    @Override
    public Map<String, Object> start(JourneyDefinition journey, String accountId, java.util.UUID assistantId,
            Map<String, Object> initialParams) {
        return start(journey, accountId, assistantId, initialParams, StepObserver.NOOP);
    }

    @Override
    public Map<String, Object> start(JourneyDefinition journey, String accountId, java.util.UUID assistantId,
            Map<String, Object> initialParams, StepObserver observer) {
        Map<String, Object> params = initialParams != null ? new HashMap<>(initialParams) : new HashMap<>();

        String executionId = UUID.randomUUID().toString();
        String parentExecutionId = stringOrNull(params.remove(TriggerJourneyStepHandler.PARENT_EXECUTION_ID));
        String rootExecutionId = stringOrNull(params.remove(TriggerJourneyStepHandler.ROOT_EXECUTION_ID));
        if (rootExecutionId == null || rootExecutionId.isBlank()) {
            rootExecutionId = executionId;
        }

        ExecutionContext context = ExecutionContext.builder()
                .executionId(executionId)
                .parentExecutionId(parentExecutionId)
                .rootExecutionId(rootExecutionId)
                .journeyId(journey.getId())
                .accountId(accountId)
                .assistantId(assistantId)
                .currentStepIndex(-1)
                .status(ExecutionStatus.RUNNING)
                .variables(new HashMap<>())
                .startedAt(new Date())
                .build();

        // The end user's bearer token arrives as a reserved start-param. Lift it into
        // engine internals before any step runs, so it never reaches run history,
        // the variable picker, CODE_SCRIPT or DATA_MAP's prompt. Before the inputs
        // are merged, not after: mergeInputs copies every param into
        // inputs.entities, and lifting afterwards left the raw token there.
        EndUserAuth.lift(context, params);

        if (isStructuredVariableMap(params)) {
            context.setVariables(shallowCopyVariables(params));
            variableContext.ensureStructure(context);
        } else {
            variableContext.mergeInputs(context, params);
        }

        // Likewise for the rehearsal flag: lifted before any step runs, so no
        // journey can read {{simulate}} and behave differently under test.
        com.itways.assistant.journey.engine.context.Simulation.lift(context, params);

        // And for what the channel can do (show a form, open a link): a fact
        // about the transport, never a variable a journey can branch on.
        com.itways.assistant.journey.engine.context.ChannelCapabilities.lift(context, params);

        // The conversation language arrives the same way, and must be on the
        // context before seedRuntime publishes {{runtime.language}} from it.
        LanguageParams.lift(context, params);

        // The trigger stack arrives as a start-param (the only channel into a child
        // run); lift it out of the author-visible variable map into engine internals.
        Object inheritedStack = context.getVariables().remove(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK);
        if (inheritedStack == null) {
            inheritedStack = params.get(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK);
        }
        if (inheritedStack instanceof List<?> list) {
            context.setInternal(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK, new ArrayList<>(list));
        } else if (journey.getTriggerIntent() != null && !journey.getTriggerIntent().isBlank()) {
            // Seed the call stack so TRIGGER_JOURNEY can detect cycles without
            // needing the parent Journey object.
            context.setInternal(TriggerJourneyStepHandler.TRIGGERED_JOURNEY_STACK,
                    new ArrayList<>(List.of(journey.getTriggerIntent())));
        }

        seedRuntime(journey, context);

        // Durable RUNNING row before any business step; failure aborts the run.
        // Durable RUNNING row before any business step — except in a rehearsal,
        // which must leave no trace: run history is what the journey's analytics
        // are computed from, and an author testing a flow twenty times would
        // otherwise bury the real traffic in it.
        if (!com.itways.assistant.journey.engine.context.Simulation.isActive(context)) {
            emitLifecycle(buildLifecycleEvent(journey, context, RunStatus.RUNNING, null, null));
        }

        return finalizeResult(journey, context, execute(journey, context, observer));
    }

    /**
     * Hands a finished step to the observer.
     *
     * <p>
     * Isolated because the observer belongs to a transport — typically an SSE
     * connection the client may already have dropped — and a broken pipe there
     * must not abort a journey that is otherwise running fine. The run is the
     * source of truth; streaming is only a view of it.
     */
    private void publish(StepObserver observer, Map<String, Object> view) {
        if (observer == null || observer == StepObserver.NOOP) {
            return;
        }
        try {
            observer.onStep(view);
        } catch (Exception e) {
            log.debug("Step observer failed; continuing the run", e);
        }
    }

    private boolean isStructuredVariableMap(Map<String, Object> params) {
        return params.get("inputs") instanceof Map && params.get("steps") instanceof Map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> shallowCopyVariables(Map<String, Object> source) {
        return (Map<String, Object>) deepCopyValue(source);
    }

    @SuppressWarnings("unchecked")
    private Object deepCopyValue(Object val) {
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> copy = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                copy.put(String.valueOf(e.getKey()), deepCopyValue(e.getValue()));
            }
            return copy;
        }
        if (val instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return val;
    }

    @Override
    public Map<String, Object> resume(JourneyDefinition journey, ExecutionContext context, Map<String, Object> inputParams) {
        return resume(journey, context, inputParams, StepObserver.NOOP);
    }

    @Override
    public Map<String, Object> resume(JourneyDefinition journey, ExecutionContext context, Map<String, Object> inputParams,
            StepObserver observer) {
        Map<String, Object> pending = inputParams != null ? new HashMap<>(inputParams) : new HashMap<>();
        // Each turn carries a freshly read token: hosts rotate them (Vikunja's access
        // JWT lives ~10 minutes), so a resumed run must adopt the new one rather than
        // keep the value captured when the journey started. Lifted before the merge
        // for the same reason as in start(): mergeInputs copies params into entities.
        EndUserAuth.lift(context, pending);
        variableContext.mergeInputs(context, pending);
        // A rehearsal stays a rehearsal across every turn: the flag is already
        // on the parked context, and this only catches a resume that re-sends it.
        com.itways.assistant.journey.engine.context.Simulation.lift(context, pending);
        // A run parked on a widget and resumed from a phone is on the phone now.
        com.itways.assistant.journey.engine.context.ChannelCapabilities.lift(context, pending);
        LanguageParams.lift(context, pending);
        context.setInternal(TriggerJourneyStepHandler.PENDING_RESUME_INPUT, pending);
        context.setStatus(ExecutionStatus.RUNNING);
        // Ensure lineage fields are present after deserialization.
        if (context.getRootExecutionId() == null || context.getRootExecutionId().isBlank()) {
            context.setRootExecutionId(context.getExecutionId());
        }
        seedRuntime(journey, context);
        Map<String, Object> result = execute(journey, context, observer);
        context.removeInternal(TriggerJourneyStepHandler.PENDING_RESUME_INPUT);
        return finalizeResult(journey, context, result);
    }

    /**
     * Publishes run identity into the {@code runtime} bucket so journey authors can
     * reference {@code {{runtime.executionId}}}, {@code {{runtime.accountId}}} and
     * friends. Previously {@code runtime} was created empty and never populated.
     */
    private void seedRuntime(JourneyDefinition journey, ExecutionContext context) {
        variableContext.writeRuntime(context, "executionId", context.getExecutionId());
        variableContext.writeRuntime(context, "parentExecutionId", context.getParentExecutionId());
        variableContext.writeRuntime(context, "rootExecutionId", context.getRootExecutionId());
        variableContext.writeRuntime(context, "accountId", context.getAccountId());
        variableContext.writeRuntime(context, "journeyId",
                context.getJourneyId() != null ? context.getJourneyId() : journey.getId());
        variableContext.writeRuntime(context, "journeyName", journey.getName());
        variableContext.writeRuntime(context, "triggerIntent", journey.getTriggerIntent());
        // Journey authors branch on these with a plain CONDITION or SWITCH step,
        // which is what makes a bilingual flow expressible without a new step type.
        variableContext.writeRuntime(context, "language", context.resolvedLanguage().code());
        variableContext.writeRuntime(context, "languageName", context.resolvedLanguage().englishName());
        variableContext.writeRuntime(context, "direction", context.resolvedLanguage().direction().toString());
        if (context.getStartedAt() != null) {
            variableContext.writeRuntime(context, "startedAt", context.getStartedAt().toInstant().toString());
        }
    }

    /**
     * This journey's translated text for the run's language, or an empty map.
     *
     * <p>
     * Read off the {@link JourneyDefinition} itself: the version payload carries the
     * translations captured at publish, so published traffic can never pick up
     * a draft retranslation, and a resume turn costs no extra lookup. A journey
     * without them (older definitions, tests) simply serves authored text.
     */
    private Map<Long, StepText> loadTranslations(JourneyDefinition journey, ExecutionContext context) {
        Map<String, Map<Long, StepText>> all = journey.getTranslations();
        if (all == null || all.isEmpty()) {
            return Map.of();
        }
        Map<Long, StepText> found = all.get(context.resolvedLanguage().code());
        return found != null ? found : Map.of();
    }

    /** Per-step runtime facts: refreshed clock and the step currently executing. */
    private void refreshStepRuntime(ExecutionContext context, JourneyStep step) {
        variableContext.writeRuntime(context, "now", Instant.now().toString());
        variableContext.writeRuntime(context, "stepOrder", step.getStepOrder());
        variableContext.writeRuntime(context, "stepName", step.getStepName());
    }

    private Map<String, Object> execute(JourneyDefinition journey, ExecutionContext context, StepObserver observer) {

        log.debug("START JOURNEY EXECUTION >> journeyId={} accountId={} executionId={}",
                journey.getId(), context.getAccountId(), context.getExecutionId());
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> stepResults = new ArrayList<>();

        List<JourneyStep> steps = journey.getSteps();
        if (steps == null || steps.isEmpty()) {
            log.warn("Journey '{}' has no steps defined.", journey.getTriggerIntent());
            result.put("status", "FINISHED");
            result.put("message", engineMessages.get(context.resolvedLanguage(), "run.noSteps"));
            result.put("context", context);
            result.put("stepResults", stepResults);
            return result;
        }

        List<JourneyStep> sortedSteps;
        try {
            sortedSteps = JourneyStepGraph.sortSteps(steps);
        } catch (IllegalStateException e) {
            log.error("Journey '{}' has a cyclic step graph - aborting execution: {}",
                    journey.getTriggerIntent(), e.getMessage());
            context.setStatus(ExecutionStatus.ERROR);
            result.put("status", "ERROR");
            result.put("message", e.getMessage());
            result.put("context", context);
            result.put("stepResults", stepResults);
            return result;
        }

        int i = 0;
        int jumps = 0;
        int executedSteps = 0;
        // One lookup for the whole run rather than one per step: a run touches most
        // of its steps, and a per-step call would put a round trip inside the loop.
        // Re-read on every turn, not cached on the context, because a resumed run
        // may have switched language since the turn that parked it.
        Map<Long, StepText> translations = loadTranslations(journey, context);
        // Authors refer to steps by stable key; everything below runs by order.
        // Within this version the mapping is fixed, so each step's key
        // references are resolved just before it runs.
        com.itways.assistant.journey.engine.util.StepKeyResolver keys =
                com.itways.assistant.journey.engine.util.StepKeyResolver.of(steps);

        while (i < sortedSteps.size() && context.getStatus() == ExecutionStatus.RUNNING) {
            JourneyStep authored = sortedSteps.get(i);
            // Everything below this line works on the localized copy, so no handler
            // needs to know translations exist.
            JourneyStep step = keys.resolve(stepLocalizer.localize(authored, translations, context.getAccountId(),
                    context.resolvedLanguage()));
            int stepOrder = step.getStepOrder();
            int startIndex = context.getCurrentStepIndex();

            // Skip already completed steps (Resumption logic)
            if (stepOrder <= startIndex) {
                i++;
                continue;
            }

            if (!isEligible(step, context, sortedSteps)) {
                i++;
                continue;
            }

            StepHandler handler = handlerRegistry.getHandler(step.getActionType());
            if (handler == null) {
                stepResults.add(createErrorResult(step, "No handler found for type: " + step.getActionType()));
                if (step.isContinueOnError()) {
                    context.addStepResult(stepOrder, "FAILED");
                    context.setCurrentStepIndex(stepOrder);
                } else {
                    context.setStatus(ExecutionStatus.ERROR);
                    break;
                }
                i++;
                continue;
            }

            refreshStepRuntime(context, step);

            executedSteps++;
            if (executedSteps > MAX_EXECUTED_STEPS_PER_TURN) {
                log.error("Journey '{}' exceeded {} step executions in one turn - stopping run {}",
                        journey.getTriggerIntent(), MAX_EXECUTED_STEPS_PER_TURN, context.getExecutionId());
                context.setStatus(ExecutionStatus.ERROR);
                result.put("status", "ERROR");
                result.put("message", engineMessages.get(context.resolvedLanguage(), "run.loopLimit"));
                result.put("context", context);
                result.put("stepResults", stepResults);
                return result;
            }

            long stepStart = System.currentTimeMillis();
            StepResult stepResult;
            List<String> unresolvedVariables;
            VariableDiagnostics.open();
            try {
                stepResult = handler.execute(step, context);
            } catch (Exception e) {
                log.error("Unhandled exception in handler for type: {}", step.getActionType(), e);
                stepResult = StepResult.error("Internal Handler Error: " + e.getMessage());
            } finally {
                unresolvedVariables = VariableDiagnostics.close();
            }
            long stepEnd = System.currentTimeMillis();

            if (!unresolvedVariables.isEmpty()) {
                log.warn("Unresolved variables in step {} '{}' ({}) of journey {}: {}",
                        stepOrder, step.getStepName(), step.getActionType(), journey.getId(),
                        unresolvedVariables);
            }

            Map<String, Object> metadata = stepResult.getMetadata();
            appendNestedStepResults(stepResults, metadata);
            boolean skipSelfView = metadata != null
                    && Boolean.TRUE.equals(metadata.get(TriggerJourneyStepHandler.META_SKIP_SELF_VIEW));

            Map<String, Object> viewResult = new HashMap<>();
            viewResult.put("type", step.getActionType());
            viewResult.put("id", step.getId());
            viewResult.put("stepName", step.getStepName());
            viewResult.put("clientVisible", step.isClientVisible());
            viewResult.put("status", stepResult.getStatus().name());
            viewResult.put("startedAt", new Date(stepStart));
            viewResult.put("completedAt", new Date(stepEnd));
            viewResult.put("durationMs", stepEnd - stepStart);
            if (!unresolvedVariables.isEmpty()) {
                viewResult.put("unresolvedVariables", unresolvedVariables);
            }

            if (stepResult.getStatus() == StepStatus.SUCCESS) {
                viewResult.put("message", stepResult.getMessage());
                if (stepResult.getData() != null) {
                    viewResult.put("data", stepResult.getData());
                    try {
                        viewResult.put("outputPayload", objectMapper.writeValueAsString(stepResult.getData()));
                    } catch (Exception ignored) {
                    }
                }
                mergeStepMetadata(viewResult, metadata);
                stepResults.add(viewResult);
                publish(observer, viewResult);
                context.setCurrentStepIndex(stepOrder);
                // Handlers store output via VariableContext.storeOutput; ensure stepResults map is set
                if (context.getStepResults().get(stepOrder) == null && stepResult.getData() != null) {
                    context.addStepResult(stepOrder, stepResult.getData());
                }
                i++;
            } else if (stepResult.getStatus() == StepStatus.WAITING) {
                if (!skipSelfView) {
                    viewResult.put("message", stepResult.getMessage());
                    mergeStepMetadata(viewResult, metadata);
                    stepResults.add(viewResult);
                publish(observer, viewResult);
                }
                result.put("status", "WAITING");
                result.put("stepResults", stepResults);
                result.put("context", context);
                if (stepResult.getMessage() != null) {
                    result.put("message", stepResult.getMessage());
                }
                return result;
            } else if (stepResult.getStatus() == StepStatus.JUMP) {
                jumps++;
                if (jumps > MAX_JUMPS_PER_TURN) {
                    log.error("Journey '{}' exceeded {} jumps in one turn - stopping run {}",
                            journey.getTriggerIntent(), MAX_JUMPS_PER_TURN, context.getExecutionId());
                    context.setStatus(ExecutionStatus.ERROR);
                    result.put("status", "ERROR");
                    result.put("message", engineMessages.get(context.resolvedLanguage(), "run.loopLimit"));
                    result.put("context", context);
                    result.put("stepResults", stepResults);
                    return result;
                }
                int targetOrder = (Integer) stepResult.getMetadata().get("targetOrder");
                variableContext.clearStepOutputsFromOrder(context, targetOrder);
                // Jumping back must roll back session state written by the steps
                // being replayed, or an INCREMENT/APPEND compounds on every pass.
                variableContext.clearStateWrittenFrom(context, targetOrder);
                context.setCurrentStepIndex(targetOrder - 1);
                i = 0;
                continue;
            } else {
                viewResult.put("message", stepResult.userFacingMessage());
                if (stepResult.getUserMessage() != null) {
                    // Keep the diagnostic reachable for run history and support,
                    // just not as the thing the customer reads.
                    viewResult.put("detail", stepResult.getMessage());
                }
                mergeStepMetadata(viewResult, metadata);
                stepResults.add(viewResult);
                publish(observer, viewResult);
                if (step.isContinueOnError()) {
                    context.addStepResult(stepOrder, "FAILED");
                    context.setCurrentStepIndex(stepOrder);
                    variableContext.writeStepOutput(context, step, "FAILED");
                } else {
                    context.setStatus(ExecutionStatus.ERROR);
                    break;
                }
                i++;
            }
        }

        if (context.getStatus() == ExecutionStatus.RUNNING) {
            context.setStatus(ExecutionStatus.COMPLETED);
            result.put("status", "FINISHED");
        } else if (context.getStatus() == ExecutionStatus.COMPLETED) {
            // A handler ended the run deliberately rather than the loop running
            // out of steps — HANDOFF does this, because a conversation given to
            // a person must not continue executing the script. Without this
            // branch the result carried no status at all, so no lifecycle event
            // was emitted and the run never appeared finished in history.
            result.put("status", "FINISHED");
        } else if (context.getStatus() == ExecutionStatus.ERROR) {
            result.put("status", "ERROR");
        }

        result.put("stepResults", stepResults);
        result.put("context", context);

        // Final message extraction.
        //
        // A halted run takes the *failing* step's message, not the last
        // successful one. Reading only SUCCESS left every halt silent — reject a
        // HUMAN_APPROVAL and the assistant said nothing at all, which reads as a
        // crash rather than as the gate doing its job. The stopping reason is the
        // most useful thing to say, so it wins when the run ended in ERROR.
        String finalStatus = (String) result.get("status");
        if ("ERROR".equals(finalStatus)) {
            for (int idx = stepResults.size() - 1; idx >= 0; idx--) {
                Map<String, Object> r = stepResults.get(idx);
                if (!StepStatus.ERROR.name().equals(r.get("status")) || r.get("message") == null) {
                    continue;
                }
                // A step that supplied a user-facing message put it in "message" and
                // its diagnostic in "detail" -- HUMAN_APPROVAL's rejection being the
                // case that matters, where the stopping reason genuinely is the most
                // useful thing to say.
                //
                // Everything else failed on configuration, and its message reads like
                // "TEMPLATE_RENDER: '{{id}}' is not a template id": exactly what an
                // operator needs in run history and exactly what a customer must
                // never be shown. It is also half identifier and so untranslatable.
                // The step log keeps it either way; the reply gets a sentence.
                result.put("message", r.containsKey("detail")
                        ? r.get("message")
                        : engineMessages.get(context.resolvedLanguage(), "run.error.generic"));
                break;
            }
        }
        if (result.get("message") == null) {
            for (int idx = stepResults.size() - 1; idx >= 0; idx--) {
                Map<String, Object> r = stepResults.get(idx);
                if (StepStatus.SUCCESS.name().equals(r.get("status")) && r.containsKey("message") && r.get("message") != null) {
                    result.put("message", r.get("message"));
                    break;
                }
            }
        }

        return result;
    }

    private Map<String, Object> finalizeResult(JourneyDefinition journey, ExecutionContext context, Map<String, Object> result) {
        putIdentity(result, context);
        if (com.itways.assistant.journey.engine.context.Simulation.isActive(context)) {
            // A rehearsal writes no terminal row either, matching the suppressed
            // RUNNING one. The console gets its trace from this result; history
            // is for real conversations.
            result.put(com.itways.assistant.journey.engine.context.Simulation.META_SIMULATED, true);
            return result;
        }
        String status = (String) result.get("status");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepLogs = result.get("stepResults") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        String message = result.get("message") != null ? String.valueOf(result.get("message")) : null;

        if ("WAITING".equals(status)) {
            emitLifecycleSoft(buildLifecycleEvent(journey, context, RunStatus.WAITING,
                    stepLogs, message));
        } else if ("ERROR".equals(status)) {
            emitLifecycleSoft(buildLifecycleEvent(journey, context, RunStatus.ERROR,
                    stepLogs, message));
        } else if ("FINISHED".equals(status) || "COMPLETED".equals(status)) {
            emitLifecycleSoft(buildLifecycleEvent(journey, context, RunStatus.COMPLETED,
                    stepLogs, message));
        }
        return result;
    }

    private void putIdentity(Map<String, Object> result, ExecutionContext context) {
        result.put("executionId", context.getExecutionId());
        result.put("parentExecutionId", context.getParentExecutionId());
        result.put("rootExecutionId", context.getRootExecutionId());
    }

    private JourneyRunLifecycleEvent buildLifecycleEvent(JourneyDefinition journey, ExecutionContext context,
                                                         RunStatus status, List<Map<String, Object>> stepLogs,
                                                         String message) {
        Date completedAt = null;
        Long durationMs = null;
        // Only a run that has ended gets a completion time (JRN-07). A WAITING run
        // is paused on a user, an approver or a timer and resumes later; stamping
        // it made history show a "completed" date for a run that had not finished.
        if (status.isFinal()) {
            completedAt = new Date();
            if (context.getStartedAt() != null) {
                durationMs = completedAt.getTime() - context.getStartedAt().getTime();
            }
        }

        Map<String, Object> stepResultsMap = new HashMap<>();
        if (context.getStepResults() != null) {
            for (Map.Entry<Integer, Object> e : context.getStepResults().entrySet()) {
                stepResultsMap.put(String.valueOf(e.getKey()), e.getValue());
            }
        }

        // Extracted entities land under inputs.entities; the flat root `userId`
        // this used to read is gone, which silently nulled userId on every run.
        Object userIdValue = VariablePath.resolve(context.getVariables(), "inputs.entities.userId");
        if (userIdValue == null) {
            userIdValue = VariablePath.resolve(context.getVariables(), "channel.user.id");
        }
        String userId = userIdValue != null ? String.valueOf(userIdValue) : null;

        return JourneyRunLifecycleEvent.builder()
                .executionId(context.getExecutionId())
                .parentExecutionId(context.getParentExecutionId())
                .rootExecutionId(context.getRootExecutionId())
                .journeyId(context.getJourneyId() != null ? context.getJourneyId() : journey.getId())
                .accountId(context.getAccountId())
                .triggerIntent(journey.getTriggerIntent())
                .status(status)
                .startedAt(context.getStartedAt())
                .completedAt(completedAt)
                .durationMs(durationMs)
                .userId(userId)
                .message(message)
                .stepLogs(toStepLogs(stepLogs))
                .variables(context.getVariables() != null ? new HashMap<>(context.getVariables()) : Map.of())
                .stepResults(stepResultsMap)
                .build();
    }

    private void emitLifecycle(JourneyRunLifecycleEvent event) {
        for (JourneyRunLifecyclePort port : lifecyclePorts) {
            port.onLifecycleEvent(event);
        }
    }

    private void emitLifecycleSoft(JourneyRunLifecycleEvent event) {
        for (JourneyRunLifecyclePort port : lifecyclePorts) {
            try {
                port.onLifecycleEvent(event);
            } catch (Exception e) {
                log.error("Lifecycle update failed for executionId={} status={}",
                        event.getExecutionId(), event.getStatus(), e);
            }
        }
    }

    private static String stringOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw);
        return s.isBlank() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private void appendNestedStepResults(List<Map<String, Object>> stepResults, Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        Object nested = metadata.get(TriggerJourneyStepHandler.META_NESTED_STEP_RESULTS);
        if (nested instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    stepResults.add((Map<String, Object>) map);
                }
            }
        }
    }

    private boolean isEligible(JourneyStep step, ExecutionContext context, List<JourneyStep> allSteps) {
        List<Integer> inbound = JourneyStepGraph.resolveInboundParents(step);
        if (inbound.isEmpty()) {
            return true;
        }

        // "Has the parent run" is whether it left a result, not whether that
        // result is non-null: a SWITCH on a missing value, an API_CALL answering
        // 204 or a RESPONSE with no text ran and produced null, and reading that
        // as "never ran" skipped every step under it, DEFAULT branch included.
        if (JourneyStepGraph.isRejoinStep(step)) {
            for (Integer parent : inbound) {
                if (context.getStepResults().containsKey(parent)) {
                    return true;
                }
            }
            return false;
        }

        Integer parentOrder = inbound.get(0);
        if (!context.getStepResults().containsKey(parentOrder)) {
            return false;
        }
        Object parentResult = context.getStepResults().get(parentOrder);

        String requiredBranch = step.getBranchName();

        if (requiredBranch == null) {
            return true;
        }

        // CONDITION: boolean true/false routing (raw Boolean or Map.result)
        if (parentResult instanceof Boolean) {
            boolean boolResult = (Boolean) parentResult;
            return (requiredBranch.equalsIgnoreCase("true") && boolResult)
                    || (requiredBranch.equalsIgnoreCase("false") && !boolResult);
        }
        if (parentResult instanceof Map<?, ?> parentMap) {
            Object resultKey = parentMap.get("result");
            if (resultKey instanceof Boolean boolResult) {
                return (requiredBranch.equalsIgnoreCase("true") && boolResult)
                        || (requiredBranch.equalsIgnoreCase("false") && !boolResult);
            }
        }

        // SWITCH (and other value-based parents): named case or DEFAULT fallback
        String parentValStr = resolveSwitchValue(parentResult);
        if ("DEFAULT".equalsIgnoreCase(requiredBranch)) {
            return !hasMatchingNamedCase(parentOrder, parentValStr, allSteps);
        }
        return parentValStr != null && requiredBranch.equalsIgnoreCase(parentValStr);
    }

    /**
     * Extracts the switch/case value from a parent step result.
     * SWITCH stores {@code { "value": ... }}; scalars are stringified as-is.
     */
    private String resolveSwitchValue(Object parentResult) {
        if (parentResult instanceof Map<?, ?> parentMap && parentMap.containsKey("value")) {
            Object valueKey = parentMap.get("value");
            return valueKey != null ? String.valueOf(valueKey) : null;
        }
        return parentResult != null ? String.valueOf(parentResult) : null;
    }

    /**
     * Returns true when a sibling under {@code parentOrder} has a non-DEFAULT
     * branchName that matches {@code switchValue} (case-insensitive).
     */
    private boolean hasMatchingNamedCase(Integer parentOrder, String switchValue, List<JourneyStep> allSteps) {
        if (switchValue == null || allSteps == null) {
            return false;
        }
        for (JourneyStep sibling : allSteps) {
            if (JourneyStepGraph.isRejoinStep(sibling)) {
                continue;
            }
            List<Integer> siblingParents = JourneyStepGraph.resolveInboundParents(sibling);
            if (siblingParents.isEmpty() || !parentOrder.equals(siblingParents.get(0))) {
                continue;
            }
            String caseName = sibling.getBranchName();
            if (caseName == null || "DEFAULT".equalsIgnoreCase(caseName)) {
                continue;
            }
            if (caseName.equalsIgnoreCase(switchValue)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merges handler metadata into the client-facing step view without overwriting
     * core fields already set on {@code viewResult} (e.g. action {@code type}).
     * Engine-reserved nested-journey keys are excluded.
     */
    private void mergeStepMetadata(Map<String, Object> viewResult, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (TriggerJourneyStepHandler.META_NESTED_STEP_RESULTS.equals(key)
                    || TriggerJourneyStepHandler.META_SKIP_SELF_VIEW.equals(key)) {
                continue;
            }
            if (!viewResult.containsKey(key)) {
                viewResult.put(key, entry.getValue());
            }
        }
    }

    private Map<String, Object> createErrorResult(JourneyStep step, String message) {
        Map<String, Object> res = new HashMap<>();
        res.put("type", step.getActionType());
        res.put("id", step.getId());
        res.put("stepName", step.getStepName());
        res.put("status", StepStatus.ERROR.name());
        res.put("message", message);
        return res;
    }

    /**
     * The step views this turn produced, as run history stores them. The views
     * themselves stay maps: they also go to the chat client, with handler
     * metadata merged in that history has no use for.
     */
    private static List<RunStepLog> toStepLogs(List<Map<String, Object>> views) {
        if (views == null) {
            return List.of();
        }
        List<RunStepLog> logs = new java.util.ArrayList<>(views.size());
        for (Map<String, Object> view : views) {
            Object status = view.get("status");
            logs.add(new RunStepLog(
                    view.get("id") instanceof Number n ? n.longValue() : null,
                    stringOrNull(view.get("type")),
                    stringOrNull(view.get("stepName")),
                    status != null ? StepStatus.valueOf(status.toString()) : null,
                    stringOrNull(view.get("message")),
                    stringOrNull(view.get("detail")),
                    stringOrNull(view.get("outputPayload")),
                    view.get("durationMs") instanceof Number n ? n.longValue() : null,
                    view.get("startedAt") instanceof Date d ? d.toInstant() : null,
                    view.get("completedAt") instanceof Date d ? d.toInstant() : null));
        }
        return logs;
    }

}