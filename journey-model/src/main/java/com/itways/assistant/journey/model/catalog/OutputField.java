package com.itways.assistant.journey.model.catalog;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputField {
    private String path;
    private String label;
    private String type;
    private boolean dynamic;
    /**
     * True when {@link #path} is already a full variable path and must not be
     * prefixed with {@code steps.<order>.} by the consumer. STATE_STORE writes to
     * the shared {@code state} bucket rather than its own step bucket.
     */
    private boolean absolute;
    private List<String> platforms;
    /**
     * True when the value is masked before it is stored (1.1.0: a connector
     * operation's output property flagged {@code sensitive}). The picker lists
     * it with a lock: a later step reads {@code ********}, never the value.
     */
    private boolean sensitive;

    public static OutputField of(String path, String label, String type) {
        return OutputField.builder().path(path).label(label).type(type).build();
    }

    public static OutputField dynamic(String path, String label, String type) {
        return OutputField.builder().path(path).label(label).type(type).dynamic(true).build();
    }

    public static OutputField absolute(String path, String label, String type) {
        return OutputField.builder().path(path).label(label).type(type).dynamic(true).absolute(true).build();
    }
}
