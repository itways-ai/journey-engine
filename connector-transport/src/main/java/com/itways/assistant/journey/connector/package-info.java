/**
 * Calls one operation of a configured connector on the wire (1.1.0; MCP and
 * the transport registry since 1.2.0).
 *
 * <p>
 * The SPI and what every transport shares: {@link ConnectorTransport} (one
 * implementation per kind of system; {@code rest} holds the REST one,
 * {@code mcp} the Model Context Protocol one, {@code http} what the two
 * share), {@link ConnectorTransports} (the host's transports by descriptor
 * {@code transport} value, which the step and the Test button pick from),
 * {@link CallOptions} and {@link CallResult} (what a call takes and returns),
 * {@link ConnectorException} with its {@link ConnectorErrorCodes} (every
 * failure, typed: a stable code, whether a retry may help, the HTTP status, a
 * message with the secrets scrubbed out), {@link EgressPolicy} (the operator's
 * egress rules: which hosts may resolve to private addresses, the optional
 * platform-wide bound, whether plain http is tolerated), {@link InputBinder}
 * (validates and places an operation's inputs), {@link OutputMasker} (masks the
 * output properties the descriptor flags sensitive) and {@link SecretScrubber}
 * (removes secret values from text that will be stored or shown).
 *
 * <p>
 * A library, not a Spring module: no beans, no Spring MVC, no auto-configuration.
 * The engine's CONNECTOR_CALL step and journey-service's Test and Try
 * endpoints each wire the transports themselves, so both exercise the same code
 * (D5). Nothing here ever logs a URL beyond host and path template, a query
 * string, a header value or a body.
 */
package com.itways.assistant.journey.connector;
