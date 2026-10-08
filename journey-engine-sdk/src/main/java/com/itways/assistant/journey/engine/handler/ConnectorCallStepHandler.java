package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.connector.CallOptions;
import com.itways.assistant.journey.connector.CallResult;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.connector.ConnectorTransport;
import com.itways.assistant.journey.connector.ConnectorTransports;
import com.itways.assistant.journey.connector.OutputMasker;
import com.itways.assistant.journey.connector.SecretScrubber;
import com.itways.assistant.journey.engine.context.Simulation;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.language.EngineMessages;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.ConnectorNotResolvableException;
import com.itways.assistant.journey.engine.service.ConnectorPort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.ConnectorCircuitBreakers;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import com.itways.assistant.journey.model.connector.ConnectorDefaults;
import com.itways.assistant.journey.model.connector.ConnectorDescriptor;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import com.itways.assistant.journey.model.step.ConnectorCallConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * CONNECTOR_CALL (1.1.0): calls one operation of a configured connector.
 *
 * <p>
 * Where API_CALL takes a URL, headers and a body from the step, this step names
 * a connector and an operation and maps inputs; everything else — the base
 * URL, the hosts it may dial, the credentials and how they are applied — comes
 * from the connector, resolved through {@link ConnectorPort} at run time
 * and kept in memory for the call only. The resolved connector and its
 * secrets are never logged, stored in the run's variables or put in a message:
 * a log line names the connector id, its type and the operation key.
 *
 * <p>
 * The call goes through the connector's circuit breaker
 * ({@link ConnectorCircuitBreakers}) and the {@link ConnectorTransport}
 * the descriptor's {@code transport} names (REST or, since 1.2.0, MCP; picked
 * from the host's {@link ConnectorTransports}), which validates the inputs
 * against the operation's schema, applies the auth scheme, retries only what
 * is safe to repeat and keeps to the step's time budget. The answer is masked (sensitive properties) and, unless the step asks
 * for the raw response, limited to the declared output before it is stored.
 *
 * <p>
 * A failure is an ERROR with a stable code in {@link StepResult#META_ERROR_CODE},
 * whether retrying may help, the HTTP status when there was one, a diagnostic in
 * {@code message} for run history and a short localized sentence for the user.
 * {@code onError: CONTINUE} marks it {@link StepResult#META_CONTINUE_ON_ERROR};
 * {@code BRANCH} is the engine's business (the step's error branch).
 */
@Slf4j
@Component
public class ConnectorCallStepHandler implements StepHandler {

    /** The read timeout when neither the step, the operation nor the connector's defaults say. */
    static final int DEFAULT_READ_TIMEOUT_MS = (int) CallOptions.DEFAULT_READ_TIMEOUT.toMillis();
    /** The longest read timeout any of them may ask for. */
    static final int MAX_READ_TIMEOUT_MS = (int) CallOptions.MAX_READ_TIMEOUT.toMillis();
    /** The shortest read timeout, as the step's config allows it. */
    static final int MIN_READ_TIMEOUT_MS = ConnectorCallConfig.MIN_TIMEOUT_MS;
    /** The whole step, retries included, gets this many read timeouts. */
    static final double BUDGET_FACTOR = 1.5;

    static final String MESSAGE_UNAVAILABLE = "step.connector.unavailable";
    static final String MESSAGE_TIMEOUT = "step.connector.timeout";
    static final String MESSAGE_AUTH_FAILED = "step.connector.authFailed";
    static final String MESSAGE_INPUT_INVALID = "step.connector.inputInvalid";
    static final String MESSAGE_REFUSED = "step.connector.refused";
    static final String MESSAGE_NOT_RESOLVABLE = "step.connector.notResolvable";
    static final String MESSAGE_CONFIG_INVALID = "step.connector.configInvalid";
    static final String MESSAGE_REJECTED = "step.connector.rejected";
    static final String MESSAGE_OPERATION_UNKNOWN = "step.connector.operationUnknown";

    /** The longest diagnostic kept from an unexpected transport failure. */
    private static final int MAX_DIAGNOSTIC_LENGTH = 300;

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final EngineMessages messages;
    private final ConnectorTransports transports;
    private final ConnectorCircuitBreakers breakers;

    /**
     * Supplied by the host. Absent when the host wires no connectors, in which
     * case this step fails NOT_RESOLVABLE rather than pretend.
     */
    private final Supplier<ConnectorPort> port;

    @Autowired
    public ConnectorCallStepHandler(EngineUtils engineUtils, VariableContext variableContext,
            StepOutputSchemaHelper schemaHelper, EngineMessages messages, ConnectorTransports transports,
            ConnectorCircuitBreakers breakers, ObjectProvider<ConnectorPort> connectorPort) {
        this(engineUtils, variableContext, schemaHelper, messages, transports, breakers,
                (Supplier<ConnectorPort>) connectorPort::getIfAvailable);
    }

    /** For tests: one transport (the 1.1.0 shape), and the port itself or null for a host that wires none. */
    public ConnectorCallStepHandler(EngineUtils engineUtils, VariableContext variableContext,
            StepOutputSchemaHelper schemaHelper, EngineMessages messages, ConnectorTransport transport,
            ConnectorCircuitBreakers breakers, ConnectorPort connectorPort) {
        this(engineUtils, variableContext, schemaHelper, messages, ConnectorTransports.of(transport), breakers,
                connectorPort);
    }

    /** For tests: the transports by kind, and the port itself or null for a host that wires none. */
    public ConnectorCallStepHandler(EngineUtils engineUtils, VariableContext variableContext,
            StepOutputSchemaHelper schemaHelper, EngineMessages messages, ConnectorTransports transports,
            ConnectorCircuitBreakers breakers, ConnectorPort connectorPort) {
        this(engineUtils, variableContext, schemaHelper, messages, transports, breakers,
                (Supplier<ConnectorPort>) () -> connectorPort);
    }

    private ConnectorCallStepHandler(EngineUtils engineUtils, VariableContext variableContext,
            StepOutputSchemaHelper schemaHelper, EngineMessages messages, ConnectorTransports transports,
            ConnectorCircuitBreakers breakers, Supplier<ConnectorPort> port) {
        this.engineUtils = engineUtils;
        this.variableContext = variableContext;
        this.schemaHelper = schemaHelper;
        this.messages = messages;
        this.transports = transports;
        this.breakers = breakers;
        this.port = port;
        // Registered once, here: an open breaker drops the cached resolution, so
        // the first call after the wait resolves the connector afresh.
        breakers.onOpen(id -> {
            ConnectorPort current = port.get();
            if (current != null) {
                current.invalidate(id);
            }
        });
    }

    @Override
    public String getType() {
        return "CONNECTOR_CALL";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.connectorCallDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.connectorCallSchema(step, this::describeOperation);
    }

    /** The operation a config names, for the variable picker; empty on any failure. */
    private Optional<ConnectorOperation> describeOperation(ConnectorCallConfig config) {
        ConnectorPort current = port.get();
        if (current == null || config == null) {
            return Optional.empty();
        }
        try {
            Optional<ConnectorOperation> operation = current.describe(config.connectorId(), config.operationKey());
            return operation != null ? operation : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("CONNECTOR_CALL: operation '{}' of connector {} could not be described: {}",
                    config.operationKey(), config.connectorId(), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        ConnectorCallConfig config;
        try {
            config = ConnectorCallConfig.parse(step.getApiConfig());
        } catch (ConnectorCallConfig.InvalidConfigException e) {
            log.warn("CONNECTOR_CALL step '{}' has an invalid configuration: {}", step.getStepName(),
                    e.getMessage());
            return failure(context, null, ConnectorErrorCodes.CONFIG_INVALID, false, null,
                    "CONNECTOR_CALL: " + e.getMessage());
        }

        if (Simulation.isActive(context)) {
            return simulate(step, context, config);
        }

        ConnectorPort connectorPort = port.get();
        if (connectorPort == null) {
            log.error("CONNECTOR_CALL step '{}' cannot run: no ConnectorPort is wired into this host.",
                    step.getStepName());
            return failure(context, config, ConnectorErrorCodes.NOT_RESOLVABLE, false, null,
                    "CONNECTOR_CALL: no ConnectorPort is wired into this host");
        }

        ResolvedConnector resolved;
        try {
            resolved = connectorPort.resolve(config.connectorId(), context.getAccountId(),
                    context.getAssistantId());
        } catch (ConnectorNotResolvableException e) {
            log.warn("CONNECTOR_CALL step '{}': connector {} not resolvable [{}]", step.getStepName(),
                    config.connectorId(), e.reason());
            return failure(context, config, ConnectorErrorCodes.NOT_RESOLVABLE, false, null,
                    "CONNECTOR_CALL: connector " + config.connectorId() + " could not be resolved ["
                            + e.reason() + "]: " + e.getMessage());
        }
        if (resolved == null) {
            return failure(context, config, ConnectorErrorCodes.NOT_RESOLVABLE, false, null,
                    "CONNECTOR_CALL: connector " + config.connectorId() + " could not be resolved");
        }

        Optional<ConnectorOperation> found = resolved.operation(config.operationKey());
        if (found.isEmpty()) {
            // The definition is edited in place: an operation removed after the journey was published.
            ConnectorException unknown = ConnectorException.operationUnknown(config.operationKey(), resolved.name());
            log.warn("CONNECTOR_CALL step '{}' {} ({}): {}", step.getStepName(), resolved.id(), resolved.kind(),
                    unknown.getMessage());
            return failure(context, config, unknown.code(), unknown.retryable(), null,
                    "CONNECTOR_CALL: " + unknown.getMessage());
        }
        ConnectorOperation operation = found.get();

        ConnectorTransport transport;
        try {
            transport = transports.require(resolved);
        } catch (ConnectorException e) {
            // The descriptor names a transport this host does not serve: nothing to send, nothing to count.
            return failure(context, config, e.code(), false, null, "CONNECTOR_CALL: " + e.getMessage());
        }

        Map<String, Object> inputs = renderInputs(config, context);
        CallOptions options = callOptions(config, resolved.descriptor(), operation, step, context);

        CircuitBreaker breaker = breakers.of(resolved.id());
        if (!breaker.tryAcquirePermission()) {
            log.warn("CONNECTOR_CALL step '{}' {} {}: circuit open, nothing sent", step.getStepName(),
                    resolved.id(), operation.key());
            return failure(context, config, ConnectorErrorCodes.CIRCUIT_OPEN, true, null,
                    "CONNECTOR_CALL " + operation.key() + " failed [" + ConnectorErrorCodes.CIRCUIT_OPEN
                            + "]: the connector's circuit breaker is open; nothing was sent");
        }

        long started = System.nanoTime();
        CallResult result;
        try {
            result = transport.call(resolved, operation, inputs, options);
            breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        } catch (ConnectorException e) {
            long elapsed = System.nanoTime() - started;
            // ADR 0010: only "the system is unwell" counts; a 4xx is the system speaking.
            if (ConnectorCircuitBreakers.countsAsFailure(e)) {
                breaker.onError(elapsed, TimeUnit.NANOSECONDS, e);
            } else {
                breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
            }
            return callFailed(step, context, config, resolved, operation, e, elapsed);
        } catch (RuntimeException e) {
            // A transport bug or an unexpected client failure: counted as the
            // system being unreachable, and reported without its raw text, which
            // may carry what the transport was holding.
            long elapsed = System.nanoTime() - started;
            breaker.onError(elapsed, TimeUnit.NANOSECONDS, e);
            String diagnostic = SecretScrubber.scrub(e.getMessage(), resolved.secretsOrEmpty().values(),
                    MAX_DIAGNOSTIC_LENGTH);
            // Logged with its stack trace but through a scrubbed copy: the raw
            // message may carry a secret, and the resolved connector is never logged.
            RuntimeException logged = new RuntimeException(
                    e.getClass().getName() + (diagnostic != null ? ": " + diagnostic : ""));
            logged.setStackTrace(e.getStackTrace());
            log.error("CONNECTOR_CALL step '{}' {} ({}) {}: unexpected transport failure", step.getStepName(),
                    resolved.id(), resolved.kind(), operation.key(), logged);
            ConnectorException unavailable = new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, null,
                    "unexpected transport failure (" + e.getClass().getSimpleName() + ")"
                            + (diagnostic != null && !diagnostic.isBlank() ? ": " + diagnostic : ""));
            return callFailed(step, context, config, resolved, operation, unavailable, elapsed);
        }

        Object output = config.storesRawResponse()
                ? OutputMasker.mask(operation.outputOrEmpty(), result.body())
                : OutputMasker.maskAndLimit(operation.outputOrEmpty(), result.body());
        variableContext.storeOutput(context, step, output);
        variableContext.writeStepField(context, step, "status", result.status());
        variableContext.writeStepField(context, step, "latencyMs", result.latencyMs());
        variableContext.writeStepField(context, step, "attempts", result.attempts());

        log.info("CONNECTOR_CALL step '{}' {} {} answered {} in {} ms", step.getStepName(), resolved.id(),
                operation.key(), result.status(), result.latencyMs());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(StepResult.META_HTTP_STATUS, result.status());
        metadata.put("latencyMs", result.latencyMs());
        metadata.put("attempts", result.attempts());
        metadata.put("connectorId", String.valueOf(resolved.id()));
        metadata.put("operationKey", operation.key());
        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(output)
                .message(step.getMessage())
                .metadata(metadata)
                .build();
    }

    /**
     * What this step would have called, without resolving or calling anything.
     *
     * <p>
     * The stub lists every output property the operation declares, each present
     * and null, so a later step's {@code {{steps.<key>.output.balance}}} resolves
     * to nothing rather than to an invented value (see ApiCallStepHandler's
     * simulate for why a rehearsal never makes data up). When the operation
     * cannot be described the stub is just {@code {simulated: true}}.
     */
    private StepResult simulate(JourneyStep step, ExecutionContext context, ConnectorCallConfig config) {
        Map<String, Object> stub = new LinkedHashMap<>();
        describeOperation(config).ifPresent(operation -> operation.outputOrEmpty().propertiesOrEmpty().keySet()
                .forEach(name -> stub.put(name, null)));
        stub.put("simulated", true);

        variableContext.storeOutput(context, step, stub);
        variableContext.writeStepField(context, step, "status", 200);
        variableContext.writeStepField(context, step, "latencyMs", 0L);
        variableContext.writeStepField(context, step, "attempts", 0);

        log.info("CONNECTOR_CALL step '{}' SIMULATED: {} {}", step.getStepName(), config.connectorId(),
                config.operationKey());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(Simulation.META_SIMULATED, true);
        metadata.put("connectorId", String.valueOf(config.connectorId()));
        metadata.put("operationKey", config.operationKey());
        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(stub)
                .message(step.getMessage())
                .metadata(metadata)
                .build();
    }

    /**
     * The step's inputs with their placeholders resolved over the run's
     * variables, and nothing else: no auth scope, no secret, no engine internal.
     * A lone placeholder keeps its type ({@code "{{steps.2.output.amount}}"} stays
     * a number); a mixed template is text. An unresolved placeholder is passed as
     * null, which the transport treats as absent (a "required" problem when the
     * operation needs it).
     */
    private Map<String, Object> renderInputs(ConnectorCallConfig config, ExecutionContext context) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : config.inputsOrEmpty().entrySet()) {
            rendered.put(entry.getKey(), render(entry.getValue(), context.getVariables()));
        }
        return rendered;
    }

    @SuppressWarnings("unchecked")
    private Object render(Object value, Map<String, Object> variables) {
        if (value instanceof String text) {
            return engineUtils.resolveSourceValue(text, variables);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                out.put(entry.getKey(), render(entry.getValue(), variables));
            }
            return out;
        } else if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(render(item, variables));
            }
            return out;
        }
        return value;
    }

    /**
     * Timeouts, attempts and the idempotency key for this call.
     *
     * <ul>
     * <li>Read timeout: the step's {@code timeoutMs}, else the operation's, else
     * the connector's default, else 10 s; within 100 ms..30 s.</li>
     * <li>Attempts: the step's {@code maxAttempts}, else the connector's retry policy,
     * else 3; within 1..3. The transport repeats only what is safe to repeat.</li>
     * <li>Budget: 1.5 read timeouts for the whole step, retries included.</li>
     * <li>Idempotency key: only when the operation or its type declares where
     * it goes (a header for REST, the {@code idempotencyArgument} for an MCP
     * tool). The step's {@code idempotencyKey} template, else
     * {@code <executionId>:<stepKey>} (the step order when the step has no key):
     * the same value on every replay of the step within a run, so the system can
     * de-duplicate a write the engine sends twice.</li>
     * </ul>
     */
    CallOptions callOptions(ConnectorCallConfig config, ConnectorDescriptor descriptor,
            ConnectorOperation operation, JourneyStep step, ExecutionContext context) {
        ConnectorDefaults defaults = descriptor != null ? descriptor.defaultsOrNone() : ConnectorDefaults.NONE;
        Integer readTimeout = firstNonNull(config.timeoutMs(), operation.timeoutMs(), defaults.readTimeoutMs());
        int readTimeoutMs = clamp(readTimeout != null ? readTimeout : DEFAULT_READ_TIMEOUT_MS, MIN_READ_TIMEOUT_MS,
                MAX_READ_TIMEOUT_MS);
        Integer attempts = firstNonNull(config.maxAttempts(),
                defaults.retry() != null ? defaults.retry().maxAttempts() : null);
        int maxAttempts = clamp(attempts != null ? attempts : CallOptions.MAX_ATTEMPTS, 1, CallOptions.MAX_ATTEMPTS);
        long budgetMs = Math.round(readTimeoutMs * BUDGET_FACTOR);

        String key = null;
        if (descriptor != null && descriptor.carriesIdempotencyKey(operation)) {
            if (config.idempotencyKey() != null) {
                key = engineUtils.replacePlaceholders(config.idempotencyKey(), context.getVariables());
            }
            if (key == null || key.isBlank()) {
                String stepKey = step.getStepKey() != null && !step.getStepKey().isBlank()
                        ? step.getStepKey()
                        : String.valueOf(step.getStepOrder());
                key = context.getExecutionId() + ":" + stepKey;
            }
        }
        return new CallOptions(Duration.ofMillis(budgetMs), Duration.ofMillis(readTimeoutMs), maxAttempts, key);
    }

    /** A call that reached the transport and failed: its status and cost on the step, the code in the result. */
    private StepResult callFailed(JourneyStep step, ExecutionContext context, ConnectorCallConfig config,
            ResolvedConnector resolved, ConnectorOperation operation, ConnectorException e, long elapsedNanos) {
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (e.status() != null) {
            variableContext.writeStepField(context, step, "status", e.status());
        }
        variableContext.writeStepField(context, step, "latencyMs", latencyMs);
        log.warn("CONNECTOR_CALL step '{}' {} {} failed [{}] status={} latencyMs={}", step.getStepName(),
                resolved.id(), operation.key(), e.code(), e.status(), latencyMs);
        return failure(context, config, e.code(), e.retryable(), e.status(),
                "CONNECTOR_CALL " + operation.key() + " failed [" + e.code() + "]: " + e.getMessage());
    }

    /**
     * An ERROR with the diagnostic in {@code message} (run history), a short
     * localized sentence for the user, and the error metadata the engine copies
     * into {@code steps.<order>.error} on the error branch.
     *
     * @param config null when the configuration could not be read
     */
    private StepResult failure(ExecutionContext context, ConnectorCallConfig config, String code,
            boolean retryable, Integer httpStatus, String message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(StepResult.META_ERROR_CODE, code);
        metadata.put(StepResult.META_RETRYABLE, retryable);
        if (httpStatus != null) {
            metadata.put(StepResult.META_HTTP_STATUS, httpStatus);
        }
        if (config != null) {
            metadata.put("connectorId", String.valueOf(config.connectorId()));
            metadata.put("operationKey", config.operationKey());
            if (config.onErrorOrFail() == ConnectorCallConfig.OnError.CONTINUE) {
                metadata.put(StepResult.META_CONTINUE_ON_ERROR, true);
            }
        }
        return StepResult.builder()
                .status(StepStatus.ERROR)
                .message(message)
                .userMessage(messages.get(context.resolvedLanguage(), userMessageKey(code)))
                .metadata(metadata)
                .build();
    }

    /**
     * The user-facing sentence for a code. An operation's own mapped code
     * ({@code ACCOUNT_NOT_FOUND}) reads as the system refusing the request: the
     * journey's error branch is where an author says something more specific.
     */
    static String userMessageKey(String code) {
        if (code == null) {
            return MESSAGE_REJECTED;
        }
        return switch (code) {
            case ConnectorErrorCodes.UNAVAILABLE, ConnectorErrorCodes.CIRCUIT_OPEN -> MESSAGE_UNAVAILABLE;
            case ConnectorErrorCodes.TIMEOUT -> MESSAGE_TIMEOUT;
            case ConnectorErrorCodes.AUTH_FAILED -> MESSAGE_AUTH_FAILED;
            case ConnectorErrorCodes.INPUT_INVALID -> MESSAGE_INPUT_INVALID;
            case ConnectorErrorCodes.EGRESS_REFUSED -> MESSAGE_REFUSED;
            case ConnectorErrorCodes.NOT_RESOLVABLE -> MESSAGE_NOT_RESOLVABLE;
            case ConnectorErrorCodes.CONFIG_INVALID -> MESSAGE_CONFIG_INVALID;
            case ConnectorErrorCodes.OPERATION_UNKNOWN -> MESSAGE_OPERATION_UNKNOWN;
            default -> MESSAGE_REJECTED;
        };
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
