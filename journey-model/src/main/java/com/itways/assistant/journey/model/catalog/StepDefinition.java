package com.itways.assistant.journey.model.catalog;

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
    /**
     * True when a child with branch name {@code error} runs after this step
     * fails (1.1.0). The builder offers an error path only for these types.
     */
    private boolean supportsErrorBranch;
    /**
     * True for a type kept for published journeys but hidden from the step
     * picker (1.1.0: API_CALL). {@link #replacedBy} names what to use instead.
     */
    private boolean deprecated;
    private String replacedBy;

    public static StepDefinition of(String type) {
        return StepDefinition.builder().type(type).label(type).build();
    }
}
