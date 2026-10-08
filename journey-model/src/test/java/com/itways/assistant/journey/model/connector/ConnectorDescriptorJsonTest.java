package com.itways.assistant.journey.model.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The mock bank descriptor as journey-service stores it reads into the records
 * with a plain mapper: unknown keys (a later version's) are ignored by the
 * records themselves, {@code enum} and {@code default} land on their fields,
 * and a resolved connector never prints a secret.
 */
class ConnectorDescriptorJsonTest {

    static final String MOCK_BANK = """
            {
              "key": "mock-bank-rest",
              "name": "Mock Bank (REST)",
              "nameAr": "البنك التجريبي",
              "transport": "REST",
              "descriptorVersion": 1,
              "auth": {"scheme": "apiKey", "in": "header", "name": "X-API-Key", "secretField": "apiKey"},
              "fields": [
                {"name": "baseUrl", "type": "url", "label": "Base URL", "required": true},
                {"name": "apiKey", "type": "secret", "label": "API key", "required": true},
                {"name": "branchCode", "type": "text", "label": "Branch code"}
              ],
              "rules": [],
              "defaults": {
                "connectTimeoutMs": 3000, "readTimeoutMs": 5000,
                "retry": {"maxAttempts": 3, "baseDelayMs": 200},
                "headers": {"Accept": "application/json"}
              },
              "test": {"operation": "ping"},
              "operations": [
                {"key": "ping", "name": "Ping", "method": "GET", "path": "/health", "riskLevel": "LOW",
                 "idempotent": true,
                 "output": {"type": "object", "properties": {"status": {"type": "string"}}}},
                {"key": "getAccountBalance", "name": "Get account balance", "method": "GET",
                 "path": "/accounts/{accountNo}/balance", "riskLevel": "LOW", "idempotent": true,
                 "input": {"type": "object", "required": ["accountNo"], "properties": {
                    "accountNo": {"type": "string", "in": "path", "pattern": "^[0-9]{10,16}$"},
                    "asOf": {"type": "string", "format": "date", "in": "query"}}},
                 "output": {"type": "object", "properties": {
                    "balance": {"type": "number"},
                    "currency": {"type": "string", "enum": ["JOD", "USD"]},
                    "iban": {"type": "string", "sensitive": true}}},
                 "errors": {"404": "ACCOUNT_NOT_FOUND"},
                 "futureHint": "ignored"},
                {"key": "stopCard", "name": "Stop card", "method": "POST", "path": "/cards/{cardId}/stop",
                 "riskLevel": "HIGH", "idempotent": false, "idempotencyHeader": "Idempotency-Key",
                 "input": {"type": "object", "required": ["cardId"], "properties": {
                    "cardId": {"type": "string", "in": "path"},
                    "reason": {"type": "string", "enum": ["LOST", "STOLEN"], "default": "LOST"}}},
                 "output": {"type": "object", "properties": {"status": {"type": "string"}, "reference": {"type": "string"}}}}
              ],
              "icon": "bank",
              "sinceVersion": "later"
            }
            """;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theMockBankParsesAndIsValid() throws Exception {
        ConnectorDescriptor descriptor = json.readValue(MOCK_BANK, ConnectorDescriptor.class);

        assertThat(descriptor.key()).isEqualTo("mock-bank-rest");
        assertThat(descriptor.authOrNone().isApiKeyInQuery()).isFalse();
        assertThat(descriptor.secretFieldNames()).containsExactly("apiKey");
        assertThat(descriptor.defaultsOrNone().retry().baseDelayMs()).isEqualTo(200);
        assertThat(descriptor.testOperation()).map(ConnectorOperation::key).hasValue("ping");
        assertThat(descriptor.operationsOrEmpty()).extracting(ConnectorOperation::key)
                .containsExactly("ping", "getAccountBalance", "stopCard");
        assertThat(ConnectorDescriptorValidator.problems(descriptor)).isEmpty();
    }

    @Test
    void enumDefaultPlacementAndSensitiveLandOnTheirFields() throws Exception {
        ConnectorDescriptor descriptor = json.readValue(MOCK_BANK, ConnectorDescriptor.class);

        ConnectorOperation balance = descriptor.operation("getAccountBalance").orElseThrow();
        SchemaNode accountNo = balance.inputOrEmpty().propertiesOrEmpty().get("accountNo");
        assertThat(accountNo.placement()).isEqualTo(SchemaNode.IN_PATH);
        assertThat(accountNo.pattern()).isEqualTo("^[0-9]{10,16}$");
        assertThat(balance.outputOrEmpty().propertiesOrEmpty().get("currency").enumValues())
                .containsExactly("JOD", "USD");
        assertThat(balance.outputOrEmpty().propertiesOrEmpty().get("iban").isSensitive()).isTrue();
        assertThat(balance.errorsOrEmpty()).containsEntry("404", "ACCOUNT_NOT_FOUND");

        ConnectorOperation stop = descriptor.operation("stopCard").orElseThrow();
        assertThat(stop.isIdempotent()).isFalse();
        assertThat(stop.riskLevelOrLow()).isEqualTo(RiskLevel.HIGH);
        assertThat(descriptor.idempotencyHeaderFor(stop)).isEqualTo("Idempotency-Key");
        assertThat(descriptor.idempotencyHeaderFor(balance)).isNull();
        SchemaNode reason = stop.inputOrEmpty().propertiesOrEmpty().get("reason");
        assertThat(reason.defaultValue()).isEqualTo("LOST");
        assertThat(reason.placement()).isEqualTo(SchemaNode.IN_BODY);
    }

    @Test
    void riskLevelIsLegacyOptionalAndNeverFailsValidation() throws Exception {
        String noRisk = MOCK_BANK.replace("\"riskLevel\": \"LOW\",", "");
        String legacyRisk = MOCK_BANK.replace("\"riskLevel\": \"HIGH\"", "\"riskLevel\": \"CRITICAL\"")
                .replace("/balance\", \"riskLevel\": \"LOW\"", "/balance\", \"riskLevel\": \"medium\"");

        ConnectorDescriptor without = json.readValue(noRisk, ConnectorDescriptor.class);
        ConnectorDescriptor legacy = json.readValue(legacyRisk, ConnectorDescriptor.class);

        assertThat(without.operation("ping").orElseThrow().riskLevel()).isNull();
        assertThat(ConnectorDescriptorValidator.problems(without)).isEmpty();
        assertThat(legacy.operation("stopCard").orElseThrow().riskLevel()).isNull();
        assertThat(legacy.operation("getAccountBalance").orElseThrow().riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(ConnectorDescriptorValidator.problems(legacy)).isEmpty();
    }

    @Test
    void aResolvedConnectorPrintsFieldNamesNotSecrets() throws Exception {
        ConnectorDescriptor descriptor = json.readValue(MOCK_BANK, ConnectorDescriptor.class);
        ResolvedConnector connector = new ResolvedConnector(UUID.randomUUID(), "Mock bank", "REST",
                descriptor, "https://bank.example/api", List.of(), Map.of("branchCode", "001"),
                Map.of("apiKey", "sk-verysecret-1234"), 3L);

        assertThat(connector.toString()).contains("kind=REST").contains("secretFields=[apiKey]").contains("configFields=[branchCode]")
                .doesNotContain("sk-verysecret-1234");
        assertThat(connector.fieldValue("apiKey")).isEqualTo("sk-verysecret-1234");
        assertThat(connector.fieldValue("branchCode")).isEqualTo("001");
        assertThat(connector.fieldValue("missing")).isNull();
    }

    @Test
    void aResolvedConnectorRoundTripsThroughJsonWithUnknownKeysIgnored() throws Exception {
        ConnectorDescriptor descriptor = json.readValue(MOCK_BANK, ConnectorDescriptor.class);
        ResolvedConnector connector = new ResolvedConnector(UUID.randomUUID(), "Mock bank", "REST",
                descriptor, "https://bank.example", null, Map.of(), Map.of("apiKey", "k"), 1L);

        String written = json.writeValueAsString(connector).replaceFirst("\\{", "{\"later\":1,");
        ResolvedConnector read = json.readValue(written, ResolvedConnector.class);

        assertThat(read.id()).isEqualTo(connector.id());
        assertThat(read.descriptor().operationsOrEmpty()).hasSize(3);
        assertThat(read.secretsOrEmpty()).containsEntry("apiKey", "k");
        assertThat(read.allowedHostsOrEmpty()).isEmpty();
        assertThat(read.kind()).isEqualTo("REST");
        assertThat(read.lockVersion()).isEqualTo(1L);
    }

    @Test
    void aResolvedConnectorWritesTheWireShapeWithKindAndNoTypeFields() throws Exception {
        ConnectorDescriptor descriptor = json.readValue(MOCK_BANK, ConnectorDescriptor.class);
        ResolvedConnector connector = new ResolvedConnector(UUID.randomUUID(), "Mock bank", "REST",
                descriptor, "https://bank.example", List.of("bank.example"), Map.of(), Map.of("apiKey", "k"), 4L);

        com.fasterxml.jackson.databind.JsonNode tree = json.readTree(json.writeValueAsString(connector));

        List<String> names = new java.util.ArrayList<>();
        tree.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("id", "name", "kind", "descriptor", "baseUrl", "allowedHosts",
                "config", "secrets", "lockVersion");
        assertThat(tree.path("kind").asText()).isEqualTo("REST");
        assertThat(tree.path("lockVersion").asLong()).isEqualTo(4L);
    }

    @Test
    void aPre15ResolvePayloadWithTypeFieldsStillReadsButHasNoKind() throws Exception {
        ConnectorDescriptor descriptor = json.readValue(MOCK_BANK, ConnectorDescriptor.class);
        String legacy = "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Mock bank\","
                + "\"typeKey\":\"mock-bank-rest\",\"typeVersion\":1,\"descriptor\":"
                + json.writeValueAsString(descriptor) + ",\"baseUrl\":\"https://bank.example\",\"lockVersion\":2}";

        ResolvedConnector read = json.readValue(legacy, ResolvedConnector.class);

        assertThat(read.kind()).isNull();
        assertThat(read.operation("getAccountBalance")).isPresent();
        assertThat(read.lockVersion()).isEqualTo(2L);
    }
}
