package com.itways.assistant.journey.model.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Systems (1.3.0): one connection, several systems behind it, each grouping
 * operations under a path prefix and headers. Descriptors without systems read
 * and validate as before.
 */
class ConnectorSystemsTest {

    static final String GATEWAY = """
            {
              "key": "bank-gateway",
              "name": "Bank gateway",
              "transport": "REST",
              "descriptorVersion": 1,
              "auth": {"scheme": "bearer", "secretField": "token"},
              "fields": [{"name": "token", "type": "secret"}],
              "systems": [
                {"key": "t24", "name": "Core banking", "nameAr": "النظام البنكي", "owner": "Core team",
                 "pathPrefix": "/t24/api/v1/", "headers": {"X-System": "T24", "Accept": "application/vnd.t24+json"},
                 "futureHint": "ignored"},
                {"key": "cards", "name": "Cards"}
              ],
              "operations": [
                {"key": "t24.getUser", "name": "Get user", "system": "t24", "method": "GET", "path": "/users/{id}",
                 "riskLevel": "LOW", "input": {"type": "object", "required": ["id"],
                   "properties": {"id": {"type": "string", "in": "path"}}},
                 "output": {"type": "object"}},
                {"key": "cards.list", "name": "List cards", "system": "cards", "method": "GET", "path": "/cards",
                 "riskLevel": "LOW", "output": {"type": "object"}},
                {"key": "ping", "name": "Ping", "method": "GET", "path": "/health", "riskLevel": "LOW",
                 "output": {"type": "object"}}
              ]
            }
            """;

    private final ObjectMapper json = new ObjectMapper();

    private ConnectorDescriptor gateway() throws Exception {
        return json.readValue(GATEWAY, ConnectorDescriptor.class);
    }

    private ConnectorDescriptor gatewayWith(String from, String to) throws Exception {
        String changed = GATEWAY.replace(from, to);
        assertThat(changed).isNotEqualTo(GATEWAY);
        return json.readValue(changed, ConnectorDescriptor.class);
    }

    @Test
    void aDescriptorWithSystemsParsesAndIsValid() throws Exception {
        ConnectorDescriptor d = gateway();

        assertThat(ConnectorDescriptorValidator.problems(d)).isEmpty();
        assertThat(d.systemsOrEmpty()).extracting(ConnectorSystem::key).containsExactly("t24", "cards");
        ConnectorSystem t24 = d.system("t24").orElseThrow();
        assertThat(t24.owner()).isEqualTo("Core team");
        assertThat(t24.nameAr()).isEqualTo("النظام البنكي");
        assertThat(t24.pathPrefixOrEmpty()).isEqualTo("/t24/api/v1");
        assertThat(t24.headersOrEmpty()).containsEntry("X-System", "T24");
        assertThat(d.systemOf(d.operation("t24.getUser").orElseThrow())).contains(t24);
        assertThat(d.systemOf(d.operation("ping").orElseThrow())).isEmpty();
        assertThat(d.system("cards").orElseThrow().pathPrefixOrEmpty()).isEmpty();
    }

    @Test
    void roundTripsWithSystems() throws Exception {
        ConnectorDescriptor d = gateway();
        ConnectorDescriptor again = json.readValue(json.writeValueAsString(d), ConnectorDescriptor.class);

        // Whole-record equality is not asserted: writing a record also writes its derived isIdempotent()
        // as "idempotent" (pre-1.3.0 behaviour), so an undeclared flag comes back declared.
        assertThat(again.systems()).isEqualTo(d.systems());
        assertThat(again.operationsOrEmpty()).extracting(ConnectorOperation::system).containsExactly("t24", "cards", null);
        assertThat(ConnectorDescriptorValidator.problems(again)).isEmpty();
        assertThat(json.readTree(json.writeValueAsString(d)).get("systems").get(0).get("pathPrefix").asText())
                .isEqualTo("/t24/api/v1/");
    }

