package com.itways.assistant.journey.model.connector;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The descriptor of one kind of external system: how to authenticate, what a
 * connector of it is configured with, and which operations it offers.
 * Written by hand or drafted from an OpenAPI document or an MCP server's tool
 * list; stored and versioned by journey-service; read by the portal (the
 * configuration form), by the engine (the step) and by the transport (the
 * call).
 *
 * <p>
 * {@code fields} and {@code rules} are the portal's {@code FormFieldConfig[]}
 * and its form rules, kept as plain maps here: this side never interprets them
 * beyond a field's {@code name} and {@code type}.
 *
 * <p>
 * Two transports (1.2.0): {@link #TRANSPORT_REST} (HTTPS JSON APIs; an
 * operation is a method and a path) and {@link #TRANSPORT_MCP} (a remote Model
 * Context Protocol server over Streamable HTTP; an operation is one named tool,
 * the connector's base URL is the server's endpoint). The {@link #mcp}
 * settings apply to the second only.
 *
 * @param key               stable identifier, e.g. {@code mock-bank-rest}
 * @param name              label
 * @param nameAr            Arabic label
 * @param transport         the transport kind: {@code REST} or {@code MCP}
 * @param descriptorVersion the version of this descriptor's content
 * @param auth              the authentication scheme
 * @param fields            the configuration fields a connector fills in
 * @param rules             the form rules between those fields
 * @param defaults          timeouts, retry and static headers
 * @param test              which operation the Test button runs
 * @param idempotency       the header that carries an idempotency key, for every write of this type (REST)
 * @param operations        the callable operations
 * @param mcp               the MCP transport's settings; null for REST
 * @param systems           (1.3.0) the systems behind this type's one connection, each grouping operations
 *                          under a path prefix and headers; null or empty when the type is one system
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectorDescriptor(
        String key,
        String name,
        String nameAr,
        String transport,
        Integer descriptorVersion,
        AuthScheme auth,
        List<Map<String, Object>> fields,
        List<Map<String, Object>> rules,
        ConnectorDefaults defaults,
        TestOperation test,
        Idempotency idempotency,
        List<ConnectorOperation> operations,
        McpSettings mcp,
        List<ConnectorSystem> systems) {

    public static final String TRANSPORT_REST = "REST";
    /** Since 1.2.0: a remote MCP server over Streamable HTTP. */
    public static final String TRANSPORT_MCP = "MCP";

    /** The field type a descriptor gives a write-only, sealed value. */
    public static final String FIELD_TYPE_SECRET = "secret";

    /** Jackson reads the full shape; the 1.1.0 and 1.2.0 constructors below keep older callers compiling. */
    @JsonCreator
    public ConnectorDescriptor {
    }

    /** The 1.2.0 shape (no systems). */
    public ConnectorDescriptor(String key, String name, String nameAr, String transport, Integer descriptorVersion,
            AuthScheme auth, List<Map<String, Object>> fields, List<Map<String, Object>> rules,
            ConnectorDefaults defaults, TestOperation test, Idempotency idempotency,
            List<ConnectorOperation> operations, McpSettings mcp) {
        this(key, name, nameAr, transport, descriptorVersion, auth, fields, rules, defaults, test, idempotency,
                operations, mcp, null);
    }

    /** The 1.1.0 shape (no MCP settings): a REST descriptor, or an MCP one with the protocol negotiated. */
    public ConnectorDescriptor(String key, String name, String nameAr, String transport, Integer descriptorVersion,
            AuthScheme auth, List<Map<String, Object>> fields, List<Map<String, Object>> rules,
            ConnectorDefaults defaults, TestOperation test, Idempotency idempotency,
            List<ConnectorOperation> operations) {
        this(key, name, nameAr, transport, descriptorVersion, auth, fields, rules, defaults, test, idempotency,
                operations, null, null);
    }

    /** Which operation the Test button runs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestOperation(String operation) {
    }

    /** The header an idempotency key is sent in, for writes that declare none of their own. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Idempotency(String header) {
    }

    /**
     * The MCP transport's settings (1.2.0).
     *
     * <p>
     * {@code protocolVersion} pins the specification revision the transport
     * speaks to this server. Null (the default) lets the transport negotiate:
     * it tries the current stateless revision first and falls back to the
     * legacy initialize handshake when the server refuses it. Set it when a
     * server misbehaves under negotiation.
     *
     * @param protocolVersion one of {@link #SUPPORTED_PROTOCOL_VERSIONS}, or null to negotiate
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpSettings(String protocolVersion) {

        /** The current revision: stateless Streamable HTTP, no handshake, no session. */
        public static final String PROTOCOL_2026_07_28 = "2026-07-28";
        /** Legacy revisions: {@code initialize} handshake, optional {@code Mcp-Session-Id}. */
        public static final String PROTOCOL_2025_11_25 = "2025-11-25";
        public static final String PROTOCOL_2025_06_18 = "2025-06-18";
        public static final String PROTOCOL_2025_03_26 = "2025-03-26";

        /** The revisions the transport implements, newest first. */
        public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(PROTOCOL_2026_07_28,
                PROTOCOL_2025_11_25, PROTOCOL_2025_06_18, PROTOCOL_2025_03_26);

        /** The revisions that use the legacy handshake. */
        public static final Set<String> LEGACY_PROTOCOL_VERSIONS = Set.of(PROTOCOL_2025_11_25, PROTOCOL_2025_06_18,
                PROTOCOL_2025_03_26);

        /** Negotiate. */
        public static final McpSettings NONE = new McpSettings(null);

        /** Whether {@code version} is one the transport implements. */
        public static boolean isSupported(String version) {
            return version != null && SUPPORTED_PROTOCOL_VERSIONS.contains(version.trim());
        }

        /** Whether {@code version} needs the legacy initialize handshake. */
        public static boolean isLegacy(String version) {
            return version != null && LEGACY_PROTOCOL_VERSIONS.contains(version.trim());
        }
    }

    public AuthScheme authOrNone() {
        return auth != null ? auth : AuthScheme.NONE;
    }

    public ConnectorDefaults defaultsOrNone() {
        return defaults != null ? defaults : ConnectorDefaults.NONE;
    }

    public McpSettings mcpOrNone() {
        return mcp != null ? mcp : McpSettings.NONE;
    }

    public List<ConnectorOperation> operationsOrEmpty() {
        return operations != null ? operations : List.of();
    }

    public List<ConnectorSystem> systemsOrEmpty() {
        return systems != null ? systems : List.of();
    }

    /** The system with this key (1.3.0). */
    public Optional<ConnectorSystem> system(String systemKey) {
        if (systemKey == null) {
            return Optional.empty();
        }
        return systemsOrEmpty().stream().filter(s -> s != null && systemKey.equals(s.key())).findFirst();
    }

    /** The system {@code operation} belongs to; empty when it names none (or one this type does not declare). */
    public Optional<ConnectorSystem> systemOf(ConnectorOperation operation) {
        return operation == null ? Optional.empty() : system(operation.system());
    }

    public List<Map<String, Object>> fieldsOrEmpty() {
        return fields != null ? fields : List.of();
    }

    /** Whether this type speaks MCP (anything else is REST, the 1.1.0 default). */
    public boolean speaksMcp() {
        return TRANSPORT_MCP.equals(transport);
    }

    /** The operation with this key. */
    public Optional<ConnectorOperation> operation(String operationKey) {
        if (operationKey == null) {
            return Optional.empty();
        }
        return operationsOrEmpty().stream().filter(op -> operationKey.equals(op.key())).findFirst();
    }

    /**
     * The header an idempotency key for {@code operation} is sent in: the
     * operation's own, else the type's; null when neither declares one.
     */
    public String idempotencyHeaderFor(ConnectorOperation operation) {
        if (operation != null && operation.idempotencyHeader() != null && !operation.idempotencyHeader().isBlank()) {
            return operation.idempotencyHeader();
        }
        if (idempotency != null && idempotency.header() != null && !idempotency.header().isBlank()) {
            return idempotency.header();
        }
        return null;
    }

    /**
     * Whether a call of {@code operation} has somewhere to put an idempotency
     * key: a header (REST, {@link #idempotencyHeaderFor}) or a tool argument
     * (MCP, {@link ConnectorOperation#idempotencyArgument()}). The engine
     * computes a key only when this is true.
     */
    public boolean carriesIdempotencyKey(ConnectorOperation operation) {
        if (speaksMcp()) {
            return operation != null && operation.idempotencyArgument() != null
                    && !operation.idempotencyArgument().isBlank();
        }
        return idempotencyHeaderFor(operation) != null;
    }

    /** The names of the configuration fields of type {@code secret}. */
    public List<String> secretFieldNames() {
        return fieldsOrEmpty().stream()
                .filter(field -> FIELD_TYPE_SECRET.equals(field.get("type")))
                .map(field -> field.get("name"))
                .filter(name -> name != null)
                .map(String::valueOf)
                .toList();
    }

    /**
     * The operation the Test button runs: {@code test.operation} when declared,
     * else the first idempotent GET (REST). An MCP type without a declared test
     * operation has none here: its transport lists the server's tools instead.
     */
    public Optional<ConnectorOperation> testOperation() {
        if (test != null && test.operation() != null) {
            return operation(test.operation());
        }
        if (speaksMcp()) {
            return Optional.empty();
        }
        return operationsOrEmpty().stream()
                .filter(op -> "GET".equals(op.methodOrGet()) && op.isIdempotent())
                .findFirst();
    }
}
