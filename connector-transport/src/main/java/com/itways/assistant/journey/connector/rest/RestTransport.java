package com.itways.assistant.journey.connector.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.itways.assistant.journey.model.connector.ConnectorSystem;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import com.itways.common.net.PublicUrlPolicy;
import com.itways.web.net.PublicOnlyDnsResolver;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;

/**
 * The REST transport: HTTPS JSON APIs described by a connector type descriptor.
 *
 * <p>
 * One instance per host process, shared by every call: it owns one pooled
 * HTTP client whose connections dial exactly the addresses a
 * {@link PublicOnlyDnsResolver} vetted (so a name cannot answer a public
 * address to the check and a private one to the connect), and one OAuth2
 * token cache. The client never follows a redirect — a 3xx comes back as
 * {@code CONNECTOR_REJECTED} — because a public URL answering 302 to a
 * private one would walk past every rule on the URL the author wrote. It keeps
 * no cookies, reads no proxy settings and never retries on its own (see
 * {@link TransportHttpClients}): whether a request is sent twice is this class's call.
 *
 * <p>
 * A call runs in this order, and stops at the first rule it fails:
 * <ol>
 * <li>the base URL is checked ({@code RequestUrls}): https (http only when the
 * policy tolerates it), a host, no user info, query or fragment; the host must
 * be on the connector's own host list (the base host alone when it has none)
 * and within the platform bound;</li>
 * <li>the inputs are validated, coerced and placed ({@link InputBinder});</li>
 * <li>the host is resolved through the pinned resolver and refused when it
 * points at a private address;</li>
 * <li>each attempt renders the path (parameters strictly percent-encoded),
 * applies the auth scheme last, sends, and reads at most
 * {@link EgressPolicy#maxResponseBytes()} bytes;</li>
 * <li>a transient failure (no connection, timeout, 429, 502, 503, 504) is sent
 * again, with back-off, only for an idempotent operation or a call with an
 * idempotency key, at most three times and never past the budget.</li>
 * </ol>
 *
 * <p>
 * The TCP connect timeout is {@link #CONNECT_TIMEOUT} for every connector: it
 * is set on the shared connection manager, and a descriptor's
 * {@code connectTimeoutMs} is read as documentation of the cap rather than
 * applied per call. The read timeout is per attempt: the call's, else the
 * operation's, else the type's, else {@link CallOptions#DEFAULT_READ_TIMEOUT},
 * never more than {@link CallOptions#MAX_READ_TIMEOUT} or what remains of the
 * budget.
 */
public final class RestTransport implements ConnectorTransport, Closeable {

    /** TCP connect timeout for every call; the descriptor's {@code connectTimeoutMs} may only shorten it. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** Connections kept in all, and to one host: several journeys may hit one connector at once. */
    public static final int POOL_MAX_TOTAL = 25;
    public static final int POOL_MAX_PER_ROUTE = 10;

    public static final Set<String> AUTH_SCHEMES = Set.of(AuthScheme.SCHEME_NONE, AuthScheme.SCHEME_API_KEY,
            AuthScheme.SCHEME_BASIC, AuthScheme.SCHEME_BEARER, AuthScheme.SCHEME_OAUTH2_CLIENT_CREDENTIALS);

    /** Headers the client computes itself; one from a descriptor or an input would break the request. */
    private static final Set<String> MANAGED_HEADERS = Set.of("host", "content-length", "transfer-encoding",
            "connection");

    private final EgressPolicy policy;
    private final ObjectMapper json;
    private final Sleeper sleeper;
    private final CloseableHttpClient client;
    private final ResponseReader reader;
    private final HttpExchange wire;
    private final AuthApplier auth;

    /** Production: the operator's egress policy, a plain mapper, real waits between retries. */
    public RestTransport(EgressPolicy policy) {
        this(policy, new ObjectMapper(), Sleeper.REAL);
    }

