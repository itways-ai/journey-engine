package com.itways.assistant.journey.engine.util;

import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * One circuit breaker per configured connector, for the CONNECTOR_CALL
 * step (1.1.0).
 *
 * <p>
 * Per connector, not per host or per type: a dead CRM must not stop the bank
 * API, and two connectors of one type can point at two different systems.
 * The numbers are ADR 0010's: a count-based window of the last 20 calls, at
 * least 10 of them before the rate means anything, open at a 50 % failure
 * rate, stay open 15 s, then let 3 trial calls through (half-open) to decide
 * whether to close again.
 *
 * <p>
 * Only a failure that says the system is unwell counts
 * ({@link #countsAsFailure}): no answer, a 5xx or 429, a timeout. A 4xx is the
 * system speaking — a wrong account number, refused credentials — and is
 * recorded as a success: opening the breaker on it would cut off every other
 * journey because one user typed a bad number.
 *
 * <p>
 * The host may read {@link #registry()} for metrics and health; the breakers
 * are named by the connector id.
 */
@Slf4j
@Component
public class ConnectorCircuitBreakers {

    /** ADR 0010: the last 20 calls. */
    public static final int SLIDING_WINDOW = 20;
    /** ADR 0010: no verdict before 10 calls. */
    public static final int MINIMUM_CALLS = 10;
    /** ADR 0010: open at 50 % failures. */
    public static final float FAILURE_RATE_THRESHOLD = 50f;
    /** ADR 0010: stay open 15 s. */
    public static final Duration OPEN_WAIT = Duration.ofSeconds(15);
    /** ADR 0010: 3 trial calls while half-open. */
    public static final int HALF_OPEN_CALLS = 3;

    private final CircuitBreakerRegistry registry;
    private final List<Consumer<UUID>> openListeners = new CopyOnWriteArrayList<>();

    /** Production: the ADR 0010 numbers. */
    public ConnectorCircuitBreakers() {
        this(defaultConfig());
    }

    /** For tests: a small window and a short wait, so a breaker opens and recovers within a test. */
    public ConnectorCircuitBreakers(CircuitBreakerConfig config) {
        this.registry = CircuitBreakerRegistry.of(config);
        // Every breaker gets one transition listener, when it is created; the
        // listener fans out to whatever onOpen registered, before or after.
        this.registry.getEventPublisher().onEntryAdded(event -> attach(event.getAddedEntry()));
    }

    /** The ADR 0010 configuration. */
    public static CircuitBreakerConfig defaultConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(SLIDING_WINDOW)
                .minimumNumberOfCalls(MINIMUM_CALLS)
                .failureRateThreshold(FAILURE_RATE_THRESHOLD)
                .waitDurationInOpenState(OPEN_WAIT)
                .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_CALLS)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }

    /** The breaker of this connector, created closed on first use. */
    public CircuitBreaker of(UUID connectorId) {
        return registry.circuitBreaker(connectorId.toString());
    }

    /** The state of this connector's breaker; empty when it has never been used. */
    public Optional<CircuitBreaker.State> state(UUID connectorId) {
        return registry.find(connectorId.toString()).map(CircuitBreaker::getState);
    }

    /** The registry, for the host's metrics and health. */
    public CircuitBreakerRegistry registry() {
        return registry;
    }

    /**
     * Calls {@code listener} with the connector id each time a breaker
     * transitions to OPEN (from CLOSED or from HALF_OPEN). The step registers
     * {@code ConnectorPort::invalidate} here, so the next call after the wait
     * resolves the connector afresh: a rotated secret or a corrected base URL
     * is often why it was failing.
     */
    public void onOpen(Consumer<UUID> listener) {
        openListeners.add(listener);
    }

    /**
     * Whether a failed call counts against the connector's breaker: an
     * {@link ConnectorException} with code {@link ConnectorErrorCodes#UNAVAILABLE}
     * or {@link ConnectorErrorCodes#TIMEOUT}. Everything else the system
     * answered (a 4xx, refused credentials, a mapped code) and everything that
     * failed before a request was sent (refused egress, invalid inputs) does not.
     * A {@code CallNotPermittedException} is never passed here: an open breaker
     * sends nothing.
     */
    public static boolean countsAsFailure(Throwable t) {
        if (t instanceof ConnectorException e) {
            return ConnectorErrorCodes.UNAVAILABLE.equals(e.code())
                    || ConnectorErrorCodes.TIMEOUT.equals(e.code());
        }
        return false;
    }

    private void attach(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() != CircuitBreaker.State.OPEN) {
                return;
            }
            UUID id;
            try {
                id = UUID.fromString(breaker.getName());
            } catch (IllegalArgumentException e) {
                return;
            }
            log.warn("Connector {} circuit breaker opened ({})", id, event.getStateTransition());
            for (Consumer<UUID> listener : openListeners) {
                try {
                    listener.accept(id);
                } catch (RuntimeException e) {
                    log.warn("Connector {} open listener failed: {}", id, e.getMessage());
                }
            }
        });
    }
}
