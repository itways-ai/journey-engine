package com.itways.assistant.journey.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.model.TemplateRenderResult;
import com.itways.assistant.journey.engine.service.TemplateRenderBusyException;
import com.itways.assistant.journey.engine.service.TemplateRenderPort;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/**
 * A published TEMPLATE_RENDER step renders the template version it was published
 * with (TPL-05), and a render the template service turns away as busy is asked
 * once more after a short pause (SPC-10).
 */
class TemplateRenderHandlerTest {

    private static final TemplateRenderResult RENDERED = new TemplateRenderResult("<p>Hi Sarah</p>", "html",
            List.of(), null);

    /** One call the handler made to the port. */
    private record Call(String accountId, long templateId, Integer version, Map<String, Object> model, long atNanos) {
    }

    /** Answers each call with the next scripted outcome: a result, or an exception to throw. */
    private static final class ScriptedPort implements TemplateRenderPort {
        final List<Call> calls = new ArrayList<>();
        private final List<Object> outcomes;

        ScriptedPort(Object... outcomes) {
            this.outcomes = new ArrayList<>(List.of(outcomes));
        }

        @Override
        public TemplateRenderResult render(String accountId, long templateId, Integer version,
                Map<String, Object> model) {
            calls.add(new Call(accountId, templateId, version, model, System.nanoTime()));
            Object next = outcomes.size() > 1 ? outcomes.remove(0) : outcomes.get(0);
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (TemplateRenderResult) next;
        }
    }

    private static TemplateRenderHandler handler(TemplateRenderPort port) {
        ObjectMapper json = new ObjectMapper();
        return new TemplateRenderHandler(new VariableContext(), new StepOutputSchemaHelper(json),
                new EngineUtils(json), port);
    }

    private static JourneyStep step(String apiConfig) {
        return JourneyStep.builder().stepOrder(2).actionType("TEMPLATE_RENDER").actionTarget("42")
                .apiConfig(apiConfig).build();
    }

    private static ExecutionContext run() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("name", "Sarah");
        Map<String, Object> variables = new HashMap<>();
        variables.put("inputs", inputs);
        return ExecutionContext.builder().accountId("acc-1").variables(variables).build();
    }

    private static TemplateRenderBusyException busy() {
        return new TemplateRenderBusyException("The template service is busy; try again shortly", null);
    }

    // ── TPL-05: the pinned version reaches the port ───────────────────────

    @Test
    void aPublishedStepRendersThePinnedTemplateVersion() {
        ScriptedPort port = new ScriptedPort(RENDERED);

        StepResult result = handler(port).execute(
                step("{\"templateVersion\":3,\"bindings\":{\"name\":\"{{inputs.name}}\"}}"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(port.calls).singleElement().satisfies(call -> {
            assertThat(call.accountId()).isEqualTo("acc-1");
            assertThat(call.templateId()).isEqualTo(42L);
            assertThat(call.version()).isEqualTo(3);
            assertThat(call.model()).containsEntry("name", "Sarah");
        });
    }

    /** A version published before pinning existed has no templateVersion: render the current one. */
    @Test
    void aStepPublishedBeforePinningRendersTheCurrentVersion() {
        ScriptedPort port = new ScriptedPort(RENDERED);

        StepResult result = handler(port).execute(step("{\"bindings\":{\"name\":\"{{inputs.name}}\"}}"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(port.calls).singleElement().satisfies(call -> assertThat(call.version()).isNull());
    }

    // ── SPC-10: one retry when the template service is busy ──────────────

    @Test
    void aBusyRenderIsAskedOnceMoreAfterAPauseAndThenSucceeds() {
        ScriptedPort port = new ScriptedPort(busy(), RENDERED);

        StepResult result = handler(port).execute(step("{\"templateVersion\":3}"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getData()).isEqualTo(Map.of("renderedContent", "<p>Hi Sarah</p>"));
        assertThat(port.calls).hasSize(2);
        assertThat(port.calls).extracting(Call::version).containsExactly(3, 3);
        long pauseMillis = (port.calls.get(1).atNanos() - port.calls.get(0).atNanos()) / 1_000_000;
        assertThat(pauseMillis).isGreaterThanOrEqualTo(TemplateRenderHandler.BUSY_RETRY_PAUSE.toMillis());
    }

    @Test
    void aRenderStillBusyAfterTheRetryFailsTheStepWithoutAThirdTry() {
        ScriptedPort port = new ScriptedPort(busy(), busy(), RENDERED);

        StepResult result = handler(port).execute(step("{\"templateVersion\":3}"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo(
                "Template Rendering Failed: The template service is busy; try again shortly");
        assertThat(port.calls).hasSize(2);
    }

    /** A rejected render (4xx) would fail the same way again, so it is not retried. */
    @Test
    void aRejectedRenderIsNotRetried() {
        ScriptedPort port = new ScriptedPort(new IllegalStateException("[404 Not Found] template 42"), RENDERED);

        StepResult result = handler(port).execute(step("{\"templateVersion\":3}"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(port.calls).hasSize(1);
    }

    /** A broken template comes back as a result, not a busy answer: not retried either. */
    @Test
    void aTemplateThatFailsToRenderIsNotRetried() {
        ScriptedPort port = new ScriptedPort(
                new TemplateRenderResult(null, "html", List.of(), "Syntax error on line 3"), RENDERED);

        StepResult result = handler(port).execute(step("{\"templateVersion\":3}"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).contains("Syntax error on line 3");
        assertThat(port.calls).hasSize(1);
    }
}
