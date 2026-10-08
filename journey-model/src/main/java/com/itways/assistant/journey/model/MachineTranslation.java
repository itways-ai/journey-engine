package com.itways.assistant.journey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A step's text translated by the engine at run time, handed back to
 * journey-service so the next run does not pay for it again
 * ({@code PUT /api/journeys/steps/{stepId}/translations/machine}).
 *
 * <p>
 * Stored as a MACHINE row, which a human translation always outranks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MachineTranslation(
        String locale,
        String message,
        String actionTarget,
        String confirmationMessage,
        String instruction) {

    public static MachineTranslation of(String locale, StepText text) {
        return new MachineTranslation(locale, text.message(), text.actionTarget(), text.confirmationMessage(),
                text.instruction());
    }
}
