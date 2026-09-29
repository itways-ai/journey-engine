package com.itways.assistant.journey.engine.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

/**
 * The DNS of an API_CALL connection: {@link EgressGuard} resolves the host
 * once, checks every address it got, and the connection dials exactly those.
 *
 * <p>
 * Installed as the connection manager's resolver, this is the only lookup the
 * connection makes, so what was checked is what is dialled: a name that
 * answered a public address to {@link EgressGuard#refusal} and a private one
 * now is refused here, not connected to. An allow-listed host resolves
 * unchecked, as it does in {@link EgressGuard#refusal}.
 *
 * <p>
 * Only the addresses come from here. The connection still speaks to the host
 * by its name: TLS sends it as SNI and verifies the certificate against it,
 * and the {@code Host} header carries it.
 */
public final class EgressDnsResolver implements DnsResolver {

    private final EgressGuard guard;

    public EgressDnsResolver(EgressGuard guard) {
        this.guard = guard;
    }

    /**
     * @throws EgressGuard.RefusedHostException when the host has a private or
     *                                          internal address and is not
     *                                          allow-listed
     */
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return guard.addressesFor(host);
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        // Only asked for by authentication schemes (SPNEGO/Kerberos) these calls never use.
        return host;
    }
}
