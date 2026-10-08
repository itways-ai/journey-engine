package com.itways.assistant.journey.connector;

import java.time.Duration;

/**
 * How one call is run: its time budget, how often an idempotent operation may
 * be tried and the idempotency key a write carries.
 *
 * @param budget         the whole call including retries must end within this; the transport
 *                       stops retrying early rather than exceed it
 * @param readTimeout    time to a complete answer per attempt; null means the descriptor's
 *                       default (10 s), capped at 30 s
 * @param maxAttempts    1..3; more than one is honoured only when the operation is idempotent
 *                       or an idempotency key is given
 * @param idempotencyKey the key sent in the operation's idempotency header on every attempt;
 *                       null sends none
 */
public record CallOptions(Duration budget, Duration readTimeout, int maxAttempts, String idempotencyKey) {

    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(30);
    public static final int MAX_ATTEMPTS = 3;

    public CallOptions {
        if (budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("budget must be positive");
        }
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("maxAttempts must be 1.." + MAX_ATTEMPTS + ", got " + maxAttempts);
        }
        if (readTimeout != null && (readTimeout.isNegative() || readTimeout.isZero())) {
            throw new IllegalArgumentException("readTimeout must be positive");
        }
    }

    /** One attempt within {@code budget}, no idempotency key: what the Test button uses. */
    public static CallOptions single(Duration budget) {
        return new CallOptions(budget, null, 1, null);
    }
}
