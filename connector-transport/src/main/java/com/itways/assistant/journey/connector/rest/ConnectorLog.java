package com.itways.assistant.journey.connector.rest;

import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The transport's log lines, in one place so what they may hold is decided
 * once: the connector id, the operation key, the method, the host and the
 * path <em>template</em> as the descriptor wrote it. Never the rendered path
 * (a path parameter may be an account number), never a query string (an API
 * key may travel in it), never a header, never a body except an error body
 * at DEBUG after scrubbing. Logged under {@link RestTransport}'s logger.
 */
final class ConnectorLog {

    private static final Logger log = LoggerFactory.getLogger(RestTransport.class);

    private ConnectorLog() {
    }

    static void started(ResolvedConnector connector, ConnectorOperation operation, String host) {
        log.info("[CONNECTOR] call connector={} operation={} {} host={} path={}", connector.id(),
                operation.key(), operation.methodOrGet(), host, operation.path());
    }

    static void answered(ResolvedConnector connector, ConnectorOperation operation, int status, int attempts,
            long latencyMs) {
        log.info("[CONNECTOR] answered connector={} operation={} status={} attempts={} latencyMs={}",
                connector.id(), operation.key(), status, attempts, latencyMs);
    }

    static void failed(ResolvedConnector connector, ConnectorOperation operation, ConnectorException failure,
            long latencyMs) {
        // The message may hold a scrubbed error body (300 chars at most); bodies stay at DEBUG.
        log.info("[CONNECTOR] failed connector={} operation={} code={} status={} retryable={} attempts={} latencyMs={}",
                connector.id(), operation.key(), failure.code(), failure.status(), failure.retryable(),
                failure.attempts(), latencyMs);
        if (log.isDebugEnabled()) {
            log.debug("[CONNECTOR] failure for connector={} operation={}: {}", connector.id(), operation.key(),
                    failure.getMessage(), failure.getCause());
        }
    }

    /** An error body, already scrubbed and cut to 300 characters by the caller. */
    static void errorBody(ResolvedConnector connector, ConnectorOperation operation, int status,
            String scrubbedBody) {
        log.debug("[CONNECTOR] connector={} operation={} status={} body={}", connector.id(), operation.key(),
                status, scrubbedBody);
    }

    static void retrying(ResolvedConnector connector, ConnectorOperation operation, int failedAttempt,
            long delayMs, String code) {
        log.info("[CONNECTOR] retry connector={} operation={} after attempt {} ({}) in {} ms", connector.id(),
                operation.key(), failedAttempt, code, delayMs);
    }

    static void debug(String message, Object... args) {
        log.debug(message, args);
    }
}
