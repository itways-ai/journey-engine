package com.itways.assistant.journey.engine.service;

/**
 * Thrown by {@link ConnectorPort#resolve} when a connector cannot be used
 * by this run: it does not exist, is disabled, belongs to another account or
 * workspace, or journey-service could not be asked. The step fails with
 * {@code CONNECTOR_NOT_RESOLVABLE}, not retried.
 */
public class ConnectorNotResolvableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why, as the resolve endpoint said it: {@code NOT_FOUND}, {@code FORBIDDEN}, {@code DISABLED}, {@code UNAVAILABLE}. */
    private final String reason;

    public ConnectorNotResolvableException(String reason, String message) {
        this(reason, message, null);
    }

    public ConnectorNotResolvableException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