    @Test
    void roundTripsWithoutSystems() throws Exception {
        ConnectorDescriptor d = json.readValue(ConnectorDescriptorJsonTest.MOCK_BANK, ConnectorDescriptor.class);
        ConnectorDescriptor again = json.readValue(json.writeValueAsString(d), ConnectorDescriptor.class);

        assertThat(d.systems()).isNull();
        assertThat(d.systemsOrEmpty()).isEmpty();
        assertThat(d.operationsOrEmpty()).allSatisfy(op -> assertThat(op.system()).isNull());
        assertThat(again.systems()).isNull();
        assertThat(again.operationsOrEmpty()).allSatisfy(op -> assertThat(op.system()).isNull());
        assertThat(json.readTree(json.writeValueAsString(d)).get("systems").isNull()).isTrue();
        assertThat(ConnectorDescriptorValidator.problems(d)).isEmpty();
        assertThat(ConnectorDescriptorValidator.problems(again)).isEmpty();
    }

    @Test
    void olderConstructorsLeaveSystemsEmpty() {
        ConnectorOperation op = new ConnectorOperation("ping", "Ping", null, "GET", "/health", RiskLevel.LOW, true,
                null, null, SchemaNode.EMPTY_OBJECT, null, null, null, null, null);
        ConnectorDescriptor d = new ConnectorDescriptor("t", "T", null, "REST", 1, null, List.of(), List.of(), null,
                null, null, List.of(op), null);

        assertThat(op.system()).isNull();
        assertThat(d.systems()).isNull();
        assertThat(ConnectorDescriptorValidator.problems(d)).isEmpty();
    }

    @Test
    void systemKeysMustBeUnique() throws Exception {
        ConnectorDescriptor d = gatewayWith("{\"key\": \"cards\", \"name\": \"Cards\"}",
                "{\"key\": \"cards\", \"name\": \"Cards\"}, {\"key\": \"cards\", \"name\": \"Cards again\"}");

        assertThat(ConnectorDescriptorValidator.problems(d)).containsExactly("systems[cards].key: duplicate system key");
    }

    @Test
    void systemKeysAndNamesMustBeWellFormed() throws Exception {
        ConnectorDescriptor d = gatewayWith("{\"key\": \"cards\", \"name\": \"Cards\"}",
                "{\"key\": \"cards\", \"name\": \"Cards\"}, {\"key\": \"9bad.key\", \"name\": \" \"}, {\"name\": \"x\"}");

        assertThat(ConnectorDescriptorValidator.problems(d)).containsExactly(
                "systems[9bad.key].key: must be letters, digits, underscores and dashes, starting with a letter (at most 64 characters)",
                "systems[9bad.key].name: required",
                "systems[3].key: must be letters, digits, underscores and dashes, starting with a letter (at most 64 characters)");
    }

    @Test
    void anOperationMustNameADeclaredSystem() throws Exception {
        ConnectorDescriptor d = gatewayWith("\"system\": \"cards\"", "\"system\": \"crm\"");

        assertThat(ConnectorDescriptorValidator.problems(d)).containsExactly("operations[cards.list].system: no system 'crm'");
    }

    @Test
    void aBlankSystemReferenceIsRefused() throws Exception {
        ConnectorDescriptor d = gatewayWith("\"system\": \"cards\"", "\"system\": \"\"");

        assertThat(ConnectorDescriptorValidator.problems(d))
                .containsExactly("operations[cards.list].system: must name a system (or be absent)");
    }

    @Test
    void operationsReferencingSystemsWithNoSystemsDeclaredAreDangling() throws Exception {
        ConnectorDescriptor d = json.readValue(GATEWAY.replaceAll("(?s)\"systems\": \\[.*?\\n  \\],", ""),
                ConnectorDescriptor.class);

        assertThat(d.systems()).isNull();
        assertThat(ConnectorDescriptorValidator.problems(d)).containsExactly(
                "operations[t24.getUser].system: no system 't24'",
                "operations[cards.list].system: no system 'cards'");
    }

