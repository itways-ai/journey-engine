package com.itways.assistant.journey.model.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An MCP type (1.2.0): one tool per operation, no method or path, idempotency
 * declared, a credential in a header only. Each rule is shown by breaking a
 * valid "mock CRM" descriptor in one place; the REST rules are untouched
 * ({@link ConnectorDescriptorValidatorTest}).
 */
class McpDescriptorValidatorTest {

    private static final AuthScheme BEARER = new AuthScheme(AuthScheme.SCHEME_BEARER, null, null, "token", null, null,
            null, null, null, null);

    private static final List<Map<String, Object>> FIELDS = List.of(field("baseUrl", "url"),
            field("token", ConnectorDescriptor.FIELD_TYPE_SECRET));

    private static final ConnectorOperation FIND_CONTACT = tool("findContact", "crm_find_contact", true, null,
            object(ordered("email", node("string", null, null)), List.of("email")),
            object(ordered("id", node("string", null, null), "phone", node("string", null, true)), List.of()));

    private static final ConnectorOperation CREATE_TICKET = tool("createTicket", null, false, "requestId",
            object(ordered("subject", node("string", null, null)), List.of("subject")), SchemaNode.EMPTY_OBJECT);

    private static ConnectorDescriptor crm(ConnectorOperation... operations) {
        return crm(BEARER, null, null, operations);
    }

    private static ConnectorDescriptor crm(AuthScheme auth, ConnectorDescriptor.McpSettings mcp,
            ConnectorDescriptor.Idempotency idempotency, ConnectorOperation... operations) {
        return new ConnectorDescriptor("mock-crm-mcp", "Mock CRM (MCP)", null, ConnectorDescriptor.TRANSPORT_MCP, 1,
                auth, FIELDS, List.of(), null, null, idempotency, List.of(operations), mcp);
    }

    @Test
    void theMockCrmIsValid() {
        assertThat(ConnectorDescriptorValidator.problems(crm(FIND_CONTACT, CREATE_TICKET))).isEmpty();
        assertThat(crm(FIND_CONTACT).speaksMcp()).isTrue();
        assertThat(FIND_CONTACT.toolNameOrKey()).isEqualTo("crm_find_contact");
        assertThat(CREATE_TICKET.toolNameOrKey()).isEqualTo("createTicket");
    }

    @Test
    void theOldConstructorsStillBuildARestDescriptor() {
        ConnectorOperation op = new ConnectorOperation("ping", "Ping", null, "GET", "/health", RiskLevel.LOW, true, null,
                null, SchemaNode.EMPTY_OBJECT, null, null, null);
        ConnectorDescriptor d = new ConnectorDescriptor("plain", "Plain", null, ConnectorDescriptor.TRANSPORT_REST, 1,
                AuthScheme.NONE, List.of(), List.of(), null, null, null, List.of(op));

        assertThat(d.mcp()).isNull();
        assertThat(d.speaksMcp()).isFalse();
        assertThat(op.toolName()).isNull();
        assertThat(op.idempotencyArgument()).isNull();
        assertThat(ConnectorDescriptorValidator.problems(d)).isEmpty();
    }

    @Test
    void anUnknownTransportIsRefused() {
        ConnectorDescriptor d = new ConnectorDescriptor("x", "X", null, "SOAP", 1, AuthScheme.NONE, List.of(),
                List.of(), null, null, null, List.of(FIND_CONTACT), null);

        assertThat(ConnectorDescriptorValidator.problems(d)).contains("transport: must be REST or MCP");
    }

    @Test
    void methodAndPathAreNotUsedByMcp() {
        ConnectorOperation withRest = new ConnectorOperation("findContact", "findContact", null, "POST", "/tools",
                RiskLevel.LOW, true, null, FIND_CONTACT.input(), FIND_CONTACT.output(), null, null, null, null, null);

        assertThat(ConnectorDescriptorValidator.problems(crm(withRest))).containsExactly(
                "operations[findContact].method: not used by the MCP transport (every tool call is a POST)",
                "operations[findContact].path: not used by the MCP transport (the base URL is the server's endpoint)");
    }

    @Test
    void idempotencyMustBeDeclaredForATool() {
        ConnectorOperation undeclared = tool("findContact", null, null, null, FIND_CONTACT.input(),
                FIND_CONTACT.output());

        assertThat(ConnectorDescriptorValidator.problems(crm(undeclared))).singleElement().asString()
                .startsWith("operations[findContact].idempotent: required for an MCP tool");
    }

