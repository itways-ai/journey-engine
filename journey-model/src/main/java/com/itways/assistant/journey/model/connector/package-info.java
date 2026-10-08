/**
 * Connectors (1.1.0; MCP since 1.2.0): the connector type descriptor and
 * what the engine receives when it resolves one configured connector. Shared by
 * journey-service (which stores types and connectors, validates descriptors
 * and serves the resolve endpoint), connector-transport (which makes the call)
 * and the engine's CONNECTOR_CALL step. Plain data, no Spring.
 *
 * <ul>
 * <li>{@link ConnectorDescriptor}: one kind of system — its transport
 * ({@code REST}: an operation is a method and a path; {@code MCP}: an operation
 * is one named tool of a remote server, with {@code ConnectorDescriptor.McpSettings}
 * for the protocol revision), its {@link AuthScheme}, configuration fields,
 * {@link ConnectorDefaults} and {@link ConnectorOperation}s, each with its
 * input and output {@link SchemaNode} and {@link RiskLevel}.
 * <li>{@link ConnectorDescriptorValidator}: the rules a descriptor must meet
 * before it is stored (unique keys; REST: every path parameter an input, no
 * secret in a path or query, writes that declare how replays are
 * de-duplicated; MCP: no method or path, inputs are tool arguments,
 * idempotency declared, a credential in a header only).
 * <li>{@link ResolvedConnector}: the connector as the engine receives it
 * per call: pinned descriptor version, base URL, allow-list, config values and
 * the opened secrets. Held in memory only; its {@code toString} never prints a
 * secret.
 * </ul>
 *
 * <p>
 * Descriptors are read leniently (unknown keys are ignored, so a newer
 * journey-service can add some); the step config in {@code model.step} is read
 * strictly.
 */
package com.itways.assistant.journey.model.connector;
