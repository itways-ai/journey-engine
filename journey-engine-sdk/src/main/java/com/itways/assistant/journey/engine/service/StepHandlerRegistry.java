package com.itways.assistant.journey.engine.service;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class StepHandlerRegistry {
    private final Map<String, StepHandler> handlers = new HashMap<>();

    public StepHandlerRegistry(List<StepHandler> handlerList) {
        handlerList.forEach(handler -> handlers.put(handler.getType().toUpperCase(), handler));
    }

    public StepHandler getHandler(String type) {
        return handlers.get(type.toUpperCase());
    }

    public void registerHandler(StepHandler handler) {
        handlers.put(handler.getType().toUpperCase(), handler);
    }
}
