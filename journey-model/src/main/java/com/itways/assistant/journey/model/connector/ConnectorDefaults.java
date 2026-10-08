package com.itways.assistant.journey.model.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * A connector type's defaults for every call: timeouts, the retry policy for
 * idempotent operations and headers sent on every request.
 *
 * @param connectTimeoutMs TCP connect timeout; the transport caps it at 3 000
 * @param readTimeoutMs    time to a complete answer; 10 000 unless said, capped at 30 000
 * @param retry            how idempotent operations are retried
 * @param headers          static headers on every request ({@code Accept}, {@code OData-Version});
 *                         never credentials — the validator refuses a header that looks like one
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectorDefaults(
        Integer connectTimeoutMs,
        Integer readTimeoutMs,
        Retry retry,
        Map<String, String> headers) {

    /** The retry policy for idempotent operations: attempts and the first back-off. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Retry(Integer maxAttempts, Integer baseDelayMs) {
    }

    /** No defaults declared. */
    public static final ConnectorDefaults NONE = new ConnectorDefaults(null, null, null, null);

    public Map<String, String> headersOrEmpty() {
        return headers != null ? headers : Map.of();
    }
}
