package com.itways.assistant.journey.connector.http;

import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.web.net.PublicOnlyDnsResolver;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnknownHostException;
import java.time.Duration;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.util.Timeout;

/**
 * One request on the wire, and what its failure means. Shared by the
 * transports; not part of the SPI.
 *
 * <p>
 * Before a connection is attempted the host goes through the same resolver the
 * connection manager uses, so a host that resolves to a private address is
 * refused even when the pool already holds a connection and would not resolve
 * again. The exception for an I/O failure never carries the client's own
 * message: it names resolved addresses, which map the platform's network; the
 * cause is kept for the DEBUG log.
 */
public final class HttpExchange {

    private final CloseableHttpClient client;
    private final DnsResolver dns;
    private final ResponseReader reader;
    private final Duration connectTimeout;

    public HttpExchange(CloseableHttpClient client, DnsResolver dns, ResponseReader reader, Duration connectTimeout) {
        this.client = client;
        this.dns = dns;
        this.reader = reader;
        this.connectTimeout = connectTimeout;
    }

    /**
     * Resolves {@code host} through the pinned resolver and refuses what it refuses.
     *
     * @throws ConnectorException {@code EGRESS_REFUSED} for a non-public address;
     *                              {@code UNAVAILABLE} (retryable) for a name DNS does not know
     */
    public void requireResolvable(String host, String what) {
        try {
            dns.resolve(host);
        } catch (PublicOnlyDnsResolver.RefusedAddressException e) {
            throw ConnectorException.egressRefused(what + " host " + host + " resolves to a non-public address");
        } catch (UnknownHostException e) {
            throw new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, null,
                    what + " host " + host + " does not resolve", e);
        }
    }

    /**
     * Sends {@code request} and reads the answer within the response cap.
     *
     * @param responseTimeout time to a complete answer for this one request
     * @param label           {@code "<METHOD> <operationKey>"} for the message
     */
    public ResponseReader.Raw send(HttpUriRequestBase request, Duration responseTimeout, String label) {
        return send(request, responseTimeout, label, reader::read);
    }

    /**
     * As {@link #send(HttpUriRequestBase, Duration, String)}, with the answer
     * read by {@code handler} instead of the plain reader (an SSE stream). The
     * handler's own {@code ConnectorException}s pass through; its I/O
     * failures are named like any other.
     */
    public <T> T send(HttpUriRequestBase request, Duration responseTimeout, String label,
            HttpClientResponseHandler<T> handler) {
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.of(responseTimeout))
                .setConnectionRequestTimeout(Timeout.of(connectTimeout))
                .build());
        try {
            return client.execute(request, handler);
        } catch (IOException e) {
            throw failure(e, label, responseTimeout);
        }
    }

    /**
     * Sends {@code request} and hands back the open response, for a caller
     * that reads the body itself (an SSE stream it stops reading once its
     * answer arrived). The caller must close the response, and should
     * {@code abort()} the request first when it did not read to the end: a
     * stream the server keeps open would otherwise be drained on close.
     *
     * @throws ConnectorException as {@link #send}, for a failure before the response head arrived
     */
    public ClassicHttpResponse open(HttpUriRequestBase request, Duration responseTimeout, String label) {
        request.setConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.of(responseTimeout))
                .setConnectionRequestTimeout(Timeout.of(connectTimeout))
                .build());
        try {
            return client.executeOpen(null, request, null);
        } catch (IOException e) {
            throw failure(e, label, responseTimeout);
        }
    }

    /** What an I/O failure while reading an open response means ({@link #open}). */
    public static ConnectorException describe(IOException e, String label, Duration responseTimeout) {
        return failure(e, label, responseTimeout);
    }

    private static ConnectorException failure(IOException e, String label, Duration responseTimeout) {
        if (PublicOnlyDnsResolver.isUnresolvedOrRefused(e)) {
            if (hasCause(e, PublicOnlyDnsResolver.RefusedAddressException.class)) {
                return new ConnectorException(ConnectorErrorCodes.EGRESS_REFUSED, false, null,
                        "refused: " + label + " host resolves to a non-public address", e);
            }
            return new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, null,
                    label + ": host does not resolve", e);
        }
        if (hasCause(e, SSLException.class)) {
            return new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, false, null,
                    label + ": TLS handshake with the host failed", e);
        }
        if (e instanceof InterruptedIOException) {
            // Both the connect timeout and the response timeout surface as this.
            return new ConnectorException(ConnectorErrorCodes.TIMEOUT, true, null,
                    label + ": no answer within " + responseTimeout.toMillis() + " ms", e);
        }
        return new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, null,
                label + ": could not reach the host", e);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
