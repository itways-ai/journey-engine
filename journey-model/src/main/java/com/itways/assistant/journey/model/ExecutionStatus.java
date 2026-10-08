package com.itways.assistant.journey.model;

/**
 * Where a paused-or-running execution instance stands — the {@code status} of a
 * journey instance row, which is what a resume claims.
 *
 * <p>
 * Distinct from {@link RunStatus}: an instance waits in
 * {@code WAITING_FOR_INPUT}, while the run's history row records
 * {@code WAITING}. Both values are stored, so neither can be renamed.
 */
public enum ExecutionStatus {
    RUNNING,
    WAITING_FOR_INPUT,
    COMPLETED,
    ERROR
}
