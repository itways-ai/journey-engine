package com.itways.assistant.journey.engine.handler;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class JumpHandler implements StepHandler {

    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "JUMP";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.jumpDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return StepOutputSchema.empty("JUMP");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            int targetStepOrder = Integer.parseInt(step.getActionTarget());
            String message = step.getMessage() != null ? step.getMessage() : "Jumping to step " + targetStepOrder;
            return StepResult.jump(targetStepOrder, message);
        } catch (NumberFormatException e) {
            return StepResult.error("Invalid JUMP target: " + step.getActionTarget());
        }
    }
}
