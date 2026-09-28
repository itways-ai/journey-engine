package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.MESSAGES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/** HANDOFF ends the run as COMPLETED and publishes where the conversation should go. */
class HandoffStepHandlerTest {

    private final HandoffStepHandler handler = new HandoffStepHandler(UTILS, VARIABLES, SCHEMAS, MESSAGES);

    private static JourneyStep step(String apiConfig, String message) {
        return JourneyStep.builder().stepOrder(4).stepName("escalate").actionType("HANDOFF").apiConfig(apiConfig)
                .message(message).build();
    }

    @Test
    void theRunEndsAndTheHostGetsTheQueueAndNote() {
        ExecutionContext context = run(Map.of("orderId", "A-17"));

        StepResult result = handler.execute(step(
                "{\"queue\":\"  billing \",\"note\":\"Order {{inputs.entities.orderId}} disputed\",\"timeoutMinutes\":30}",
                null), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(context.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("handoff", true).containsEntry("queue", "billing")
                .containsEntry("note", "Order A-17 disputed").containsEntry("subStatus", "HANDED_OFF")
                .containsEntry("timeoutMinutes", 30);
        assertThat(output(context, 4)).isEqualTo(Map.of("handedOff", true, "queue", "billing",
                "note", "Order A-17 disputed"));
        assertThat(result.getMessage()).isEqualTo(
                "Let me bring in a colleague who can help with this. Someone from our team will continue this conversation with you.");
    }

    @Test
    void theAuthorsMessageInterpolatesAndBlankSettingsAreNull() {
        ExecutionContext context = run(Map.of("name", "Sara"));

        StepResult result = handler.execute(step("{\"queue\":\"   \",\"note\":\"\"}", "One moment, {{inputs.entities.name}}"),
                context);

        assertThat(result.getMessage()).isEqualTo("One moment, Sara");
        assertThat(result.getMetadata()).containsEntry("queue", null).containsEntry("note", null)
                .doesNotContainKey("timeoutMinutes");
    }
}
