package com.itways.assistant.journey.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.engine.handler.ApiCallStepHandler;
import com.itways.assistant.journey.engine.handler.CodeScriptStepHandler;
import com.itways.assistant.journey.engine.handler.DataMapStepHandler;
import com.itways.assistant.journey.engine.handler.KnowledgeRetrievalStepHandler;
import com.itways.assistant.journey.engine.handler.UserInputStepHandler;
import com.itways.assistant.journey.engine.util.EgressGuard;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * Pins every setting the engine reads, with its default (1.0.19: the keys moved
 * from the legacy {@code nibras.} prefix to {@code journey.*}). A host sets these
 * keys; renaming one silently drops the host's value, so a change here is a
 * change the host has to make too.
 */
class EngineSettingsTest {

    @Test
    void theEngineReadsTheseSettingsAndNoOthers() {
        List<String> placeholders = new ArrayList<>();
        for (Class<?> type : List.of(ApiCallStepHandler.class, CodeScriptStepHandler.class, DataMapStepHandler.class,
                KnowledgeRetrievalStepHandler.class, UserInputStepHandler.class, EgressGuard.class)) {
            valuesOf(type).forEach(placeholders::add);
        }

        assertThat(new TreeSet<>(placeholders)).containsExactly(
                "${journey.api-call.allowed-hosts:}",
                "${journey.api-call.block-private-networks:true}",
                "${journey.api-call.connect-timeout-ms:5000}",
                "${journey.api-call.read-timeout-ms:30000}",
                "${journey.data-map.context-budget-chars:8000}",
                "${journey.knowledge.synthesis.enabled:true}",
                "${journey.knowledge.synthesis.max-chunks:3}",
                "${journey.script.statement-limit:500000}",
                "${journey.script.timeout-seconds:10}",
                "${journey.user-input.max-attempts:3}");
    }

    private static Stream<String> valuesOf(Class<?> type) {
        List<AnnotatedElement> elements = new ArrayList<>(List.of(type.getDeclaredFields()));
        List<Executable> executables = new ArrayList<>(List.of(type.getDeclaredConstructors()));
        executables.addAll(List.of(type.getDeclaredMethods()));
        for (Executable executable : executables) {
            elements.add(executable);
            for (Parameter parameter : executable.getParameters()) {
                elements.add(parameter);
            }
        }
        return elements.stream().map(e -> e.getAnnotation(Value.class)).filter(v -> v != null).map(Value::value);
    }
}
