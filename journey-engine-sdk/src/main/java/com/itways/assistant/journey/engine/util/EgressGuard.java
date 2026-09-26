package com.itways.assistant.journey.engine.util;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether an API_CALL step may send a request to a URL.
 *
 * <p>
 * API_CALL URLs are written by tenants — and may be assembled from what an end
 * user typed — yet the request leaves from inside the platform's network. With
 * no check, a journey could call {@code http://ollama:11434/api/delete} and
 * remove the embedding model every tenant depends on, read
 * {@code http://169.254.169.254/} cloud metadata, or reach any other service's
 * port directly. So by default a URL must resolve only to public addresses.
 *
 * <p>
 * Every address the host resolves to is checked, not just the first: a name
 * with one public and one private record would otherwise pass on the public
 * one and connect to the private one. The JVM caches the lookup (30s by
 * default), so the connection that follows uses the addresses checked here;
 * redirects are not followed at all (see ApiCallStepHandler), because a public
 * URL answering 302 to a private one would walk straight past this check.
 *
 * <p>
 * Hosts a deployment genuinely wants reachable — an on-premise API on the same
 * network — go in {@code nibras.journey.api-call.allowed-hosts}: exact names,
 * or {@code .example.internal} for a domain and its subdomains.
 */
@Component
public class EgressGuard {

	private final boolean blockPrivateNetworks;
	private final Set<String> allowedHosts;

	public EgressGuard(
			@Value("${nibras.journey.api-call.block-private-networks:true}") boolean blockPrivateNetworks,
			@Value("${nibras.journey.api-call.allowed-hosts:}") String allowedHosts) {
		this.blockPrivateNetworks = blockPrivateNetworks;
		this.allowedHosts = Arrays.stream(allowedHosts.split(","))
				.map(h -> h.trim().toLowerCase(Locale.ROOT))
				.filter(h -> !h.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
	}

	/**
	 * Null when the request may go; otherwise why it may not, as a diagnostic
	 * for run history (never shown to the end user as-is).
	 */
	public String refusal(String url) {
		URI uri;
		try {
			uri = URI.create(url == null ? "" : url.trim());
		} catch (IllegalArgumentException e) {
			return "not a valid URL";
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			return "only http and https URLs may be called";
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			return "the URL has no host";
		}
		host = host.toLowerCase(Locale.ROOT);
		if (isAllowed(host) || !blockPrivateNetworks) {
			return null;
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			return "host " + host + " could not be resolved";
		}
		for (InetAddress address : addresses) {
			if (isInternal(address)) {
				return "host " + host + " resolves to " + address.getHostAddress()
						+ ", a private or internal address; add it to nibras.journey.api-call.allowed-hosts to allow it";
			}
		}
		return null;
	}

	private boolean isAllowed(String host) {
		for (String allowed : allowedHosts) {
			if (allowed.startsWith(".") ? host.endsWith(allowed) || host.equals(allowed.substring(1))
					: host.equals(allowed)) {
				return true;
			}
		}
		return false;
	}

	static boolean isInternal(InetAddress address) {
		if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] b = address.getAddress();
		if (address instanceof Inet4Address) {
			int first = b[0] & 0xFF;
			int second = b[1] & 0xFF;
			return first == 0 // "this network"
					|| (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT, 100.64.0.0/10
					|| (first == 192 && second == 0 && (b[2] & 0xFF) == 0) // IETF protocol assignments
					|| (first == 198 && (second == 18 || second == 19)) // benchmarking
					|| first >= 240; // reserved and broadcast
		}
		if (address instanceof Inet6Address) {
			// Unique local fc00::/7 — the IPv6 counterpart of the private ranges.
			if ((b[0] & 0xFE) == 0xFC) {
				return true;
			}
			// IPv4-mapped (::ffff:a.b.c.d): judge the IPv4 address inside.
			boolean mapped = true;
			for (int i = 0; i < 10; i++) {
				mapped &= b[i] == 0;
			}
			if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
				try {
					return isInternal(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
				} catch (UnknownHostException e) {
					return true;
				}
			}
		}
		return false;
	}
}
