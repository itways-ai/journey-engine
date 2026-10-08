package com.itways.assistant.journey.connector.rest;

import static com.itways.assistant.journey.connector.MockBankDescriptors.API_KEY;
import static com.itways.assistant.journey.connector.MockBankDescriptors.connector;
import static com.itways.assistant.journey.connector.MockBankDescriptors.mockBank;
import static com.itways.assistant.journey.connector.MockBankDescriptors.node;
import static com.itways.assistant.journey.connector.MockBankDescriptors.object;
import static com.itways.assistant.journey.connector.MockBankDescriptors.ordered;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.connector.CallOptions;
import com.itways.assistant.journey.connector.EgressPolicy;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.connector.LocalServer;
import com.itways.assistant.journey.connector.Sleeper;
import com.itways.assistant.journey.connector.http.RequestUrls;
import com.itways.assistant.journey.model.connector.ConnectorDefaults;
import com.itways.assistant.journey.model.connector.ConnectorDescriptor;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ConnectorSystem;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import com.itways.assistant.journey.model.connector.RiskLevel;
import com.itways.assistant.journey.model.connector.SchemaNode;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Systems (1.3.0) on the wire: an operation of a system is sent to
 * {@code baseUrl + pathPrefix + path}, with the system's headers between the
 * type's defaults and the operation's own header inputs, and a prefix that
 * would leave the base URL is refused before anything is sent.
 */
class RestTransportSystemsTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);

    private LocalServer server;
    private RestTransport transport;

    @BeforeEach
    void start() throws IOException {
        server = new LocalServer();
        transport = new RestTransport(EgressPolicy.STRICT.withPrivateHosts(List.of("127.0.0.1")).withAllowHttp(true),
                new ObjectMapper(), Sleeper.NONE);
    }

    @AfterEach
    void stop() {
        transport.close();
        server.close();
    }

    private static ConnectorOperation getUser(String system) {
        return new ConnectorOperation("t24.getUser", "Get user", null, "GET", "/users/{id}", RiskLevel.LOW, true, null,
                object(ordered(
                        "id", node("string").in(SchemaNode.IN_PATH).build(),
                        "branch", node("string").in(SchemaNode.IN_HEADER).as("X-Branch").build()), List.of("id")),
                SchemaNode.EMPTY_OBJECT, null, null, null, null, null, system);
    }

    private static ConnectorDescriptor gateway(ConnectorSystem system, ConnectorOperation operation) {
        ConnectorDescriptor bank = mockBank();
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("Accept", "application/json");
        defaults.put("X-Client", "platform");
        defaults.put("X-System", "default");
        return new ConnectorDescriptor(bank.key(), bank.name(), bank.nameAr(), bank.transport(),
                bank.descriptorVersion(), bank.auth(), bank.fields(), bank.rules(),
                new ConnectorDefaults(3000, 5000, null, defaults), null, null, List.of(operation), null,
                system != null ? List.of(system) : null);
    }

    private ResolvedConnector connectorOf(ConnectorDescriptor descriptor, String baseUrl) {
        return connector(descriptor, baseUrl, Map.of(), Map.of("apiKey", API_KEY));
    }

    private static ConnectorSystem t24(String prefix, Map<String, String> headers) {
        return new ConnectorSystem("t24", "Core banking", null, "Core team", prefix, headers);
    }

    @Test
    void theSystemPrefixSitsBetweenTheBasePathAndTheOperationPath() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-System", "T24");
        headers.put("Accept", "application/vnd.t24+json");
        headers.put("X-Branch", "system-branch");
        headers.put("X-API-Key", "system-must-not-win");
        ConnectorDescriptor d = gateway(t24("/t24/api/v1/", headers), getUser("t24"));

        transport.call(connectorOf(d, server.baseUrl() + "/gw/"), getUser("t24"),
                Map.of("id", "a/b", "branch", "001"), CallOptions.single(BUDGET));

        LocalServer.Received sent = server.only();
        assertThat(sent.rawPath()).isEqualTo("/gw/t24/api/v1/users/" + RequestUrls.percentEncode("a/b"));
        // defaults < system < operation input < credential
        assertThat(sent.header("X-Client")).isEqualTo("platform");
        assertThat(sent.header("X-System")).isEqualTo("T24");
        assertThat(sent.header("Accept")).isEqualTo("application/vnd.t24+json");
        assertThat(sent.header("X-Branch")).isEqualTo("001");
        assertThat(sent.header("X-API-Key")).isEqualTo(API_KEY);
    }

    @Test
    void anOperationWithoutASystemIsSentAsBefore() {
        ConnectorDescriptor d = gateway(t24("/t24", Map.of("X-System", "T24")), getUser(null));

        transport.call(connectorOf(d, server.baseUrl()), getUser(null), Map.of("id", "7"), CallOptions.single(BUDGET));

        LocalServer.Received sent = server.only();
        assertThat(sent.rawPath()).isEqualTo("/users/7");
        assertThat(sent.header("X-System")).isEqualTo("default");
    }

    @Test
    void aSystemWithoutPrefixAddsOnlyItsHeaders() {
        ConnectorDescriptor d = gateway(t24(null, Map.of("X-System", "T24")), getUser("t24"));

        transport.call(connectorOf(d, server.baseUrl()), getUser("t24"), Map.of("id", "7"), CallOptions.single(BUDGET));

        LocalServer.Received sent = server.only();
        assertThat(sent.rawPath()).isEqualTo("/users/7");
        assertThat(sent.header("X-System")).isEqualTo("T24");
    }

    @Test
    void anUnsafePrefixIsRefusedBeforeAnythingIsSent() {
        for (String bad : List.of("/../admin", "//evil.example", "https://evil.example", "t24", "/a?x", "/a#f",
                "/a@b", "/%2e%2e", "/{id}")) {
            ConnectorDescriptor d = gateway(t24(bad, null), getUser("t24"));
            try {
                transport.call(connectorOf(d, server.baseUrl()), getUser("t24"), Map.of("id", "7"),
                        CallOptions.single(BUDGET));
                throw new AssertionError("expected a refusal for " + bad);
            } catch (ConnectorException e) {
                assertThat(e.code()).as(bad).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
                assertThat(e.getMessage()).as(bad).doesNotContain("evil.example");
            }
        }
        assertThat(server.received()).isEmpty();
    }

    @Test
    void anOperationNamingAnUndeclaredSystemIsAConfigError() {
        ConnectorDescriptor d = gateway(null, getUser("t24"));

        try {
            transport.call(connectorOf(d, server.baseUrl()), getUser("t24"), Map.of("id", "7"),
                    CallOptions.single(BUDGET));
            throw new AssertionError("expected a refusal");
        } catch (ConnectorException e) {
            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);
        }
        assertThat(server.received()).isEmpty();
    }

    @Test
    void pathPrefixIsCheckedAndNormalised() {
        assertThat(RequestUrls.pathPrefix(null)).isEmpty();
        assertThat(RequestUrls.pathPrefix(t24(null, null))).isEmpty();
        assertThat(RequestUrls.pathPrefix(t24("/", null))).isEmpty();
        assertThat(RequestUrls.pathPrefix(t24("/t24/v1/", null))).isEqualTo("/t24/v1");
    }
}
