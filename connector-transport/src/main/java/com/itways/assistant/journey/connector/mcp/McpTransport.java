package com.itways.assistant.journey.connector.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itways.assistant.journey.connector.CallOptions;
import com.itways.assistant.journey.connector.CallResult;
import com.itways.assistant.journey.connector.EgressPolicy;
import com.itways.assistant.journey.connector.InputBinder;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.connector.ConnectorTransport;
import com.itways.assistant.journey.connector.SecretScrubber;
import com.itways.assistant.journey.connector.Sleeper;
import com.itways.assistant.journey.connector.http.AuthApplier;
import com.itways.assistant.journey.connector.http.Deadline;
import com.itways.assistant.journey.connector.http.HttpExchange;
import com.itways.assistant.journey.connector.http.OAuth2ClientCredentials;
import com.itways.assistant.journey.connector.http.RequestUrls;
import com.itways.assistant.journey.connector.http.ResponseReader;
import com.itways.assistant.journey.connector.http.RetrySchedule;
import com.itways.assistant.journey.connector.http.TransportHttpClients;
import com.itways.assistant.journey.model.connector.AuthScheme;
import com.itways.assistant.journey.model.connector.ConnectorDefaults;
import com.itways.assistant.journey.model.connector.ConnectorDescriptor;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import com.itways.common.net.PublicUrlPolicy;
import com.itways.web.net.PublicOnlyDnsResolver;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

/**
 * The MCP transport (1.2.0): a remote Model Context Protocol server over
 * Streamable HTTP, one named tool per operation, called deterministically by
 * the journey. No model chooses a tool here, and nothing the server says — a
 * tool's description, its annotations, a result — is ever shown to one.
 *
 * <p>
 * One instance per host process, shared by every call, with the same pooled
 * client, pinned resolver and egress rules as the REST transport
 * ({@link TransportHttpClients}, {@link RequestUrls}): the connector's base
 * URL is the server's endpoint and must be https (http only where the policy
 * tolerates it), on the connector's host list and within the platform
 * bound, and resolve to a public address; redirects are never followed; TLS
 * is the JDK default; an answer — a JSON body or an SSE stream — is read within
 * {@link EgressPolicy#maxResponseBytes()}. No stdio, no local process, ever.
 *
 * <p>
 * A call runs in this order and stops at the first rule it fails:
 * <ol>
 * <li>the descriptor's auth scheme must be one of {@link #AUTH_SCHEMES} (a
 * credential travels in a header; {@code apiKey} in the query is refused);</li>
 * <li>the inputs are validated and coerced against the operation's input
 * schema ({@link InputBinder}) and become the tool's {@code arguments}; the
 * idempotency key, when the call has one and the operation names an
 * {@code idempotencyArgument}, is added to them;</li>
 * <li>the host is resolved through the pinned resolver;</li>
 * <li>{@code tools/call} is sent ({@link McpProtocol}: the current revision,
 * or the legacy handshake when the server needs it, remembered per
 * connector);</li>
 * <li>a transient failure (no connection, timeout, 429, 502, 503, 504) is
 * sent again, with back-off, only when the operation declares
 * {@code idempotent: true} or the call carries a key the tool takes, at most
 * three times and never past the budget.</li>
 * </ol>
 *
 * <p>
 * The answer: {@code structuredContent} when the tool returned it; else the
 * first {@code text} content block, parsed as a JSON object when it is one,
 * otherwise {@code {"text": "..."}}; image, audio and resource blocks are
 * dropped (noted at DEBUG). The caller masks it against the operation's output
 * schema exactly as for REST. A tool that answers {@code isError: true} failed
 * in its own terms: {@code CONNECTOR_REJECTED}, not retried, not a breaker
 * failure. A result that asks for more input ({@code resultType:
 * input_required}, 2026-07-28's MRTR) is {@code CONNECTOR_REJECTED} too:
 * a journey step has no one to ask.
 */
public final class McpTransport implements ConnectorTransport, Closeable {

    /** TCP connect timeout for every call. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    public static final int POOL_MAX_TOTAL = 25;
    public static final int POOL_MAX_PER_ROUTE = 10;

    /** Header-borne credentials only: the specification's own authorization is a Bearer token. */
    public static final Set<String> AUTH_SCHEMES = Set.of(AuthScheme.SCHEME_NONE, AuthScheme.SCHEME_API_KEY,
            AuthScheme.SCHEME_BEARER, AuthScheme.SCHEME_OAUTH2_CLIENT_CREDENTIALS);

