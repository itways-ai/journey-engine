package com.itways.assistant.journey.model.catalog;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
