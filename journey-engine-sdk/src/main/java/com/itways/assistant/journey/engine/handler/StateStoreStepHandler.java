package com.itways.assistant.journey.engine.handler;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class StateStoreStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "STATE_STORE";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.stateStoreDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.stateStoreSchema(step);
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
            String variableName = config.getVariable();
            String operation = config.getOperation() != null ? config.getOperation().toUpperCase() : "SET";
            // resolveSourceValue keeps the original type when the source is a lone
            // placeholder, so `{{steps.2.output.score}}` stores 8 rather than "8".
            Object sourceValue = engineUtils.resolveSourceValue(
                    config.getSource() != null ? config.getSource() : "", context.getVariables());

            if (variableName == null || variableName.isEmpty()) {
                return StepResult.error("State variable name is missing");
            }

            Object finalValue = sourceValue;
            switch (operation) {
                case "APPEND" -> {
                    Object existing = variableContext.getState(context).get(variableName);
                    List<Object> list = (existing instanceof List) ? new ArrayList<>((List<Object>) existing)
                            : new ArrayList<>();
                    list.add(sourceValue);
                    finalValue = list;
                }
                case "INCREMENT" -> {
                    Object current = variableContext.getState(context).get(variableName);
                    int val = (current instanceof Number) ? ((Number) current).intValue() : 0;
                    finalValue = val + toIncrement(sourceValue);
                }
                default -> finalValue = sourceValue;
            }

            variableContext.writeState(context, step, variableName, finalValue);
            variableContext.storeOutput(context, step, finalValue);
            return StepResult.success(finalValue, step.getMessage());
        } catch (Exception e) {
            log.error("State Store failed", e);
            return StepResult.error("State Persistence Failure: " + e.getMessage());
        }
    }

    /** Step size for INCREMENT; a blank or non-numeric source counts as 1. */
    private static int toIncrement(Object sourceValue) {
        if (sourceValue instanceof Number number) {
            return number.intValue();
        }
        if (sourceValue == null) {
            return 1;
        }
        try {
            return Integer.parseInt(String.valueOf(sourceValue).trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

}
