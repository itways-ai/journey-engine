package com.itways.assistant.journey.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One executed step, as run history stores it.
 *
 * @param id      the step's id in the journey version that ran
 * @param type    the step type code, e.g. {@code API_CALL}
 * @param message what the user was shown
 * @param detail  the technical reason behind a failure, when it differs from
 *                {@code message}. Never shown to a user; this is what an operator
 *                reads in history
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunStepLog(
        Long id,
        String type,
        String stepName,
        StepStatus status,
        String message,
        String detail,
        String outputPayload,
        Long durationMs,
        Instant startedAt,
        Instant completedAt) {
}
