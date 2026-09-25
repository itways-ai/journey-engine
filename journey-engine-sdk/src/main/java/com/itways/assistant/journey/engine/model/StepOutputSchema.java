package com.itways.assistant.journey.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepOutputSchema {
    private String stepType;
    @Builder.Default
    private List<OutputField> fields = new ArrayList<>();
    private boolean writesToState;

    public static StepOutputSchema empty(String stepType) {
        return StepOutputSchema.builder().stepType(stepType).fields(new ArrayList<>()).build();
    }
}
