package com.itways.assistant.journey.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
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
    void masksAJwtByItsHeaderWhateverTheSegmentLengths() {
        String shortSegments = "eyJhbGciOiJub25lIn0.e30.sig";
        assertThat(SecretScrubber.scrub("got " + shortSegments + " back", List.of()))
                .isEqualTo("got " + MASK + " back");
    }

    @Test
    void anAcquiredAccessTokenIsScrubbedLikeASecretWhenItIsInTheSet() {
        String token = "opaque-access-token-77";
        assertThat(SecretScrubber.scrub("upstream said: token " + token + " is invalid", List.of("cs-1234", token)))
                .isEqualTo("upstream said: token " + MASK + " is invalid");
    }

    @Test
    void scrubDeepWalksMapsAndListsAndKeepsQueryStringsInBodies() {
        Map<String, Object> body = Map.of("echo", Map.of("X-API-Key", "k-secret-1", "authorization", "Bearer tok-abc"),
                "items", List.of("key k-secret-1 seen", 7, true), "next", "https://api.test/accounts?page=2",
                "jwt", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhYmMifQ.c2lnbmF0dXJlLXNpZ25hdHVyZQ");

        Object out = SecretScrubber.scrubDeep(body, List.of("k-secret-1"));

        assertThat(out).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) out;
        assertThat(((Map<?, ?>) map.get("echo")).get("X-API-Key")).isEqualTo(MASK);
        assertThat(((Map<?, ?>) map.get("echo")).get("authorization")).isEqualTo("Bearer " + MASK);
        assertThat(map.get("items")).isEqualTo(List.of("key " + MASK + " seen", 7, true));
        assertThat(map.get("next")).isEqualTo("https://api.test/accounts?page=2");
        assertThat(map.get("jwt")).isEqualTo(MASK);
        assertThat(out.toString()).doesNotContain("k-secret-1", "tok-abc");
        assertThat(SecretScrubber.scrubDeep(null, List.of())).isNull();
        assertThat(SecretScrubber.scrubDeep(42, List.of())).isEqualTo(42);
        assertThat(SecretScrubber.scrubDeep("k-secret-1", List.of("k-secret-1"))).isEqualTo(MASK);
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
