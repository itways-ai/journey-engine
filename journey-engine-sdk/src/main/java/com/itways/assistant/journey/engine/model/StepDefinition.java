package com.itways.assistant.journey.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepDefinition {
    private String type;
    private String category;
    private String label;
    private String icon;
    private String uiComponent;
    private boolean supportsBranches;
    private boolean waitsForInput;
    private boolean writesToState;
    /**
     * Per-channel support, e.g. {@code {"voice": "ADAPTED"}} — see
     * {@code ChannelSupport}. Filled by the registry when the catalog is served.
     */
    private java.util.Map<String, String> channels;

    public static StepDefinition of(String type) {
        return StepDefinition.builder().type(type).label(type).build();
    }
}
