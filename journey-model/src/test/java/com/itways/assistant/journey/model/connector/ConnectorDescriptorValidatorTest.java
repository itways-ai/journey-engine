package com.itways.assistant.journey.model.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The mock bank of the design is acceptable; each rule the validator holds is
 * shown by breaking the mock bank in exactly one place.
 */
class ConnectorDescriptorValidatorTest {

    private static final AuthScheme API_KEY = new AuthScheme(AuthScheme.SCHEME_API_KEY, AuthScheme.IN_HEADER,
            "X-API-Key", "apiKey", null, null, null, null, null, null);

    private static final List<Map<String, Object>> FIELDS = List.of(field("baseUrl", "url"),
            field("apiKey", ConnectorDescriptor.FIELD_TYPE_SECRET), field("branchCode", "text"));

    private static final ConnectorOperation PING = operation("ping", "GET", "/health", true, null, null,
            object(Map.of("status", node("string", null, null, null, null)), List.of()));

    private static final ConnectorOperation GET_BALANCE = operation("getAccountBalance", "GET",
            "/accounts/{accountNo}/balance", true, null,
            object(ordered(
                    "accountNo", node("string", SchemaNode.IN_PATH, "^[0-9]{10,16}$", null, null),
                    "asOf", node("string", SchemaNode.IN_QUERY, null, null, null)), List.of("accountNo")),
            object(ordered(
                    "balance", node("number", null, null, null, null),
                    "currency", node("string", null, null, null, null),
                    "iban", node("string", null, null, null, true)), List.of()));

    private static final ConnectorOperation STOP_CARD = operation("stopCard", "POST", "/cards/{cardId}/stop",
            false, "Idempotency-Key",
            object(ordered(
                    "cardId", node("string", SchemaNode.IN_PATH, null, null, null),
                    "reason", node("string", null, null, "LOST", null)), List.of("cardId")),
            object(ordered(
                    "status", node("string", null, null, null, null),
                    "reference", node("string", null, null, null, null)), List.of()));

    private static ConnectorDescriptor mockBank() {
        return mockBank(API_KEY, FIELDS, Map.of("Accept", "application/json"), new ConnectorDescriptor.TestOperation("ping"),
                PING, withErrors(GET_BALANCE, Map.of("404", "ACCOUNT_NOT_FOUND")), STOP_CARD);
    }

    private static ConnectorDescriptor mockBank(AuthScheme auth, List<Map<String, Object>> fields,
            Map<String, String> headers, ConnectorDescriptor.TestOperation test, ConnectorOperation... operations) {
        return new ConnectorDescriptor("mock-bank-rest", "Mock Bank (REST)", null, ConnectorDescriptor.TRANSPORT_REST,
                1, auth, fields, List.of(), new ConnectorDefaults(3000, 5000, new ConnectorDefaults.Retry(3, 200), headers),
                test, null, List.of(operations));
    }

    @Test
    void theMockBankIsValid() {
        assertThat(ConnectorDescriptorValidator.problems(mockBank())).isEmpty();
        assertThat(ConnectorDescriptorValidator.isValid(mockBank())).isTrue();
    }

    @Test
    void duplicateOperationKey() {
        ConnectorDescriptor d = mockBank(API_KEY, FIELDS, Map.of(), null, PING, PING);

        assertThat(ConnectorDescriptorValidator.problems(d)).containsExactly("operations[ping].key: duplicate operation key");
    }

    @Test
    void pathParameterWithoutAnInput() {
        ConnectorOperation op = operation("byId", "GET", "/accounts/{accountNo}", true, null, null, SchemaNode.EMPTY_OBJECT);

        assertThat(ConnectorDescriptorValidator.problems(mockBank(API_KEY, FIELDS, Map.of(), null, op)))
                .containsExactly("operations[byId].path: parameter {accountNo} has no input property");
    }

    @Test
    void aSecretFieldMayNotTravelInTheQuery() {
        ConnectorOperation op = operation("leak", "GET", "/x", true, null,
                object(ordered("apiKey", node("string", SchemaNode.IN_QUERY, null, null, null)), List.of()),
                SchemaNode.EMPTY_OBJECT);

        assertThat(ConnectorDescriptorValidator.problems(mockBank(API_KEY, FIELDS, Map.of(), null, op)))
                .containsExactly("operations[leak].input.properties[apiKey]: a secret field may not be sent in the path or query");
    }