    /** {@link #discover}: tools kept, pages read, bytes of one tool's schema (as JSON) kept. */
    public static final int MAX_TOOLS = 200;
    public static final int MAX_PAGES = 20;
    public static final int MAX_SCHEMA_BYTES = 64 * 1024;

    private final EgressPolicy policy;
    private final ObjectMapper json;
    private final Sleeper sleeper;
    private final CloseableHttpClient client;
    private final HttpExchange wire;
    private final McpProtocol protocol;

    /** Production: the operator's egress policy, a plain mapper, real waits between retries. */
    public McpTransport(EgressPolicy policy) {
        this(policy, new ObjectMapper(), Sleeper.REAL);
    }

    /** For tests: a stub DNS comes with the policy, waits can be recorded instead of slept. */
    public McpTransport(EgressPolicy policy, ObjectMapper json, Sleeper sleeper) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.json = Objects.requireNonNull(json, "json");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        PublicOnlyDnsResolver dns = new PublicOnlyDnsResolver(policy.dns(), PublicUrlPolicy::isPublic,
                policy.privateHosts().entries());
        this.client = TransportHttpClients.create(dns, POOL_MAX_TOTAL, POOL_MAX_PER_ROUTE, CONNECT_TIMEOUT);
        ResponseReader reader = new ResponseReader(json, policy.maxResponseBytes());
        this.wire = new HttpExchange(client, dns, reader, CONNECT_TIMEOUT);
        AuthApplier auth = new AuthApplier(new OAuth2ClientCredentials(wire, json, policy));
        this.protocol = new McpProtocol(json, wire, reader, auth, policy.maxResponseBytes());
    }

    @Override
    public String kind() {
        return ConnectorDescriptor.TRANSPORT_MCP;
    }

    @Override
    public Set<String> supportedAuthSchemes() {
        return AUTH_SCHEMES;
    }

    /** One tool as the server lists it; the schemas and annotations are the server's words, unchecked. */
    public record DiscoveredTool(
            String name,
            String title,
            String description,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Map<String, Object> annotations,
            /** True when a schema was larger than {@link #MAX_SCHEMA_BYTES} and left out. */
            boolean schemaOmitted) {
    }

    /**
     * The server's tool list.
     *
     * @param protocolVersion the revision the server spoke
     * @param truncated       true when more tools exist than {@link #MAX_TOOLS} or {@link #MAX_PAGES} allowed
     * @param notes           what was left out and why, one sentence each
     */
    public record Discovery(List<DiscoveredTool> tools, String protocolVersion, boolean truncated, List<String> notes) {
    }

    // ---- call ---------------------------------------------------------------------------------------

    @Override
    public CallResult call(ResolvedConnector connector, ConnectorOperation operation, Map<String, Object> inputs,
            CallOptions options) {
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(options, "options");
        Deadline deadline = Deadline.start(options.budget());
        ConnectorDescriptor descriptor = connector.descriptor();
        if (descriptor == null) {
            throw ConnectorException.configInvalid("connector carries no descriptor");
        }
        AuthScheme scheme = checkedScheme(descriptor);
        RequestUrls.Base base = RequestUrls.base(connector.baseUrl(), connector.allowedHostsOrEmpty(), policy);
        McpLog.started(connector, operation, base.host(), base.path());
        int attempts = 0;
        try {
            ObjectNode params = arguments(operation, inputs, options);
            boolean safeToRepeat = operation.declaredIdempotent()
                    || (options.idempotencyKey() != null && hasIdempotencyArgument(operation));
            ConnectorDefaults defaults = descriptor.defaultsOrNone();
            McpProtocol.Target target = new McpProtocol.Target(connector, endpoint(base), scheme,
                    descriptor.mcpOrNone().protocolVersion(), readTimeout(options, operation, defaults), deadline,
                    operation.errorsOrEmpty(), "MCP tools/call " + operation.toolNameOrKey());
            wire.requireResolvable(base.host(), "base URL");
            RetrySchedule retry = new RetrySchedule(defaults, safeToRepeat, options);
            while (true) {
                attempts++;
                try {
                    McpProtocol.Answer answer = protocol.request(target, McpProtocol.METHOD_TOOLS_CALL, params,
                            operation.toolNameOrKey());
                    attempts += answer.requests() - 1;
                    Object body = toolOutput(connector, answer.result(), target.label());
                    CallResult result = new CallResult(answer.status(), body, answer.headers(), attempts,
                            deadline.elapsedMs());
                    McpLog.answered(connector, operation, result.status(), attempts, result.latencyMs());
                    return result;
                } catch (ConnectorException failure) {
                    if (!retry.allowsAnother(attempts) || !RetrySchedule.retryable(failure)) {
                        throw failure.withAttempts(attempts);
                    }
                    Duration delay = retry.delayAfter(attempts);
                    if (!deadline.allows(delay)) {
                        throw failure.withAttempts(attempts);
                    }
                    McpLog.retrying(connector, operation, attempts, delay.toMillis(), failure.code());
                    pause(delay, failure.withAttempts(attempts));
                }
            }
        } catch (ConnectorException failure) {
            ConnectorException recorded = failure.withAttempts(Math.max(failure.attempts(), attempts));
            McpLog.failed(connector, operation, recorded, deadline.elapsedMs());
            throw recorded;
        }
    }

    /**
     * The Test button: the declared {@code test.operation} when the type has
     * one (a harmless tool the admin chose), else {@code tools/list}, which
     * every server answers and which exercises the endpoint, the credential
     * and the protocol without running anything. The body of a listing is
     * {@code {"toolCount": n, "tools": [names...]}}, every page read (at most
     * {@link #MAX_PAGES} pages and {@link #MAX_TOOLS} names, as for
     * {@link #discover}), so the count is the server's whole list.
     */
    @Override
    public CallResult test(ResolvedConnector connector, Duration budget) {
        Objects.requireNonNull(connector, "connector");
        ConnectorDescriptor descriptor = connector.descriptor();
        ConnectorOperation declared = descriptor != null ? descriptor.testOperation().orElse(null) : null;
        if (declared != null) {
            return call(connector, declared, Map.of(), CallOptions.single(budget));
        }
        Deadline deadline = Deadline.start(budget);
        McpProtocol.Target target = target(connector, deadline, "MCP tools/list");
        List<String> names = new ArrayList<>();
        McpProtocol.Answer answer;
        int requests = 0;
        int pages = 0;
        String cursor = null;
        while (true) {
            ObjectNode params = json.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            answer = protocol.request(target, McpProtocol.METHOD_TOOLS_LIST, params, null);
            requests += answer.requests();
            pages++;
            for (JsonNode tool : answer.result().path("tools")) {
                String name = tool.path("name").asText(null);
                if (name != null && names.size() < MAX_TOOLS) {
                    names.add(name);
                }
            }
            String next = answer.result().path("nextCursor").asText(null);
            if (next == null || next.isBlank() || next.equals(cursor) || pages >= MAX_PAGES
                    || names.size() >= MAX_TOOLS) {
                break;
            }
            cursor = next;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("toolCount", names.size());
        body.put("tools", names);
        return new CallResult(answer.status(), body, answer.headers(), requests, deadline.elapsedMs());
    }

    /**
     * Lists the server's tools ({@code tools/list}, every page) for
     * journey-service's import: name, title, description, input and output
     * schema, annotations. The same egress rules and credential handling as a
     * call. {@code connector} may carry a draft descriptor (transport, auth,
     * fields, optional {@code mcp.protocolVersion}) and no operations yet; a
     * null descriptor means no credential.
     *
     * <p>
     * Caps: {@link #MAX_TOOLS} tools, {@link #MAX_PAGES} pages, a schema over
     * {@link #MAX_SCHEMA_BYTES} is left out and the tool marked. Annotations
     * ({@code readOnlyHint} and the like) are the server's claims: the caller
     * treats a discovered tool as not idempotent unless the server marks it
     * read-only, and an admin reviews it.
     *
     * @throws ConnectorException for everything that stops the listing
     */
    public Discovery discover(ResolvedConnector connector, Duration budget) {
        Objects.requireNonNull(connector, "connector");
        Deadline deadline = Deadline.start(budget);
        McpProtocol.Target target = target(connector, deadline, "MCP tools/list");
        List<DiscoveredTool> tools = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean truncated = false;
        String cursor = null;
        int pages = 0;
        while (true) {
            ObjectNode params = json.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            McpProtocol.Answer answer = protocol.request(target, McpProtocol.METHOD_TOOLS_LIST, params, null);
            pages++;
            JsonNode listed = answer.result().path("tools");
            if (!listed.isArray()) {
                throw new ConnectorException(ConnectorErrorCodes.RESPONSE_INVALID, false, answer.status(),
                        target.label() + " answered without a tools array");
            }
            for (JsonNode tool : listed) {
                if (tools.size() >= MAX_TOOLS) {
                    truncated = true;
                    break;
                }
                DiscoveredTool discovered = discovered(tool, notes);
                if (discovered != null) {
                    tools.add(discovered);
                }
            }
            String next = answer.result().path("nextCursor").asText(null);
            if (truncated) {
                notes.add("the server lists more than " + MAX_TOOLS + " tools; only the first " + MAX_TOOLS
                        + " were read");
                break;
            }
            if (next == null || next.isBlank()) {
                break;
            }
            if (next.equals(cursor) || pages >= MAX_PAGES) {
                truncated = true;
                notes.add("the listing did not end within " + MAX_PAGES + " pages; what was read so far is kept");
                break;
            }
            cursor = next;
        }
        McpLog.discovered(connector, tools.size(), pages, truncated);
        String version = protocol.versionFor(connector, target.pinnedVersion());
        return new Discovery(List.copyOf(tools), version, truncated, List.copyOf(notes));
    }

    @Override
    public Collection<String> scrubValues(ResolvedConnector connector) {
        return protocol.scrubValues(connector);
    }

    /** Closes the HTTP client and its pooled connections; the token and negotiation caches die with the instance. */
    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            McpLog.debug("[CONNECTOR] closing the HTTP client failed", e);
        }
    }

    // ---- preparation --------------------------------------------------------------------------------

    private AuthScheme checkedScheme(ConnectorDescriptor descriptor) {
        AuthScheme scheme = descriptor.authOrNone();
        if (!AUTH_SCHEMES.contains(scheme.schemeOrNone())) {
            throw ConnectorException.configInvalid(
                    "auth.scheme: '" + scheme.schemeOrNone() + "' is not a scheme the MCP transport applies");
        }
        if (scheme.isApiKeyInQuery()) {
            throw ConnectorException.configInvalid("auth.in: the MCP transport sends a credential in a header only");
        }
        return scheme;
    }

    /** The endpoint and credential of {@code connector}, for a listing (no operation). */
    private McpProtocol.Target target(ResolvedConnector connector, Deadline deadline, String label) {
        ConnectorDescriptor descriptor = connector.descriptor();
        AuthScheme scheme = descriptor != null ? checkedScheme(descriptor) : AuthScheme.NONE;
        String pinned = descriptor != null ? descriptor.mcpOrNone().protocolVersion() : null;
        RequestUrls.Base base = RequestUrls.base(connector.baseUrl(), connector.allowedHostsOrEmpty(), policy);
        wire.requireResolvable(base.host(), "base URL");
        Duration readTimeout = CallOptions.DEFAULT_READ_TIMEOUT.compareTo(deadline.budget()) > 0 ? deadline.budget()
                : CallOptions.DEFAULT_READ_TIMEOUT;
        return new McpProtocol.Target(connector, endpoint(base), scheme, pinned, readTimeout, deadline, Map.of(),
                label);
    }

    /** The server's endpoint is the base URL itself: no path is added, so {@link RequestUrls} checks it as a base alone. */
    private static URI endpoint(RequestUrls.Base base) {
        return RequestUrls.request(base, "", Map.of());
    }

    /**
     * The {@code tools/call} params: the tool's name and its arguments, which
     * are the bound inputs (every one a body property, under its wire name)
     * plus the idempotency key under {@code idempotencyArgument} when both
     * exist.
     */
    private ObjectNode arguments(ConnectorOperation operation, Map<String, Object> inputs, CallOptions options) {
        InputBinder.BoundInputs bound = InputBinder.bind(operation, inputs);
        if (!bound.path().isEmpty() || !bound.query().isEmpty() || !bound.headers().isEmpty()) {
            throw ConnectorException.configInvalid("operation " + operation.key()
                    + " places an input in the path, query or a header; an MCP input is a tool argument");
        }
        Map<String, Object> arguments = new LinkedHashMap<>(bound.body());
        if (options.idempotencyKey() != null) {
            if (hasIdempotencyArgument(operation)) {
                arguments.put(operation.idempotencyArgument().trim(), options.idempotencyKey());
            } else {
                McpLog.debug("[CONNECTOR] operation={} has an idempotency key but names no idempotencyArgument",
                        operation.key());
            }
        }
        ObjectNode params = json.createObjectNode();
        params.put("name", operation.toolNameOrKey());
        try {
            params.set("arguments", json.valueToTree(arguments));
        } catch (IllegalArgumentException e) {
            throw ConnectorException.inputInvalid(List.of("arguments: cannot be written as JSON"));
        }
        return params;
    }

    private static boolean hasIdempotencyArgument(ConnectorOperation operation) {
        return operation.idempotencyArgument() != null && !operation.idempotencyArgument().isBlank();
    }

    private static Duration readTimeout(CallOptions options, ConnectorOperation operation, ConnectorDefaults defaults) {
        Duration timeout;
        if (options.readTimeout() != null) {
            timeout = options.readTimeout();
        } else if (operation.timeoutMs() != null && operation.timeoutMs() > 0) {
            timeout = Duration.ofMillis(operation.timeoutMs());
        } else if (defaults.readTimeoutMs() != null && defaults.readTimeoutMs() > 0) {
            timeout = Duration.ofMillis(defaults.readTimeoutMs());
        } else {
            timeout = CallOptions.DEFAULT_READ_TIMEOUT;
        }
        return timeout.compareTo(CallOptions.MAX_READ_TIMEOUT) > 0 ? CallOptions.MAX_READ_TIMEOUT : timeout;
    }

    // ---- the answer ---------------------------------------------------------------------------------

    /**
     * The value a {@code tools/call} result becomes (see the class comment).
     *
     * @throws ConnectorException {@code REJECTED} for {@code isError} and {@code input_required};
     *                              {@code RESPONSE_INVALID} for a result that is not an object
     */
    private Object toolOutput(ResolvedConnector connector, JsonNode result, String label) {
        if (result == null || !result.isObject()) {
            throw new ConnectorException(ConnectorErrorCodes.RESPONSE_INVALID, false, null,
                    label + " answered with a result that is not an object");
        }
        if ("input_required".equals(result.path("resultType").asText(null))) {
            throw new ConnectorException(ConnectorErrorCodes.REJECTED, false, null, label
                    + " asked for more input (resultType input_required), which an CONNECTOR_CALL step cannot supply");
        }
        String text = null;
        int dropped = 0;
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText(null))) {
                if (text == null) {
                    text = block.path("text").asText("");
                }
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            McpLog.debug("[CONNECTOR] {} returned {} non-text content block(s), which were dropped", label, dropped);
        }
        if (result.path("isError").asBoolean(false)) {
            String reason = SecretScrubber.scrub(text, protocol.scrubValues(connector), 300);
            throw new ConnectorException(ConnectorErrorCodes.REJECTED, false, null,
                    label + " reported an error" + (reason == null || reason.isBlank() ? "" : ": " + reason.strip()));
        }
        JsonNode structured = result.get("structuredContent");
        if (structured != null && structured.isObject()) {
            return toMap(structured);
        }
        if (text != null && !text.isBlank()) {
            try {
                JsonNode parsed = json.readTree(text);
                if (parsed != null && parsed.isObject()) {
                    return toMap(parsed);
                }
            } catch (JsonProcessingException e) {
                // plain text: wrapped below
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (text != null) {
            out.put("text", text);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode object) {
        return json.convertValue(object, LinkedHashMap.class);
    }

    /** One listed tool, or null (with a note) when it has no name. */
    private DiscoveredTool discovered(JsonNode tool, List<String> notes) {
        String name = tool.path("name").asText(null);
        if (name == null || name.isBlank()) {
            notes.add("a tool without a name was skipped");
            return null;
        }
        boolean omitted = false;
        Map<String, Object> input = schema(tool.get("inputSchema"));
        Map<String, Object> output = schema(tool.get("outputSchema"));
        if (tooLarge(input)) {
            input = null;
            omitted = true;
        }
        if (tooLarge(output)) {
            output = null;
            omitted = true;
        }
        if (omitted) {
            notes.add("tool " + name + ": a schema larger than " + MAX_SCHEMA_BYTES + " bytes was left out");
        }
        return new DiscoveredTool(name, tool.path("title").asText(null), tool.path("description").asText(null), input,
                output, schema(tool.get("annotations")), omitted);
    }

    private Map<String, Object> schema(JsonNode node) {
        return node != null && node.isObject() ? toMap(node) : null;
    }

    private boolean tooLarge(Map<String, Object> schema) {
        if (schema == null) {
            return false;
        }
        try {
            return json.writeValueAsBytes(schema).length > MAX_SCHEMA_BYTES;
        } catch (JsonProcessingException e) {
            return true;
        }
    }

    private void pause(Duration delay, ConnectorException pending) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw pending;
        }
    }
}
