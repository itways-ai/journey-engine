package com.itways.assistant.journey.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** What the scrubber takes out of a message before it is stored or shown. */
class SecretScrubberTest {

    private static final String MASK = SecretScrubber.MASK;

    @Test
    void replacesTheConnectorsOwnSecretValues() {
        String out = SecretScrubber.scrub("key sk-verysecret-1234 and password hunter22 echoed",
                List.of("sk-verysecret-1234", "hunter22"));

        assertThat(out).isEqualTo("key " + MASK + " and password " + MASK + " echoed");
    }

    @Test
    void masksAuthorizationStyleHeaderValues() {
        assertThat(SecretScrubber.scrub("Authorization: tok-abcdef, X-API-Key: k-123", List.of()))
                .doesNotContain("tok-abcdef", "k-123").contains("Authorization: " + MASK)
                .contains("X-API-Key: " + MASK);
        assertThat(SecretScrubber.scrub("cookie=session-77; next", List.of())).doesNotContain("session-77");
    }

    @Test
    void masksBearerBasicAndJwtTokensWhereverTheyAppear() {
        assertThat(SecretScrubber.scrub("got bearer sk-live-aaaa in body", List.of()))
                .isEqualTo("got Bearer " + MASK + " in body");
        assertThat(SecretScrubber.scrub("Basic dXNlcjpwYXNz rejected", List.of()))
                .isEqualTo("Basic " + MASK + " rejected");
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhYmMifQ.c2lnbmF0dXJlLXNpZ25hdHVyZQ";
        assertThat(SecretScrubber.scrub("token " + jwt + " expired", List.of())).isEqualTo("token " + MASK + " expired");
    }

    @Test
    void masksQueryStrings() {
        assertThat(SecretScrubber.scrub("GET /accounts?token=k&asOf=2026 failed", List.of()))
                .isEqualTo("GET /accounts?" + MASK + " failed");
        // A credential-named parameter is caught by the header rule first, then the query rule; both mask it.
        assertThat(SecretScrubber.scrub("GET /accounts?api_key=k&asOf=2026 failed", List.of()))
                .doesNotContain("api_key=k", "asOf");
    }

    @Test
    void shortOrBlankSecretsAreNotMatched() {
        assertThat(SecretScrubber.scrub("a 1 b", List.of("a", "1", "", " "))).isEqualTo("a 1 b");
        assertThat(SecretScrubber.scrub("abcd here", List.of("abcd"))).isEqualTo(MASK + " here");
    }

    @Test
    void truncatesAfterScrubbing() {
        String out = SecretScrubber.scrub("sk-verysecret-1234 followed by a long tail of text",
                List.of("sk-verysecret-1234"), 12);

        assertThat(out).hasSize(12).startsWith(MASK).endsWith("…");
        assertThat(SecretScrubber.scrub("short", List.of(), 300)).isEqualTo("short");
        assertThat(SecretScrubber.scrub(null, List.of(), 10)).isNull();
    }
}
