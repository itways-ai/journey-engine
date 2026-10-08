package com.itways.assistant.journey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
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

    /**
     * The step's stable name for references: {@code {{steps.<stepKey>.output}}},
     * a JUMP target, a {@code steps.<stepKey>.} path in a condition.
     *
     * <p>
     * Unlike {@link #stepOrder}, which is the step's position and changes
     * whenever a step is inserted, removed or moved above it, a key is assigned
     * once — derived from the step's name when it is first saved — and kept
     * for good. References written with keys therefore survive any reordering
     * without being rewritten. Lower case letters, digits and underscores,
     * starting with a letter, so a key can never be mistaken for an order.
     *
     * <p>
     * Null in versions published before keys existed; their references are by
     * order, which the engine still resolves.
     */
    private String stepKey;

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
