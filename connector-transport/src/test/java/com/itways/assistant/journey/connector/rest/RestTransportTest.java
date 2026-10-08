package com.itways.assistant.journey.connector.rest;

import static com.itways.assistant.journey.connector.MockBankDescriptors.API_KEY;
import static com.itways.assistant.journey.connector.MockBankDescriptors.GET_ACCOUNT_BALANCE;
import static com.itways.assistant.journey.connector.MockBankDescriptors.PING;
import static com.itways.assistant.journey.connector.MockBankDescriptors.STOP_CARD;
import static com.itways.assistant.journey.connector.MockBankDescriptors.field;
import static com.itways.assistant.journey.connector.MockBankDescriptors.connector;
import static com.itways.assistant.journey.connector.MockBankDescriptors.mockBank;
import static com.itways.assistant.journey.connector.MockBankDescriptors.node;
import static com.itways.assistant.journey.connector.MockBankDescriptors.object;
import static com.itways.assistant.journey.connector.MockBankDescriptors.operation;
import static com.itways.assistant.journey.connector.MockBankDescriptors.ordered;
import static com.itways.assistant.journey.connector.MockBankDescriptors.withAuth;
import static com.itways.assistant.journey.connector.MockBankDescriptors.withDefaultHeaders;
import static com.itways.assistant.journey.connector.MockBankDescriptors.withOperations;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.connector.CallOptions;
import com.itways.assistant.journey.connector.CallResult;
import com.itways.assistant.journey.connector.EgressPolicy;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.connector.LocalServer;
import com.itways.assistant.journey.connector.LocalServer.Reply;
import com.itways.assistant.journey.connector.MockBankDescriptors;
import com.itways.assistant.journey.connector.http.RequestUrls;
import com.itways.assistant.journey.model.connector.AuthScheme;
import com.itways.assistant.journey.model.connector.ConnectorDescriptor;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import com.itways.assistant.journey.model.connector.RiskLevel;
import com.itways.assistant.journey.model.connector.SchemaNode;
import com.itways.common.net.HostAllowList;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The REST transport against a loopback server that records exactly what
 * arrived. The server is reachable only because the policy allow-lists
 * {@code 127.0.0.1} and tolerates http; the first tests show that without
 * either the very same call is refused before anything is sent.
 */
class RestTransportTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);

    private LocalServer server;
    private final List<Duration> sleeps = new ArrayList<>();
    private RestTransport transport;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void start() throws IOException {
        server = new LocalServer();
        transport = transport(localPolicy());
        logger = (Logger) LoggerFactory.getLogger(RestTransport.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        logger.detachAppender(logs);
        transport.close();
        server.close();
    }

    private static EgressPolicy localPolicy() {
        return EgressPolicy.STRICT.withPrivateHosts(List.of("127.0.0.1")).withAllowHttp(true);
    }

    private RestTransport transport(EgressPolicy policy) {
        return new RestTransport(policy, new ObjectMapper(), sleeps::add);
    }

    private ResolvedConnector bank() {
        return MockBankDescriptors.mockBankConnector(server.baseUrl());
    }

    private static CallOptions once() {
        return CallOptions.single(BUDGET);
    }

    private static CallOptions attempts(int maxAttempts, String idempotencyKey) {
        return new CallOptions(BUDGET, null, maxAttempts, idempotencyKey);
    }

    private static ConnectorException failure(Runnable call) {
        try {
            call.run();
        } catch (ConnectorException e) {
            return e;
        }
        throw new AssertionError("expected a ConnectorException");
    }

    /** Every captured line: formatted messages plus any attached cause's message. */
    private String logText() {
        return logs.list.stream()
                .map(event -> event.getFormattedMessage()
                        + (event.getThrowableProxy() != null ? " " + event.getThrowableProxy().getMessage() : ""))
                .collect(Collectors.joining("\n"));
    }

    private static final Map<String, Object> BALANCE_INPUTS = Map.of("accountNo", "1234567890", "asOf", "2026-10-01");

    // ---- egress gates -----------------------------------------------------------------------------

    @Nested
    class Egress {

        @Test
        void withoutPrivateHostsTheLoopbackServerIsRefused() {
            try (RestTransport strict = transport(EgressPolicy.STRICT.withAllowHttp(true))) {
                ConnectorException e = failure(() -> strict.call(bank(), PING, Map.of(), once()));

                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
                assertThat(e.retryable()).isFalse();
                assertThat(e.attempts()).isZero();
            }
            assertThat(server.received()).isEmpty();
        }

        @Test
        void withoutAllowHttpAnHttpBaseUrlIsRefused() {
            try (RestTransport httpsOnly = transport(EgressPolicy.STRICT.withPrivateHosts(List.of("127.0.0.1")))) {
                ConnectorException e = failure(() -> httpsOnly.call(bank(), PING, Map.of(), once()));

                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
                assertThat(e.getMessage()).contains("must use https");
            }
            assertThat(server.received()).isEmpty();
        }

        /**
         * A port that speaks plain HTTP answers the TLS hello with an HTTP line:
         * the handshake fails, and the transport must report that rather than
         * retry over http. (The JDK's HttpServer would sit on the binary hello,
         * so a raw socket plays the plain-text server here.)
         */
        @Test
        void anHttpsBaseUrlNeverFallsBackToPlainHttp() throws Exception {
            try (ServerSocket plain = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                AtomicInteger connections = new AtomicInteger();
                Thread answerer = new Thread(() -> {
                    try (java.net.Socket socket = plain.accept()) {
                        connections.incrementAndGet();
                        socket.getInputStream().read();
                        socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                    } catch (IOException ignored) {
                        // the test is over
                    }
                });
                answerer.setDaemon(true);
                answerer.start();
                ResolvedConnector tls = connector(mockBank(), "https://127.0.0.1:" + plain.getLocalPort(),
                        Map.of(), Map.of("apiKey", API_KEY));

                ConnectorException e = failure(() -> transport.call(tls, PING, Map.of(), attempts(3, null)));

                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
                assertThat(e.retryable()).isFalse();
                assertThat(e.getMessage()).contains("TLS");
                assertThat(e.attempts()).isEqualTo(1);
                assertThat(connections).hasValue(1);
            }
            assertThat(server.received()).isEmpty();
        }

        @Test
        void baseUrlWithUserInfoOrQueryIsRefused() {
            String withUser = "http://user:pw@127.0.0.1:" + server.port();
            String withQuery = server.baseUrl() + "/?x=1";
            for (String baseUrl : List.of(withUser, withQuery, "not a url", "ftp://127.0.0.1/")) {
                ResolvedConnector bad = connector(mockBank(), baseUrl, Map.of(), Map.of("apiKey", API_KEY));
                ConnectorException e = failure(() -> transport.call(bad, PING, Map.of(), once()));
                assertThat(e.code()).as(baseUrl).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
            }
            assertThat(server.received()).isEmpty();
        }

        @Test
        void connectorAllowedHostsMustContainTheBaseHost() {
            ResolvedConnector other = connector(mockBank(), server.baseUrl(), List.of("bank.example"), Map.of(),
                    Map.of("apiKey", API_KEY));

            ConnectorException e = failure(() -> transport.call(other, PING, Map.of(), once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
            assertThat(e.getMessage()).contains("connector's allowed hosts");
            assertThat(server.received()).isEmpty();
        }

        @Test
        void platformBoundToAnotherHostRefusesTheCall() {
            EgressPolicy bounded = new EgressPolicy(HostAllowList.of(List.of("127.0.0.1")),
                    HostAllowList.of(List.of("other.example")), true, EgressPolicy.DEFAULT_MAX_RESPONSE_BYTES, null);
            try (RestTransport t = transport(bounded)) {
                ConnectorException e = failure(() -> t.call(bank(), PING, Map.of(), once()));

                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
                assertThat(e.getMessage()).contains("platform's allowed hosts");
            }
            assertThat(server.received()).isEmpty();
        }

        @Test
        void aHostResolvingToAPrivateAddressIsRefusedBeforeAnythingIsSent() {
            EgressPolicy stubbed = localPolicy()
                    .withDns(host -> new InetAddress[] { InetAddress.getByName("10.0.0.7") });
            ResolvedConnector internal = connector(mockBank(), "http://bank.example:" + server.port(), Map.of(),
                    Map.of("apiKey", API_KEY));
            try (RestTransport t = transport(stubbed)) {
                ConnectorException e = failure(() -> t.call(internal, PING, Map.of(), once()));

                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
                assertThat(e.getMessage()).contains("bank.example").doesNotContain("10.0.0.7");
            }
            assertThat(server.received()).isEmpty();
        }

        @Test
        void anAbsoluteOperationPathIsRefused() {
            ConnectorOperation evil = operation("evil", "GET", "https://evil.example/x", RiskLevel.LOW, true, null,
                    null, SchemaNode.EMPTY_OBJECT, null, null);

            ConnectorException e = failure(() -> transport.call(bank(), evil, Map.of(), once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
            assertThat(server.received()).isEmpty();
        }

        @Test
        void pathParameterValuesCannotLeaveTheBasePath() {
            ConnectorOperation item = operation("item", "GET", "/items/{id}", RiskLevel.LOW, true, null,
                    object(ordered("id", node("string").in(SchemaNode.IN_PATH).build()), List.of("id")),
                    SchemaNode.EMPTY_OBJECT, null, null);
            ResolvedConnector prefixed = connector(mockBank(), server.baseUrl() + "/api/v1/", Map.of(),
                    Map.of("apiKey", API_KEY));

            for (String value : List.of("../admin", "..%2Fadmin", "a@b", "x?y=1", "x#f", "//evil", "..", ".")) {
                int before = server.received().size();
                try {
                    transport.call(prefixed, item, Map.of("id", value), once());
                    LocalServer.Received sent = server.received().get(before);
                    assertThat(sent.rawPath()).as(value).isEqualTo("/api/v1/items/" + RequestUrls.percentEncode(value));
                    assertThat(sent.rawQuery()).as(value).isNull();
                } catch (ConnectorException e) {
                    assertThat(e.code()).as(value).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
                    assertThat(server.received()).hasSize(before);
                }
            }
            // Whatever arrived is one segment under the base path: no slash, no dot segment, no raw '@'.
            assertThat(server.received()).isNotEmpty().allSatisfy(r -> {
                assertThat(r.rawPath()).startsWith("/api/v1/items/");
                String segment = r.rawPath().substring("/api/v1/items/".length());
                assertThat(segment).doesNotContain("/", "@", "?", "#").isNotIn(".", "..");
            });
        }

        @Test
        void aRedirectIsNotFollowedAndItsTargetIsNeverNamed() {
            server.respond(r -> r.rawPath().equals("/health")
                    ? new Reply(302, Map.of("Location", server.baseUrl() + "/private"), "", 0)
                    : Reply.json(200, "{\"leak\":true}"));

            ConnectorException e = failure(() -> transport.call(bank(), PING, Map.of(), attempts(3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.REJECTED);
            assertThat(e.status()).isEqualTo(302);
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).contains("redirect not followed").doesNotContain("private");
            assertThat(server.received()).hasSize(1).noneMatch(r -> r.rawPath().equals("/private"));
        }
    }

    // ---- happy paths -------------------------------------------------------------------------------

    @Nested
    class Requests {

        @Test
        @SuppressWarnings("unchecked")
        void getWithPathAndQueryInputsAndApiKeyHeader() {
            server.respond(r -> Reply.json(200, "{\"balance\":120.5,\"currency\":\"JOD\",\"iban\":\"JO94\"}")
                    .withHeader("X-Request-Id", "req-1").withHeader("Set-Cookie", "sid=1"));

            CallResult result = transport.call(bank(), GET_ACCOUNT_BALANCE, BALANCE_INPUTS, once());

            LocalServer.Received sent = server.only();
            assertThat(sent.method()).isEqualTo("GET");
            assertThat(sent.rawPath()).isEqualTo("/accounts/1234567890/balance");
            assertThat(sent.rawQuery()).isEqualTo("asOf=2026-10-01");
            assertThat(sent.header("X-API-Key")).isEqualTo(API_KEY);
            assertThat(sent.header("Accept")).isEqualTo("application/json");
            assertThat(sent.body()).isEmpty();

            assertThat(result.status()).isEqualTo(200);
            Map<String, Object> body = (Map<String, Object>) result.body();
            assertThat(body).containsEntry("balance", 120.5).containsEntry("currency", "JOD");
            assertThat(result.headers()).containsEntry("x-request-id", "req-1").doesNotContainKey("set-cookie");
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(result.latencyMs()).isBetween(0L, BUDGET.toMillis());
        }

        @Test
        void apiKeyInTheQueryReachesTheServerAndNeverTheLog() {
            AuthScheme inQuery = new AuthScheme(AuthScheme.SCHEME_API_KEY, AuthScheme.IN_QUERY, "api_key", "apiKey",
                    null, null, null, null, null, null);
            ResolvedConnector connector = connector(withAuth(mockBank(), inQuery, MockBankDescriptors.FIELDS),
                    server.baseUrl(), Map.of(), Map.of("apiKey", API_KEY));
            server.respond(r -> Reply.json(404, "{\"error\":\"no such account for key " + API_KEY + "\"}"));

            failure(() -> transport.call(connector, GET_ACCOUNT_BALANCE, BALANCE_INPUTS, once()));

            LocalServer.Received sent = server.only();
            assertThat(sent.rawQuery()).isEqualTo("asOf=2026-10-01&api_key=" + API_KEY);
            String text = logText();
            assertThat(text).contains("/accounts/{accountNo}/balance").contains("getAccountBalance");
            assertThat(text).doesNotContain(API_KEY).doesNotContain("asOf=").doesNotContain("1234567890")
                    .doesNotContain("api_key=");
        }

        @Test
        void basicAndBearerAreSentAsTheServerExpects() {
            List<Map<String, Object>> fields = List.of(field("user", "text"), field("password", "secret"),
                    field("token", "secret"));
            AuthScheme basic = new AuthScheme(AuthScheme.SCHEME_BASIC, null, null, null, "user", "password", null,
                    null, null, null);
            AuthScheme bearer = new AuthScheme(AuthScheme.SCHEME_BEARER, null, null, "token", null, null, null, null,
                    null, null);
            ResolvedConnector withBasic = connector(withAuth(mockBank(), basic, fields), server.baseUrl(),
                    Map.of("user", "lina"), Map.of("password", "pw-secret-1"));
            ResolvedConnector withBearer = connector(withAuth(mockBank(), bearer, fields), server.baseUrl(),
                    Map.of(), Map.of("token", "tok-secret-1"));

            transport.call(withBasic, PING, Map.of(), once());
            transport.call(withBearer, PING, Map.of(), once());

            List<LocalServer.Received> sent = server.received();
            assertThat(sent.get(0).header("Authorization")).isEqualTo("Basic "
                    + Base64.getEncoder().encodeToString("lina:pw-secret-1".getBytes(StandardCharsets.UTF_8)));
            assertThat(sent.get(1).header("Authorization")).isEqualTo("Bearer tok-secret-1");
            assertThat(logText()).doesNotContain("pw-secret-1").doesNotContain("tok-secret-1");
        }

        @Test
        void aMissingCredentialNamesTheFieldOnly() {
            ResolvedConnector noKey = connector(mockBank(), server.baseUrl(), Map.of(), Map.of());

            ConnectorException e = failure(() -> transport.call(noKey, PING, Map.of(), once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.AUTH_FAILED);
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).contains("'apiKey'");
            assertThat(server.received()).isEmpty();
        }

        @Test
        @SuppressWarnings("unchecked")
        void postSendsATypedJsonBody() throws IOException {
            ConnectorOperation transfer = operation("transfer", "POST", "/transfers", RiskLevel.HIGH, false,
                    "Idempotency-Key",
                    object(ordered(
                            "amount", node("number").build(),
                            "urgent", node("boolean").build(),
                            "to", node("string").as("beneficiary").build()), List.of("amount", "to")),
                    SchemaNode.EMPTY_OBJECT, null, null);
            server.respond(r -> Reply.json(201, "{\"reference\":\"T-1\"}"));

            CallResult result = transport.call(bank(), transfer, Map.of("amount", "12.5", "urgent", "true", "to", "x"),
                    once());

            LocalServer.Received sent = server.only();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.header("Content-Type")).startsWith("application/json");
            assertThat(sent.header("Accept")).isEqualTo("application/json");
            Map<String, Object> body = new ObjectMapper().readValue(sent.body(), Map.class);
            assertThat(body.get("amount")).isInstanceOf(Number.class);
            assertThat(((Number) body.get("amount")).doubleValue()).isEqualTo(12.5);
            assertThat(body.get("urgent")).isEqualTo(Boolean.TRUE);
            assertThat(body).containsEntry("beneficiary", "x").doesNotContainKey("to");
            assertThat(result.status()).isEqualTo(201);
        }

        @Test
        void aGetSendsNoBodyEvenWithBodyInputs() {
            ConnectorOperation search = operation("search", "GET", "/search", RiskLevel.LOW, true, null,
                    object(ordered("q", node("string").build()), List.of()), SchemaNode.EMPTY_OBJECT, null, null);

            transport.call(bank(), search, Map.of("q", "lina"), once());

            assertThat(server.only().body()).isEmpty();
        }

        @Test
        void defaultHeadersThenInputHeadersThenAuthWins() {
            ConnectorDescriptor descriptor = withDefaultHeaders(mockBank(),
                    Map.of("X-Client", "portal", "Accept", "application/vnd.bank+json", "Host", "spoofed"));
            ConnectorOperation op = operation("op", "GET", "/health", RiskLevel.LOW, true, null,
                    object(ordered(
                            "branch", node("string").in(SchemaNode.IN_HEADER).as("X-Branch").build(),
                            "spoof", node("string").in(SchemaNode.IN_HEADER).as("x-api-key").build()), List.of()),
                    SchemaNode.EMPTY_OBJECT, null, null);
            ResolvedConnector connector = connector(descriptor, server.baseUrl(), Map.of(),
                    Map.of("apiKey", API_KEY));

            transport.call(connector, op, Map.of("branch", "001", "spoof", "not-the-key"), once());

            LocalServer.Received sent = server.only();
            assertThat(sent.header("X-Client")).isEqualTo("portal");
            assertThat(sent.header("Accept")).isEqualTo("application/vnd.bank+json");
            assertThat(sent.header("X-Branch")).isEqualTo("001");
            assertThat(sent.header("X-API-Key")).isEqualTo(API_KEY);
            assertThat(sent.header("Host")).startsWith("127.0.0.1");
        }

        @Test
        @SuppressWarnings("unchecked")
        void idFromHeaderBecomesOutputId() {
            ConnectorOperation create = operation("createLead", "POST", "/leads", RiskLevel.MEDIUM, false,
                    "Idempotency-Key", object(ordered("name", node("string").build()), List.of("name")),
                    SchemaNode.EMPTY_OBJECT, null, new ConnectorOperation.ResponseRules("OData-EntityId"));
            server.respond(r -> Reply.json(201, "{\"name\":\"x\"}").withHeader("OData-EntityId", "leads(42)"));

            CallResult result = transport.call(bank(), create, Map.of("name", "x"), once());

            assertThat((Map<String, Object>) result.body()).containsEntry("id", "leads(42)").containsEntry("name", "x");
        }

        @Test
        void bodiesOfOtherShapes() {
            server.respond(r -> Reply.text(200, "text/plain", "pong"));
            assertThat(transport.call(bank(), PING, Map.of(), once()).body()).isEqualTo("pong");

            server.respond(r -> Reply.status(204));
            assertThat(transport.call(bank(), PING, Map.of(), once()).body()).isNull();

            server.respond(r -> Reply.text(200, "text/plain", "[1, 2]"));
            assertThat(transport.call(bank(), PING, Map.of(), once()).body()).isEqualTo(List.of(1, 2));

            server.respond(r -> Reply.json(200, "{not json"));
            ConnectorException e = failure(() -> transport.call(bank(), PING, Map.of(), once()));
            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.RESPONSE_INVALID);
            assertThat(e.retryable()).isFalse();
        }

        @Test
        void aBodyBeyondTheCapIsInvalid() {
            try (RestTransport small = transport(localPolicy().withMaxResponseBytes(64))) {
                server.respond(r -> Reply.json(200, "{\"pad\":\"" + "x".repeat(200) + "\"}"));

                ConnectorException e = failure(() -> small.call(bank(), PING, Map.of(), once()));

                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.RESPONSE_INVALID);
                assertThat(e.status()).isEqualTo(200);
                assertThat(e.getMessage()).contains("64 bytes");
            }
        }
    }

    // ---- statuses ----------------------------------------------------------------------------------

    @Nested
    class Statuses {

        private ConnectorException answer(int status, String body) {
            server.respond(r -> Reply.json(status, body));
            return failure(() -> transport.call(bank(), GET_ACCOUNT_BALANCE, BALANCE_INPUTS, once()));
        }

        @Test
        void a404MapsToTheOperationsOwnCode() {
            ConnectorException e = answer(404, "{\"error\":\"no such account\"}");

            assertThat(e.code()).isEqualTo("ACCOUNT_NOT_FOUND");
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.retryable()).isFalse();
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(e.getMessage()).isEqualTo("GET getAccountBalance answered 404: {\"error\":\"no such account\"}");
        }

        @Test
        void otherStatuses() {
            assertThat(answer(400, "{}")).satisfies(e -> {
                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.REJECTED);
                assertThat(e.retryable()).isFalse();
            });
            assertThat(answer(401, "")).satisfies(e -> {
                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.AUTH_FAILED);
                assertThat(e.retryable()).isFalse();
                assertThat(e.getMessage()).isEqualTo("GET getAccountBalance answered 401");
            });
            assertThat(answer(403, "")).extracting(ConnectorException::code)
                    .isEqualTo(ConnectorErrorCodes.AUTH_FAILED);
            assertThat(answer(500, "{\"oops\":true}")).satisfies(e -> {
                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
                assertThat(e.retryable()).isFalse();
            });
            assertThat(answer(503, "")).satisfies(e -> {
                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
                assertThat(e.retryable()).isTrue();
            });
        }

        @Test
        void errorMessagesNeverCarryASecret() {
            AuthScheme bearer = new AuthScheme(AuthScheme.SCHEME_BEARER, null, null, "apiKey", null, null, null, null,
                    null, null);
            ResolvedConnector connector = connector(withAuth(mockBank(), bearer, MockBankDescriptors.FIELDS),
                    server.baseUrl(), Map.of(), Map.of("apiKey", API_KEY));
            server.respond(r -> Reply.json(400, "{\"error\":\"bad header " + r.header("Authorization") + "\"}"));

            ConnectorException e = failure(() -> transport.call(connector, PING, Map.of(), once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.REJECTED);
            assertThat(e.getMessage()).startsWith("GET ping answered 400: ").doesNotContain(API_KEY)
                    .doesNotContain("Bearer sk-");
            assertThat(logText()).doesNotContain(API_KEY);
        }

        @Test
        void errorBodiesAreCutTo300Characters() {
            ConnectorException e = answer(400, "{\"pad\":\"" + "y".repeat(1000) + "\"}");

            assertThat(e.getMessage()).hasSizeLessThan(350).endsWith("…");
        }
    }

    // ---- retries, timeouts, availability -------------------------------------------------------------

    @Nested
    class Retries {

        @Test
        void theIdempotencyKeyIsSentOnEveryAttempt() {
            AtomicInteger hits = new AtomicInteger();
            server.respond(r -> hits.incrementAndGet() < 3 ? Reply.status(503) : Reply.json(200, "{\"status\":\"STOPPED\"}"));

            CallResult result = transport.call(bank(), STOP_CARD, Map.of("cardId", "c-1"), attempts(3, "key-77"));

            assertThat(result.status()).isEqualTo(200);
            assertThat(result.attempts()).isEqualTo(3);
            List<LocalServer.Received> sent = server.received();
            assertThat(sent).hasSize(3).allSatisfy(r -> {
                assertThat(r.header("Idempotency-Key")).isEqualTo("key-77");
                assertThat(r.rawPath()).isEqualTo("/cards/c-1/stop");
                assertThat(r.body()).isEqualTo("{\"reason\":\"LOST\"}");
            });
            assertThat(sleeps).hasSize(2);
            assertThat(sleeps.get(0).toMillis()).isBetween(150L, 250L);
            assertThat(sleeps.get(1).toMillis()).isBetween(300L, 500L);
        }

        @Test
        void aNonIdempotentPostWithoutAKeyIsNeverRetried() {
            server.respond(r -> Reply.status(503));

            ConnectorException e = failure(() -> transport.call(bank(), STOP_CARD, Map.of("cardId", "c-1"),
                    attempts(3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
            assertThat(e.retryable()).isTrue();
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(server.received()).hasSize(1);
            assertThat(sleeps).isEmpty();
        }

        @Test
        void retryStopsWhenTheBudgetWouldBeExceeded() {
            server.respond(r -> Reply.status(503));

            ConnectorException e = failure(() -> transport.call(bank(), PING, Map.of(),
                    new CallOptions(Duration.ofMillis(150), null, 3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(server.received()).hasSize(1);
            assertThat(sleeps).isEmpty();
        }

        @Test
        void a429IsRetried() {
            AtomicInteger hits = new AtomicInteger();
            server.respond(r -> hits.incrementAndGet() == 1 ? Reply.status(429) : Reply.json(200, "{}"));

            CallResult result = transport.call(bank(), PING, Map.of(), attempts(2, null));

            assertThat(result.attempts()).isEqualTo(2);
            assertThat(server.received()).hasSize(2);
        }

        @Test
        void a500IsNotRetried() {
            server.respond(r -> Reply.status(500));

            ConnectorException e = failure(() -> transport.call(bank(), PING, Map.of(), attempts(3, null)));

            assertThat(e.attempts()).isEqualTo(1);
            assertThat(server.received()).hasSize(1);
        }

        @Test
        void aReadTimeoutIsRetriedForAnIdempotentOperation() {
            AtomicInteger hits = new AtomicInteger();
            server.respond(r -> hits.incrementAndGet() == 1 ? Reply.json(200, "{}").delayed(1500) : Reply.json(200, "{}"));

            CallResult result = transport.call(bank(), PING, Map.of(),
                    new CallOptions(BUDGET, Duration.ofMillis(300), 3, null));

            assertThat(result.status()).isEqualTo(200);
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(server.received()).hasSize(2);
            assertThat(sleeps).hasSize(1);
        }

        @Test
        void aReadTimeoutOnAWriteWithoutAKeyIsATimeoutAfterOneAttempt() {
            server.respond(r -> Reply.json(200, "{}").delayed(800));

            ConnectorException e = failure(() -> transport.call(bank(), STOP_CARD, Map.of("cardId", "c-1"),
                    new CallOptions(BUDGET, Duration.ofMillis(200), 3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.TIMEOUT);
            assertThat(e.retryable()).isTrue();
            assertThat(e.status()).isNull();
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(e.getMessage()).contains("200 ms");
        }

        @Test
        void aRefusedConnectionIsUnavailableAndRetried() throws IOException {
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                closedPort = socket.getLocalPort();
            }
            ResolvedConnector nobody = connector(mockBank(), "http://127.0.0.1:" + closedPort, Map.of(),
                    Map.of("apiKey", API_KEY));

            ConnectorException e = failure(() -> transport.call(nobody, PING, Map.of(), attempts(3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
            assertThat(e.retryable()).isTrue();
            assertThat(e.attempts()).isEqualTo(3);
            assertThat(sleeps).hasSize(2);
            assertThat(e.getMessage()).doesNotContain(String.valueOf(closedPort));
        }
    }

    // ---- OAuth2 client credentials ---------------------------------------------------------------------

    @Nested
    class OAuth2 {

        private static final String CLIENT_SECRET = "cs-topsecret-9999";

        private final AtomicInteger tokenRequests = new AtomicInteger();
        private final AtomicReference<String> accepted = new AtomicReference<>("tok-1");

        private ResolvedConnector oauthBank() {
            AuthScheme oauth = new AuthScheme(AuthScheme.SCHEME_OAUTH2_CLIENT_CREDENTIALS, null, null, null, null,
                    null, server.baseUrl() + "/oauth/{tenant}/token", "clientId", "clientSecret", "scope");
            List<Map<String, Object>> fields = List.of(field("clientId", "text"), field("clientSecret", "secret"),
                    field("scope", "text"), field("tenant", "text"));
            return connector(withAuth(mockBank(), oauth, fields), server.baseUrl(),
                    Map.of("clientId", "cid-1", "scope", "read", "tenant", "acme"),
                    Map.of("clientSecret", CLIENT_SECRET));
        }

        @BeforeEach
        void tokenEndpoint() {
            server.respond(r -> {
                if (r.rawPath().equals("/oauth/acme/token")) {
                    int n = tokenRequests.incrementAndGet();
                    return Reply.json(200, "{\"access_token\":\"tok-" + n + "\",\"expires_in\":3600}");
                }
                return ("Bearer " + accepted.get()).equals(r.header("Authorization"))
                        ? Reply.json(200, "{\"status\":\"ok\"}")
                        : Reply.json(401, "{\"error\":\"invalid_token\"}");
            });
        }

        @Test
        void theTokenIsFetchedOnceAndReused() {
            transport.call(oauthBank(), PING, Map.of(), once());
            transport.call(oauthBank(), PING, Map.of(), once());

            assertThat(tokenRequests).hasValue(1);
            List<LocalServer.Received> sent = server.received();
            assertThat(sent).hasSize(3);
            LocalServer.Received token = sent.get(0);
            assertThat(token.method()).isEqualTo("POST");
            assertThat(token.header("Content-Type")).startsWith("application/x-www-form-urlencoded");
            assertThat(token.body())
                    .isEqualTo("grant_type=client_credentials&client_id=cid-1&client_secret=" + CLIENT_SECRET + "&scope=read");
            assertThat(sent.get(1).header("Authorization")).isEqualTo("Bearer tok-1");
            assertThat(sent.get(2).header("Authorization")).isEqualTo("Bearer tok-1");
            assertThat(logText()).doesNotContain(CLIENT_SECRET);
        }

        @Test
        void a401RefreshesTheTokenOnceAndResends() {
            transport.call(oauthBank(), PING, Map.of(), once());
            accepted.set("tok-2");

            CallResult result = transport.call(oauthBank(), PING, Map.of(), once());

            assertThat(result.status()).isEqualTo(200);
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(tokenRequests).hasValue(2);
            List<LocalServer.Received> health = server.received().stream()
                    .filter(r -> r.rawPath().equals("/health")).toList();
            assertThat(health).hasSize(3);
            assertThat(health.get(1).header("Authorization")).isEqualTo("Bearer tok-1");
            assertThat(health.get(2).header("Authorization")).isEqualTo("Bearer tok-2");
        }

        @Test
        void aSecondRefusalIsAnAuthFailureNotALoop() {
            accepted.set("never");

            ConnectorException e = failure(() -> transport.call(oauthBank(), PING, Map.of(), attempts(3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.AUTH_FAILED);
            assertThat(e.status()).isEqualTo(401);
            assertThat(e.attempts()).isEqualTo(2);
            assertThat(tokenRequests).hasValue(2);
        }

        @Test
        void aTokenEndpointRefusalIsAnAuthFailureWithoutTheSecret() {
            server.respond(r -> Reply.json(400, "{\"error\":\"invalid_client\",\"echo\":\"" + r.body() + "\"}"));

            ConnectorException e = failure(() -> transport.call(oauthBank(), PING, Map.of(), once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.AUTH_FAILED);
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).startsWith("token endpoint answered 400").doesNotContain(CLIENT_SECRET);
            assertThat(logText()).doesNotContain(CLIENT_SECRET);
            assertThat(server.received()).hasSize(1);
        }

        @Test
        void anUpstreamErrorThatEchoesTheAccessTokenIsScrubbed() {
            String token = "opaque-access-token-77";
            server.respond(r -> {
                if (r.rawPath().equals("/oauth/acme/token")) {
                    return Reply.json(200, "{\"access_token\":\"" + token + "\",\"expires_in\":3600}");
                }
                // A 500 that prints the request it could not handle, bearer and all, plus the bare token.
                return Reply.json(500, "{\"error\":\"boom\",\"request\":{\"authorization\":\""
                        + r.header("Authorization") + "\"},\"token\":\"" + token + "\"}");
            });

            ConnectorException e = failure(() -> transport.call(oauthBank(), PING, Map.of(), once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
            assertThat(e.status()).isEqualTo(500);
            assertThat(e.getMessage()).startsWith("GET ping answered 500").doesNotContain(token);
            assertThat(logText()).doesNotContain(token);
            assertThat(transport.scrubValues(oauthBank())).contains(token, CLIENT_SECRET);
        }

        @Test
        void aTokenEndpointThatCannotBeTrustedWithArithmeticStillYieldsAToken() {
            for (String expiresIn : List.of("9223372036854775807", "-1", "\"soon\"", "300.5", "1e400", "null",
                    "true", "\"\"")) {
                tokenRequests.set(0);
                server.respond(r -> {
                    if (r.rawPath().equals("/oauth/acme/token")) {
                        tokenRequests.incrementAndGet();
                        return Reply.json(200, "{\"access_token\":\"tok-1\",\"expires_in\":" + expiresIn + "}");
                    }
                    return Reply.json(200, "{\"status\":\"ok\"}");
                });
                // A fresh connector per value: the cache key is the connector id and version.
                RestTransport fresh = transport(localPolicy());
                try {
                    CallResult result = fresh.call(oauthBank(), PING, Map.of(), once());
                    assertThat(result.status()).as("expires_in=" + expiresIn).isEqualTo(200);
                    assertThat(tokenRequests).as("expires_in=" + expiresIn).hasValue(1);
                } finally {
                    fresh.close();
                }
            }
        }

        @Test
        void aTokenUrlPlaceholderMayNotNameASecret() {
            AuthScheme oauth = new AuthScheme(AuthScheme.SCHEME_OAUTH2_CLIENT_CREDENTIALS, null, null, null, null,
                    null, server.baseUrl() + "/oauth/{clientSecret}/token", "clientId", "clientSecret", null);
            ResolvedConnector leaky = connector(
                    withAuth(mockBank(), oauth, List.of(field("clientId", "text"), field("clientSecret", "secret"))),
                    server.baseUrl(), Map.of("clientId", "cid-1"), Map.of("clientSecret", CLIENT_SECRET));

            ConnectorException e = failure(() -> transport.call(leaky, PING, Map.of(), once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);
            assertThat(e.getMessage()).doesNotContain(CLIENT_SECRET);
            assertThat(server.received()).isEmpty();
        }
    }

    // ---- test() ------------------------------------------------------------------------------------

    @Nested
    class ConnectionTest {

        @Test
        void runsTheDeclaredTestOperation() {
            CallResult result = transport.test(bank(), BUDGET);

            assertThat(result.status()).isEqualTo(200);
            assertThat(server.only().rawPath()).isEqualTo("/health");
            assertThat(server.only().header("X-API-Key")).isEqualTo(API_KEY);
        }

        @Test
        void fallsBackToTheFirstIdempotentGet() {
            ResolvedConnector connector = connector(withOperations(mockBank(), null, STOP_CARD, PING),
                    server.baseUrl(), Map.of(), Map.of("apiKey", API_KEY));

            transport.test(connector, BUDGET);

            assertThat(server.only().rawPath()).isEqualTo("/health");
        }

        @Test
        void failsWhenTheTypeHasNothingTestable() {
            ResolvedConnector connector = connector(withOperations(mockBank(), null, STOP_CARD),
                    server.baseUrl(), Map.of(), Map.of("apiKey", API_KEY));

            ConnectorException e = failure(() -> transport.test(connector, BUDGET));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);
            assertThat(e.getMessage()).isEqualTo("type declares no testable operation");
            assertThat(server.received()).isEmpty();
        }
    }
}