    @Test
    void operationKeysStayUniqueAndMayCarryDots() throws Exception {
        ConnectorDescriptor dup = gatewayWith("\"key\": \"cards.list\"", "\"key\": \"t24.getUser\"");
        assertThat(ConnectorDescriptorValidator.problems(dup))
                .containsExactly("operations[t24.getUser].key: duplicate operation key");

        for (String bad : List.of(".lead", "trail.", "a..b", "a.9b", "a b")) {
            ConnectorDescriptor d = gatewayWith("\"key\": \"ping\"", "\"key\": \"" + bad + "\"");
            assertThat(ConnectorDescriptorValidator.problems(d)).as(bad).singleElement().asString()
                    .startsWith("operations[" + bad + "].key: must be letters");
        }
    }

    @Test
    void badPathPrefixesAreRefused() throws Exception {
        for (String bad : List.of("t24", "//evil.example", "https://evil.example/x", "/a/../b", "/a/./b", "/a?x=1",
                "/a#f", "/a@b", "/a/%2e%2e", "/{id}", "/a b", " ")) {
            ConnectorDescriptor d = gatewayWith("\"/t24/api/v1/\"", json.writeValueAsString(bad));
            assertThat(ConnectorDescriptorValidator.problems(d)).as(bad).singleElement().asString()
                    .startsWith("systems[t24].pathPrefix: must be /segment");
        }
        for (String good : List.of("", "/", "/t24", "/odata/v4.0/", "/a_b/c~d")) {
            ConnectorDescriptor d = gatewayWith("\"/t24/api/v1/\"", json.writeValueAsString(good));
            assertThat(ConnectorDescriptorValidator.problems(d)).as(good).isEmpty();
        }
    }

    @Test
    void systemHeadersMayNotCarryCredentials() throws Exception {
        ConnectorDescriptor d = gatewayWith("\"X-System\": \"T24\"", "\"Authorization\": \"Basic abc\"");

        assertThat(ConnectorDescriptorValidator.problems(d)).containsExactly(
                "systems[t24].headers[Authorization]: looks like a credential; use a secret field and the auth scheme instead");
    }

    @Test
    void mcpSystemsGroupOnly() {
        ConnectorOperation tool = new ConnectorOperation("search", "Search", null, null, null, RiskLevel.LOW, true,
                null, null, SchemaNode.EMPTY_OBJECT, null, null, null, null, null, "kb");
        ConnectorDescriptor ok = new ConnectorDescriptor("kb-mcp", "KB", null, ConnectorDescriptor.TRANSPORT_MCP, 1,
                null, List.of(), List.of(), null, null, null, List.of(tool), null,
                List.of(new ConnectorSystem("kb", "Knowledge", null, null, null, null)));
        assertThat(ConnectorDescriptorValidator.problems(ok)).isEmpty();

        ConnectorDescriptor bad = new ConnectorDescriptor("kb-mcp", "KB", null, ConnectorDescriptor.TRANSPORT_MCP, 1,
                null, List.of(), List.of(), null, null, null, List.of(tool), null,
                List.of(new ConnectorSystem("kb", "Knowledge", null, null, "/kb", Map.of("X-A", "b"))));
        assertThat(ConnectorDescriptorValidator.problems(bad)).containsExactly(
                "systems[kb].pathPrefix: not used by the MCP transport (the base URL is the server's endpoint)",
                "systems[kb].headers: not used by the MCP transport");
    }

    @Test
    void safePathPrefixRule() {
        assertThat(ConnectorSystem.isSafePathPrefix(null)).isTrue();
        assertThat(ConnectorSystem.isSafePathPrefix("/a/b/")).isTrue();
        assertThat(ConnectorSystem.isSafePathPrefix("/" + "a".repeat(256))).isFalse();
        assertThat(ConnectorSystem.isSafePathPrefix("/..")).isFalse();
        assertThat(ConnectorSystem.normalizePathPrefix("/a/b//")).isEqualTo("/a/b");
        assertThat(ConnectorSystem.normalizePathPrefix(null)).isEmpty();
    }
}
