package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.JourneyDefinition;
import java.util.Map;

public interface JourneyEngine {

    /**
     * Starts a new journey execution.
     */
    Map<String, Object> start(JourneyDefinition journey, String accountId, java.util.UUID assistantId,
            Map<String, Object> initialParams);

    /**
     * Starts a new journey execution, publishing each step as it completes.
     *
     * <p>
     * Same semantics as {@link #start}; the observer only adds visibility. Steps
     * of a nested {@code TRIGGER_JOURNEY} run arrive as a group when the parent's
     * trigger step finishes, because the child engine call produces them together.
     */
    Map<String, Object> start(JourneyDefinition journey, String accountId, java.util.UUID assistantId,
            Map<String, Object> initialParams, StepObserver observer);

    /**
     * Resumes an existing journey execution.
     */
    Map<String, Object> resume(JourneyDefinition journey, ExecutionContext context, Map<String, Object> inputParams);

    /**
     * Resumes an existing journey execution, publishing each step as it completes.
     */
    Map<String, Object> resume(JourneyDefinition journey, ExecutionContext context, Map<String, Object> inputParams,
            StepObserver observer);
}
