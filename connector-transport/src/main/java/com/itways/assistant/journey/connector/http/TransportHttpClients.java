package com.itways.assistant.journey.connector.http;

import com.itways.assistant.journey.connector.CallOptions;
import java.time.Duration;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

/**
 * The HTTP client every transport dials with: common-web's
 * {@code PinnedHttpClients} settings (DNS pinned to the resolver that vetted
 * the address, no cookies, no proxy, no redirects, a bounded wait for a pooled
 * connection) plus two a transport cannot do without. The library's own retry
 * strategy is off — it would re-send a POST on a 503 by itself, which only the
 * transport's {@link RetrySchedule} may decide — and the TLS handshake is
 * bounded by the connect timeout, so a port that accepts and then says nothing
 * costs a connect timeout, not a read timeout.
 */
public final class TransportHttpClients {

    private TransportHttpClients() {
    }

    /**
     * @param dns            the pinned resolver; connections dial only what it answered
     * @param maxTotal       connections kept in all
     * @param maxPerRoute    connections kept to one host
     * @param connectTimeout TCP connect, TLS handshake and pool wait bound
     */
    public static CloseableHttpClient create(DnsResolver dns, int maxTotal, int maxPerRoute, Duration connectTimeout) {
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dns)
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(maxPerRoute)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(connectTimeout))
                        .setSocketTimeout(Timeout.of(CallOptions.MAX_READ_TIMEOUT))
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom()
                        .setHandshakeTimeout(Timeout.of(connectTimeout))
                        .build())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.of(connectTimeout))
                        .setResponseTimeout(Timeout.of(CallOptions.MAX_READ_TIMEOUT))
                        .build())
                .disableCookieManagement()
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
    }
}
