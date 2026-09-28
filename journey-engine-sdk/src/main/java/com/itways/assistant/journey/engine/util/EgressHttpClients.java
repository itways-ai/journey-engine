package com.itways.assistant.journey.engine.util;

import java.time.Duration;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * The HTTP client API_CALL steps call tenant URLs with: connections go only to
 * addresses {@link EgressGuard} vetted, through {@link EgressDnsResolver}.
 *
 * <p>
 * Apache HttpClient 5 because its connection manager takes its DNS from us
 * while everything else stays standard: the JDK clients have no resolver
 * hook, and dialling a checked IP by hand would lose SNI and certificate
 * verification against the host name. TLS is the library default (system
 * trust store, default hostname verifier).
 *
 * <ul>
 * <li>Redirects are never followed: a 3xx comes back to the journey as the
 * response it is, as it always has (see ApiCallStepHandler).
 * <li>No proxy: system properties are not read, since a proxy would resolve
 * the name itself.
 * <li>No cookies: one client serves every tenant's calls, and a cookie one
 * host set must not ride along on another journey's request.
 * </ul>
 */
public final class EgressHttpClients {

	/** Calls run on the request thread; a caller waits for a pooled connection no longer than for a connect. */
	private static final int MAX_CONNECTIONS = 200;
	private static final int MAX_CONNECTIONS_PER_HOST = 50;

	private EgressHttpClients() {
	}

	public static CloseableHttpClient client(EgressGuard guard, Duration connectTimeout, Duration readTimeout) {
		PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
				.setDnsResolver(new EgressDnsResolver(guard))
				.setMaxConnTotal(MAX_CONNECTIONS)
				.setMaxConnPerRoute(MAX_CONNECTIONS_PER_HOST)
				.setDefaultConnectionConfig(ConnectionConfig.custom()
						.setConnectTimeout(Timeout.of(connectTimeout))
						.setSocketTimeout(Timeout.of(readTimeout))
						.build())
				.build();
		return HttpClients.custom()
				.setConnectionManager(connections)
				.setDefaultRequestConfig(RequestConfig.custom()
						.setConnectionRequestTimeout(Timeout.of(connectTimeout))
						.setResponseTimeout(Timeout.of(readTimeout))
						.build())
				.disableRedirectHandling()
				.disableCookieManagement()
				.build();
	}

	/** The same, for Spring's {@code RestTemplate} and {@code RestClient}. */
	public static HttpComponentsClientHttpRequestFactory requestFactory(EgressGuard guard, Duration connectTimeout,
			Duration readTimeout) {
		return new HttpComponentsClientHttpRequestFactory(client(guard, connectTimeout, readTimeout));
	}
}
