package com.itways.assistant.journey.model;

/**
 * How one step ended — the {@code status} of every entry in a run's step log.
 */
public enum StepStatus {
    SUCCESS,
    ERROR,
    /** The run pauses here and resumes on the next turn. */
    WAITING,
    /** Execution continues at another step order (JUMP, GO_TO, a failed branch). */
    JUMP
}
