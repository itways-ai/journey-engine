package com.itways.assistant.journey.engine.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * One breaker per connector: what counts as a failure, that one connector
 * opening leaves the others alone, and that an opening is announced once with
 * the connector's id.
 */
class ConnectorCircuitBreakersTest {

    private static final UUID CRM = UUID.fromString("0b6d3c1e-5a7f-4e2b-8c90-1d2e3f4a5b6c");
    private static final UUID BANK = UUID.fromString("6f1c2a52-8d0e-4b61-9a43-2f0d1c7e5b10");

    private static ConnectorCircuitBreakers small() {
        return new ConnectorCircuitBreakers(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
    }

    private static void fail(CircuitBreaker breaker) {
        assertThat(breaker.tryAcquirePermission()).isTrue();
        breaker.onError(1, TimeUnit.MILLISECONDS,
                new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, 503, "the system answered 503"));
    }

    @Test
    void onlyUnavailableAndTimeoutCountAsFailures() {
        assertThat(ConnectorCircuitBreakers.countsAsFailure(
                new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, 503, "503"))).isTrue();
        assertThat(ConnectorCircuitBreakers.countsAsFailure(
                new ConnectorException(ConnectorErrorCodes.TIMEOUT, true, null, "timeout"))).isTrue();
        for (String code : List.of(ConnectorErrorCodes.REJECTED, ConnectorErrorCodes.AUTH_FAILED,
                ConnectorErrorCodes.INPUT_INVALID, ConnectorErrorCodes.EGRESS_REFUSED,
                ConnectorErrorCodes.RESPONSE_INVALID, "ACCOUNT_NOT_FOUND")) {
            assertThat(ConnectorCircuitBreakers.countsAsFailure(new ConnectorException(code, false, 400, code)))
                    .as(code).isFalse();
        }
        assertThat(ConnectorCircuitBreakers.countsAsFailure(new IllegalStateException("boom"))).isFalse();
        assertThat(ConnectorCircuitBreakers.countsAsFailure(null)).isFalse();
    }

    @Test
    void oneConnectorOpeningLeavesTheOthersClosed() {
        ConnectorCircuitBreakers breakers = small();
        breakers.of(BANK).acquirePermission();
        breakers.of(BANK).onSuccess(1, TimeUnit.MILLISECONDS);

        fail(breakers.of(CRM));
        fail(breakers.of(CRM));

        assertThat(breakers.state(CRM)).contains(CircuitBreaker.State.OPEN);
        assertThat(breakers.state(BANK)).contains(CircuitBreaker.State.CLOSED);
        assertThat(breakers.of(BANK).tryAcquirePermission()).isTrue();
        assertThat(breakers.of(CRM).tryAcquirePermission()).isFalse();
    }

    @Test
    void anOpeningIsAnnouncedOnceWithTheConnectorId() {
        ConnectorCircuitBreakers breakers = small();
        List<UUID> opened = new ArrayList<>();
        breakers.onOpen(opened::add);

        fail(breakers.of(CRM));
        fail(breakers.of(CRM));
        // Refused while open: no second announcement.
        assertThat(breakers.of(CRM).tryAcquirePermission()).isFalse();

        assertThat(opened).containsExactly(CRM);
    }

    @Test
    void aListenerThatThrowsDoesNotStopTheOthers() {
        ConnectorCircuitBreakers breakers = small();
        List<UUID> opened = new ArrayList<>();
        breakers.onOpen(id -> {
            throw new IllegalStateException("cache gone");
        });
        breakers.onOpen(opened::add);

        fail(breakers.of(BANK));
        fail(breakers.of(BANK));

        assertThat(opened).containsExactly(BANK);
    }

    @Test
    void stateIsEmptyUntilFirstUse() {
        ConnectorCircuitBreakers breakers = small();

        assertThat(breakers.state(BANK)).isEmpty();
        breakers.of(BANK);
        assertThat(breakers.state(BANK)).contains(CircuitBreaker.State.CLOSED);
        assertThat(breakers.registry().find(BANK.toString())).isPresent();
    }

    @Test
    void theDefaultsAreTheAdrNumbers() {
        CircuitBreakerConfig config = new ConnectorCircuitBreakers().registry().getDefaultConfig();

        assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(20);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isTrue();
    }
}
