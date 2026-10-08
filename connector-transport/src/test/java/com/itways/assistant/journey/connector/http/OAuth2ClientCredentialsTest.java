package com.itways.assistant.journey.connector.http;

import static com.itways.assistant.journey.connector.http.OAuth2ClientCredentials.DEFAULT_EXPIRES_IN_SECONDS;
import static com.itways.assistant.journey.connector.http.OAuth2ClientCredentials.MAX_EXPIRES_IN_SECONDS;
import static com.itways.assistant.journey.connector.http.OAuth2ClientCredentials.expiresInSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How a token endpoint's {@code expires_in} is read: the endpoint is not
 * trusted to pick the arithmetic, so no value it sends throws or caches a
 * token forever.
 */
class OAuth2ClientCredentialsTest {

    @Test
    void ordinaryValuesAreTakenAsTheyAre() {
        assertThat(expiresInSeconds(3600)).isEqualTo(3600);
        assertThat(expiresInSeconds(3600L)).isEqualTo(3600);
        assertThat(expiresInSeconds("3600")).isEqualTo(3600);
        assertThat(expiresInSeconds(" 120 ")).isEqualTo(120);
    }

    @Test
    void missingOrNonNumericFallsBackToTheDefault() {
        assertThat(expiresInSeconds(null)).isEqualTo(DEFAULT_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds("")).isEqualTo(DEFAULT_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds("soon")).isEqualTo(DEFAULT_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds("1h")).isEqualTo(DEFAULT_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds(Boolean.TRUE)).isEqualTo(DEFAULT_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds(List.of(1))).isEqualTo(DEFAULT_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds(Double.NaN)).isEqualTo(DEFAULT_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds("NaN")).isEqualTo(DEFAULT_EXPIRES_IN_SECONDS);
    }

    @Test
    void hugeValuesAreClampedToThirtyDaysInsteadOfOverflowing() {
        assertThat(expiresInSeconds(Long.MAX_VALUE)).isEqualTo(MAX_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds(Double.MAX_VALUE)).isEqualTo(MAX_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds(Double.POSITIVE_INFINITY)).isEqualTo(MAX_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds(new BigInteger("99999999999999999999999999999")))
                .isEqualTo(MAX_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds(new BigDecimal("1e400"))).isEqualTo(MAX_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds("9223372036854775807")).isEqualTo(MAX_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds("99999999999999999999999")).isEqualTo(MAX_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds("1e99")).isEqualTo(MAX_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds(MAX_EXPIRES_IN_SECONDS + 1)).isEqualTo(MAX_EXPIRES_IN_SECONDS);
        assertThat(expiresInSeconds(MAX_EXPIRES_IN_SECONDS - 1)).isEqualTo(MAX_EXPIRES_IN_SECONDS - 1);
    }

    @Test
    void negativeValuesClampToZero() {
        assertThat(expiresInSeconds(-1)).isZero();
        assertThat(expiresInSeconds(Long.MIN_VALUE)).isZero();
        assertThat(expiresInSeconds(Double.NEGATIVE_INFINITY)).isZero();
        assertThat(expiresInSeconds("-3600")).isZero();
        assertThat(expiresInSeconds(new BigInteger("-99999999999999999999999999999"))).isZero();
    }

    @Test
    void fractionsAreDropped() {
        assertThat(expiresInSeconds(300.9)).isEqualTo(300);
        assertThat(expiresInSeconds("300.9")).isEqualTo(300);
        assertThat(expiresInSeconds(new BigDecimal("59.5"))).isEqualTo(59);
        assertThat(expiresInSeconds(0.5)).isZero();
    }
}
