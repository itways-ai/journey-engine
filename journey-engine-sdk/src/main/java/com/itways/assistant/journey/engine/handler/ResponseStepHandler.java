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
public class ResponseStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "RESPONSE";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.responseDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.responseSchema(step);
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String resp = engineUtils.replacePlaceholders(step.getActionTarget(), context.getVariables());

        variableContext.storeOutput(context, step, resp);

        return StepResult.success(resp, resp);
    }
}
