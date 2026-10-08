package com.itways.assistant.journey.connector.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.connector.SecretScrubber;
import com.itways.assistant.journey.connector.http.AuthApplier;
import com.itways.assistant.journey.connector.http.Deadline;
import com.itways.assistant.journey.connector.http.HttpExchange;
import com.itways.assistant.journey.connector.http.ResponseReader;
import com.itways.assistant.journey.model.connector.AuthScheme;
import com.itways.assistant.journey.model.connector.ConnectorDescriptor.McpSettings;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;

/**
 * JSON-RPC over MCP's Streamable HTTP transport: one request, one answer, in
 * whichever revision the server speaks.
 *
 * <p>
 * Verified against the specification at modelcontextprotocol.io on 2026-10-04:
 * <ul>
 * <li><b>2026-07-28</b> (current; {@code /specification/2026-07-28/basic/transports/streamable-http}):
 * stateless. No {@code initialize}, no session. Every request is one POST with
 * {@code Accept: application/json, text/event-stream}, the headers
 * {@code MCP-Protocol-Version}, {@code Mcp-Method} and, for {@code tools/call},
 * {@code Mcp-Name}; the body's {@code params._meta} carries
 * {@code io.modelcontextprotocol/protocolVersion} and
 * {@code io.modelcontextprotocol/clientCapabilities} (required) and
 * {@code clientInfo}. The answer is one JSON object or an SSE stream that
 * carries the response (and should end with it). Errors: {@code -32020}
 * header mismatch, {@code -32021} missing capability, {@code -32022}
 * unsupported version (HTTP 400, {@code data.supported} lists the server's),
 * {@code -32601} unknown method (HTTP 404), {@code -32602} unknown tool.
 * Results carry {@code resultType}: {@code complete} or
 * {@code input_required}.</li>
 * <li><b>2025-11-25, 2025-06-18, 2025-03-26</b> (legacy;
 * {@code /specification/2025-11-25/basic/transports}, {@code .../basic/lifecycle}):
 * an {@code initialize} request first (the server answers with the revision
 * it will speak, and may assign {@code Mcp-Session-Id}), then
 * {@code notifications/initialized} (HTTP 202), then requests with
 * {@code MCP-Protocol-Version} and the session id; a 404 means the session
 * expired and the client starts again. {@code -32602} on initialize means the
 * revision is not supported.</li>
 * </ul>
 *
 * <p>
 * Negotiation, when the descriptor pins no revision: the current one is tried
 * first; a server that refuses it the way a legacy server does ({@code -32022},
 * a {@code -32000..-32019} server error such as "not initialized", a bare 400)
 * gets the legacy handshake, and the outcome is remembered per connector
 * (id and lock version) so the next call pays nothing. Anything else the
 * server answers is final. A pinned revision is never negotiated away.
 *
 * <p>
 * What never happens here: no GET (the server-initiated stream), no DELETE, no
 * resumption with {@code Last-Event-ID}, no batches sent, no request from the
 * server answered, no stdio.
 */
final class McpProtocol {

    static final String CURRENT_VERSION = McpSettings.PROTOCOL_2026_07_28;
    /** The revision the legacy handshake asks for; the server may answer with an older one. */
    static final String DEFAULT_LEGACY_VERSION = McpSettings.PROTOCOL_2025_11_25;

    static final String CLIENT_NAME = "connector-transport";
    static final String CLIENT_VERSION = "1.2.0";

    static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    static final String HEADER_METHOD = "Mcp-Method";
    static final String HEADER_NAME = "Mcp-Name";
    static final String HEADER_SESSION = "Mcp-Session-Id";
    static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    static final String ACCEPT = "application/json, text/event-stream";

    static final String METHOD_INITIALIZE = "initialize";
    static final String METHOD_INITIALIZED = "notifications/initialized";
    static final String METHOD_TOOLS_LIST = "tools/list";
    static final String METHOD_TOOLS_CALL = "tools/call";

