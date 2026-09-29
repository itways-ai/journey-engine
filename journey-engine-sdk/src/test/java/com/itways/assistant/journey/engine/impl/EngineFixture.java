package com.itways.assistant.journey.engine.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.handler.ConditionStepHandler;
import com.itways.assistant.journey.engine.handler.HandoffStepHandler;
import com.itways.assistant.journey.engine.handler.JumpHandler;
import com.itways.assistant.journey.engine.handler.ResponseStepHandler;
import com.itways.assistant.journey.engine.handler.StateStoreStepHandler;
import com.itways.assistant.journey.engine.handler.SwitchStepHandler;
import com.itways.assistant.journey.engine.handler.UserInputStepHandler;
import com.itways.assistant.journey.engine.language.DecisionWords;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.language.LanguageDetector;
import com.itways.assistant.journey.engine.language.StepLocalizer;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.RunHistoryEvent;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.JourneyRunLifecyclePort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.StepHandlerRegistry;
import com.itways.assistant.journey.engine.service.StepTextPort;
import com.itways.assistant.journey.engine.service.TextTranslator;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.JourneyDefinition;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.RunStatus;

/**
 * The real engine with the real control-flow handlers (CONDITION, SWITCH, JUMP,
 * RESPONSE, STATE_STORE, USER_INPUT, HANDOFF), plus stub step types a test adds
 * for effects it wants to observe or failures it wants to cause. Lifecycle
 * events are recorded instead of persisted.
 */
final class EngineFixture {

    final ObjectMapper json = new ObjectMapper();
    final EngineUtils utils = new EngineUtils(json);
    final VariableContext variables = new VariableContext();
    final StepOutputSchemaHelper schemas = new StepOutputSchemaHelper(json);
    final EngineMessages messages = new EngineMessages();
    final List<RunHistoryEvent> events = new ArrayList<>();
    /** Step names, in the order the RECORD stub ran them. */
    final List<String> executed = new ArrayList<>();
    final StepHandlerRegistry registry;
    final JourneyEngineImpl engine;

    EngineFixture() {
        DecisionWords words = new DecisionWords(messages);
        words.load();
        registry = new StepHandlerRegistry(new ArrayList<>(List.of(
                new ConditionStepHandler(utils, variables, schemas),
                new SwitchStepHandler(utils, variables, schemas),
                new JumpHandler(schemas),
                new ResponseStepHandler(utils, variables, schemas),
                new StateStoreStepHandler(utils, variables, schemas),
                new UserInputStepHandler(utils, variables, schemas, messages, words),
                new HandoffStepHandler(utils, variables, schemas, messages),
                stub("RECORD", (step, context) -> {
                    executed.add(step.getStepName());
                    return StepResult.success(step.getStepName(), step.getStepName() + " done");
                }))));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        StepLocalizer localizer = new StepLocalizer(json, new LanguageDetector(),
                beans.getBeanProvider(TextTranslator.class), beans.getBeanProvider(StepTextPort.class));
        JourneyRunLifecyclePort recorder = events::add;
        engine = new JourneyEngineImpl(registry, utils, variables, json, List.of(recorder), messages, localizer);
    }

    static StepHandler stub(String type, BiFunction<JourneyStep, ExecutionContext, StepResult> behaviour) {
        return new StepHandler() {
            @Override
            public String getType() {
                return type;
            }

            @Override
            public StepResult execute(JourneyStep step, ExecutionContext context) {
                return behaviour.apply(step, context);
            }
        };
    }

    EngineFixture with(StepHandler handler) {
        registry.registerHandler(handler);
        return this;
    }

    static JourneyStep.JourneyStepBuilder step(int order, String type) {
        return JourneyStep.builder().id((long) order).stepOrder(order).stepName("s" + order).actionType(type);
    }

    static JourneyDefinition journey(String intent, JourneyStep.JourneyStepBuilder... steps) {
        List<JourneyStep> built = new ArrayList<>();
        for (JourneyStep.JourneyStepBuilder step : steps) {
            built.add(step.build());
        }
        return JourneyDefinition.builder().id(7L).versionId(70L).name(intent.toLowerCase()).triggerIntent(intent)
                .steps(built).build();
    }

    Map<String, Object> start(JourneyDefinition journey, Map<String, Object> params) {
        return engine.start(journey, "acc-1", null, params);
    }

    static ExecutionContext context(Map<String, Object> result) {
        return (ExecutionContext) result.get("context");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> views(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("stepResults");
    }

    /** "TYPE:status" for each step view the turn produced. */
    static List<String> trace(Map<String, Object> result) {
        return views(result).stream().map(v -> v.get("type") + ":" + v.get("status")).toList();
    }

    List<RunStatus> statuses() {
        return events.stream().map(RunHistoryEvent::status).toList();
    }

    RunHistoryEvent last() {
        return events.get(events.size() - 1);
    }
}
