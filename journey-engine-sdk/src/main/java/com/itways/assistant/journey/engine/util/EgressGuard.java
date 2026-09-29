package com.itways.assistant.journey.engine.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.itways.common.net.PublicUrlPolicy;

import lombok.extern.slf4j.Slf4j;

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
 * one and connect to the private one. {@link #refusal} checks the URL before
 * the call; the call itself then connects through {@link EgressDnsResolver},
 * which looks the name up once more, applies the same rule to that answer and
 * hands the connection exactly those addresses. Without it the HTTP client
 * would resolve the name again on its own, and a name with a short TTL could
 * answer a public address to the check and a private one to the connect (DNS
 * rebinding). Redirects are not followed at all (see ApiCallStepHandler),
 * because a public URL answering 302 to a private one would walk straight past
 * the check on the URL the author wrote.
 *
 * <p>
 * Hosts a deployment genuinely wants reachable — an on-premise API on the same
 * network — go in {@code nibras.journey.api-call.allowed-hosts} (env var
 * {@value #ALLOW_LIST_SETTING}): exact names or IP literals, or
 * {@code .example.internal} for a domain and its subdomains. It is one
 * platform-wide operator setting per deployment, not a per-tenant one.
 *
 * <p>
 * A refusal names the host as the URL wrote it, never the address it resolved
 * to: the message is kept in run history and returned to API callers, and the
 * resolved address maps the platform's internal network. That address is
 * logged for the operator instead.
 */
@Slf4j
@Component
public class EgressGuard {

	/** The operator setting that allows internal hosts, as a deployment sets it. */
	public static final String ALLOW_LIST_SETTING = "JOURNEY_API_ALLOWED_HOSTS";

	/** Resolves a host name: the system resolver in production, a stub in tests. */
	@FunctionalInterface
	public interface Lookup {
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	/**
	 * A host the guard will not let a call connect to, as the connection's DNS
	 * reports it: an {@link UnknownHostException}, so HTTP clients treat it as a
	 * name that did not resolve. The message is the refusal as
	 * {@link #refusal} words it: the host as written, never the address.
	 */
	public static final class RefusedHostException extends UnknownHostException {

		private static final long serialVersionUID = 1L;

		RefusedHostException(String refusal) {
			super(refusal);
		}
	}

	private final boolean blockPrivateNetworks;
	private final Set<String> allowedHosts;
	private final Lookup lookup;
	private final Predicate<InetAddress> internal;

	@Autowired
	public EgressGuard(
			@Value("${nibras.journey.api-call.block-private-networks:true}") boolean blockPrivateNetworks,
			@Value("${nibras.journey.api-call.allowed-hosts:}") String allowedHosts) {
		this(blockPrivateNetworks, allowedHosts, InetAddress::getAllByName, EgressGuard::isInternal);
	}

	/** For tests: a stub lookup, and what counts as internal. */
	public EgressGuard(boolean blockPrivateNetworks, String allowedHosts, Lookup lookup,
			Predicate<InetAddress> internal) {
		this.blockPrivateNetworks = blockPrivateNetworks;
		this.allowedHosts = Arrays.stream(allowedHosts.split(","))
				.map(h -> h.trim().toLowerCase(Locale.ROOT))
				.filter(h -> !h.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
		this.lookup = lookup;
		this.internal = internal;
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
		try {
			vetted(host);
			return null;
		} catch (RefusedHostException e) {
			return e.getMessage();
		} catch (UnknownHostException e) {
			return "host " + host + " could not be resolved";
		}
	}

	/**
	 * The addresses a call to {@code host} may connect to, for the connection's
	 * DNS ({@link EgressDnsResolver}): what the name resolves to, looked up
	 * once, when every one of them passes. For an allow-listed host (or with
	 * blocking switched off) whatever it resolves to, since reaching a private
	 * address is what the allow-list is for.
	 *
	 * @param host a host name or IP literal, IPv6 with or without brackets
	 * @throws RefusedHostException when any address is private or internal
	 * @throws UnknownHostException when the name does not resolve
	 */
	public InetAddress[] addressesFor(String host) throws UnknownHostException {
		if (host == null || host.isBlank()) {
			throw new UnknownHostException(host);
		}
		String name = host.toLowerCase(Locale.ROOT);
		// Matched and reported as a URL writes the host: an IPv6 literal in brackets.
		String asWritten = name.indexOf(':') >= 0 && !name.startsWith("[") ? "[" + name + "]" : name;
		if (isAllowed(asWritten) || !blockPrivateNetworks) {
			return resolve(asWritten);
		}
		return vetted(asWritten);
	}

	/** The refusal carried by {@code error} or anything that caused it; null when there is none. */
	public static String refusalIn(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof RefusedHostException) {
				return t.getMessage();
			}
		}
		return null;
	}

	/** Resolves {@code host} once and returns those addresses, provided none of them is internal. */
	private InetAddress[] vetted(String host) throws UnknownHostException {
		InetAddress[] addresses = resolve(host);
		for (InetAddress address : addresses) {
			if (internal.test(address)) {
				// The address maps the internal network: the log has it, the message does not.
				log.warn("API_CALL to host '{}' refused: it resolves to {}, a private or internal address",
						host, address.getHostAddress());
				throw new RefusedHostException("host " + host + " is not allowed: it is a private or internal address. "
						+ "An operator can allow it with the " + ALLOW_LIST_SETTING
						+ " setting (nibras.journey.api-call.allowed-hosts)");
			}
		}
		return addresses;
	}

	private InetAddress[] resolve(String host) throws UnknownHostException {
		String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
		InetAddress[] addresses = lookup.resolve(name);
		if (addresses == null || addresses.length == 0) {
			throw new UnknownHostException(host);
		}
		return addresses.clone();
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

	/**
	 * The platform's one address rule, {@link PublicUrlPolicy#isPublic} in
	 * common-core: anything that is not a public address is internal. That is
	 * loopback, unspecified, link-local, private (10/8, 172.16/12, 192.168/16),
	 * multicast, "this network" (0/8), carrier-grade NAT (100.64/10), IETF
	 * protocol assignments (192.0.0/24), benchmarking (198.18/15), reserved and
	 * broadcast (240/4), IPv6 unique local (fc00::/7), and an IPv4-mapped IPv6
	 * address by the IPv4 address inside. The documentation ranges (192.0.2/24,
	 * 198.51.100/24, 203.0.113/24, 2001:db8::/32) count as internal too: no real
	 * API lives there.
	 */
	static boolean isInternal(InetAddress address) {
		return !PublicUrlPolicy.isPublic(address);
	}
}
