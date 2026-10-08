package com.itways.assistant.journey.model.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One configured connector as the engine receives it for one call: the
 * pinned descriptor version, the base URL, the hosts it may dial, its
 * configuration values and its secrets, opened.
 *
 * <p>
 * This is the body of journey-service's internal resolve endpoint
 * ({@code GET /api/journeys/internal/connectors/{id}/resolve}) and the only
 * place a plaintext secret travels. The receiving side keeps it in memory for
 * the call (and a short cache) and never writes it anywhere: not into the run
 * context, run history or a log. {@link #toString()} prints the secrets' field
 * names only.
 *
 * @param id           the connector
 * @param name         its label, for logs and messages
 * @param typeKey      the connector type
 * @param typeVersion  the descriptor version this connector is pinned to
 * @param descriptor   that version's descriptor
 * @param baseUrl      the system's base URL ({@code https://...}, no query, no user info)
 * @param allowedHosts the hosts this connector may dial; null or empty means the base URL's host
 * @param config       the non-secret configuration values, by field name
 * @param secrets      the secret configuration values, opened, by field name
 * @param lockVersion  the connector row's version: a change invalidates any cache of this
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResolvedConnector(
        UUID id,
        String name,
        String typeKey,
        Integer typeVersion,
        ConnectorDescriptor descriptor,
        String baseUrl,
        List<String> allowedHosts,
        Map<String, Object> config,
        Map<String, String> secrets,
        Long lockVersion) {

    public Map<String, Object> configOrEmpty() {
        return config != null ? config : Map.of();
    }

    public Map<String, String> secretsOrEmpty() {
        return secrets != null ? secrets : Map.of();
    }

    public List<String> allowedHostsOrEmpty() {
        return allowedHosts != null ? allowedHosts : List.of();
    }

    /** The operation with this key in the pinned descriptor. */
    public Optional<ConnectorOperation> operation(String operationKey) {
        return descriptor != null ? descriptor.operation(operationKey) : Optional.empty();
    }

    /**
     * The value of a configuration field by name: a secret when one is stored
     * under it, else the config value as text; null when neither.
     */
    public String fieldValue(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        String secret = secretsOrEmpty().get(fieldName);
        if (secret != null) {
            return secret;
        }
        Object value = configOrEmpty().get(fieldName);
        return value != null ? String.valueOf(value) : null;
    }

    /** Field names only: a secret value must never end up in a log line by accident. */
    @Override
    public String toString() {
        return "ResolvedConnector[id=" + id + ", name=" + name + ", typeKey=" + typeKey + ", typeVersion="
                + typeVersion + ", baseUrl=" + baseUrl + ", allowedHosts=" + allowedHostsOrEmpty()
                + ", configFields=" + configOrEmpty().keySet() + ", secretFields=" + secretsOrEmpty().keySet()
                + ", lockVersion=" + lockVersion + "]";
    }
}
