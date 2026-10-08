package com.itways.assistant.journey.connector;

import com.itways.assistant.journey.model.connector.AuthScheme;
import com.itways.assistant.journey.model.connector.ConnectorDefaults;
import com.itways.assistant.journey.model.connector.ConnectorDescriptor;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import com.itways.assistant.journey.model.connector.RiskLevel;
import com.itways.assistant.journey.model.connector.SchemaNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mock bank of the design, as Java objects: an API key in a header, a
 * health check, a balance read with a path and a query parameter and a mapped
 * 404, and a HIGH-risk card stop that carries an idempotency key. Tests derive
 * what they need from it ({@link #withAuth}, {@link #withOperations}) instead
 * of writing descriptors of their own.
 */
public final class MockBankDescriptors {

    public static final String API_KEY = "sk-verysecret-1234";

    public static final List<Map<String, Object>> FIELDS = List.of(
            field("baseUrl", "url"),
            field("apiKey", ConnectorDescriptor.FIELD_TYPE_SECRET),
            field("branchCode", "text"));

    public static final AuthScheme API_KEY_HEADER = new AuthScheme(AuthScheme.SCHEME_API_KEY, AuthScheme.IN_HEADER,
            "X-API-Key", "apiKey", null, null, null, null, null, null);

    public static final ConnectorOperation PING = operation("ping", "GET", "/health", RiskLevel.LOW, true, null,
            null, object(Map.of("status", node("string").build()), List.of()), null, null);

    public static final ConnectorOperation GET_ACCOUNT_BALANCE = operation("getAccountBalance", "GET",
            "/accounts/{accountNo}/balance", RiskLevel.LOW, true, null,
            object(ordered(
                    "accountNo", node("string").in(SchemaNode.IN_PATH).pattern("^[0-9]{10,16}$").build(),
                    "asOf", node("string").format("date").in(SchemaNode.IN_QUERY).build()), List.of("accountNo")),
            object(ordered(
                    "balance", node("number").build(),
                    "currency", node("string").enumValues(List.of("JOD", "USD")).build(),
                    "iban", node("string").sensitive().build()), List.of()),
            Map.of("404", "ACCOUNT_NOT_FOUND"), null);

    public static final ConnectorOperation STOP_CARD = operation("stopCard", "POST", "/cards/{cardId}/stop",
            RiskLevel.HIGH, false, "Idempotency-Key",
            object(ordered(
                    "cardId", node("string").in(SchemaNode.IN_PATH).build(),
                    "reason", node("string").enumValues(List.of("LOST", "STOLEN")).defaultValue("LOST").build()),
                    List.of("cardId")),
            object(ordered("status", node("string").build(), "reference", node("string").build()), List.of()),
            null, null);

    private MockBankDescriptors() {
    }

    /** The mock bank as the design writes it. */
    public static ConnectorDescriptor mockBank() {
        return new ConnectorDescriptor("mock-bank-rest", "Mock Bank (REST)", "البنك التجريبي",
                ConnectorDescriptor.TRANSPORT_REST, 1, API_KEY_HEADER, FIELDS, List.of(),
                new ConnectorDefaults(3000, 5000, new ConnectorDefaults.Retry(3, 200),
                        Map.of("Accept", "application/json")),
                new ConnectorDescriptor.TestOperation("ping"), null,
                List.of(PING, GET_ACCOUNT_BALANCE, STOP_CARD));
    }

    /** {@code descriptor} authenticating with {@code auth}, whose fields are {@code fields}. */
    public static ConnectorDescriptor withAuth(ConnectorDescriptor descriptor, AuthScheme auth,
            List<Map<String, Object>> fields) {
        return new ConnectorDescriptor(descriptor.key(), descriptor.name(), descriptor.nameAr(),
                descriptor.transport(), descriptor.descriptorVersion(), auth, fields, descriptor.rules(),
                descriptor.defaults(), descriptor.test(), descriptor.idempotency(), descriptor.operations());
    }

    /** {@code descriptor} with these operations and this test declaration ({@code null} for none). */
    public static ConnectorDescriptor withOperations(ConnectorDescriptor descriptor, String testOperation,
            ConnectorOperation... operations) {
        return new ConnectorDescriptor(descriptor.key(), descriptor.name(), descriptor.nameAr(),
                descriptor.transport(), descriptor.descriptorVersion(), descriptor.auth(), descriptor.fields(),
                descriptor.rules(), descriptor.defaults(),
                testOperation != null ? new ConnectorDescriptor.TestOperation(testOperation) : null,
                descriptor.idempotency(), List.of(operations));
    }

    /** {@code descriptor} with these static default headers. */
    public static ConnectorDescriptor withDefaultHeaders(ConnectorDescriptor descriptor, Map<String, String> headers) {
        ConnectorDefaults defaults = descriptor.defaultsOrNone();
        return new ConnectorDescriptor(descriptor.key(), descriptor.name(), descriptor.nameAr(),
                descriptor.transport(), descriptor.descriptorVersion(), descriptor.auth(), descriptor.fields(),
                descriptor.rules(),
                new ConnectorDefaults(defaults.connectTimeoutMs(), defaults.readTimeoutMs(), defaults.retry(), headers),
                descriptor.test(), descriptor.idempotency(), descriptor.operations());
    }

    /** A connector of {@code descriptor} at {@code baseUrl}, dialling the base host only. */
    public static ResolvedConnector connector(ConnectorDescriptor descriptor, String baseUrl,
            Map<String, Object> config, Map<String, String> secrets) {
        return connector(descriptor, baseUrl, null, config, secrets);
    }

    public static ResolvedConnector connector(ConnectorDescriptor descriptor, String baseUrl,
            List<String> allowedHosts, Map<String, Object> config, Map<String, String> secrets) {
        return new ResolvedConnector(UUID.fromString("00000000-0000-0000-0000-000000000bad"), "Mock bank (test)",
                descriptor.key(), descriptor.descriptorVersion(), descriptor, baseUrl, allowedHosts, config, secrets,
                7L);
    }

    /** The mock bank connector with its API key opened. */
    public static ResolvedConnector mockBankConnector(String baseUrl) {
        return connector(mockBank(), baseUrl, Map.of("branchCode", "001"), Map.of("apiKey", API_KEY));
    }

    public static Map<String, Object> field(String name, String type) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("type", type);
        field.put("label", name);
        return field;
    }

    public static ConnectorOperation operation(String key, String method, String path, RiskLevel risk,
            Boolean idempotent, String idempotencyHeader, SchemaNode input, SchemaNode output,
            Map<String, String> errors, ConnectorOperation.ResponseRules response) {
        return new ConnectorOperation(key, key, null, method, path, risk, idempotent, idempotencyHeader, input,
                output, errors, response, null);
    }

    public static SchemaNode object(Map<String, SchemaNode> properties, List<String> required) {
        return new SchemaNode("object", null, null, required, properties, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    /** Properties in a stable order: the wire order of query parameters and body keys matters to assertions. */
    public static Map<String, SchemaNode> ordered(Object... namesAndNodes) {
        Map<String, SchemaNode> out = new LinkedHashMap<>();
        for (int i = 0; i < namesAndNodes.length; i += 2) {
            out.put((String) namesAndNodes[i], (SchemaNode) namesAndNodes[i + 1]);
        }
        return out;
    }

    public static Node node(String type) {
        return new Node(type);
    }

    /** A small builder over the 17-component {@link SchemaNode} record. */
    public static final class Node {
        private final String type;
        private String format;
        private List<String> required;
        private Map<String, SchemaNode> properties;
        private SchemaNode items;
        private List<Object> enumValues;
        private String pattern;
        private Number minimum;
        private Number maximum;
        private Integer minLength;
        private Integer maxLength;
        private String in;
        private String as;
        private String template;
        private Object defaultValue;
        private Boolean sensitive;

        private Node(String type) {
            this.type = type;
        }

        public Node format(String value) {
            format = value;
            return this;
        }

        public Node properties(Map<String, SchemaNode> value, List<String> requiredNames) {
            properties = value;
            required = requiredNames;
            return this;
        }

        public Node items(SchemaNode value) {
            items = value;
            return this;
        }

        public Node enumValues(List<Object> values) {
            enumValues = new ArrayList<>(values);
            return this;
        }

        public Node pattern(String value) {
            pattern = value;
            return this;
        }

        public Node range(Number min, Number max) {
            minimum = min;
            maximum = max;
            return this;
        }

        public Node length(Integer min, Integer max) {
            minLength = min;
            maxLength = max;
            return this;
        }

        public Node in(String value) {
            in = value;
            return this;
        }

        public Node as(String value) {
            as = value;
            return this;
        }

        public Node template(String value) {
            template = value;
            return this;
        }

        public Node defaultValue(Object value) {
            defaultValue = value;
            return this;
        }

        public Node sensitive() {
            sensitive = true;
            return this;
        }

        public SchemaNode build() {
            return new SchemaNode(type, format, null, required, properties, items, enumValues, pattern, minimum,
                    maximum, minLength, maxLength, in, as, template, defaultValue, sensitive);
        }
    }
}
