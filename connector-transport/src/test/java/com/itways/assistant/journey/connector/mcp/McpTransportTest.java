package com.itways.assistant.journey.connector.mcp;

import static com.itways.assistant.journey.connector.MockBankDescriptors.field;
import static com.itways.assistant.journey.connector.MockBankDescriptors.node;
import static com.itways.assistant.journey.connector.MockBankDescriptors.object;
import static com.itways.assistant.journey.connector.MockBankDescriptors.ordered;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.connector.CallOptions;
import com.itways.assistant.journey.connector.CallResult;
import com.itways.assistant.journey.connector.EgressPolicy;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.connector.ConnectorTransports;
import com.itways.assistant.journey.connector.LocalServer;
import com.itways.assistant.journey.connector.LocalServer.Reply;
import com.itways.assistant.journey.connector.OutputMasker;
import com.itways.assistant.journey.connector.mcp.FakeMcpServer.ToolAnswer;
import com.itways.assistant.journey.connector.rest.RestTransport;
import com.itways.assistant.journey.model.connector.AuthScheme;
import com.itways.assistant.journey.model.connector.ConnectorDefaults;
import com.itways.assistant.journey.model.connector.ConnectorDescriptor;
import com.itways.assistant.journey.model.connector.ConnectorDescriptorValidator;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import com.itways.assistant.journey.model.connector.RiskLevel;
import com.itways.assistant.journey.model.connector.SchemaNode;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The MCP transport against a loopback MCP server that records exactly what
 * arrived, in the current stateless revision and the legacy handshake one,
 * answering as JSON and as SSE. Reachable only because the policy allow-lists
 * {@code 127.0.0.1} and tolerates http.
 */
class McpTransportTest {

    private static final Duration BUDGET = Duration.ofSeconds(5);
    static final String TOKEN = "tok-verysecret-5678";
    static final UUID CRM = UUID.fromString("00000000-0000-0000-0000-00000000c0de");

    static final AuthScheme BEARER = new AuthScheme(AuthScheme.SCHEME_BEARER, null, null, "token", null, null, null,
            null, null, null);
    static final List<Map<String, Object>> FIELDS = List.of(field("baseUrl", "url"), field("token", "secret"));

    /** A read: idempotent, a sensitive output, an argument renamed on the wire. */
    static final ConnectorOperation FIND_CONTACT = tool("findContact", "crm_find_contact", true, null,
            object(ordered(
                    "email", node("string").build(),
                    "limit", node("integer").as("max_results").defaultValue(5).build()), List.of("email")),
            object(ordered(
                    "id", node("string").build(),
                    "phone", node("string").sensitive().build()), List.of()));

    /** A write: not idempotent, the key travels in the {@code requestId} argument. */
    static final ConnectorOperation CREATE_TICKET = tool("createTicket", null, false, "requestId",
            object(ordered("subject", node("string").build()), List.of("subject")),
            object(ordered("ticketId", node("string").build()), List.of()));

    /** A write without anywhere to put a key. */
    static final ConnectorOperation SEND_NOTE = tool("sendNote", null, false, null,
            object(ordered("text", node("string").build()), List.of("text")), SchemaNode.EMPTY_OBJECT);

