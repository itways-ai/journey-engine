package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DOCUMENT_INSIGHT, as it stands: a placeholder that returns a fixed result
 * without reading any document (the AiService it is given is never called).
 * These tests pin that behaviour so a real implementation replaces it knowingly.
 */
class DocumentInsightStepHandlerTest {

    private final DocumentInsightStepHandler handler = new DocumentInsightStepHandler(UTILS, VARIABLES, SCHEMAS, null);

    private static JourneyStep step(String apiConfig) {
        return JourneyStep.builder().stepOrder(2).actionType("DOCUMENT_INSIGHT").apiConfig(apiConfig).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void itReturnsAFixedResultShapedByTheStrategy() {
        ExecutionContext context = run();

        StepResult result = handler.execute(step("{\"strategy\":\"FINANCE_INVOICE\",\"autoExtract\":false}"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMessage()).isEqualTo("Document processed successfully using FINANCE_INVOICE");
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertThat(data).containsEntry("document_type", "Invoice").containsEntry("confidence_score", 0.98);
        assertThat((Map<String, Object>) data.get("extracted_fields")).containsEntry("automatic_extraction", false);
        assertThat(output(context, 2)).isEqualTo(data);
    }

    @Test
    void theDefaultStrategyIsDetailedOcr() {
        StepResult result = handler.execute(step(null), run());

        assertThat(result.getMessage()).endsWith("OCR_DETAILED");
        assertThat(((Map<?, ?>) result.getData()).get("document_type")).isEqualTo("Standard Document");
    }
}
