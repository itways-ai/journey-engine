package com.itways.assistant.journey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * One lifecycle event of a run, as the engine's host reports it to
 * journey-service ({@code POST /api/journeys/history}).
 *
 * <p>
 * Idempotent on {@code executionId}: RUNNING, then WAITING or a terminal status,
 * each rewrite the same history row. The account is taken from the caller's
 * credential, never from the body.
 *
 * @param rootExecutionId the top-level run; equals {@code executionId} for a
 *                        run that is not a nested journey
 * @param contextData     JSON of the run's variables and step results, stored
 *                        as-is for support
 * @param stepLogs        every step this turn executed; null leaves the stored
 *                        steps alone, empty clears them
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunHistoryEvent(
        Long journeyId,
        RunStatus status,
        String executionId,
        String parentExecutionId,
        String rootExecutionId,
        String triggerIntent,
        String userId,
        String message,
        Long durationMs,
        Instant startedAt,
        Instant completedAt,
        String contextData,
        List<RunStepLog> stepLogs) {
}