    private LocalServer server;
    private FakeMcpServer mcp;
    private final List<Duration> sleeps = new ArrayList<>();
    private McpTransport transport;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void start() throws IOException {
        server = new LocalServer();
        mcp = new FakeMcpServer()
                .tool("crm_find_contact", FakeMcpServer.schema("email"), args -> ToolAnswer.structured(
                        Map.of("id", "c-1", "phone", "+962790000000", "email", args.path("email").asText())))
                .tool("createTicket", FakeMcpServer.schema("subject"),
                        args -> ToolAnswer.text("{\"ticketId\":\"T-" + args.path("subject").asText() + "\"}"))
                .tool("sendNote", FakeMcpServer.schema("text"), args -> ToolAnswer.text("noted"));
        server.respond(mcp);
        transport = transport(localPolicy());
        logger = (Logger) LoggerFactory.getLogger(McpTransport.class);
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

    private McpTransport transport(EgressPolicy policy) {
        return new McpTransport(policy, new ObjectMapper(), sleeps::add);
    }

    private ResolvedConnector crm() {
        return crm(descriptor(null, FIND_CONTACT, CREATE_TICKET, SEND_NOTE));
    }

    private ResolvedConnector crm(ConnectorDescriptor descriptor) {
        return connector(descriptor, server.baseUrl() + "/mcp", Map.of("token", TOKEN));
    }

    private static ResolvedConnector connector(ConnectorDescriptor descriptor, String baseUrl,
            Map<String, String> secrets) {
        return new ResolvedConnector(CRM, "Mock CRM (test)", descriptor.transport(),
                descriptor, baseUrl, null, Map.of(), secrets, 3L);
    }

    static ConnectorDescriptor descriptor(String protocolVersion, ConnectorOperation... operations) {
        return descriptor(BEARER, FIELDS, protocolVersion, null, operations);
    }

    static ConnectorDescriptor descriptor(AuthScheme auth, List<Map<String, Object>> fields, String protocolVersion,
            String testOperation, ConnectorOperation... operations) {
        ConnectorDescriptor d = new ConnectorDescriptor("mock-crm-mcp", "Mock CRM (MCP)", null,
                ConnectorDescriptor.TRANSPORT_MCP, 1, auth, fields, List.of(),
                new ConnectorDefaults(3000, 5000, new ConnectorDefaults.Retry(3, 200), null),
                testOperation != null ? new ConnectorDescriptor.TestOperation(testOperation) : null, null,
                List.of(operations), protocolVersion != null ? new ConnectorDescriptor.McpSettings(protocolVersion) : null);
        assertThat(ConnectorDescriptorValidator.problems(d)).as("fixture descriptor").isEmpty();
        return d;
    }

    static ConnectorOperation tool(String key, String toolName, Boolean idempotent, String idempotencyArgument,
            SchemaNode input, SchemaNode output) {
        return new ConnectorOperation(key, key, null, null, null, RiskLevel.LOW, idempotent, null, input, output, null,
                null, null, toolName, idempotencyArgument);
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

    private String logText() {
        return logs.list.stream()
                .map(event -> event.getFormattedMessage()
                        + (event.getThrowableProxy() != null ? " " + event.getThrowableProxy().getMessage() : ""))
                .collect(Collectors.joining("\n"));
    }

    private static final Map<String, Object> FIND_INPUTS = Map.of("email", "lina@example.com", "limit", "3");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object body) {
        return (Map<String, Object>) body;
    }

    // ---- the current revision ---------------------------------------------------------------------

    @Nested
    class Stateless {

        @Test
        void aToolCallCarriesTheHeadersMetaAndBoundArgumentsAndReturnsStructuredContent() throws Exception {
            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());

            LocalServer.Received sent = server.only();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.rawPath()).isEqualTo("/mcp");
            assertThat(sent.rawQuery()).isNull();
            assertThat(sent.header("Accept")).isEqualTo("application/json, text/event-stream");
            assertThat(sent.header("Content-Type")).startsWith("application/json");
            assertThat(sent.header("MCP-Protocol-Version")).isEqualTo("2026-07-28");
            assertThat(sent.header("Mcp-Method")).isEqualTo("tools/call");
            assertThat(sent.header("Mcp-Name")).isEqualTo("crm_find_contact");
            assertThat(sent.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(sent.header("Mcp-Session-Id")).isNull();
            JsonNode body = new ObjectMapper().readTree(sent.body());
            assertThat(body.path("jsonrpc").asText()).isEqualTo("2.0");
            assertThat(body.path("method").asText()).isEqualTo("tools/call");
            assertThat(body.path("id").isNumber()).isTrue();
            JsonNode params = body.path("params");
            assertThat(params.path("name").asText()).isEqualTo("crm_find_contact");
            assertThat(params.path("arguments").path("email").asText()).isEqualTo("lina@example.com");
            assertThat(params.path("arguments").path("max_results").asInt()).isEqualTo(3);
            assertThat(params.path("arguments").has("limit")).isFalse();
            JsonNode meta = params.path("_meta");
            assertThat(meta.path("io.modelcontextprotocol/protocolVersion").asText()).isEqualTo("2026-07-28");
            assertThat(meta.path("io.modelcontextprotocol/clientCapabilities").isObject()).isTrue();
            assertThat(meta.path("io.modelcontextprotocol/clientInfo").path("name").asText()).isEqualTo("connector-transport");

            assertThat(result.status()).isEqualTo(200);
            assertThat(map(result.body())).containsEntry("id", "c-1").containsEntry("phone", "+962790000000")
                    .containsEntry("email", "lina@example.com");
            assertThat(result.attempts()).isEqualTo(1);
            assertThat(result.latencyMs()).isBetween(0L, BUDGET.toMillis());
            assertThat(result.headers()).containsKey("content-type").doesNotContainKey("mcp-session-id");
        }

        @Test
        void theSecondCallSkipsNegotiationAndNothingSecretReachesTheLog() {
            transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());
            transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());

