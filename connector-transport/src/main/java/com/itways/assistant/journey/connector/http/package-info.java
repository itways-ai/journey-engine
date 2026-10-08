/**
 * What the HTTP-based transports share (1.2.0; until then these were the REST
 * transport's package-private parts): {@code TransportHttpClients} builds the
 * pinned-DNS client, {@code RequestUrls} decides where a request may go,
 * {@code HttpExchange} sends one request and names what a failure means,
 * {@code AuthApplier} and {@code OAuth2ClientCredentials} put the credential
 * on, {@code ResponseReader} reads an answer within the response cap, and
 * {@code RetrySchedule} and {@code Deadline} decide whether a failed attempt is
 * sent again.
 *
 * <p>
 * Public because two packages ({@code rest}, {@code mcp}) use them, not because
 * a host should: the contract stays the SPI in the parent package, and these
 * may change between minor versions.
 */
package com.itways.assistant.journey.connector.http;
