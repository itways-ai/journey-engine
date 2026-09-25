package com.itways.assistant.journey.engine.handler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
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
public class CodeScriptStepHandler implements StepHandler {

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final ObjectMapper objectMapper;

    /**
     * Hard bound on script work. The statement limit stops loops
     * deterministically ({@code while(true){}} counts statements, not time); the
     * wall-clock watchdog covers the single-statement pathologies a statement
     * count cannot see (catastrophic regex backtracking, giant string repeats).
     * Both exist because the engine runs scripts on the request thread — an
     * unbounded script used to hang the whole turn forever.
     */
    @org.springframework.beans.factory.annotation.Value("${nibras.journey.script.statement-limit:500000}")
    private long statementLimit = 500_000;

    @org.springframework.beans.factory.annotation.Value("${nibras.journey.script.timeout-seconds:10}")
    private long timeoutSeconds = 10;

    /** One daemon watchdog for all scripts; a timer entry per execution, not a thread. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "code-script-watchdog");
        t.setDaemon(true);
        return t;
    });

    @Override
    public String getType() {
        return "CODE_SCRIPT";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.codeScriptDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("CODE_SCRIPT", "Script Result");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        try {
            ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
            String code = config.getCode();
            if (code == null || code.isBlank()) {
                return StepResult.error("CODE_SCRIPT: script code is required");
            }

            // JSON round-trip so scripts see plain JS objects (steps['3'].output.status)
            // instead of Java Maps that do not support JS property access.
            Map<String, Object> variables = new HashMap<>(context.getVariables());
            String ctxJson = objectMapper.writeValueAsString(variables);

            Object resultValue;
            // HostAccess.NONE: the script gets its variables as parsed JSON and
            // nothing else — no host objects, no Java interop. ALL was never
            // needed (the only binding is a JSON string) and left the door ajar.
            try (Context js = Context.newBuilder("js")
                    .allowHostAccess(HostAccess.NONE)
                    .resourceLimits(ResourceLimits.newBuilder()
                            .statementLimit(statementLimit, null)
                            .build())
                    .option("engine.WarnInterpreterOnly", "false")
                    .allowExperimentalOptions(true)
                    .build()) {
                js.getBindings("js").putMember("__ctxJson", ctxJson);
                // Seed VariableContext roots as plain JS locals shared across evals.
                js.eval("js", """
                        var __ctx = JSON.parse(__ctxJson);
                        var inputs = __ctx.inputs;
                        var steps = __ctx.steps;
                        var state = __ctx.state;
                        var channel = __ctx.channel;
                        var runtime = __ctx.runtime;
                        """);
                ScheduledFuture<?> interrupter = WATCHDOG.schedule(() -> {
                    try {
                        js.interrupt(Duration.ofSeconds(1));
                    } catch (Exception ignored) {
                        // Interrupting a context that already finished is fine.
                    }
                }, timeoutSeconds, TimeUnit.SECONDS);
                try {
                    // Top-level script completion value (last expression) is returned —
                    // same semantics journey authors expect from ScriptEngine.eval.
                    Value result = js.eval("js", code);
                    resultValue = valueToJava(result);
                } finally {
                    interrupter.cancel(false);
                }
            }

            variableContext.storeOutput(context, step, resultValue);
            return StepResult.success(resultValue, step.getMessage());
        } catch (PolyglotException e) {
            if (e.isInterrupted() || e.isCancelled() || e.isResourceExhausted()) {
                log.error("CODE_SCRIPT step '{}' exceeded its execution limits (statements<={}, {}s)",
                        step.getStepName(), statementLimit, timeoutSeconds);
                return StepResult.error("CODE_SCRIPT exceeded its execution limits and was stopped");
            }
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            log.error("CODE_SCRIPT execution failed for step '{}': {}", step.getStepName(), detail, e);
            return StepResult.error("CODE_SCRIPT execution failed: " + detail);
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            log.error("CODE_SCRIPT execution failed for step '{}': {}", step.getStepName(), detail, e);
            return StepResult.error("CODE_SCRIPT execution failed: " + detail);
        }
    }

    private static Object valueToJava(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            if (value.fitsInInt()) {
                return value.asInt();
            }
            if (value.fitsInLong()) {
                return value.asLong();
            }
            return value.asDouble();
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.hasArrayElements()) {
            int size = (int) value.getArraySize();
            List<Object> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(valueToJava(value.getArrayElement(i)));
            }
            return list;
        }
        if (value.hasMembers()) {
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, valueToJava(value.getMember(key)));
            }
            return map;
        }
        return value.toString();
    }

}
