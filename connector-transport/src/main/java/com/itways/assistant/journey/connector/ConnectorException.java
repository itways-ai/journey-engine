package com.itways.assistant.journey.connector;

import java.util.List;

/**
 * A connector call that did not produce a usable answer: a stable
 * {@link #code()}, whether a retry may help, the HTTP status when there was
 * one, and a message that has had every secret of the connector scrubbed out
 * of it, so it can be stored in run history and shown in the portal.
 *
 * <p>
 * Unchecked, so the transport's internals stay readable; the step handler and
 * the Test endpoint catch it at their boundary and turn it into a step result
 * or a check row.
 */
public class ConnectorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;
    private final Integer status;
    private final List<String> details;
    private final int attempts;

    public ConnectorException(String code, boolean retryable, Integer status, String scrubbedMessage) {
        this(code, retryable, status, scrubbedMessage, List.of(), null);
    }

    public ConnectorException(String code, boolean retryable, Integer status, String scrubbedMessage,
            Throwable cause) {
        this(code, retryable, status, scrubbedMessage, List.of(), cause);
    }

    /**
     * @param code            one of {@link ConnectorErrorCodes}, or an operation's own mapped code
     * @param retryable       whether sending the same request again may succeed
     * @param status          the HTTP status, or null when none was received
     * @param scrubbedMessage a diagnostic with secrets removed; never a URL with a query, never a body
     * @param details         per-field problems for {@link ConnectorErrorCodes#INPUT_INVALID}
     * @param cause           the underlying failure, kept for the log only
     */
    public ConnectorException(String code, boolean retryable, Integer status, String scrubbedMessage,
            List<String> details, Throwable cause) {
        this(code, retryable, status, scrubbedMessage, details, cause, 0);
    }

    /**
     * As the constructor above, recording how many requests were sent before
     * the transport gave up ({@link #attempts()}).
     */
    public ConnectorException(String code, boolean retryable, Integer status, String scrubbedMessage,
            List<String> details, Throwable cause, int attempts) {
        super(scrubbedMessage, cause);
        this.code = code;
        this.retryable = retryable;
        this.status = status;
        this.details = details != null ? List.copyOf(details) : List.of();
        this.attempts = Math.max(0, attempts);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    /** The HTTP status, or null when the failure happened before an answer. */
    public Integer status() {
        return status;
    }

    /** Per-field problems, for an input that did not satisfy the schema; otherwise empty. */
    public List<String> details() {
        return details;
    }

    /**
     * How many requests the transport sent before failing: 1 for a plain
     * refusal by the system, more when retries were used up, 0 when nothing
     * was sent (refused egress, invalid inputs) or the failure was raised
     * outside a transport.
     */
    public int attempts() {
        return attempts;
    }

    /** This failure with {@code attempts} recorded; the same code, status, message, details and cause. */
    public ConnectorException withAttempts(int attempts) {
        if (attempts == this.attempts) {
            return this;
        }
        return new ConnectorException(code, retryable, status, getMessage(), details, getCause(), attempts);
    }

    public static ConnectorException inputInvalid(List<String> problems) {
        return new ConnectorException(ConnectorErrorCodes.INPUT_INVALID, false, null,
                "inputs do not satisfy the operation: " + String.join("; ", problems), problems, null);
    }

    public static ConnectorException egressRefused(String reason) {
        return new ConnectorException(ConnectorErrorCodes.EGRESS_REFUSED, false, null, "refused: " + reason);
    }

    public static ConnectorException configInvalid(String reason) {
        return new ConnectorException(ConnectorErrorCodes.CONFIG_INVALID, false, null, reason);
    }
}