    @Test
    void aNonIdempotentWriteNeedsAnIdempotencyHeader() {
        ConnectorOperation write = operation("stopCard", "POST", "/cards/{cardId}/stop", false, null,
                STOP_CARD.input(), STOP_CARD.output());

        assertThat(ConnectorDescriptorValidator.problems(mockBank(API_KEY, FIELDS, Map.of(), null, write)))
                .singleElement().asString().startsWith("operations[stopCard].idempotencyHeader:");
    }

    @Test
    void aCredentialLookingDefaultHeaderIsRefused() {
        ConnectorDescriptor d = mockBank(API_KEY, FIELDS, Map.of("Authorization", "Bearer x"), null, PING);

        assertThat(ConnectorDescriptorValidator.problems(d)).singleElement().asString()
                .startsWith("defaults.headers[Authorization]: looks like a credential");
    }

    @Test
    void anUnknownAuthSchemeIsRefused() {
        AuthScheme odd = new AuthScheme("digest", null, null, null, null, null, null, null, null, null);

        assertThat(ConnectorDescriptorValidator.problems(mockBank(odd, FIELDS, Map.of(), null, PING)))
                .containsExactly("auth.scheme: unknown scheme 'digest'");
    }

    @Test
    void anOperationNeedsAnOutputSchema() {
        ConnectorOperation blind = operation("blind", "GET", "/x", true, null, null, null);

        assertThat(ConnectorDescriptorValidator.problems(mockBank(API_KEY, FIELDS, Map.of(), null, blind)))
                .containsExactly("operations[blind].output: required (the output schema; use an empty object when nothing is read)");
    }

    @Test
    void theTestOperationMustBeIdempotent() {
        ConnectorDescriptor d = mockBank(API_KEY, FIELDS, Map.of(), new ConnectorDescriptor.TestOperation("stopCard"),
                PING, STOP_CARD);

        assertThat(ConnectorDescriptorValidator.problems(d)).containsExactly("test.operation: 'stopCard' must be idempotent");
    }

    @Test
    void aPathMayNotClimbOutOfTheBaseUrl() {
        ConnectorOperation climb = operation("climb", "GET", "/../admin", true, null, null, SchemaNode.EMPTY_OBJECT);

        assertThat(ConnectorDescriptorValidator.problems(mockBank(API_KEY, FIELDS, Map.of(), null, climb)))
                .containsExactly("operations[climb].path: must be a relative path under the base URL (no scheme, host, '..', '@', query or fragment)");
    }

    @Test
    void baseUrlRules() {
        assertThat(ConnectorDescriptorValidator.baseUrlProblems("https://bank.example/api")).isEmpty();
        assertThat(ConnectorDescriptorValidator.baseUrlProblems("http://bank.example")).containsExactly("baseUrl: must use https");
        assertThat(ConnectorDescriptorValidator.baseUrlProblems("https://u:p@bank.example/?q=1")).containsExactly(
                "baseUrl: must not carry a user name or password", "baseUrl: must not carry a query or fragment");
    }

    // ---- builders ----------------------------------------------------------------------------------

    private static Map<String, Object> field(String name, String type) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("type", type);
        return field;
    }

    private static ConnectorOperation operation(String key, String method, String path, boolean idempotent,
            String idempotencyHeader, SchemaNode input, SchemaNode output) {
        return new ConnectorOperation(key, key, null, method, path, RiskLevel.LOW, idempotent, idempotencyHeader, input,
                output, null, null, null);
    }

    private static ConnectorOperation withErrors(ConnectorOperation op, Map<String, String> errors) {
        return new ConnectorOperation(op.key(), op.name(), op.nameAr(), op.method(), op.path(), op.riskLevel(),
                op.idempotent(), op.idempotencyHeader(), op.input(), op.output(), errors, op.response(), op.timeoutMs());
    }

    private static SchemaNode object(Map<String, SchemaNode> properties, List<String> required) {
        return new SchemaNode("object", null, null, required, properties, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static SchemaNode node(String type, String in, String pattern, Object defaultValue, Boolean sensitive) {
        return new SchemaNode(type, null, null, null, null, null, null, pattern, null, null, null, null, in, null, null,
                defaultValue, sensitive);
    }

    private static Map<String, SchemaNode> ordered(Object... namesAndNodes) {
        Map<String, SchemaNode> out = new LinkedHashMap<>();
        for (int i = 0; i < namesAndNodes.length; i += 2) {
            out.put((String) namesAndNodes[i], (SchemaNode) namesAndNodes[i + 1]);
        }
        return out;
    }
}
