package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;

public interface StepHandler {
    /**
     * Returns the step type this handler supports (e.g., "CONDITION", "API_CALL").
     */
    String getType();

    /**
     * Executes the specific logic for the given step.
     */
    StepResult execute(JourneyStep step, ExecutionContext context);

    default StepDefinition describe() {
        return StepDefinition.of(getType());
    }

    default StepOutputSchema describeOutputs(JourneyStep step) {
        return StepOutputSchema.empty(getType());
    }
}
