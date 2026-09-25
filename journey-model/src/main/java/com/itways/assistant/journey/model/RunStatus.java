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
    ERROR
}
