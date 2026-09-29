package com.itways.assistant.journey.engine.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Inet6Address;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** IP literals and localhost only: nothing here performs a DNS lookup over the network. */
class EgressGuardTest {

    private final EgressGuard guard = new EgressGuard(true, "api.partner.local, .corp.internal");

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data",
            "http://127.0.0.1:11434/api/delete",
            "http://localhost:8084/api/journeys/internal/instances",
            "http://10.1.2.3/x",
            "http://172.20.0.5/x",
            "http://192.168.1.10/x",
            "http://100.64.1.1/x",
            "http://0.0.0.0/x",
            "http://[::1]/x",
            "http://[fd00::1]/x",
            "http://[fe80::1]/x",
            "http://[::ffff:192.168.1.10]/x" })
    void internalAddressesAreRefused(String url) {
        assertThat(guard.refusal(url)).contains("private or internal");
    }

    /**
     * ARC-12: the guard judges addresses with common-core's PublicUrlPolicy.isPublic, the
     * platform's one address rule, instead of its own copy. These are the edges of every
     * range the guard's own rule knew, with the verdicts that rule gave (true = internal).
     */
    @ParameterizedTest(name = "{0} internal={1}")
    @CsvSource({
            "0.0.0.0, true", "0.255.255.255, true", "1.0.0.0, false", "1.1.1.1, false", "8.8.8.8, false",
            "9.255.255.255, false", "10.0.0.0, true", "10.255.255.255, true", "11.0.0.0, false",
            "100.63.255.255, false", "100.64.0.0, true", "100.127.255.255, true", "100.128.0.0, false",
            "126.255.255.255, false", "127.0.0.1, true", "127.255.255.255, true", "128.0.0.0, false",
            "169.253.255.255, false", "169.254.0.0, true", "169.254.169.254, true", "169.255.0.0, false",
            "172.15.255.255, false", "172.16.0.0, true", "172.31.255.255, true", "172.32.0.0, false",
            "191.255.255.255, false", "192.0.0.0, true", "192.0.0.255, true", "192.0.1.0, false",
            "192.0.3.0, false", "192.167.255.255, false", "192.168.0.0, true", "192.168.255.255, true",
            "192.169.0.0, false", "198.17.255.255, false", "198.18.0.0, true", "198.19.255.255, true",
            "198.20.0.0, false", "198.51.99.255, false", "198.51.101.0, false", "203.0.112.255, false",
            "203.0.114.0, false", "223.255.255.255, false", "224.0.0.0, true", "239.255.255.255, true",
            "240.0.0.0, true", "255.255.255.255, true",
            "::, true", "::1, true", "::2, false", "fe80::1, true", "febf::1, true", "fec0::1, true",
            "fbff::1, false", "fc00::1, true", "fdff::1, true", "fe00::1, false", "ff02::1, true",
            "2606:4700:4700::1111, false", "2001:db7::1, false", "2001:db9::1, false", "64:ff9b::808:808, false" })
    void sharedAddressRuleGivesTheGuardsPreviousVerdicts(String address, boolean internal) throws Exception {
        assertThat(EgressGuard.isInternal(InetAddress.getByName(address))).isEqualTo(internal);
    }

    /** An IPv4-mapped IPv6 address (::ffff:a.b.c.d) is judged by the IPv4 address inside, as before. */
    @ParameterizedTest(name = "::ffff:{0} internal={1}")
    @CsvSource({ "10.0.0.1, true", "127.0.0.1, true", "169.254.169.254, true", "100.64.0.1, true",
            "240.0.0.1, true", "1.1.1.1, false", "8.8.8.8, false" })
    void ipv4MappedAddressesAreJudgedByTheIpv4Inside(String ipv4, boolean internal) throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        System.arraycopy(InetAddress.getByName(ipv4).getAddress(), 0, mapped, 12, 4);

        assertThat(EgressGuard.isInternal(Inet6Address.getByAddress(null, mapped, -1))).isEqualTo(internal);
    }

    /**
     * The one difference from the guard's own rule: the documentation ranges are not public
     * in the shared rule, so a call to them is refused. No real API lives there.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://192.0.2.10/x",
            "http://198.51.100.10/x",
            "http://203.0.113.10/x",
            "http://[2001:db8::10]/x" })
    void documentationRangesAreRefused(String url) {
        assertThat(guard.refusal(url)).contains("private or internal");
    }

    @Test
    void publicAddressIsAllowed() {
        assertThat(guard.refusal("https://1.1.1.1/dns-query")).isNull();
    }

    @Test
    void allowListedHostsSkipTheCheck() {
        assertThat(guard.refusal("http://api.partner.local/x")).isNull();
        assertThat(guard.refusal("http://svc.corp.internal/x")).isNull();
        assertThat(guard.refusal("http://corp.internal/x")).isNull();
    }

    @Test
    void onlyHttpSchemes() {
        assertThat(guard.refusal("ftp://1.1.1.1/file")).contains("only http and https");
        assertThat(guard.refusal("file:///etc/passwd")).isNotNull();
        assertThat(guard.refusal("not a url")).isNotNull();
    }

    @Test
    void blockingCanBeSwitchedOffPerDeployment() {
        assertThat(new EgressGuard(false, "").refusal("http://127.0.0.1/x")).isNull();
    }

    /** JRN-17: the message reaches API callers, so it names the host, never what it resolved to. */
    @Test
    void refusalNamesTheConfiguredHostAndTheSettingButNotTheResolvedAddress() {
        String refusal = guard.refusal("http://localhost:8084/api/x");

        assertThat(refusal).contains("host localhost is not allowed")
                .contains("JOURNEY_API_ALLOWED_HOSTS")
                .doesNotContain("127.0.0.1")
                .doesNotContain("::1")
                .doesNotContain("resolves to");
    }

    /** The connection's DNS applies the same rule, with the same words, and hands back what it checked. */
    @Test
    void addressesForAConnectionAreTheCheckedOnes() throws Exception {
        InetAddress internal = InetAddress.getByName("10.0.0.7");
        InetAddress outside = InetAddress.getByName("1.1.1.1");
        EgressGuard stubbed = new EgressGuard(true, "api.partner.local, [fd00::5]",
                host -> host.equals("public.example") ? new InetAddress[] { outside } : new InetAddress[] { internal },
                EgressGuard::isInternal);

        assertThat(stubbed.addressesFor("PUBLIC.example")).containsExactly(outside);
        assertThat(stubbed.addressesFor("api.partner.local")).containsExactly(internal);
        assertThat(stubbed.addressesFor("fd00::5")).containsExactly(internal); // allow-listed as [fd00::5]
        assertThatThrownBy(() -> stubbed.addressesFor("inside.example"))
                .isInstanceOf(EgressGuard.RefusedHostException.class)
                .hasMessageContaining("host inside.example is not allowed")
                .hasMessageContaining("JOURNEY_API_ALLOWED_HOSTS")
                .hasMessageNotContaining("10.0.0.7")
                .satisfies(e -> assertThat(EgressGuard.refusalIn(new RuntimeException("wrapped", e)))
                        .isEqualTo(stubbed.refusal("http://inside.example/x")));
        assertThat(EgressGuard.refusalIn(new java.net.UnknownHostException("nope"))).isNull();
    }
}
