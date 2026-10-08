package com.itways.assistant.journey.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One journey step's user-visible text, in one language.
 *
 * <p>
 * Only the fields a user can actually read. Everything else on a step —
 * {@code conditionExpression}, the non-text parts of {@code apiConfig}, an
 * API_CALL's URL — is machine-facing, and letting a translation reach those
 * would turn a wording change into a behaviour change. That is the whole reason
 * this is a narrow record rather than a second copy of the step.
 *
 * @param message             the step's prompt or closing line
 * @param actionTarget        RESPONSE step body only; on every other step type
 *                            this field is a URL, a template id or an intent
 *                            code, and translating it would break the step
 * @param confirmationMessage USER_INPUT's INTERACTIVE confirmation line
 * @param instruction         HUMAN_APPROVAL's instruction to the approver
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepText(
        String message,
        String actionTarget,
        String confirmationMessage,
        String instruction) {

    @JsonIgnore
    public boolean isEmpty() {
        return isBlank(message) && isBlank(actionTarget) && isBlank(confirmationMessage) && isBlank(instruction);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
