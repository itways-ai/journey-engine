package com.itways.assistant.journey.engine.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
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
import com.itways.assistant.journey.model.JourneyDefinition;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.RunStatus;

/**
 * JRN-07: only a run that has ended carries a completion time. A WAITING run
 * resumes later, so history must not show it as completed.
 */
class JourneyEngineCompletedAtTest {

    private final List<RunHistoryEvent> events = new ArrayList<>();

    private static StepHandler handler(String type, StepResult result) {
        return new StepHandler() {
            @Override
            public String getType() {
                return type;
            }

            @Override
            public StepResult execute(JourneyStep step, ExecutionContext context) {
                return result;
            }
        };
    }

    private JourneyEngineImpl engine() {
        ObjectMapper objectMapper = new ObjectMapper();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        StepLocalizer localizer = new StepLocalizer(objectMapper, new LanguageDetector(),
                beans.getBeanProvider(TextTranslator.class), beans.getBeanProvider(StepTextPort.class));
        StepHandlerRegistry registry = new StepHandlerRegistry(List.of(
                handler("WAIT_HERE", StepResult.waiting("Waiting for the user", Map.of())),
                handler("FAIL_HERE", StepResult.error("boom")),
                handler("PASS_HERE", StepResult.success("ok"))));
        JourneyRunLifecyclePort recorder = events::add;
        return new JourneyEngineImpl(registry, new EngineUtils(objectMapper), new VariableContext(), objectMapper,
                List.of(recorder), new EngineMessages(), localizer);
    }

    private static JourneyDefinition journey(String actionType) {
        return JourneyDefinition.builder().id(7L).name("j").triggerIntent("j")
                .steps(List.of(JourneyStep.builder().stepOrder(1).stepName("only").actionType(actionType).build()))
                .build();
    }

    private RunHistoryEvent last(RunStatus status) {
        return events.stream().filter(e -> e.status() == status).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void aWaitingRunHasNoCompletionTime() {
        engine().start(journey("WAIT_HERE"), "acc-1", null, Map.of());

        assertThat(last(RunStatus.RUNNING).completedAt()).isNull();
        RunHistoryEvent waiting = last(RunStatus.WAITING);
        assertThat(waiting.completedAt()).isNull();
        assertThat(waiting.durationMs()).isNull();
    }

    @Test
    void aCompletedRunHasACompletionTime() {
        engine().start(journey("PASS_HERE"), "acc-1", null, Map.of());

        RunHistoryEvent completed = last(RunStatus.COMPLETED);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.durationMs()).isNotNull();
    }

    @Test
    void aFailedRunHasACompletionTime() {
        engine().start(journey("FAIL_HERE"), "acc-1", null, Map.of());

        assertThat(last(RunStatus.ERROR).completedAt()).isNotNull();
    }

    @Test
    void onlyCompletedAndErrorAreFinal() {
        assertThat(RunStatus.RUNNING.isFinal()).isFalse();
        assertThat(RunStatus.WAITING.isFinal()).isFalse();
        assertThat(RunStatus.COMPLETED.isFinal()).isTrue();
        assertThat(RunStatus.ERROR.isFinal()).isTrue();
    }
}