    /** For tests: a stub DNS comes with the policy, waits can be recorded instead of slept. */
    public RestTransport(EgressPolicy policy, ObjectMapper json, Sleeper sleeper) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.json = Objects.requireNonNull(json, "json");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        PublicOnlyDnsResolver dns = new PublicOnlyDnsResolver(policy.dns(), PublicUrlPolicy::isPublic,
                policy.privateHosts().entries());
        this.client = TransportHttpClients.create(dns, POOL_MAX_TOTAL, POOL_MAX_PER_ROUTE, CONNECT_TIMEOUT);
        this.reader = new ResponseReader(json, policy.maxResponseBytes());
        this.wire = new HttpExchange(client, dns, reader, CONNECT_TIMEOUT);
        this.auth = new AuthApplier(new OAuth2ClientCredentials(wire, json, policy));
    }

    @Override
    public String kind() {
        return ConnectorDescriptor.TRANSPORT_REST;
    }

    @Override
    public Set<String> supportedAuthSchemes() {
        return AUTH_SCHEMES;
    }

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
        AuthScheme scheme = descriptor.authOrNone();
        if (!AUTH_SCHEMES.contains(scheme.schemeOrNone())) {
            throw ConnectorException
                    .configInvalid("auth.scheme: '" + scheme.schemeOrNone() + "' is not a scheme the REST transport applies");
        }
        RequestUrls.Base base = RequestUrls.base(connector.baseUrl(), connector.allowedHostsOrEmpty(), policy);
        ConnectorLog.started(connector, operation, base.host());
        int attempts = 0;
        try {
            Call call = prepare(connector, operation, inputs, options, base, descriptor);
            wire.requireResolvable(base.host(), "base URL");
            RetrySchedule retry = new RetrySchedule(descriptor.defaultsOrNone(), operation, options);
            while (true) {
                // Counted before sending: a request that got no answer was still sent.
                attempts++;
                try {
                    Sent sent = exchange(call, scheme, deadline);
                    attempts += sent.extraRequests();
                    CallResult result = interpret(call, sent.raw(), attempts, deadline);
                    ConnectorLog.answered(connector, operation, result.status(), attempts, result.latencyMs());
                    return result;
                } catch (ConnectorException failure) {
                    if (!retry.allowsAnother(attempts) || !RetrySchedule.retryable(failure)) {
                        throw failure.withAttempts(attempts);
                    }
                    Duration delay = retry.delayAfter(attempts);
                    if (!deadline.allows(delay)) {
                        throw failure.withAttempts(attempts);
                    }
                    ConnectorLog.retrying(connector, operation, attempts, delay.toMillis(), failure.code());
                    pause(delay, failure.withAttempts(attempts));
                }
            }
        } catch (ConnectorException failure) {
            ConnectorException recorded = failure.withAttempts(Math.max(failure.attempts(), attempts));
            ConnectorLog.failed(connector, operation, recorded, deadline.elapsedMs());
            throw recorded;
        }
    }

    @Override
    public CallResult test(ResolvedConnector connector, Duration budget) {
        Objects.requireNonNull(connector, "connector");
        ConnectorDescriptor descriptor = connector.descriptor();
        ConnectorOperation operation = descriptor != null ? descriptor.testOperation().orElse(null) : null;
        if (operation == null) {
            throw ConnectorException.configInvalid("type declares no testable operation");
        }
        return call(connector, operation, Map.of(), CallOptions.single(budget));
    }

    @Override
    public Collection<String> scrubValues(ResolvedConnector connector) {
        return auth.scrubValues(connector);
    }

    /** Closes the HTTP client and its pooled connections; the token cache dies with the instance. */
    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            ConnectorLog.debug("[CONNECTOR] closing the HTTP client failed", e);
        }
    }

    // ---- one call, prepared once ------------------------------------------------------------------

    /** Everything about a call that does not change between attempts. */
    private record Call(
            ResolvedConnector connector,
            ConnectorOperation operation,
            RequestUrls.Base base,
            InputBinder.BoundInputs inputs,
            String renderedPath,
            Map<String, String> headers,
            String body,
            Duration readTimeout,
            String label) {
    }

    /** What one exchange produced, and how many requests it took beyond the first (one when a token was refreshed). */
    private record Sent(ResponseReader.Raw raw, int extraRequests) {
    }

    private Call prepare(ResolvedConnector connector, ConnectorOperation operation, Map<String, Object> inputs,
            CallOptions options, RequestUrls.Base base, ConnectorDescriptor descriptor) {
        InputBinder.BoundInputs bound = InputBinder.bind(operation, inputs);
        // Rendered once, before anything is resolved: a template that leaves the base URL never costs a lookup.
        // A system's prefix (1.3.0) sits between the base path and the operation's path.
        Optional<ConnectorSystem> system = descriptor.systemOf(operation);
        if (operation.system() != null && system.isEmpty()) {
            throw ConnectorException.configInvalid("operation " + operation.key() + " names system '"
                    + operation.system() + "', which the type does not declare");
        }
        String renderedPath = RequestUrls.pathPrefix(system.orElse(null))
                + RequestUrls.renderPath(operation.path(), bound.path());
        String method = operation.methodOrGet();
        ConnectorDefaults defaults = descriptor.defaultsOrNone();

        // Later wins: type defaults, then the system's headers, then the operation's header inputs;
        // the idempotency key and the credential are put after all of them.
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        defaults.headersOrEmpty().forEach((name, value) -> putHeader(headers, name, value));
        system.ifPresent(s -> s.headersOrEmpty().forEach((name, value) -> putHeader(headers, name, value)));
        bound.headers().forEach((name, value) -> putHeader(headers, name, value));
        headers.putIfAbsent("Accept", "application/json");

        String body = null;
        if (!bound.body().isEmpty()) {
            if ("GET".equals(method) || "HEAD".equals(method)) {
                ConnectorLog.debug("[CONNECTOR] operation={} is a {}: its body inputs {} are not sent",
                        operation.key(), method, bound.body().keySet());
            } else {
                try {
                    body = json.writeValueAsString(bound.body());
                } catch (JsonProcessingException e) {
                    throw ConnectorException.inputInvalid(List.of("body: cannot be written as JSON"));
                }
            }
        }

        String idempotencyHeader = descriptor.idempotencyHeaderFor(operation);
        if (options.idempotencyKey() != null) {
            if (idempotencyHeader != null) {
                headers.put(idempotencyHeader, options.idempotencyKey());
            } else {
                ConnectorLog.debug("[CONNECTOR] operation={} has an idempotency key but its type declares no header",
                        operation.key());
            }
        }

        Duration readTimeout = readTimeout(options, operation, defaults);
        return new Call(connector, operation, base, bound, renderedPath, Map.copyOf(headers), body, readTimeout,
                method + " " + operation.key());
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        if (name == null || name.isBlank() || value == null) {
            return;
        }
        if (MANAGED_HEADERS.contains(name.trim().toLowerCase(Locale.ROOT))) {
            ConnectorLog.debug("[CONNECTOR] header {} is managed by the client and was not set", name.trim());
            return;
        }
        headers.put(name.trim(), value);
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

    // ---- one attempt -----------------------------------------------------------------------------

    /**
     * Sends the request once; for the OAuth2 scheme, a 401 drops the cached
     * token and sends once more with a fresh one (the system refused the
     * credential before acting, so even a write may be repeated here).
     */
    private Sent exchange(Call call, AuthScheme scheme, Deadline deadline) {
        ResponseReader.Raw raw = send(call, scheme, deadline);
        if (raw.status() == 401 && AuthApplier.refreshable(scheme)) {
            ConnectorLog.debug("[CONNECTOR] operation={} answered 401: refreshing the OAuth2 token once",
                    call.operation().key());
            auth.invalidate(call.connector());
            return new Sent(send(call, scheme, deadline), 1);
        }
        return new Sent(raw, 0);
    }

    private ResponseReader.Raw send(Call call, AuthScheme scheme, Deadline deadline) {
        Duration remaining = deadline.remaining();
        if (remaining.isZero()) {
            throw new ConnectorException(ConnectorErrorCodes.TIMEOUT, false, null,
                    call.label() + ": the call's budget of " + deadline.budget().toMillis() + " ms is spent");
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(call.headers());
        Map<String, String> query = new LinkedHashMap<>(call.inputs().query());
        auth.apply(call.connector(), scheme, headers, query, deadline);

        URI uri = RequestUrls.request(call.base(), call.renderedPath(), query);
        HttpUriRequestBase request = new HttpUriRequestBase(call.operation().methodOrGet(), uri);
        headers.forEach(request::setHeader);
        if (call.body() != null) {
            request.setEntity(new StringEntity(call.body(), ContentType.APPLICATION_JSON));
        }
        Duration timeout = call.readTimeout().compareTo(remaining) > 0 ? remaining : call.readTimeout();
        return wire.send(request, timeout, call.label());
    }

    // ---- the answer ------------------------------------------------------------------------------

    private CallResult interpret(Call call, ResponseReader.Raw raw, int attempts, Deadline deadline) {
        int status = raw.status();
        if (status >= 200 && status < 300) {
            Object body = reader.body(raw, call.label());
            body = withIdFromHeader(call.operation(), raw, body);
            return new CallResult(status, body, raw.headers(), attempts, deadline.elapsedMs());
        }
        throw failure(call, raw);
    }

    @SuppressWarnings("unchecked")
    private static Object withIdFromHeader(ConnectorOperation operation, ResponseReader.Raw raw, Object body) {
        String header = operation.response() != null ? operation.response().idFromHeader() : null;
        if (header == null || header.isBlank() || !(body instanceof Map<?, ?> map) || map.containsKey("id")) {
            return body;
        }
        String id = raw.header(header.trim());
        if (id == null) {
            return body;
        }
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) map);
        out.put("id", id);
        return out;
    }

    private ConnectorException failure(Call call, ResponseReader.Raw raw) {
        int status = raw.status();
        String scrubbedBody = SecretScrubber.scrub(raw.text(), auth.scrubValues(call.connector()), 300);
        if (scrubbedBody != null && !scrubbedBody.isBlank()) {
            ConnectorLog.errorBody(call.connector(), call.operation(), status, scrubbedBody);
        }
        String message = call.label() + " answered " + status;
        if (status >= 300 && status < 400) {
            return new ConnectorException(ConnectorErrorCodes.REJECTED, false, status,
                    message + ": redirect not followed");
        }
        String detail = scrubbedBody == null || scrubbedBody.isBlank() ? "" : ": " + scrubbedBody.strip();
        if (status == 401 || status == 403) {
            return new ConnectorException(ConnectorErrorCodes.AUTH_FAILED, false, status, message + detail);
        }
        if (status == 429 || status == 502 || status == 503 || status == 504) {
            return new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, true, status, message + detail);
        }
        if (status >= 500) {
            return new ConnectorException(ConnectorErrorCodes.UNAVAILABLE, false, status, message + detail);
        }
        String mapped = call.operation().errorsOrEmpty().get(String.valueOf(status));
        String code = mapped != null && !mapped.isBlank() ? mapped.trim() : ConnectorErrorCodes.REJECTED;
        return new ConnectorException(code, false, status, message + detail);
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
