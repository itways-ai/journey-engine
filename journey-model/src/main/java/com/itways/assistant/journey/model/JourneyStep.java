package com.itways.assistant.journey.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One step of a journey — the same shape in the builder, in a published version
 * and in the engine.
 *
 * <p>
 * {@code actionType} is a step type code from the engine's catalog
 * ({@code GET /api/speech/step-types/catalog}); it stays a string because new
 * step types ship with the engine, not with this model.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneyStep {
    private Long id;
    private int stepOrder;
    private String stepName;
    private String actionType;
    private String actionTarget;
    private String requiredParams;
    private String apiConfig; // JSON configuration for generic API calls
    private boolean continueOnError;

    public String getActionType() {
        return actionType;
    }

    public String getActionTarget() {
        return actionTarget;
    }

    public String getRequiredParams() {
        return requiredParams;
    }

    public boolean isContinueOnError() {
        return continueOnError;
    }

    private String conditionExpression;
    private String branchName;
    private Integer parentOrder;
    private List<Integer> parentOrders;
    private String message;
    @Builder.Default
    private Boolean clientVisible = true; // Visibility in Chat UI

    public boolean isClientVisible() {
        return clientVisible == null || clientVisible;
    }
}
