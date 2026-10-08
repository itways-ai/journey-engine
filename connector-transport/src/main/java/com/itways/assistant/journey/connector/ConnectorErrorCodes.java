package com.itways.assistant.journey.connector;

/**
 * The stable codes a connector call fails with, as run history, the step's
 * {@code steps.<key>.error.code} and the portal's Test result report them.
 * Operations may map an HTTP status to a code of their own
 * ({@code errors: {"404": "ACCOUNT_NOT_FOUND"}}); those replace
 * {@link #REJECTED} for that status.
 */
public final class ConnectorErrorCodes {

    /** The system could not be reached, or answered 429, 502, 503, 504 or another 5xx. */
    public static final String UNAVAILABLE = "CONNECTOR_UNAVAILABLE";
    /** No complete answer within the read timeout or the step's budget. */
    public static final String TIMEOUT = "CONNECTOR_TIMEOUT";
    /** 401 or 403, or the OAuth2 token could not be obtained. */
    public static final String AUTH_FAILED = "CONNECTOR_AUTH_FAILED";
    /** A 4xx the operation does not map to a code of its own. */
    public static final String REJECTED = "CONNECTOR_REJECTED";
    /** The mapped inputs do not satisfy the operation's input schema. */
    public static final String INPUT_INVALID = "CONNECTOR_INPUT_INVALID";
    /** The URL may not be dialled: scheme, host, port or path outside what the connector allows, or a private address. */
    public static final String EGRESS_REFUSED = "CONNECTOR_EGRESS_REFUSED";
    /** The connector's circuit breaker is open; nothing was sent. */
    public static final String CIRCUIT_OPEN = "CONNECTOR_CIRCUIT_OPEN";
    /** The connector could not be resolved: unknown, disabled, out of scope, or journey-service unavailable. */
    public static final String NOT_RESOLVABLE = "CONNECTOR_NOT_RESOLVABLE";
    /** The step's configuration could not be read, or names an operation the type does not have. */
    public static final String CONFIG_INVALID = "CONNECTOR_CONFIG_INVALID";
    /** The answer was not what the operation declares: not JSON, or larger than the response cap. */
    public static final String RESPONSE_INVALID = "CONNECTOR_RESPONSE_INVALID";

    private ConnectorErrorCodes() {
    }
}
