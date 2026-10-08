package com.itways.assistant.journey.connector.http;

import java.time.Duration;

/**
 * One call's time budget, counted from the moment the transport was asked:
 * every attempt's response timeout is cut to what remains, and a retry that
 * could not finish inside it is not started. Shared by the transports.
 */
public final class Deadline {

    private final long startNanos;
    private final Duration budget;

    private Deadline(long startNanos, Duration budget) {
        this.startNanos = startNanos;
        this.budget = budget;
    }

    public static Deadline start(Duration budget) {
        return new Deadline(System.nanoTime(), budget);
    }

    public Duration budget() {
        return budget;
    }

    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    public long elapsedMs() {
        return elapsed().toMillis();
    }

    /** What is left of the budget; zero (never negative) once it is spent. */
    public Duration remaining() {
        Duration left = budget.minus(elapsed());
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Whether waiting {@code delay} and then sending at least one more millisecond of work still fits. */
    public boolean allows(Duration delay) {
        return elapsed().plus(delay).plusMillis(1).compareTo(budget) <= 0;
    }
}
