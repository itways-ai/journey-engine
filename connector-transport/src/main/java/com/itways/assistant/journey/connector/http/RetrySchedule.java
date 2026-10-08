package com.itways.assistant.journey.connector.http;

import com.itways.assistant.journey.connector.CallOptions;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.model.connector.ConnectorDefaults;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When a failed attempt is sent again, and how long to wait first.
 *
 * <p>
 * A second attempt is only ever made when sending the same request twice is
 * harmless: the operation is idempotent, or the call carries an idempotency key
 * the system de-duplicates on. Only failures that say nothing about the request
 * itself qualify ({@link #retryable}): no connection, no answer in time, 429,
 * 502, 503, 504. A 500 is not retried — the system may well have acted before
 * it failed — and nor is any other 4xx, a refused egress, invalid inputs or a
 * credential the system rejected.
 *
 * <p>
 * The wait doubles from the type's {@code retry.baseDelayMs} (200 ms unless
 * said) with ±25 % jitter, so many journeys hitting one struggling system do
 * not all come back in the same millisecond.
 */
public final class RetrySchedule {

    public static final long DEFAULT_BASE_DELAY_MS = 200;

    private final int maxAttempts;
    private final long baseDelayMs;

    public RetrySchedule(ConnectorDefaults defaults, ConnectorOperation operation, CallOptions options) {
        this(defaults, operation.isIdempotent() || options.idempotencyKey() != null, options);
    }

    /**
     * With the "safe to repeat" verdict made by the caller: the MCP transport
     * reads an explicit {@code idempotent: true} and accepts a key only when
     * the tool has an argument to carry it.
     */
    public RetrySchedule(ConnectorDefaults defaults, boolean safeToRepeat, CallOptions options) {
        this.maxAttempts = safeToRepeat ? Math.min(options.maxAttempts(), CallOptions.MAX_ATTEMPTS) : 1;
        ConnectorDefaults.Retry retry = defaults != null ? defaults.retry() : null;
        this.baseDelayMs = retry != null && retry.baseDelayMs() != null && retry.baseDelayMs() > 0
                ? retry.baseDelayMs()
                : DEFAULT_BASE_DELAY_MS;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Whether another attempt may follow {@code attemptsSoFar} failed ones (the failure permitting). */
    public boolean allowsAnother(int attemptsSoFar) {
        return attemptsSoFar < maxAttempts;
    }

    /** The wait after attempt number {@code failedAttempt} (1-based): base × 2^(n−1), ±25 %. */
    public Duration delayAfter(int failedAttempt) {
        long nominal = baseDelayMs << Math.max(0, Math.min(failedAttempt - 1, 20));
        double jitter = 1 + (ThreadLocalRandom.current().nextDouble() * 0.5 - 0.25);
        return Duration.ofMillis(Math.round(nominal * jitter));
    }

    /** Whether {@code failure} is one of the transient kinds another attempt may get past. */
    public static boolean retryable(ConnectorException failure) {
        return failure.retryable() && (ConnectorErrorCodes.TIMEOUT.equals(failure.code())
                || ConnectorErrorCodes.UNAVAILABLE.equals(failure.code()));
    }
}
