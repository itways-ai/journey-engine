package com.itways.assistant.journey.model;

/**
 * A run's state as its history row records it, and as the engine reports it in
 * each lifecycle event.
 */
public enum RunStatus {
    RUNNING,
    /** Paused on a step that needs the user, an approver or a timer. */
    WAITING,
    COMPLETED,
    ERROR;

    /**
     * Whether the run has ended and will not continue. Only a final run has a
     * completion time: a WAITING run resumes later.
     */
    public boolean isFinal() {
        return this == COMPLETED || this == ERROR;
    }
}
