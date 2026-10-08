package com.itways.assistant.journey.connector;

import com.itways.common.net.HostAllowList;
import com.itways.common.net.PublicUrlPolicy;
import java.util.Collection;

/**
 * The operator's egress rules for connector calls, one per deployment. The
 * per-connector rules (which hosts a connector may dial, its base URL
 * prefix) come with the {@code ResolvedConnector}; these are the bounds
 * around them.
 *
 * <ul>
 * <li>{@code privateHosts}: hosts that may resolve to a private address
 * (loopback, RFC 1918, link-local) and still be dialled. Empty in production.
 * The dev profile lists {@code .test} for the local mock bank; tests list
 * {@code 127.0.0.1}. Entries follow {@link HostAllowList}.</li>
 * <li>{@code allowedHosts}: the optional platform-wide bound
 * ({@code itways.connectors.egress.allowed-hosts}). Empty means any public
 * host; when set, a connector's host must also be on it — an operator's
 * kill switch that needs no tenant to edit anything.</li>
 * <li>{@code allowHttp}: whether plain {@code http://} base URLs are tolerated.
 * False in production; true only for local development and tests, where the
 * mock systems have no certificate.</li>
 * <li>{@code maxResponseBytes}: the most a response body may hold; larger
 * answers fail with {@link ConnectorErrorCodes#RESPONSE_INVALID}.</li>
 * </ul>
 *
 * @param privateHosts     hosts allowed to resolve to private addresses
 * @param allowedHosts     the platform-wide bound; empty for none
 * @param allowHttp        whether http (not https) base URLs may be dialled
 * @param maxResponseBytes the response body cap
 * @param dns              where names are resolved; {@link PublicUrlPolicy#SYSTEM_DNS} in production
 */
public record EgressPolicy(
        HostAllowList privateHosts,
        HostAllowList allowedHosts,
        boolean allowHttp,
        long maxResponseBytes,
        PublicUrlPolicy.Resolver dns) {

    public static final long DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;

    /** Production: public hosts only, https only, 1 MB answers, system DNS. */
    public static final EgressPolicy STRICT = new EgressPolicy(HostAllowList.EMPTY, HostAllowList.EMPTY, false,
            DEFAULT_MAX_RESPONSE_BYTES, PublicUrlPolicy.SYSTEM_DNS);

    public EgressPolicy {
        if (privateHosts == null) {
            privateHosts = HostAllowList.EMPTY;
        }
        if (allowedHosts == null) {
            allowedHosts = HostAllowList.EMPTY;
        }
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        if (dns == null) {
            dns = PublicUrlPolicy.SYSTEM_DNS;
        }
    }

    /**
     * The policy from an operator's settings: comma-separated host lists as
     * {@code HostAllowList.parse} reads them.
     *
     * @throws IllegalArgumentException for a list entry that is not a host
     */
    public static EgressPolicy of(String privateHosts, String allowedHosts, boolean allowHttp) {
        return new EgressPolicy(HostAllowList.parse(privateHosts), HostAllowList.parse(allowedHosts), allowHttp,
                DEFAULT_MAX_RESPONSE_BYTES, PublicUrlPolicy.SYSTEM_DNS);
    }

    /** This policy with these hosts allowed to resolve privately (tests, local development). */
    public EgressPolicy withPrivateHosts(Collection<String> hosts) {
        return new EgressPolicy(HostAllowList.of(hosts), allowedHosts, allowHttp, maxResponseBytes, dns);
    }

    /** This policy with the platform-wide bound set to these hosts (tests of an operator's kill switch). */
    public EgressPolicy withAllowedHosts(Collection<String> hosts) {
        return new EgressPolicy(privateHosts, HostAllowList.of(hosts), allowHttp, maxResponseBytes, dns);
    }

    public EgressPolicy withAllowHttp(boolean allow) {
        return new EgressPolicy(privateHosts, allowedHosts, allow, maxResponseBytes, dns);
    }

    public EgressPolicy withMaxResponseBytes(long bytes) {
        return new EgressPolicy(privateHosts, allowedHosts, allowHttp, bytes, dns);
    }

    public EgressPolicy withDns(PublicUrlPolicy.Resolver resolver) {
        return new EgressPolicy(privateHosts, allowedHosts, allowHttp, maxResponseBytes, resolver);
    }

    /** Whether the platform-wide bound, when set, allows {@code host}. */
    public boolean withinPlatformBound(String host) {
        return allowedHosts.isEmpty() || allowedHosts.allows(host);
    }
}