    /** JSON-RPC 2.0 codes, and MCP's own. */
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int HEADER_MISMATCH = -32020;
    static final int MISSING_CLIENT_CAPABILITY = -32021;
    static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /** Events read from one stream before giving up on an answer. */
    static final int MAX_SSE_EVENTS = 256;
    /** Connectors whose negotiated revision is remembered at once. */
    static final int NEGOTIATION_CACHE_SIZE = 1024;

    /** Response headers never handed on: credentials, challenges, cookies and the session id. */
    private static final Set<String> HIDDEN_HEADERS = Set.of("set-cookie", "set-cookie2", "authorization",
            "proxy-authorization", "www-authenticate", "proxy-authenticate", "mcp-session-id");

    /** The revision settled with a server, and the session it gave (legacy only). */
    record Negotiated(String protocolVersion, String sessionId) {
        boolean legacy() {
            return McpSettings.isLegacy(protocolVersion);
        }
    }

    /**
     * One connector's endpoint for one call: everything a request needs that
     * does not change between requests.
     *
     * @param pinnedVersion the descriptor's {@code mcp.protocolVersion}, or null to negotiate
     * @param readTimeout   time to a complete answer per request
     * @param label         for messages: {@code "MCP tools/call crm_find_contact"}
     */
    record Target(ResolvedConnector connector, URI uri, AuthScheme scheme, String pinnedVersion,
            Duration readTimeout, Deadline deadline, Map<String, String> errorsByStatus, String label) {
    }

    /**
     * The server's result for one request.
     *
     * @param requests how many times the request itself was sent (a handshake is not counted)
     */
    record Answer(JsonNode result, int status, Map<String, String> headers, int requests) {
    }

    /**
     * One HTTP exchange, before interpretation. {@code message} is the JSON-RPC
     * message answering the request, if any; {@code sends} how many requests it
     * took (two when an OAuth2 token was refreshed).
     */
    private record Exchange(int status, Map<String, String> headers, String sessionId, JsonNode message,
            String bodyText, boolean truncated, int sends) {

        Exchange(int status, Map<String, String> headers, String sessionId, JsonNode message, String bodyText,
                boolean truncated) {
            this(status, headers, sessionId, message, bodyText, truncated, 1);
        }

        Exchange resent() {
            return new Exchange(status, headers, sessionId, message, bodyText, truncated, sends + 1);
        }

        boolean hasResult() {
            return status >= 200 && status < 300 && message != null && message.has("result");
        }

        JsonNode error() {
            return message != null && message.hasNonNull("error") && message.get("error").isObject()
                    ? message.get("error")
                    : null;
        }

        int errorCode() {
            JsonNode error = error();
            return error != null ? error.path("code").asInt(0) : 0;
        }
    }

