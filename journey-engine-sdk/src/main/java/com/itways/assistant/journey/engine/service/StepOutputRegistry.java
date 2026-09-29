package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StepOutputRegistry {

    private final Map<String, StepHandler> handlersByType;

    public StepOutputRegistry(List<StepHandler> handlers) {
        this.handlersByType = new LinkedHashMap<>();
        for (StepHandler handler : handlers) {
            handlersByType.put(handler.getType(), handler);
        }
    }

    public Map<String, StepOutputSchema> getAllDefaultSchemas() {
        Map<String, StepOutputSchema> schemas = new LinkedHashMap<>();
        for (StepHandler handler : handlersByType.values()) {
            schemas.put(handler.getType(), handler.describeOutputs(null));
        }
        return schemas;
    }

    public StepOutputSchema describeOutputs(String type, JourneyStep step) {
        StepHandler handler = handlersByType.get(type);
        if (handler == null) {
            return StepOutputSchema.empty(type);
        }
        return handler.describeOutputs(step);
    }

    public List<StepDefinition> getCatalog() {
        return handlersByType.values().stream()
                .map(StepHandler::describe)
                .map(com.itways.assistant.journey.engine.util.ChannelSupport::annotate)
                .toList();
    }
}