    @Test
    void aNonIdempotentToolWithoutAnIdempotencyArgumentIsAcceptedButAHeaderIsNot() {
        ConnectorOperation plainWrite = tool("createTicket", null, false, null, CREATE_TICKET.input(),
                SchemaNode.EMPTY_OBJECT);
        assertThat(ConnectorDescriptorValidator.problems(crm(plainWrite))).isEmpty();

        ConnectorOperation withHeader = new ConnectorOperation("createTicket", "createTicket", null, null, null,
                RiskLevel.HIGH, false, "Idempotency-Key", CREATE_TICKET.input(), SchemaNode.EMPTY_OBJECT, null, null,
                null, null, null);
        assertThat(ConnectorDescriptorValidator.problems(crm(withHeader))).containsExactly(
                "operations[createTicket].idempotencyHeader: not used by the MCP transport (declare idempotencyArgument)");

        assertThat(ConnectorDescriptorValidator.problems(
                crm(BEARER, null, new ConnectorDescriptor.Idempotency("Idempotency-Key"), FIND_CONTACT)))
                .containsExactly("idempotency.header: not used by the MCP transport (declare idempotencyArgument on the operation)");
    }

    @Test
    void inputsAreArgumentsNotPathQueryOrHeader() {
        ConnectorOperation placed = tool("findContact", null, true, null,
                object(ordered("email", node("string", SchemaNode.IN_QUERY, null)), List.of()), SchemaNode.EMPTY_OBJECT);

        assertThat(ConnectorDescriptorValidator.problems(crm(placed))).containsExactly(
                "operations[findContact].input.properties[email].in: must be body or absent (an MCP input is a tool argument)");
    }

    @Test
    void toolNamesHaveNoWhitespace() {
        ConnectorOperation spaced = tool("findContact", "find contact", true, null, null, SchemaNode.EMPTY_OBJECT);

        assertThat(ConnectorDescriptorValidator.problems(crm(spaced)))
                .containsExactly("operations[findContact].toolName: must be 1 to 128 characters without whitespace");
    }

    @Test
    void basicAndApiKeyInTheQueryAreRefusedForMcp() {
        AuthScheme basic = new AuthScheme(AuthScheme.SCHEME_BASIC, null, null, null, "baseUrl", "token", null, null,
                null, null);
        assertThat(ConnectorDescriptorValidator.problems(crm(basic, null, null, FIND_CONTACT)))
                .containsExactly("auth.scheme: basic is not supported by the MCP transport");

        AuthScheme inQuery = new AuthScheme(AuthScheme.SCHEME_API_KEY, AuthScheme.IN_QUERY, "api_key", "token", null,
                null, null, null, null, null);
        assertThat(ConnectorDescriptorValidator.problems(crm(inQuery, null, null, FIND_CONTACT)))
                .containsExactly("auth.in: must be header for MCP (a credential never travels in the URL)");

        AuthScheme inHeader = new AuthScheme(AuthScheme.SCHEME_API_KEY, AuthScheme.IN_HEADER, "X-API-Key", "token",
                null, null, null, null, null, null);
        assertThat(ConnectorDescriptorValidator.problems(crm(inHeader, null, null, FIND_CONTACT))).isEmpty();
    }

    @Test
    void theProtocolVersionMustBeOneTheTransportSpeaks() {
        ConnectorDescriptor pinned = crm(BEARER, new ConnectorDescriptor.McpSettings("2025-11-25"), null, FIND_CONTACT);
        assertThat(ConnectorDescriptorValidator.problems(pinned)).isEmpty();

        ConnectorDescriptor odd = crm(BEARER, new ConnectorDescriptor.McpSettings("2024-11-05"), null, FIND_CONTACT);
        assertThat(ConnectorDescriptorValidator.problems(odd)).singleElement().asString()
                .startsWith("mcp.protocolVersion: must be one of [2026-07-28, 2025-11-25, 2025-06-18, 2025-03-26]");

        assertThat(ConnectorDescriptor.McpSettings.isLegacy("2025-06-18")).isTrue();
        assertThat(ConnectorDescriptor.McpSettings.isLegacy("2026-07-28")).isFalse();
    }

    @Test
    void mcpFieldsAreRefusedOnARestDescriptor() {
        ConnectorOperation restTool = new ConnectorOperation("ping", "Ping", null, "GET", "/health", RiskLevel.LOW,
                true, null, null, SchemaNode.EMPTY_OBJECT, null, null, null, "ping_tool", "requestId");
        ConnectorDescriptor d = new ConnectorDescriptor("plain", "Plain", null, ConnectorDescriptor.TRANSPORT_REST, 1,
                AuthScheme.NONE, List.of(), List.of(), null, null, null, List.of(restTool),
                new ConnectorDescriptor.McpSettings("2026-07-28"));

        assertThat(ConnectorDescriptorValidator.problems(d)).containsExactly(
                "mcp.protocolVersion: not used by the REST transport",
                "operations[ping].toolName: not used by the REST transport",
                "operations[ping].idempotencyArgument: not used by the REST transport (declare idempotencyHeader)");
    }

