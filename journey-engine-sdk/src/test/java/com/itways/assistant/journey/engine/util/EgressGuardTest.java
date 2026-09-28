package com.itways.assistant.journey.engine.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
