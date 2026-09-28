package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/** JUMP only names its target; the engine does the moving (see JourneyEngineFlowTest). */
class JumpHandlerTest {

    private final JumpHandler handler = new JumpHandler(SCHEMAS);

    private static JourneyStep step(String target, String message) {
        return JourneyStep.builder().stepOrder(4).actionType("JUMP").actionTarget(target).message(message).build();
    }

    @Test
    void aNumericTargetBecomesAJumpWithTheTargetOrder() {
        StepResult result = handler.execute(step("2", null), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.JUMP);
        assertThat(result.getMetadata()).containsEntry("targetOrder", 2);
        assertThat(result.getData()).isEqualTo(2);
        assertThat(result.getMessage()).isEqualTo("Jumping to step 2");
    }

    @Test
    void theAuthorsMessageIsKept() {
        assertThat(handler.execute(step("7", "Back to the start"), run()).getMessage()).isEqualTo("Back to the start");
    }

    @Test
    void aTargetThatIsNotAnOrderIsAnError() {
        for (String target : new String[] { null, "", "confirm_step", "2.5" }) {
            StepResult result = handler.execute(step(target, null), run());

            assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
            assertThat(result.getMessage()).startsWith("Invalid JUMP target");
        }
    }
}
