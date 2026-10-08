/**
 * The REST transport: HTTPS JSON APIs described by a connector type descriptor.
 *
 * <p>
 * {@link com.itways.assistant.journey.connector.rest.RestTransport} is the
 * public class; {@code ConnectorLog} holds its only log lines, so what they
 * may carry is decided once. Its other parts — {@code RequestUrls} (where a
 * request may go: the base URL owns scheme, host and port; a path parameter
 * can never leave the base path), {@code HttpExchange} (one request through
 * the pinned-DNS client and what a failure means), {@code AuthApplier} and
 * {@code OAuth2ClientCredentials} (the credential), {@code ResponseReader}
 * (the answer within the response cap), {@code RetrySchedule} and
 * {@code Deadline} (whether a failed attempt is sent again) — moved to
 * {@code connector.http} in 1.2.0, where the MCP transport shares them; their
 * behaviour here is unchanged.
 */
package com.itways.assistant.journey.connector.rest;
