/**
 * The MCP transport (1.2.0): a remote Model Context Protocol server over
 * Streamable HTTP, one named tool per operation.
 *
 * <p>
 * {@link com.itways.assistant.journey.connector.mcp.McpTransport} is the
 * public class ({@code call}, {@code test}, and {@code discover} for
 * journey-service's import); the rest are its parts, package-private so the
 * contract stays the SPI's. {@code McpProtocol} speaks JSON-RPC in the
 * revision the server accepts — the current stateless one (2026-07-28) first,
 * the legacy initialize handshake (2025-11-25 and older) when the server
 * needs it — and names what a failure means; {@code SseReader} reads a
 * {@code text/event-stream} answer within caps; {@code McpLog} holds the only
 * log lines, so what they may carry is decided once (never an argument, a
 * result, a credential or a session id). The HTTP plumbing is shared with the
 * REST transport ({@code connector.http}).
 *
 * <p>
 * Not here, by design: stdio or any local process, the agent loop in which a
 * model picks tools, resources and prompts, elicitation and
 * {@code input_required} results, OAuth authorization-code flows (a static
 * header credential or client credentials only), the server-initiated stream
 * (GET), sessions ended with DELETE, resumption with {@code Last-Event-ID}.
 */
package com.itways.assistant.journey.connector.mcp;
