package com.itways.assistant.journey.connector.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itways.assistant.journey.connector.LocalServer;
import com.itways.assistant.journey.connector.LocalServer.Received;
import com.itways.assistant.journey.connector.LocalServer.Reply;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An MCP server as the specification describes one, behind {@link LocalServer}:
 * the current stateless revision (2026-07-28) by default, or a legacy one
 * (2025-11-25 and older: {@code initialize}, {@code Mcp-Session-Id}), answering
 * as JSON or as an SSE stream. Tools are scripted per test ({@link #tool});
 * the odd cases (5xx, redirects, oversized bodies) are the test's own replies
 * on the {@code LocalServer}.
 */
final class FakeMcpServer implements Function<Received, Reply> {

    enum Mode {
        STATELESS, LEGACY
    }

    /** What one tool answers: structured content, text, an error flag, extra content blocks, a result type. */
    record ToolAnswer(Map<String, Object> structured, String text, boolean isError, List<Map<String, Object>> extra,
            String resultType) {

        static ToolAnswer structured(Map<String, Object> value) {
            return new ToolAnswer(value, null, false, List.of(), null);
        }

        static ToolAnswer text(String text) {
            return new ToolAnswer(null, text, false, List.of(), null);
        }

        static ToolAnswer error(String text) {
            return new ToolAnswer(null, text, true, List.of(), null);
        }
    }

    record Tool(String name, String title, String description, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema, Map<String, Object> annotations,
            Function<JsonNode, ToolAnswer> behaviour) {
    }

    static final String SUPPORTED_CURRENT = "2026-07-28";
    static final Set<String> LEGACY_VERSIONS = Set.of("2025-11-25", "2025-06-18", "2025-03-26");

    private final ObjectMapper json = new ObjectMapper();
    private final List<Tool> tools = new ArrayList<>();
    private final Set<String> sessions = Collections.synchronizedSet(new java.util.HashSet<>());
    private final AtomicInteger sessionCounter = new AtomicInteger();
    final List<String> methods = Collections.synchronizedList(new ArrayList<>());

    Mode mode = Mode.STATELESS;
    /** LEGACY: the revision the server answers initialize with. */
    String legacyVersion = "2025-11-25";
    /** LEGACY: whether initialize assigns a session id (and every later request must carry it). */
    boolean sessions_ = true;
    /** Whether answers are SSE streams rather than JSON bodies. */
    boolean sse = false;
    /** SSE: a notification event before the response event. */
    boolean notificationFirst = false;
    /** SSE: keep the connection open this long after the response (0 = close at once). */
    long holdOpenMs = 0;
    /** An {@code Authorization} or {@code X-API-Key} value every request must carry; null for none. */
    String requiredAuthorization;
    String requiredApiKey;
    /** How many tools one {@code tools/list} page holds. */
    int pageSize = 100;
    /** LEGACY without sessions: whether initialize happened. */
    private volatile boolean initialized;
    /** The requests, in arrival order, as JSON-RPC messages (bodies only). */
    final List<JsonNode> messages = Collections.synchronizedList(new ArrayList<>());

    FakeMcpServer tool(String name, Map<String, Object> inputSchema, Function<JsonNode, ToolAnswer> behaviour) {
        return tool(new Tool(name, null, "Tool " + name, inputSchema, null, null, behaviour));
    }

    FakeMcpServer tool(Tool tool) {
        tools.add(tool);
        return this;
    }

    FakeMcpServer legacy(String version, boolean withSessions) {
        this.mode = Mode.LEGACY;
        this.legacyVersion = version;
        this.sessions_ = withSessions;
        return this;
    }

    /** Forgets every session: the next request with an old id gets 404. */
    void expireSessions() {
        sessions.clear();
        initialized = false;
    }

    int sessionsIssued() {
        return sessionCounter.get();
    }

    @Override
    public Reply apply(Received request) {
        if (requiredAuthorization != null && !requiredAuthorization.equals(request.header("Authorization"))) {
            return Reply.text(401, "text/plain", "unauthorized");
        }
        if (requiredApiKey != null && !requiredApiKey.equals(request.header("X-API-Key"))) {
            return Reply.text(403, "text/plain", "forbidden");
        }
        if (!"POST".equals(request.method())) {
            return Reply.status(405);
        }
        String accept = request.header("Accept");
        if (accept == null || !accept.contains("application/json") || !accept.contains("text/event-stream")) {
            return Reply.text(406, "text/plain", "Not Acceptable: Accept must list application/json and text/event-stream");
        }
        JsonNode message;
        try {
            message = json.readTree(request.body());
        } catch (Exception e) {
            return error(400, null, -32700, "Parse error", null);
        }
        messages.add(message);
        String method = message.path("method").asText(null);
        JsonNode id = message.get("id");
        methods.add(method);
        return mode == Mode.STATELESS ? stateless(request, message, method, id) : legacy(request, message, method, id);
    }

    // ---- 2026-07-28 --------------------------------------------------------------------------------

    private Reply stateless(Received request, JsonNode message, String method, JsonNode id) {
        if ("initialize".equals(method) || "ping".equals(method)) {
            return error(404, id, -32601, "Method not found", null);
        }
        String version = request.header("MCP-Protocol-Version");
        if (version == null || !SUPPORTED_CURRENT.equals(version)) {
            ObjectNode data = json.createObjectNode();
            data.putArray("supported").add(SUPPORTED_CURRENT);
            data.put("requested", version == null ? "" : version);
            return error(400, id, -32022, "Unsupported protocol version", data);
        }
        if (!method.equals(request.header("Mcp-Method"))) {
            return error(400, id, -32020, "Mcp-Method header does not match the method", null);
        }
        JsonNode meta = message.path("params").path("_meta");
        if (!SUPPORTED_CURRENT.equals(meta.path("io.modelcontextprotocol/protocolVersion").asText(null))
                || !meta.has("io.modelcontextprotocol/clientCapabilities")) {
            return error(400, id, -32602, "params._meta must carry protocolVersion and clientCapabilities", null);
        }
        if (id == null) {
            return Reply.status(202); // a notification
        }
        if ("tools/call".equals(method)
                && !message.path("params").path("name").asText("").equals(request.header("Mcp-Name"))) {
            return error(400, id, -32020, "Mcp-Name header does not match params.name", null);
        }
        return dispatch(message, method, id, true, null);
    }

    // ---- 2025-11-25 and older ----------------------------------------------------------------------

    private Reply legacy(Received request, JsonNode message, String method, JsonNode id) {
        if ("initialize".equals(method)) {
            String requested = message.path("params").path("protocolVersion").asText("");
            if (!LEGACY_VERSIONS.contains(requested)) {
                ObjectNode data = json.createObjectNode();
                data.putArray("supported").add(legacyVersion);
                data.put("requested", requested);
                return error(400, id, -32602, "Unsupported protocol version", data);
            }
            ObjectNode result = json.createObjectNode();
            result.put("protocolVersion", legacyVersion);
            result.putObject("capabilities").putObject("tools");
            ObjectNode info = result.putObject("serverInfo");
            info.put("name", "fake-mcp");
            info.put("version", "0");
            initialized = true;
            String session = null;
            if (sessions_) {
                session = "sess-" + sessionCounter.incrementAndGet();
                sessions.add(session);
            }
            Reply reply = respond(id, result, null);
            return session != null ? reply.withHeader("Mcp-Session-Id", session) : reply;
        }
        String version = request.header("MCP-Protocol-Version");
        if (version != null && !LEGACY_VERSIONS.contains(version)) {
            return error(400, id, -32000, "Bad Request: Unsupported protocol version (supported versions: "
                    + legacyVersion + ")", null);
        }
        if (sessions_) {
            String session = request.header("Mcp-Session-Id");
            if (session == null) {
                return error(400, id, -32000, "Bad Request: Server not initialized", null);
            }
            if (!sessions.contains(session)) {
                return Reply.text(404, "text/plain", "Session not found");
            }
        } else if (!initialized) {
            return error(400, id, -32000, "Bad Request: Server not initialized", null);
        }
        if (id == null) {
            return Reply.status(202);
        }
        if ("ping".equals(method)) {
            return respond(id, json.createObjectNode(), null);
        }
        return dispatch(message, method, id, false, null);
    }

    // ---- tools ---------------------------------------------------------------------------------------

    private Reply dispatch(JsonNode message, String method, JsonNode id, boolean current, String unused) {
        switch (method) {
            case "tools/list" -> {
                int from = 0;
                String cursor = message.path("params").path("cursor").asText(null);
                if (cursor != null) {
                    from = Integer.parseInt(cursor);
                }
                ObjectNode result = json.createObjectNode();
                ArrayNode listed = result.putArray("tools");
                int to = Math.min(tools.size(), from + pageSize);
                for (Tool tool : tools.subList(from, to)) {
                    ObjectNode node = listed.addObject();
                    node.put("name", tool.name());
                    if (tool.title() != null) {
                        node.put("title", tool.title());
                    }
                    if (tool.description() != null) {
                        node.put("description", tool.description());
                    }
                    node.set("inputSchema", json.valueToTree(tool.inputSchema() != null ? tool.inputSchema()
                            : Map.of("type", "object")));
                    if (tool.outputSchema() != null) {
                        node.set("outputSchema", json.valueToTree(tool.outputSchema()));
                    }
                    if (tool.annotations() != null) {
                        node.set("annotations", json.valueToTree(tool.annotations()));
                    }
                }
                if (to < tools.size()) {
                    result.put("nextCursor", String.valueOf(to));
                }
                if (current) {
                    result.put("ttlMs", 60_000);
                    result.put("cacheScope", "private");
                    result.put("resultType", "complete");
                }
                return respond(id, result, null);
            }
            case "tools/call" -> {
                String name = message.path("params").path("name").asText("");
                Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
                if (tool == null) {
                    return error(current ? 400 : 200, id, -32602, "Unknown tool: " + name, null);
                }
                ToolAnswer answer = tool.behaviour().apply(message.path("params").path("arguments"));
                ObjectNode result = json.createObjectNode();
                ArrayNode content = result.putArray("content");
                if (answer.text() != null) {
                    ObjectNode block = content.addObject();
                    block.put("type", "text");
                    block.put("text", answer.text());
                }
                for (Map<String, Object> extra : answer.extra()) {
                    content.add(json.valueToTree(extra));
                }
                if (answer.structured() != null) {
                    result.set("structuredContent", json.valueToTree(answer.structured()));
                }
                if (answer.isError()) {
                    result.put("isError", true);
                }
                if (current || answer.resultType() != null) {
                    result.put("resultType", answer.resultType() != null ? answer.resultType() : "complete");
                }
                return respond(id, result, null);
            }
            default -> {
                return error(current ? 404 : 200, id, -32601, "Method not found", null);
            }
        }
    }

    // ---- framing -------------------------------------------------------------------------------------

    private Reply respond(JsonNode id, ObjectNode result, String unused) {
        ObjectNode message = json.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.set("id", id);
        message.set("result", result);
        return frame(200, message);
    }

    private Reply error(int status, JsonNode id, int code, String text, ObjectNode data) {
        ObjectNode message = json.createObjectNode();
        message.put("jsonrpc", "2.0");
        if (id != null) {
            message.set("id", id);
        } else {
            message.putNull("id");
        }
        ObjectNode error = message.putObject("error");
        error.put("code", code);
        error.put("message", text);
        if (data != null) {
            error.set("data", data);
        }
        return frame(status, message);
    }

    private Reply frame(int status, ObjectNode message) {
        String body = message.toString();
        if (!sse) {
            return Reply.json(status, body);
        }
        StringBuilder stream = new StringBuilder();
        stream.append(": keep-alive\n\n");
        if (notificationFirst) {
            stream.append("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\","
                    + "\"params\":{\"level\":\"info\",\"data\":\"working\"}}\n\n");
        }
        stream.append("event: message\nid: 1\ndata: ").append(body).append("\n\n");
        Reply reply = Reply.text(status, "text/event-stream", stream.toString());
        return holdOpenMs > 0 ? reply.heldOpen(holdOpenMs) : reply;
    }

    /** A JSON Schema object with these string properties, all required. */
    static Map<String, Object> schema(String... properties) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (String property : properties) {
            props.put(property, Map.of("type", "string"));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of(properties));
        return schema;
    }
}
