package com.itways.assistant.journey.connector;

import java.util.Map;

/**
 * A completed call: the status, the parsed body, the response headers and
 * what it cost. The body is the JSON as Jackson reads it (a {@code Map}, a
 * {@code List}, a scalar) or null for an empty answer; it is not yet masked or
 * limited to the output schema — {@link OutputMasker} does that for the caller
 * that stores it.
 *
 * @param status    the HTTP status (2xx; anything else is an {@link ConnectorException})
 * @param body      the parsed JSON body, or null
 * @param headers   the response headers, first value each, names in lower case
 * @param attempts  how many requests were sent (1 unless retried)
 * @param latencyMs wall-clock time of the whole call, retries included
 */
public record CallResult(int status, Object body, Map<String, String> headers, int attempts, long latencyMs) {

    public Map<String, String> headersOrEmpty() {
        return headers != null ? headers : Map.of();
    }
}
