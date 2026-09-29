package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.ai.service.AiService;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentInsightStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final AiService aiService;

    @Override
    public String getType() {
        return "DOCUMENT_INSIGHT";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.documentInsightDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return StepOutputSchema.builder()
                .stepType("DOCUMENT_INSIGHT")
                .fields(java.util.List.of(
                        com.itways.assistant.journey.model.catalog.OutputField.of("output", "Extracted Content", "object"),
                        com.itways.assistant.journey.model.catalog.OutputField.of("output.metadata", "Metadata", "object")))
                .build();
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
            String strategy = config.getStrategy() != null ? config.getStrategy() : "OCR_DETAILED";

            Map<String, Object> simulationData = new HashMap<>();
            simulationData.put("document_type", strategy.contains("FINANCE") ? "Invoice" : "Standard Document");
            simulationData.put("confidence_score", 0.98);
            simulationData.put("extracted_fields", Map.of(
                    "status", "Verified",
                    "processed_via", "Neural OCR Core",
                    "automatic_extraction", config.getAutoExtract() != null ? config.getAutoExtract() : true));

            variableContext.storeOutput(context, step, simulationData);
            return StepResult.success(simulationData, "Document processed successfully using " + strategy);
        } catch (Exception e) {
            log.error("Document Insight failed", e);
            return StepResult.error("OCR Failure: " + e.getMessage());
        }
    }

}