    @Test
    void carriesIdempotencyKeyFollowsTheTransport() {
        ConnectorDescriptor crm = crm(FIND_CONTACT, CREATE_TICKET);
        assertThat(crm.carriesIdempotencyKey(CREATE_TICKET)).isTrue();
        assertThat(crm.carriesIdempotencyKey(FIND_CONTACT)).isFalse();
        assertThat(crm.testOperation()).isEmpty();

        ConnectorDescriptor withTest = new ConnectorDescriptor("mock-crm-mcp", "Mock CRM", null,
                ConnectorDescriptor.TRANSPORT_MCP, 1, BEARER, FIELDS, List.of(), null,
                new ConnectorDescriptor.TestOperation("findContact"), null, List.of(FIND_CONTACT, CREATE_TICKET), null);
        assertThat(ConnectorDescriptorValidator.problems(withTest)).isEmpty();
        assertThat(withTest.testOperation()).map(ConnectorOperation::key).hasValue("findContact");
    }

    @Test
    void theJsonShapeReadsIntoTheRecords() throws Exception {
        String json = """
                {
                  "key": "mock-crm-mcp", "name": "Mock CRM (MCP)", "transport": "MCP", "descriptorVersion": 1,
                  "auth": {"scheme": "bearer", "secretField": "token"},
                  "fields": [{"name": "baseUrl", "type": "url"}, {"name": "token", "type": "secret"}],
                  "mcp": {"protocolVersion": "2026-07-28", "later": true},
                  "operations": [
                    {"key": "findContact", "name": "Find contact", "toolName": "crm_find_contact", "riskLevel": "LOW",
                     "idempotent": true,
                     "input": {"type": "object", "required": ["email"], "properties": {"email": {"type": "string"}}},
                     "output": {"type": "object", "properties": {"id": {"type": "string"}, "phone": {"type": "string", "sensitive": true}}}},
                    {"key": "createTicket", "riskLevel": "HIGH", "idempotent": false, "idempotencyArgument": "requestId",
                     "input": {"type": "object", "properties": {"subject": {"type": "string"}}},
                     "output": {"type": "object"}}
                  ]
                }
                """;

        ConnectorDescriptor d = new ObjectMapper().readValue(json, ConnectorDescriptor.class);

        assertThat(d.speaksMcp()).isTrue();
        assertThat(d.mcpOrNone().protocolVersion()).isEqualTo("2026-07-28");
        assertThat(d.operation("findContact")).map(ConnectorOperation::toolNameOrKey).hasValue("crm_find_contact");
        assertThat(d.operation("createTicket")).map(ConnectorOperation::idempotencyArgument).hasValue("requestId");
        assertThat(d.operation("createTicket")).map(ConnectorOperation::declaredIdempotent).hasValue(false);
        assertThat(ConnectorDescriptorValidator.problems(d)).isEmpty();
        // Written and read back, the MCP fields survive.
        ObjectMapper mapper = new ObjectMapper();
        ConnectorDescriptor again = mapper.readValue(mapper.writeValueAsString(d), ConnectorDescriptor.class);
        assertThat(again.mcp()).isEqualTo(d.mcp());
        assertThat(again.operation("findContact")).map(ConnectorOperation::toolName).hasValue("crm_find_contact");
        assertThat(again.operation("createTicket")).map(ConnectorOperation::idempotencyArgument).hasValue("requestId");
    }

    // ---- builders ----------------------------------------------------------------------------------

    private static Map<String, Object> field(String name, String type) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("type", type);
        return field;
    }

    private static ConnectorOperation tool(String key, String toolName, Boolean idempotent, String idempotencyArgument,
            SchemaNode input, SchemaNode output) {
        return new ConnectorOperation(key, key, null, null, null, RiskLevel.LOW, idempotent, null, input, output, null,
                null, null, toolName, idempotencyArgument);
    }

    private static SchemaNode object(Map<String, SchemaNode> properties, List<String> required) {
        return new SchemaNode("object", null, null, required, properties, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static SchemaNode node(String type, String in, Boolean sensitive) {
        return new SchemaNode(type, null, null, null, null, null, null, null, null, null, null, null, in, null, null,
                null, sensitive);
    }

    private static Map<String, SchemaNode> ordered(Object... namesAndNodes) {
        Map<String, SchemaNode> out = new LinkedHashMap<>();
        for (int i = 0; i < namesAndNodes.length; i += 2) {
            out.put((String) namesAndNodes[i], (SchemaNode) namesAndNodes[i + 1]);
        }
        return out;
    }
}
