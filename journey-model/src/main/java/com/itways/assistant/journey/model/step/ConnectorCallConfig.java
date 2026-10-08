package com.itways.assistant.journey.model.step;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.UUID;

/**
 * An CONNECTOR_CALL step's {@code apiConfig}: which connector, which
 * operation, how its inputs are mapped and how a failure is handled.
 *
 * <p>
 * Unlike {@code ApiConfig} (one Lombok class with some forty fields shared by
 * every step type, where bad JSON silently becomes a default GET), this record
 * is read by {@link #parse(String)} and nothing else: unknown fields fail,
 * missing identifiers fail, and limits are checked here, so a step that cannot
 * run is refused at publish and reported as an ERROR at run time, never run
 * with a guess. It holds no credential and no URL: those belong to the
 * connector, which the engine resolves at run time.
 *
 * @param connectorId     which configured connector
 * @param operationKey      which operation of its type
 * @param inputs            input name → a literal, or a {@code {{path}}} template over the run's variables
 * @param idempotencyKey    optional template for the key sent on writes; when absent the engine
 *                          sends {@code <executionId>:<stepKey>} (the step order when the step has
 *                          no key), the same value on every replay of the step within a run
 * @param onError           what the engine does when the call fails
 * @param timeoutMs         read timeout per attempt, over the type's default; 100..30000
 * @param maxAttempts       1..3; honoured only for idempotent operations (or ones with an idempotency key)
 * @param storeRawResponse  false (default): the stored output holds the declared output properties only;
 *                          true: the whole response body, with sensitive declared properties still masked
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConnectorCallConfig(
        UUID connectorId,
        String operationKey,
        Map<String, Object> inputs,
        String idempotencyKey,
        OnError onError,
        Integer timeoutMs,
        Integer maxAttempts,
        Boolean storeRawResponse) {

    /** What the engine does when the call fails. */
    public enum OnError {
        /** The step fails and the run ends in ERROR (unless the step has {@code continueOnError}). */
        FAIL,
        /** The step is recorded as FAILED and the run goes on, as with {@code continueOnError}. */
        CONTINUE,
        /** The children with branch name {@code error} run; the builder writes this when the author adds an error path. */
        BRANCH
    }

    /** The branch name of a child that runs when its parent step fails. */
    public static final String ERROR_BRANCH = "error";

    public static final int MIN_TIMEOUT_MS = 100;
    public static final int MAX_TIMEOUT_MS = 30_000;
    public static final int MAX_ATTEMPTS = 3;

    /** Thrown by {@link #parse} for JSON this record refuses, with a sentence that names what is wrong. */
    public static final class InvalidConfigException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        public InvalidConfigException(String message) {
            super(message);
        }

        public InvalidConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The one strict reader. Unknown fields, malformed JSON, a missing
     * connector or operation, a timeout or attempt count out of range are
     * each refused with a message that names the problem.
     *
     * @throws InvalidConfigException when {@code json} is not a usable config
     */
    public static ConnectorCallConfig parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidConfigException("the step has no configuration");
        }
        ConnectorCallConfig config;
        try {
            config = STRICT.readValue(json, ConnectorCallConfig.class);
        } catch (JsonProcessingException e) {
            throw new InvalidConfigException("the step configuration is not valid: " + e.getOriginalMessage(), e);
        }
        if (config == null) {
            throw new InvalidConfigException("the step has no configuration");
        }
        return config.validated();
    }

    /** This config with its defaults filled in, after checking its limits. */
    private ConnectorCallConfig validated() {
        if (connectorId == null) {
            throw new InvalidConfigException("connectorId is required");
        }
        if (operationKey == null || operationKey.isBlank()) {
            throw new InvalidConfigException("operationKey is required");
        }
        if (timeoutMs != null && (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS)) {
            throw new InvalidConfigException(
                    "timeoutMs must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS + ", got " + timeoutMs);
        }
        if (maxAttempts != null && (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS)) {
            throw new InvalidConfigException("maxAttempts must be between 1 and " + MAX_ATTEMPTS + ", got " + maxAttempts);
        }
        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new InvalidConfigException("idempotencyKey must not be blank when given");
        }
        return new ConnectorCallConfig(connectorId, operationKey.trim(), inputs != null ? inputs : Map.of(),
                idempotencyKey, onError != null ? onError : OnError.FAIL, timeoutMs, maxAttempts,
                storeRawResponse != null ? storeRawResponse : Boolean.FALSE);
    }

    public Map<String, Object> inputsOrEmpty() {
        return inputs != null ? inputs : Map.of();
    }

    public OnError onErrorOrFail() {
        return onError != null ? onError : OnError.FAIL;
    }

    public boolean storesRawResponse() {
        return Boolean.TRUE.equals(storeRawResponse);
    }

    /** Writes this config as the JSON {@link #parse} reads. */
    public String toJson() {
        try {
            return STRICT.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();
}
