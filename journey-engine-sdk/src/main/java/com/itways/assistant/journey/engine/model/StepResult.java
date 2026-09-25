package com.itways.assistant.journey.engine.model;

import com.itways.assistant.journey.model.StepStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepResult {
    private StepStatus status;
    private Object data;
    private String message;
    private Map<String, Object> metadata;
    private String actionTarget;

    /**
     * What to say to the end user, when that differs from {@link #message}.
     *
     * <p>
     * Failure messages are the reason this exists. "TEMPLATE_RENDER: '{{id}}' is
     * not a template id" is exactly what an operator needs in run history and
     * exactly what a customer should never be shown, and it is untranslatable
     * besides, being half identifier. Handlers put the diagnostic in
     * {@code message} and a localized sentence here; the engine shows this one
     * to the user and keeps both in the step log.
     *
     * <p>
     * Null means {@code message} is already fit for a user to read, which is the
     * normal case for authored step text.
     */
    private String userMessage;

    public static StepResult success(Object data) {
        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(data)
                .build();
    }

    public static StepResult success(Object data, String message) {
        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(data)
                .message(message)
                .build();
    }

    public static StepResult error(String message) {
        return StepResult.builder()
                .status(StepStatus.ERROR)
                .message(message)
                .build();
    }

    /**
     * A failure with a diagnostic for the log and a separate sentence for the user.
     *
     * @param message     technical detail; goes to run history, never to the user
     * @param userMessage localized, user-safe explanation
     */
    public static StepResult error(String message, String userMessage) {
        return StepResult.builder()
                .status(StepStatus.ERROR)
                .message(message)
                .userMessage(userMessage)
                .build();
    }

    /** The text to show a user for this result. */
    public String userFacingMessage() {
        return userMessage != null ? userMessage : message;
    }

    public static StepResult waiting(String message, Map<String, Object> metadata) {
        return StepResult.builder()
                .status(StepStatus.WAITING)
                .message(message)
                .metadata(metadata)
                .build();
    }

    public static StepResult jump(int targetOrder, String message) {
        return StepResult.builder()
                .status(StepStatus.JUMP)
                .message(message)
                .metadata(Map.of("targetOrder", targetOrder))
                .data(targetOrder)
                .build();
    }
}