            assertThat(mcp.methods).containsExactly("tools/call", "tools/call");
            String text = logText();
            assertThat(text).contains("tool=crm_find_contact").contains("host=127.0.0.1").contains("path=/mcp");
            assertThat(text).doesNotContain(TOKEN).doesNotContain("lina@example.com").doesNotContain("+962790000000")
                    .doesNotContain("Bearer");
        }

        @Test
        void theOutputIsMaskedByTheOperationsSchemaLikeRest() {
            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());

            Map<String, Object> shaped = map(OutputMasker.maskAndLimit(FIND_CONTACT.outputOrEmpty(), result.body()));
            assertThat(shaped).containsEntry("id", "c-1").containsEntry("phone", OutputMasker.MASK)
                    .doesNotContainKey("email");
        }

        @Test
        void textContentThatIsJsonBecomesTheBodyAndPlainTextIsWrapped() {
            CallResult json = transport.call(crm(), CREATE_TICKET, Map.of("subject", "x"), once());
            assertThat(map(json.body())).containsEntry("ticketId", "T-x");

            CallResult text = transport.call(crm(), SEND_NOTE, Map.of("text", "hello"), once());
            assertThat(map(text.body())).containsExactly(Map.entry("text", "noted"));
        }

        @Test
        void imageAndResourceBlocksAreDroppedAndTheFirstTextKept() {
            mcp.tool("mixed", FakeMcpServer.schema(), args -> new ToolAnswer(null, "first", false, List.of(
                    Map.of("type", "image", "data", "AAAA", "mimeType", "image/png"),
                    Map.of("type", "resource_link", "uri", "file:///x", "name", "x")), null));
            ConnectorOperation mixed = tool("mixed", null, true, null, null, SchemaNode.EMPTY_OBJECT);

            CallResult result = transport.call(crm(descriptor(null, mixed)), mixed, Map.of(), once());

            assertThat(map(result.body())).containsExactly(Map.entry("text", "first"));
        }

        @Test
        void aToolErrorIsRejectedNotRetriedAndCarriesTheScrubbedText() {
            mcp.tool("failing", FakeMcpServer.schema(), args -> ToolAnswer.error("no such contact; token " + TOKEN));
            ConnectorOperation failing = tool("failing", null, true, null, null, SchemaNode.EMPTY_OBJECT);

            ConnectorException e = failure(() -> transport.call(crm(descriptor(null, failing)), failing, Map.of(),
                    attempts(3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.REJECTED);
            assertThat(e.retryable()).isFalse();
            assertThat(e.status()).isNull();
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(e.getMessage()).contains("reported an error").contains("no such contact").doesNotContain(TOKEN);
            assertThat(server.received()).hasSize(1);
            assertThat(sleeps).isEmpty();
        }

        @Test
        void anInputRequiredResultIsRejected() {
            mcp.tool("asking", FakeMcpServer.schema(), args -> new ToolAnswer(null, null, false, List.of(),
                    "input_required"));
            ConnectorOperation asking = tool("asking", null, true, null, null, SchemaNode.EMPTY_OBJECT);

            ConnectorException e = failure(() -> transport.call(crm(descriptor(null, asking)), asking, Map.of(),
                    once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.REJECTED);
            assertThat(e.getMessage()).contains("input_required");
        }

        @Test
        void anUnknownToolAndAnUnknownMethodAreConfigurationProblems() {
            ConnectorOperation gone = tool("gone", "crm_gone", true, null, null, SchemaNode.EMPTY_OBJECT);
            ConnectorException unknownTool = failure(() -> transport.call(crm(descriptor(null, gone)), gone, Map.of(),
                    attempts(3, null)));
            assertThat(unknownTool.code()).isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);
            assertThat(unknownTool.status()).isEqualTo(400);
            assertThat(unknownTool.getMessage()).contains("-32602").contains("Unknown tool: crm_gone");
            assertThat(server.received()).hasSize(1);

            // A server without tools at all: -32601 with 404 (not a session problem, not a fallback).
            server.respond(r -> Reply.json(404, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}"));
            ConnectorException unknownMethod = failure(() -> transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once()));
            assertThat(unknownMethod.code()).isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);
            assertThat(unknownMethod.getMessage()).contains("does not implement tools/call");
        }

        @Test
        void invalidInputsFailBeforeAnythingIsSent() {
            ConnectorException e = failure(() -> transport.call(crm(), FIND_CONTACT, Map.of("limit", "x"), once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.INPUT_INVALID);
            assertThat(e.details()).contains("email: required", "limit: must be an integer");
            assertThat(server.received()).isEmpty();
        }

        @Test
        void anSseFramedAnswerWithANotificationFirstIsRead() {
            mcp.sse = true;
            mcp.notificationFirst = true;

            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());

            assertThat(map(result.body())).containsEntry("id", "c-1");
            assertThat(result.attempts()).isEqualTo(1);
        }

        @Test
        void aStreamTheServerKeepsOpenAfterTheResponseDoesNotHoldTheCall() {
            mcp.sse = true;
            mcp.holdOpenMs = 4000;

            long started = System.nanoTime();
            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS,
                    new CallOptions(BUDGET, Duration.ofSeconds(3), 1, null));

            assertThat(map(result.body())).containsEntry("id", "c-1");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        }

        @Test
        void anSseErrorIsMappedLikeAJsonOne() {
            mcp.sse = true;
            ConnectorOperation gone = tool("gone", null, true, null, null, SchemaNode.EMPTY_OBJECT);

            ConnectorException e = failure(() -> transport.call(crm(descriptor(null, gone)), gone, Map.of(), once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);
        }
    }

    // ---- the legacy handshake ----------------------------------------------------------------------

    @Nested
    class Legacy {

        @Test
        void aLegacyServerGetsTheHandshakeAfterRefusingTheCurrentRevision() throws Exception {
            mcp.legacy("2025-11-25", true);

            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());

            assertThat(map(result.body())).containsEntry("id", "c-1");
            assertThat(mcp.methods).containsExactly("tools/call", "initialize", "notifications/initialized",
                    "tools/call");
            assertThat(result.attempts()).isEqualTo(2);
            List<LocalServer.Received> sent = server.received();
            LocalServer.Received init = sent.get(1);
            assertThat(init.header("MCP-Protocol-Version")).isNull();
            assertThat(init.header("Authorization")).isEqualTo("Bearer " + TOKEN);
            JsonNode initBody = new ObjectMapper().readTree(init.body());
            assertThat(initBody.path("params").path("protocolVersion").asText()).isEqualTo("2025-11-25");
            assertThat(initBody.path("params").path("clientInfo").path("name").asText()).isEqualTo("connector-transport");
            assertThat(initBody.path("params").has("_meta")).isFalse();
            LocalServer.Received initialized = sent.get(2);
            assertThat(initialized.header("Mcp-Session-Id")).isEqualTo("sess-1");
            assertThat(initialized.header("MCP-Protocol-Version")).isEqualTo("2025-11-25");
            LocalServer.Received call = sent.get(3);
            assertThat(call.header("Mcp-Session-Id")).isEqualTo("sess-1");
            assertThat(call.header("MCP-Protocol-Version")).isEqualTo("2025-11-25");
            assertThat(call.header("Mcp-Method")).isNull();
            assertThat(call.header("Mcp-Name")).isNull();
            assertThat(new ObjectMapper().readTree(call.body()).path("params").has("_meta")).isFalse();
            assertThat(result.headers()).doesNotContainKey("mcp-session-id");
            assertThat(logText()).contains("mode=legacy-initialize").doesNotContain("sess-1");
        }

        @Test
        void theNegotiationIsRememberedPerConnector() {
            mcp.legacy("2025-06-18", true);

            transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());
            transport.call(crm(), CREATE_TICKET, Map.of("subject", "a"), once());

            assertThat(mcp.sessionsIssued()).isEqualTo(1);
            assertThat(mcp.methods).containsExactly("tools/call", "initialize", "notifications/initialized",
                    "tools/call", "tools/call");
        }

        @Test
        void anExpiredSessionIsInitializedAgainOnce() {
            mcp.legacy("2025-11-25", true);
            transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());
            mcp.expireSessions();

            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());

            assertThat(map(result.body())).containsEntry("id", "c-1");
            assertThat(mcp.sessionsIssued()).isEqualTo(2);
            assertThat(result.attempts()).isEqualTo(2);
        }

        @Test
        void aLegacyServerWithoutSessionsWorksToo() {
            mcp.legacy("2025-03-26", false);

            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());

            assertThat(map(result.body())).containsEntry("id", "c-1");
            assertThat(server.received().get(3).header("Mcp-Session-Id")).isNull();
        }

        @Test
        void aLegacyServerAnsweringAsSseIsRead() {
            mcp.legacy("2025-11-25", true);
            mcp.sse = true;

            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());

            assertThat(map(result.body())).containsEntry("id", "c-1");
        }

        @Test
        void aPinnedLegacyRevisionSkipsTheStatelessAttempt() {
            mcp.legacy("2025-11-25", true);

            transport.call(crm(descriptor("2025-11-25", FIND_CONTACT)), FIND_CONTACT, FIND_INPUTS, once());

            assertThat(mcp.methods).containsExactly("initialize", "notifications/initialized", "tools/call");
        }

        @Test
        void aPinnedCurrentRevisionAgainstALegacyServerIsUnsupportedNotRetried() {
            mcp.legacy("2025-11-25", true);

            ConnectorException e = failure(() -> transport.call(crm(descriptor("2026-07-28", FIND_CONTACT)),
                    FIND_CONTACT, FIND_INPUTS, attempts(3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.REJECTED);
            assertThat(e.retryable()).isFalse();
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(mcp.methods).containsExactly("tools/call");
        }

        @Test
        void aServerNamingSupportedVersionsIsFollowed() {
            // The current server would have accepted; this one says it only speaks 2025-06-18.
            AtomicInteger calls = new AtomicInteger();
            server.respond(r -> {
                if (calls.getAndIncrement() == 0) {
                    return Reply.json(400, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32022,"
                            + "\"message\":\"Unsupported protocol version\",\"data\":{\"supported\":[\"2025-06-18\"]}}}");
                }
                return mcp.apply(r);
            });
            mcp.legacy("2025-06-18", true);

            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once());

            assertThat(map(result.body())).containsEntry("id", "c-1");
            assertThat(mcp.methods).containsExactly("initialize", "notifications/initialized", "tools/call");
        }

        @Test
        void aServerSpeakingNoRevisionWeKnowIsUnavailableNotRetryable() {
            mcp.legacy("2024-11-05", true);

            ConnectorException e = failure(() -> transport.call(crm(), FIND_CONTACT, FIND_INPUTS, attempts(3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).contains("unsupported protocol version").contains("2024-11-05");
            assertThat(mcp.methods).containsExactly("tools/call", "initialize");
        }

        @Test
        void aPinnedLegacyRevisionAgainstACurrentServerIsUnsupported() {
            ConnectorException e = failure(() -> transport.call(crm(descriptor("2025-11-25", FIND_CONTACT)),
                    FIND_CONTACT, FIND_INPUTS, once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
            assertThat(e.retryable()).isFalse();
            assertThat(e.getMessage()).contains("initialize");
        }
    }

    // ---- statuses, retries, caps -------------------------------------------------------------------

    @Nested
    class Failures {

        @Test
        void refusedCredentialsAreAuthFailures() {
            mcp.requiredAuthorization = "Bearer other";
            ConnectorException e = failure(() -> transport.call(crm(), FIND_CONTACT, FIND_INPUTS, attempts(3, null)));
            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.AUTH_FAILED);
            assertThat(e.status()).isEqualTo(401);
            assertThat(e.retryable()).isFalse();
            assertThat(e.attempts()).isEqualTo(1);

            mcp.requiredAuthorization = null;
            mcp.requiredApiKey = "k";
            assertThat(failure(() -> transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once())).status()).isEqualTo(403);
        }

        @Test
        void anApiKeyHeaderIsAppliedAndAnApiKeyInTheQueryOrBasicIsRefusedBeforeSending() {
            AuthScheme header = new AuthScheme(AuthScheme.SCHEME_API_KEY, AuthScheme.IN_HEADER, "X-API-Key", "token",
                    null, null, null, null, null, null);
            mcp.requiredApiKey = TOKEN;
            transport.call(crm(descriptor(header, FIELDS, null, null, FIND_CONTACT)), FIND_CONTACT, FIND_INPUTS, once());
            assertThat(server.only().header("X-API-Key")).isEqualTo(TOKEN);

            AuthScheme query = new AuthScheme(AuthScheme.SCHEME_API_KEY, AuthScheme.IN_QUERY, "api_key", "token", null,
                    null, null, null, null, null);
            ConnectorDescriptor inQuery = new ConnectorDescriptor("q", "Q", null, ConnectorDescriptor.TRANSPORT_MCP, 1,
                    query, FIELDS, List.of(), null, null, null, List.of(FIND_CONTACT), null);
            ConnectorException e = failure(() -> transport.call(crm(inQuery), FIND_CONTACT, FIND_INPUTS, once()));
            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);

            AuthScheme basic = new AuthScheme(AuthScheme.SCHEME_BASIC, null, null, null, "baseUrl", "token", null,
                    null, null, null);
            ConnectorDescriptor withBasic = new ConnectorDescriptor("b", "B", null, ConnectorDescriptor.TRANSPORT_MCP,
                    1, basic, FIELDS, List.of(), null, null, null, List.of(FIND_CONTACT), null);
            assertThat(failure(() -> transport.call(crm(withBasic), FIND_CONTACT, FIND_INPUTS, once())).code())
                    .isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);
            assertThat(server.received()).hasSize(1);
        }

        @Test
        void a503IsRetriedForAnIdempotentToolAndNotForAWriteWithoutAKey() {
            AtomicInteger hits = new AtomicInteger();
            server.respond(r -> hits.incrementAndGet() < 3 ? Reply.status(503) : mcp.apply(r));

            CallResult result = transport.call(crm(), FIND_CONTACT, FIND_INPUTS, attempts(3, null));
            assertThat(result.attempts()).isEqualTo(3);
            assertThat(sleeps).hasSize(2);
            assertThat(sleeps.get(0).toMillis()).isBetween(150L, 250L);

            hits.set(0);
            sleeps.clear();
            ConnectorException e = failure(() -> transport.call(crm(), SEND_NOTE, Map.of("text", "x"),
                    attempts(3, "key-1")));
            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
            assertThat(e.retryable()).isTrue();
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(sleeps).isEmpty();
        }

        @Test
        void aWriteWithAKeyAndAnArgumentToCarryItIsRetriedAndTheKeyIsSentEveryTime() throws Exception {
            AtomicInteger hits = new AtomicInteger();
            server.respond(r -> hits.incrementAndGet() == 1 ? Reply.status(503) : mcp.apply(r));

            CallResult result = transport.call(crm(), CREATE_TICKET, Map.of("subject", "a"), attempts(3, "key-77"));

            assertThat(result.attempts()).isEqualTo(2);
            for (LocalServer.Received sent : server.received()) {
                JsonNode arguments = new ObjectMapper().readTree(sent.body()).path("params").path("arguments");
                assertThat(arguments.path("requestId").asText()).isEqualTo("key-77");
                assertThat(arguments.path("subject").asText()).isEqualTo("a");
            }
        }

        @Test
        void aKeyWithoutAnArgumentIsNotInjected() throws Exception {
            transport.call(crm(), SEND_NOTE, Map.of("text", "x"), attempts(1, "key-77"));

            JsonNode arguments = new ObjectMapper().readTree(server.only().body()).path("params").path("arguments");
            assertThat(arguments.has("requestId")).isFalse();
            assertThat(arguments.fieldNames()).toIterable().containsExactly("text");
        }

        @Test
        void a500IsNotRetriedAndASlowAnswerIsATimeout() {
            server.respond(r -> Reply.status(500));
            ConnectorException e = failure(() -> transport.call(crm(), FIND_CONTACT, FIND_INPUTS, attempts(3, null)));
            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.UNAVAILABLE);
            assertThat(e.retryable()).isFalse();
            assertThat(e.attempts()).isEqualTo(1);

            server.respond(r -> mcp.apply(r).delayed(800));
            ConnectorException slow = failure(() -> transport.call(crm(), SEND_NOTE, Map.of("text", "x"),
                    new CallOptions(BUDGET, Duration.ofMillis(200), 3, null)));
            assertThat(slow.code()).isEqualTo(ConnectorErrorCodes.TIMEOUT);
            assertThat(slow.retryable()).isTrue();
            assertThat(slow.attempts()).isEqualTo(1);
        }

        @Test
        void aRedirectIsNotFollowed() {
            server.respond(r -> r.rawPath().equals("/mcp")
                    ? new Reply(302, Map.of("Location", server.baseUrl() + "/private"), "", 0)
                    : Reply.json(200, "{\"leak\":true}"));

            ConnectorException e = failure(() -> transport.call(crm(), FIND_CONTACT, FIND_INPUTS, attempts(3, null)));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.REJECTED);
            assertThat(e.status()).isEqualTo(302);
            assertThat(e.getMessage()).contains("redirect not followed").doesNotContain("private");
            assertThat(server.received()).hasSize(1);
        }

        @Test
        void aBodyOrStreamBeyondTheCapIsInvalid() {
            try (McpTransport small = transport(localPolicy().withMaxResponseBytes(200))) {
                mcp.tool("big", FakeMcpServer.schema(), args -> ToolAnswer.text("x".repeat(500)));
                ConnectorOperation big = tool("big", null, true, null, null, SchemaNode.EMPTY_OBJECT);
                ResolvedConnector connector = crm(descriptor(null, big));

                ConnectorException json = failure(() -> small.call(connector, big, Map.of(), once()));
                assertThat(json.code()).isEqualTo(ConnectorErrorCodes.RESPONSE_INVALID);
                assertThat(json.getMessage()).contains("200 bytes");

                mcp.sse = true;
                ConnectorException sse = failure(() -> small.call(connector, big, Map.of(), once()));
                assertThat(sse.code()).isEqualTo(ConnectorErrorCodes.RESPONSE_INVALID);
                assertThat(sse.getMessage()).contains("event stream larger than 200 bytes");
            }
        }

        @Test
        void aStreamWithoutAnAnswerIsInvalid() {
            server.respond(r -> Reply.text(200, "text/event-stream", ": hello\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n\n"));

            ConnectorException e = failure(() -> transport.call(crm(), FIND_CONTACT, FIND_INPUTS, once()));

            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.RESPONSE_INVALID);
            assertThat(e.getMessage()).contains("without a JSON-RPC response");
        }

        @Test
        void aHostResolvingToAMetadataAddressIsRefusedBeforeAnythingIsSent() {
            EgressPolicy stubbed = localPolicy()
                    .withDns(host -> new InetAddress[] { InetAddress.getByName("169.254.169.254") });
            ResolvedConnector internal = connector(descriptor(null, FIND_CONTACT),
                    "http://mcp.example:" + server.port() + "/mcp", Map.of("token", TOKEN));
            try (McpTransport t = transport(stubbed)) {
                ConnectorException e = failure(() -> t.call(internal, FIND_CONTACT, FIND_INPUTS, once()));

                assertThat(e.code()).isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
                assertThat(e.getMessage()).contains("mcp.example").doesNotContain("169.254");
            }
            assertThat(server.received()).isEmpty();
        }

        @Test
        void egressRulesAreTheRestOnes() {
            try (McpTransport strict = transport(EgressPolicy.STRICT.withAllowHttp(true))) {
                assertThat(failure(() -> strict.call(crm(), FIND_CONTACT, FIND_INPUTS, once())).code())
                        .isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
            }
            try (McpTransport httpsOnly = transport(EgressPolicy.STRICT.withPrivateHosts(List.of("127.0.0.1")))) {
                assertThat(failure(() -> httpsOnly.call(crm(), FIND_CONTACT, FIND_INPUTS, once())).getMessage())
                        .contains("must use https");
            }
            ResolvedConnector withQuery = connector(descriptor(null, FIND_CONTACT), server.baseUrl() + "/mcp?x=1",
                    Map.of("token", TOKEN));
            assertThat(failure(() -> transport.call(withQuery, FIND_CONTACT, FIND_INPUTS, once())).code())
                    .isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
            assertThat(server.received()).isEmpty();
        }

        @Test
        void aMappedStatusCodeIsHonoured() {
            ConnectorOperation mapped = new ConnectorOperation("findContact", "findContact", null, null, null,
                    RiskLevel.LOW, true, null, FIND_CONTACT.input(), FIND_CONTACT.output(), Map.of("409", "BUSY"), null,
                    null, "crm_find_contact", null);
            server.respond(r -> Reply.text(409, "text/plain", "busy"));

            ConnectorException e = failure(() -> transport.call(crm(descriptor(null, mapped)), mapped, FIND_INPUTS,
                    once()));

            assertThat(e.code()).isEqualTo("BUSY");
            assertThat(e.status()).isEqualTo(409);
        }
    }

    // ---- OAuth2 client credentials -----------------------------------------------------------------

    @Nested
    class OAuth2 {

        @Test
        void aTokenIsFetchedAndA401RefreshesItOnce() {
            AuthScheme oauth = new AuthScheme(AuthScheme.SCHEME_OAUTH2_CLIENT_CREDENTIALS, null, null, null, null,
                    null, server.baseUrl() + "/oauth/token", "clientId", "clientSecret", null);
            List<Map<String, Object>> fields = List.of(field("clientId", "text"), field("clientSecret", "secret"));
            // Built directly: the validator wants an https token URL, which the loopback server has not.
            ConnectorDescriptor withOauth = new ConnectorDescriptor("mock-crm-mcp", "Mock CRM (MCP)", null,
                    ConnectorDescriptor.TRANSPORT_MCP, 1, oauth, fields, List.of(), null, null, null,
                    List.of(FIND_CONTACT), null);
            ResolvedConnector connector = new ResolvedConnector(CRM, "CRM", "MCP", withOauth,
                    server.baseUrl() + "/mcp", null, Map.of("clientId", "cid"), Map.of("clientSecret", "cs-secret-1"),
                    1L);
            AtomicInteger tokens = new AtomicInteger();
            server.respond(r -> {
                if (r.rawPath().equals("/oauth/token")) {
                    return Reply.json(200, "{\"access_token\":\"tok-" + tokens.incrementAndGet() + "\",\"expires_in\":3600}");
                }
                mcp.requiredAuthorization = "Bearer tok-2";
                return mcp.apply(r);
            });

            CallResult result = transport.call(connector, FIND_CONTACT, FIND_INPUTS, once());

            assertThat(result.attempts()).isEqualTo(2);
            assertThat(tokens).hasValue(2);
            assertThat(logText()).doesNotContain("cs-secret-1").doesNotContain("tok-2");
        }
    }

    // ---- test() and discover() ---------------------------------------------------------------------

    @Nested
    class TestAndDiscover {

        @Test
        void theTestButtonListsToolsUnlessATestOperationIsDeclared() {
            CallResult listed = transport.test(crm(), BUDGET);
            assertThat(listed.status()).isEqualTo(200);
            assertThat(map(listed.body())).containsEntry("toolCount", 3);
            assertThat(map(listed.body()).get("tools")).isEqualTo(List.of("crm_find_contact", "createTicket",
                    "sendNote"));
            assertThat(mcp.methods).containsExactly("tools/list");

            ConnectorOperation ping = tool("ping", "crm_find_contact", true, null,
                    object(ordered("email", node("string").defaultValue("probe@example.com").build()), List.of()),
                    SchemaNode.EMPTY_OBJECT);
            CallResult called = transport.test(crm(descriptor(BEARER, FIELDS, null, "ping", ping)), BUDGET);
            assertThat(map(called.body())).containsEntry("email", "probe@example.com");
        }

        @Test
        void theTestButtonCountsEveryPageOfTheList() {
            mcp.pageSize = 2;
            CallResult listed = transport.test(crm(), BUDGET);
            assertThat(map(listed.body())).containsEntry("toolCount", 3);
            assertThat(map(listed.body()).get("tools")).isEqualTo(List.of("crm_find_contact", "createTicket",
                    "sendNote"));
            assertThat(mcp.methods).containsExactly("tools/list", "tools/list");
            assertThat(listed.attempts()).isEqualTo(2);
        }

        @Test
        void discoverReadsEveryPageAndReportsTheRevision() {
            mcp.pageSize = 2;
            mcp.tool(new FakeMcpServer.Tool("annotated", "Annotated", "has hints", FakeMcpServer.schema("a"),
                    Map.of("type", "object", "properties", Map.of("ok", Map.of("type", "boolean"))),
                    Map.of("readOnlyHint", true, "destructiveHint", false), args -> ToolAnswer.text("x")));

            McpTransport.Discovery discovery = transport.discover(crm(), BUDGET);

            assertThat(discovery.tools()).extracting(McpTransport.DiscoveredTool::name)
                    .containsExactly("crm_find_contact", "createTicket", "sendNote", "annotated");
            assertThat(discovery.truncated()).isFalse();
            assertThat(discovery.protocolVersion()).isEqualTo("2026-07-28");
            assertThat(mcp.methods).containsExactly("tools/list", "tools/list");
            McpTransport.DiscoveredTool annotated = discovery.tools().get(3);
            assertThat(annotated.title()).isEqualTo("Annotated");
            assertThat(annotated.description()).isEqualTo("has hints");
            assertThat(annotated.inputSchema()).containsEntry("type", "object");
            assertThat(annotated.outputSchema()).containsKey("properties");
            assertThat(annotated.annotations()).containsEntry("readOnlyHint", true);
            assertThat(annotated.schemaOmitted()).isFalse();
            assertThat(discovery.tools().get(0).outputSchema()).isNull();
        }

        @Test
        void discoverWorksWithADraftDescriptorAndALegacyServer() {
            mcp.legacy("2025-11-25", true);
            ConnectorDescriptor draft = new ConnectorDescriptor("draft", "Draft", null, ConnectorDescriptor.TRANSPORT_MCP,
                    1, BEARER, FIELDS, List.of(), null, null, null, List.of(), null);

            McpTransport.Discovery discovery = transport.discover(crm(draft), BUDGET);

            assertThat(discovery.tools()).hasSize(3);
            assertThat(discovery.protocolVersion()).isEqualTo("2025-11-25");
        }

        @Test
        void discoverCapsToolsAndSchemas() {
            FakeMcpServer many = new FakeMcpServer();
            for (int i = 0; i < McpTransport.MAX_TOOLS + 5; i++) {
                many.tool("t" + i, FakeMcpServer.schema("a"), args -> ToolAnswer.text("x"));
            }
            many.tool(new FakeMcpServer.Tool("huge", null, null,
                    Map.of("type", "object", "description", "y".repeat(McpTransport.MAX_SCHEMA_BYTES + 10)), null, null,
                    args -> ToolAnswer.text("x")));
            many.pageSize = 50;
            server.respond(many);

            McpTransport.Discovery discovery = transport.discover(crm(), BUDGET);

            assertThat(discovery.tools()).hasSize(McpTransport.MAX_TOOLS);
            assertThat(discovery.truncated()).isTrue();
            assertThat(discovery.notes()).anySatisfy(note -> assertThat(note).contains("more than 200 tools"));

            FakeMcpServer withHuge = new FakeMcpServer()
                    .tool(new FakeMcpServer.Tool("huge", null, null,
                            Map.of("type", "object", "description", "y".repeat(McpTransport.MAX_SCHEMA_BYTES + 10)), null,
                            null, args -> ToolAnswer.text("x")));
            server.respond(withHuge);
            McpTransport.Discovery omitted = transport.discover(crm(), BUDGET);
            assertThat(omitted.tools()).singleElement().satisfies(tool -> {
                assertThat(tool.schemaOmitted()).isTrue();
                assertThat(tool.inputSchema()).isNull();
            });
            assertThat(omitted.notes()).anySatisfy(note -> assertThat(note).contains("huge"));
        }

        @Test
        void discoverRefusesTheSameEgressAsACall() {
            try (McpTransport strict = transport(EgressPolicy.STRICT.withAllowHttp(true))) {
                assertThat(failure(() -> strict.discover(crm(), BUDGET)).code())
                        .isEqualTo(ConnectorErrorCodes.EGRESS_REFUSED);
            }
            mcp.requiredAuthorization = "Bearer other";
            assertThat(failure(() -> transport.discover(crm(), BUDGET)).code()).isEqualTo(ConnectorErrorCodes.AUTH_FAILED);
        }
    }

    // ---- the registry ------------------------------------------------------------------------------

    @Nested
    class Registry {

        @Test
        void transportsAreFoundByDescriptorKind() throws IOException {
            RestTransport rest = new RestTransport(localPolicy());
            ConnectorTransports transports = ConnectorTransports.of(rest, transport);

            assertThat(transports.kinds()).containsExactlyInAnyOrder("REST", "MCP");
            assertThat(transports.require(crm())).isSameAs(transport);
            assertThat(transports.find("rest")).containsSame(rest);
            ConnectorDescriptor soap = new ConnectorDescriptor("s", "S", null, "SOAP", 1, null, null, null, null, null,
                    null, List.of(), null);
            ConnectorException e = failure(() -> transports.require(soap));
            assertThat(e.code()).isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);
            assertThat(e.getMessage()).contains("'SOAP'");
            assertThat(failure(() -> transports.require((ConnectorDescriptor) null)).code())
                    .isEqualTo(ConnectorErrorCodes.CONFIG_INVALID);
            transports.close();
        }
    }
}
