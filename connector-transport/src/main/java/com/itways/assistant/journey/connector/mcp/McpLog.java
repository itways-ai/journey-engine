package com.itways.assistant.journey.connector.mcp;

import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The MCP transport's log lines, in one place so what they may hold is decided
 * once: the connector id, the operation key, the tool name, the JSON-RPC
 * method, the host and the endpoint path as the connector's base URL wrote
 * it, the protocol revision in use. Never a tool argument (an account number),
 * never a result (a customer record), never a header (the credential, the
 * session id), never an error body except at DEBUG after scrubbing. Logged
 * under {@link McpTransport}'s logger.
 */
final class McpLog {

    private static final Logger log = LoggerFactory.getLogger(McpTransport.class);

    private McpLog() {
    }

    static void started(ResolvedConnector connector, ConnectorOperation operation, String host, String path) {
        log.info("[CONNECTOR] call connector={} operation={} tool={} host={} path={}", connector.id(),
                operation.key(), operation.toolNameOrKey(), host, path.isEmpty() ? "/" : path);
    }

    static void answered(ResolvedConnector connector, ConnectorOperation operation, int status, int attempts,
            long latencyMs) {
        log.info("[CONNECTOR] answered connector={} operation={} status={} attempts={} latencyMs={}",
                connector.id(), operation.key(), status, attempts, latencyMs);
    }

    static void failed(ResolvedConnector connector, ConnectorOperation operation, ConnectorException failure,
            long latencyMs) {
        // The message may hold a scrubbed error text (300 chars at most); bodies stay at DEBUG.
        log.info("[CONNECTOR] failed connector={} operation={} code={} status={} retryable={} attempts={} latencyMs={}",
                connector.id(), operation.key(), failure.code(), failure.status(), failure.retryable(),
                failure.attempts(), latencyMs);
        if (log.isDebugEnabled()) {
            log.debug("[CONNECTOR] failure for connector={} operation={}: {}", connector.id(), operation.key(),
                    failure.getMessage(), failure.getCause());
        }
    }

    static void retrying(ResolvedConnector connector, ConnectorOperation operation, int failedAttempt,
            long delayMs, String code) {
        log.info("[CONNECTOR] retry connector={} operation={} after attempt {} ({}) in {} ms", connector.id(),
                operation.key(), failedAttempt, code, delayMs);
    }

    /** The protocol revision settled with a server, and how. */
    static void negotiated(ResolvedConnector connector, String protocolVersion, boolean legacy, boolean session) {
        log.info("[CONNECTOR] mcp connector={} protocolVersion={} mode={} session={}", connector.id(),
                protocolVersion, legacy ? "legacy-initialize" : "stateless", session);
    }

    static void discovered(ResolvedConnector connector, int tools, int pages, boolean truncated) {
        log.info("[CONNECTOR] mcp connector={} tools/list tools={} pages={} truncated={}", connector.id(), tools,
                pages, truncated);
    }

    /** An error body or JSON-RPC error, already scrubbed and cut to 300 characters by the caller. */
    static void errorBody(ResolvedConnector connector, String method, int status, String scrubbed) {
        log.debug("[CONNECTOR] connector={} method={} status={} body={}", connector.id(), method, status,
                scrubbed);
    }

    static void debug(String message, Object... args) {
        log.debug(message, args);
    }
}
