package com.itways.assistant.journey.model.connector;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Locale;
import java.util.Map;

/**
 * One callable unit of a connector type: {@code getAccountBalance},
 * {@code stopCard}; for an MCP type, one tool of the server.
 *
 * <p>
 * REST: the path is relative to the connector's base URL and may carry
 * {@code {param}} placeholders, each of which must be an input property with
 * {@code in: path}. An operation that is not idempotent should declare an
 * {@code idempotencyHeader} (or the type a default one): the engine replays
 * steps after a backward JUMP and sends the same key again, so the external
 * system can de-duplicate.
 *
 * <p>
 * MCP (1.2.0): {@code method} and {@code path} are not used; {@code toolName}
 * names the server's tool (the operation key when absent) and every input
 * property is a tool argument. MCP has no idempotency header, so a write that
 * can take the key as an argument names it in {@code idempotencyArgument};
 * {@code idempotent} must be declared explicitly (a discovered tool starts as
 * {@code false} until an admin says otherwise).
 *
 * @param key                 stable identifier, unique within the type
 * @param name                label for the builder
 * @param nameAr              Arabic label
 * @param method              REST: {@code GET}, {@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}
 * @param path                REST: path template under the base URL
 * @param riskLevel           legacy and ignored ({@link RiskLevel}): kept as stored, optional, never required
 * @param idempotent          whether repeating the call is safe; the only operations the engine retries
 * @param idempotencyHeader   REST: the header the key is sent in, when this operation has its own
 * @param input               the input schema (an object)
 * @param output              the output schema (an object; what the variable picker shows)
 * @param errors              HTTP status → stable error code, e.g. {@code "404": "ACCOUNT_NOT_FOUND"}
 * @param response            how the answer is read beyond its JSON body
 * @param timeoutMs           this operation's read timeout, over the type's default
 * @param toolName            MCP: the tool's name on the server; the key when absent
 * @param idempotencyArgument MCP: the tool argument that carries the idempotency key, when the tool takes one
 * @param system              (1.3.0) the key of the {@link ConnectorSystem} this operation belongs to; null when ungrouped
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectorOperation(
        String key,
        String name,
        String nameAr,
        String method,
        String path,
        RiskLevel riskLevel,
        Boolean idempotent,
        String idempotencyHeader,
        SchemaNode input,
        SchemaNode output,
        Map<String, String> errors,
        ResponseRules response,
        Integer timeoutMs,
        String toolName,
        String idempotencyArgument,
        String system) {

    /** Jackson reads the full shape; the 1.1.0 and 1.2.0 constructors below keep older callers compiling. */
    @JsonCreator
    public ConnectorOperation {
    }

    /** The 1.2.0 shape (no system). */
    public ConnectorOperation(String key, String name, String nameAr, String method, String path, RiskLevel riskLevel,
            Boolean idempotent, String idempotencyHeader, SchemaNode input, SchemaNode output,
            Map<String, String> errors, ResponseRules response, Integer timeoutMs, String toolName,
            String idempotencyArgument) {
        this(key, name, nameAr, method, path, riskLevel, idempotent, idempotencyHeader, input, output, errors, response,
                timeoutMs, toolName, idempotencyArgument, null);
    }

    /** The 1.1.0 shape (no MCP fields). */
    public ConnectorOperation(String key, String name, String nameAr, String method, String path, RiskLevel riskLevel,
            Boolean idempotent, String idempotencyHeader, SchemaNode input, SchemaNode output,
            Map<String, String> errors, ResponseRules response, Integer timeoutMs) {
        this(key, name, nameAr, method, path, riskLevel, idempotent, idempotencyHeader, input, output, errors, response,
                timeoutMs, null, null, null);
    }

    /** How a response is read beyond its JSON body. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResponseRules(
            /** A header whose value becomes output {@code id} (OData's {@code OData-EntityId}). */
            String idFromHeader) {
    }

    /** The method in upper case, {@code GET} when absent. */
    public String methodOrGet() {
        return method == null || method.isBlank() ? "GET" : method.toUpperCase(Locale.ROOT);
    }

    /** Whether repeating this call is safe: declared so, or a GET that declares nothing. */
    public boolean isIdempotent() {
        if (idempotent != null) {
            return idempotent;
        }
        return "GET".equals(methodOrGet());
    }

    /**
     * Whether repeating this call is safe when nothing may be assumed from a
     * method: declared {@code true}, and nothing else. What the MCP transport
     * reads, since every tool call is a POST.
     */
    public boolean declaredIdempotent() {
        return Boolean.TRUE.equals(idempotent);
    }

    /** MCP: the tool's name on the server, the operation key when the descriptor names none. */
    public String toolNameOrKey() {
        return toolName == null || toolName.isBlank() ? key : toolName.trim();
    }

    public SchemaNode inputOrEmpty() {
        return input != null ? input : SchemaNode.EMPTY_OBJECT;
    }

    public SchemaNode outputOrEmpty() {
        return output != null ? output : SchemaNode.EMPTY_OBJECT;
    }

    public Map<String, String> errorsOrEmpty() {
        return errors != null ? errors : Map.of();
    }

    /** Legacy: the stored {@link RiskLevel}, {@code LOW} when absent. Nothing gates on it. */
    public RiskLevel riskLevelOrLow() {
        return riskLevel != null ? riskLevel : RiskLevel.LOW;
    }
}
