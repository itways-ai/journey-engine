package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SwitchStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "SWITCH";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.switchDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.switchSchema();
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        Object switchVal = engineUtils.evaluateExpression(step.getConditionExpression(), context.getVariables());
        variableContext.storeOutput(context, step, switchVal);
        return StepResult.success(switchVal);
    }
}
