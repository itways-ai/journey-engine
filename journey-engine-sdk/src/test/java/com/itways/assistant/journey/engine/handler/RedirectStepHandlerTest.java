package com.itways.assistant.journey.engine.handler;

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
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/** REDIRECT hands the client an http(s) URL through step metadata, which is what reaches the browser. */
class RedirectStepHandlerTest {

    private final RedirectStepHandler handler = new RedirectStepHandler(UTILS, VARIABLES, SCHEMAS);

    private static JourneyStep step(String url, String message) {
        return JourneyStep.builder().stepOrder(6).stepName("go").actionType("REDIRECT").actionTarget(url)
                .message(message).build();
    }

    @Test
    void theResolvedUrlTravelsInMetadataAndAsTheOutput() {
        ExecutionContext context = run(Map.of("orderId", "A-17", "name", "Sara"));

        StepResult result = handler.execute(
                step("https://shop.example.com/orders/{{inputs.entities.orderId}}", "Opening it, {{inputs.entities.name}}"),
                context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMetadata()).containsEntry(RedirectStepHandler.META_REDIRECT_URL,
                "https://shop.example.com/orders/A-17");
        assertThat(result.getActionTarget()).isEqualTo("https://shop.example.com/orders/A-17");
        assertThat(result.getMessage()).isEqualTo("Opening it, Sara");
        assertThat(output(context, 6)).isEqualTo("https://shop.example.com/orders/A-17");
    }

    @Test
    void withoutAMessageTheStepSaysNothing() {
        assertThat(handler.execute(step("http://example.com", null), run()).getMessage()).isNull();
    }

    @Test
    void aMissingUrlIsAnError() {
        for (String url : new String[] { null, "", "   " }) {
            StepResult result = handler.execute(step(url, null), run());

            assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
            assertThat(result.getMessage()).contains("actionTarget (URL) is required");
        }
    }

    /** Anything but http(s) — javascript:, data:, a relative path — never reaches the client. */
    @Test
    void aNonHttpUrlIsRefused() {
        for (String url : new String[] { "javascript:alert(1)", "data:text/html,hi", "/relative", "{{inputs.entities.x}}" }) {
            ExecutionContext context = run(Map.of("x", "ftp://files.example.com"));

            StepResult result = handler.execute(step(url, null), context);

            assertThat(result.getStatus()).as(url).isEqualTo(StepStatus.ERROR);
            assertThat(result.getMessage()).contains("must start with http:// or https://");
            assertThat(output(context, 6)).isNull();
        }
    }
}
