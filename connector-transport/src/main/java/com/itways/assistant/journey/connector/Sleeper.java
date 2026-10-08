package com.itways.assistant.journey.connector;

import java.time.Duration;

/** Waits between retry attempts; the real clock in production, a recorder in tests. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    Sleeper REAL = duration -> Thread.sleep(Math.max(0, duration.toMillis()));

    /** Never waits: for tests that assert the back-off schedule without paying for it. */
    Sleeper NONE = duration -> {
    };
}