    private final ObjectMapper json;
    private final HttpExchange wire;
    private final ResponseReader reader;
    private final AuthApplier auth;
    private final long maxResponseBytes;
    private final AtomicLong ids = new AtomicLong();
    private final Map<String, Negotiated> negotiated = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Negotiated> eldest) {
                    return size() > NEGOTIATION_CACHE_SIZE;
                }
            });

    McpProtocol(ObjectMapper json, HttpExchange wire, ResponseReader reader, AuthApplier auth, long maxResponseBytes) {
        this.json = json;
        this.wire = wire;
        this.reader = reader;
        this.auth = auth;
        this.maxResponseBytes = maxResponseBytes;
    }

    static String cacheKey(ResolvedConnector connector) {
        return connector.id() + ":" + connector.lockVersion();
    }

    /** The revision in use with {@code connector}: pinned, remembered, or null before the first call. */
    String versionFor(ResolvedConnector connector, String pinnedVersion) {
        if (pinnedVersion != null) {
            return pinnedVersion;
        }
        Negotiated mode = negotiated.get(cacheKey(connector));
        return mode != null ? mode.protocolVersion() : null;
    }

    /**
     * Sends {@code method} and returns the server's result, negotiating the
     * revision and running the legacy handshake as needed.
     *
     * @param mcpName the {@code Mcp-Name} value (the tool name) for {@code tools/call}; null otherwise
     * @throws ConnectorException for everything that is not a result
     */
    Answer request(Target target, String method, ObjectNode params, String mcpName) {
        String key = cacheKey(target.connector());
        String pinned = target.pinnedVersion();
        Negotiated mode;
        int requests = 0;
        if (pinned != null && !McpSettings.isLegacy(pinned)) {
            mode = new Negotiated(pinned, null);
        } else if (pinned != null) {
            mode = negotiated.get(key);
            if (mode == null) {
                mode = remember(key, handshake(target, pinned));
            }
        } else {
            mode = negotiated.get(key);
            if (mode == null) {
                Exchange first = exchange(target, CURRENT_VERSION, null, method, params, mcpName, false);
                requests += first.sends();
                if (first.hasResult()) {
                    remember(key, new Negotiated(CURRENT_VERSION, null));
                    return new Answer(first.message().get("result"), first.status(), first.headers(), requests);
                }
                String legacy = legacyVersionToTry(first);
                if (legacy == null) {
                    throw failure(target, first, method);
                }
                McpLog.debug("[CONNECTOR] mcp connector={} did not accept revision {} (status={}, code={}): "
                        + "trying the legacy handshake with {}", target.connector().id(), CURRENT_VERSION,
                        first.status(), first.errorCode(), legacy);
                mode = remember(key, handshake(target, legacy));
            }
        }

        Exchange answer = exchange(target, mode.protocolVersion(), mode.sessionId(), method, params, mcpName, false);
        requests += answer.sends();
        if (mode.legacy() && answer.status() == 404 && mode.sessionId() != null) {
            // The session expired: the spec says start a new one. Once.
            negotiated.remove(key);
            McpLog.debug("[CONNECTOR] mcp connector={} session expired: initializing again",
                    target.connector().id());
            mode = remember(key, handshake(target, mode.protocolVersion()));
            answer = exchange(target, mode.protocolVersion(), mode.sessionId(), method, params, mcpName, false);
            requests += answer.sends();
        }
        if (answer.hasResult()) {
            return new Answer(answer.message().get("result"), answer.status(), answer.headers(), requests);
        }
        throw failure(target, answer, method);
    }

    /** Forgets what was negotiated with {@code connector} (a test hook; a changed lock version does this by itself). */
    void forget(ResolvedConnector connector) {
        negotiated.remove(cacheKey(connector));
    }

    private Negotiated remember(String key, Negotiated mode) {
        negotiated.put(key, mode);
        return mode;
    }

    // ---- negotiation --------------------------------------------------------------------------------

    /**
     * The legacy revision to try after the current one was refused, or null
     * when the refusal was not about the revision: {@code -32022} (the server
     * names what it supports), a server-defined {@code -32000..-32019} or
     * {@code -32600} error (a legacy server saying "not initialized" or
     * "unsupported protocol version header"), or a bare 400/406 with no
     * JSON-RPC body at all.
     */
    private static String legacyVersionToTry(Exchange first) {
        JsonNode error = first.error();
        if (error != null) {
            int code = error.path("code").asInt(0);
            if (code == UNSUPPORTED_PROTOCOL_VERSION) {
                JsonNode supported = error.path("data").path("supported");
                if (supported.isArray()) {
                    for (String candidate : McpSettings.SUPPORTED_PROTOCOL_VERSIONS) {
                        if (McpSettings.isLegacy(candidate) && contains(supported, candidate)) {
                            return candidate;
                        }
                    }
                    return null;
                }
                return DEFAULT_LEGACY_VERSION;
            }
            if (code == INVALID_REQUEST || (code <= -32000 && code >= -32019)) {
                return DEFAULT_LEGACY_VERSION;
            }
            return null;
        }
        if (first.message() == null && (first.status() == 400 || first.status() == 406)) {
            return DEFAULT_LEGACY_VERSION;
        }
        return null;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The legacy handshake: {@code initialize} asking for {@code version},
     * then {@code notifications/initialized} with whatever the server
     * answered. The server's revision must be one this transport speaks.
     */
    private Negotiated handshake(Target target, String version) {
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", version);
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);
        // No MCP-Protocol-Version header on initialize itself: the revision is what is being settled.
        Exchange init = exchange(target, null, null, METHOD_INITIALIZE, params, null, false);
        if (!init.hasResult()) {
            int code = init.errorCode();
            if (code == METHOD_NOT_FOUND) {
                throw unsupportedVersion(target, "the server does not implement the initialize handshake; it may "
                        + "speak the current revision only (pin mcp.protocolVersion to " + CURRENT_VERSION + ")");
            }
            if (code == INVALID_PARAMS || code == UNSUPPORTED_PROTOCOL_VERSION) {
                throw unsupportedVersion(target, "the server refused protocol version " + version);
            }
            throw failure(target, init, METHOD_INITIALIZE);
        }
        String serverVersion = init.message().get("result").path("protocolVersion").asText(null);
        if (!McpSettings.isLegacy(serverVersion)) {
            throw unsupportedVersion(target, "the server answered protocol version "
                    + (serverVersion == null ? "(none)" : serverVersion) + ", which this transport does not speak");
        }
        Exchange done = exchange(target, serverVersion, init.sessionId(), METHOD_INITIALIZED, null, null, true);
        if (done.status() < 200 || done.status() >= 300) {
            throw failure(target, done, METHOD_INITIALIZED);
        }
        McpLog.negotiated(target.connector(), serverVersion, true, init.sessionId() != null);
        return new Negotiated(serverVersion, init.sessionId());
    }

    private static ConnectorException unsupportedVersion(Target target, String reason) {
        return new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, false, null,
                target.label() + ": unsupported protocol version: " + reason);
    }

    // ---- one exchange -------------------------------------------------------------------------------

    /**
     * Builds and sends one JSON-RPC message. For the OAuth2 scheme a 401 drops
     * the cached token and sends once more with a fresh one.
     *
     * @param version      the {@code MCP-Protocol-Version} header; null sends none (initialize)
     * @param sessionId    the legacy session id; null sends none
     * @param notification a message without an id, expecting no result
     */
    private Exchange exchange(Target target, String version, String sessionId, String method, ObjectNode params,
            String mcpName, boolean notification) {
        long id = notification ? -1 : ids.incrementAndGet();
        boolean current = CURRENT_VERSION.equals(version);
        ObjectNode body = json.createObjectNode();
        body.put("jsonrpc", "2.0");
        if (!notification) {
            body.put("id", id);
        }
        body.put("method", method);
        ObjectNode sent = params != null ? params.deepCopy() : (current ? json.createObjectNode() : null);
        if (current) {
            ObjectNode meta = sent.withObject("/_meta");
            meta.put(META_PROTOCOL_VERSION, version);
            meta.putObject(META_CLIENT_CAPABILITIES);
            ObjectNode clientInfo = meta.putObject(META_CLIENT_INFO);
            clientInfo.put("name", CLIENT_NAME);
            clientInfo.put("version", CLIENT_VERSION);
        }
        if (sent != null) {
            body.set("params", sent);
        }
        String text;
        try {
            text = json.writeValueAsString(body);
        } catch (IOException e) {
            throw ConnectorException.inputInvalid(java.util.List.of("arguments: cannot be written as JSON"));
        }

        String label = target.label() + " (" + method + ")";
        Exchange exchange = send(target, request(target, version, sessionId, method, mcpName, current, text), id,
                label, notification);
        if (exchange.status() == 401 && AuthApplier.refreshable(target.scheme())) {
            McpLog.debug("[CONNECTOR] {} answered 401: refreshing the OAuth2 token once", label);
            auth.invalidate(target.connector());
            exchange = send(target, request(target, version, sessionId, method, mcpName, current, text), id, label,
                    notification).resent();
        }
        return exchange;
    }

    private HttpUriRequestBase request(Target target, String version, String sessionId, String method, String mcpName,
            boolean current, String body) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Accept", ACCEPT);
        if (version != null) {
            headers.put(HEADER_PROTOCOL_VERSION, version);
        }
        if (current) {
            headers.put(HEADER_METHOD, method);
            if (mcpName != null) {
                headers.put(HEADER_NAME, mcpName);
            }
        }
        if (sessionId != null) {
            headers.put(HEADER_SESSION, sessionId);
        }
        // Applied last; a credential goes in a header only (the transport refused apiKey-in-query earlier).
        auth.apply(target.connector(), target.scheme(), headers, new LinkedHashMap<>(), target.deadline());
        HttpUriRequestBase request = new HttpUriRequestBase("POST", target.uri());
        headers.forEach(request::setHeader);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return request;
    }

    /**
     * Sends and reads one answer: a JSON body within the response cap, or an
     * SSE stream read until the message with this request's id (or an
     * id-less error). A stream is aborted once the answer arrived, so a server
     * that keeps it open costs nothing more.
     */
    private Exchange send(Target target, HttpUriRequestBase request, long id, String label, boolean notification) {
        Duration remaining = target.deadline().remaining();
        if (remaining.isZero()) {
            throw new ConnectorException(ConnectorErrorCodes.TIMEOUT, false, null,
                    label + ": the call's budget of " + target.deadline().budget().toMillis() + " ms is spent");
        }
        Duration timeout = target.readTimeout().compareTo(remaining) > 0 ? remaining : target.readTimeout();
        ClassicHttpResponse response = wire.open(request, timeout, label);
        boolean abort = false;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            String sessionId = null;
            for (Header header : response.getHeaders()) {
                String name = header.getName().toLowerCase(Locale.ROOT);
                if (name.equals("mcp-session-id") && sessionId == null) {
                    sessionId = header.getValue();
                }
                if (!HIDDEN_HEADERS.contains(name)) {
                    headers.putIfAbsent(name, header.getValue());
                }
            }
            HttpEntity entity = response.getEntity();
            ContentType contentType = entity != null ? ContentType.parseLenient(entity.getContentType()) : null;
            if (entity != null && contentType != null
                    && "text/event-stream".equalsIgnoreCase(contentType.getMimeType())) {
                if (notification) {
                    abort = true;
                    return new Exchange(response.getCode(), Map.copyOf(headers), sessionId, null, "", false);
                }
                SseReader sse = new SseReader(maxResponseBytes, MAX_SSE_EVENTS, target.deadline(), label);
                AtomicReference<JsonNode> matched = new AtomicReference<>();
                String data = sse.readUntil(entity.getContent(), event -> {
                    JsonNode message = answerTo(parseLenient(event), id);
                    if (message != null) {
                        matched.set(message);
                        return true;
                    }
                    return false;
                });
                abort = true;
                return new Exchange(response.getCode(), Map.copyOf(headers), sessionId, matched.get(),
                        data == null ? "" : data, false);
            }
            ResponseReader.Raw raw = reader.read(response);
            String text = raw.truncated() ? "" : raw.text();
            JsonNode message = notification ? null : answerTo(parseLenient(text), id);
            return new Exchange(raw.status(), Map.copyOf(headers), sessionId, message, text, raw.truncated());
        } catch (IOException e) {
            throw HttpExchange.describe(e, label, timeout);
        } finally {
            if (abort) {
                request.abort();
            }
            try {
                response.close();
            } catch (IOException ignored) {
                // the connection is discarded either way
            }
        }
    }

    private JsonNode parseLenient(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The JSON-RPC message that answers request {@code id}: the object whose
     * {@code id} matches (as text, so {@code 7} and {@code "7"} agree), or an
     * error without an id (a protocol error the server could not attribute).
     * Looks inside a batch array too (2025-03-26 allowed them).
     */
    private static JsonNode answerTo(JsonNode parsed, long id) {
        if (parsed == null) {
            return null;
        }
        if (parsed.isArray()) {
            for (JsonNode element : parsed) {
                JsonNode found = answerTo(element, id);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!parsed.isObject()) {
            return null;
        }
        JsonNode messageId = parsed.get("id");
        if (messageId != null && !messageId.isNull() && String.valueOf(id).equals(messageId.asText())) {
            return parsed.has("result") || parsed.has("error") ? parsed : null;
        }
        if ((messageId == null || messageId.isNull()) && parsed.has("error")) {
            return parsed;
        }
        return null;
    }

    // ---- failures -----------------------------------------------------------------------------------

    /**
     * What an exchange that produced no result means. The HTTP status first
     * (a redirect, refused credentials, an unwell server), then the JSON-RPC
     * error code, then an answer with no message at all.
     */
    ConnectorException failure(Target target, Exchange exchange, String method) {
        int status = exchange.status();
        String label = target.label() + " (" + method + ")";
        String scrubbed = SecretScrubber.scrub(exchange.bodyText(), target.connector().secretsOrEmpty().values(),
                300);
        if (scrubbed != null && !scrubbed.isBlank()) {
            McpLog.errorBody(target.connector(), method, status, scrubbed.strip());
        }
        String answered = label + " answered " + status;
        if (status >= 300 && status < 400) {
            return new ConnectorException(ConnectorErrorCodes.REJECTED, false, status,
                    answered + ": redirect not followed");
        }
        if (status == 401 || status == 403) {
            return new ConnectorException(ConnectorErrorCodes.AUTH_FAILED, false, status, answered);
        }
        if (status == 429 || status == 502 || status == 503 || status == 504) {
            return new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, status, answered);
        }
        if (status >= 500) {
            return new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, false, status, answered);
        }
        JsonNode error = exchange.error();
        if (error != null) {
            int code = error.path("code").asInt(0);
            String message = SecretScrubber.scrub(error.path("message").asText(""),
                    target.connector().secretsOrEmpty().values(), 200);
            String detail = answered + " with JSON-RPC error " + code + (message.isBlank() ? "" : ": " + message);
            return switch (code) {
                case METHOD_NOT_FOUND -> new ConnectorException(ConnectorErrorCodes.CONFIG_INVALID, false, status,
                        detail + " (the server does not implement " + method + ")");
                case INVALID_PARAMS -> new ConnectorException(ConnectorErrorCodes.CONFIG_INVALID, false, status,
                        detail + " (the tool is unknown to the server or its arguments do not match the tool's schema)");
                case UNSUPPORTED_PROTOCOL_VERSION, HEADER_MISMATCH, MISSING_CLIENT_CAPABILITY ->
                    new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, false, status,
                            target.label() + ": unsupported protocol version: " + detail);
                default -> new ConnectorException(ConnectorErrorCodes.REJECTED, false, status, detail);
            };
        }
        if (exchange.truncated()) {
            return new ConnectorException(ConnectorErrorCodes.RESPONSE_INVALID, false, status,
                    answered + " with a body larger than " + maxResponseBytes + " bytes");
        }
        if (status >= 200 && status < 300) {
            return new ConnectorException(ConnectorErrorCodes.RESPONSE_INVALID, false, status,
                    answered + " without a JSON-RPC response to the request");
        }
        String mapped = target.errorsByStatus().get(String.valueOf(status));
        String code = mapped != null && !mapped.isBlank() ? mapped.trim() : ConnectorErrorCodes.REJECTED;
        return new ConnectorException(code, false, status,
                answered + (scrubbed == null || scrubbed.isBlank() ? "" : ": " + scrubbed.strip()));
    }
}
