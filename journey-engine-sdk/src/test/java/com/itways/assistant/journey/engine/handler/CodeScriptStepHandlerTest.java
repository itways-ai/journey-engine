package com.itways.assistant.journey.engine.handler;

import static com.itways.assistant.journey.engine.handler.HandlerFixtures.JSON;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.SCHEMAS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.UTILS;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.VARIABLES;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.json;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.output;
import static com.itways.assistant.journey.engine.handler.HandlerFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CODE_SCRIPT runs author JavaScript in a GraalVM sandbox over a JSON copy of
 * the variables: no host access, bounded statements, and the last expression's
 * value becomes the step output.
 */
class CodeScriptStepHandlerTest {

    private final CodeScriptStepHandler handler = new CodeScriptStepHandler(UTILS, VARIABLES, SCHEMAS, JSON);

    private static JourneyStep step(String code) {
        return JourneyStep.builder().stepOrder(3).stepName("script").actionType("CODE_SCRIPT")
                .apiConfig(json(Map.of("code", code))).message("done").build();
    }

    @Test
    void theScriptReadsTheVariablesAndItsLastValueIsTheOutput() {
        ExecutionContext context = run(Map.of("price", 20, "qty", 3));
        VARIABLES.writeStepOutput(context, JourneyStep.builder().stepOrder(2).build(), Map.of("status", "OK"));

        StepResult result = handler.execute(step(
                "({ total: inputs.entities.price * inputs.entities.qty, ok: steps['2'].output.status === 'OK',"
                        + " tags: ['a', 'b'] })"),
                context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.getMessage()).isEqualTo("done");
        assertThat(result.getData()).isEqualTo(Map.of("total", 60, "ok", true, "tags", List.of("a", "b")));
        assertThat(output(context, 3)).isEqualTo(result.getData());
    }

    @Test
    void scalarsComeBackAsJavaValues() {
        assertThat(handler.execute(step("1.5 * 2"), run()).getData()).isEqualTo(3);
        assertThat(handler.execute(step("2.5 + 0"), run()).getData()).isEqualTo(2.5);
        assertThat(handler.execute(step("'a' + 'b'"), run()).getData()).isEqualTo("ab");
        assertThat(handler.execute(step("null"), run()).getData()).isNull();
    }

    /** The script sees a copy: whatever it assigns never reaches the run's variables. */
    @Test
    void theScriptCannotWriteBackIntoTheRun() {
        ExecutionContext context = run(Map.of("name", "Sara"));

        handler.execute(step("inputs.entities.name = 'Mallory'; state.hacked = true; 1"), context);

        assertThat(VARIABLES.getInputs(context).get("entities")).isEqualTo(Map.of("name", "Sara"));
        assertThat(VARIABLES.getState(context)).isEmpty();
    }

    @Test
    void anEmptyScriptIsAnError() {
        StepResult result = handler.execute(step("  "), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("CODE_SCRIPT: script code is required");
    }

    @Test
    void aThrowingScriptIsAnErrorWithTheReason() {
        StepResult result = handler.execute(step("throw new Error('bad input')"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).startsWith("CODE_SCRIPT execution failed").contains("bad input");
    }

    /** HostAccess.NONE: no Java classes from inside the sandbox. */
    @Test
    void theScriptCannotReachJava() {
        StepResult result = handler.execute(step("Java.type('java.lang.System').exit(1)"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
    }

    /** An endless loop hits the statement limit instead of hanging the turn. */
    @Test
    void anEndlessLoopIsStopped() {
        StepResult result = handler.execute(step("while (true) {}"), run());

        assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(result.getMessage()).isEqualTo("CODE_SCRIPT exceeded its execution limits and was stopped");
    }
}
