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
public class ChannelVariableGroup {
    private String id;
    private String label;
    private List<String> platforms;
    @Builder.Default
    private List<OutputField> fields = new ArrayList<>();
}
