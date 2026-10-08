package com.itways.assistant.journey.connector.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.connector.CallOptions;
import com.itways.assistant.journey.connector.EgressPolicy;
import com.itways.assistant.journey.connector.ConnectorErrorCodes;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.connector.SecretScrubber;
import com.itways.assistant.journey.model.connector.AuthScheme;
import com.itways.assistant.journey.model.connector.ResolvedConnector;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;

/**
 * The {@code oauth2-client-credentials} scheme: one access token per
 * connector, fetched from the type's token endpoint and reused until shortly
 * before it expires.
 *
 * <p>
 * The cache key is {@code <connector id>:<lockVersion>}, so editing the
 * connector (a new client secret, a new tenant id in the token URL) starts a
 * fresh token without anyone having to clear anything. Fetches are
 * single-flight per key: when ten journeys hit a connector whose token just
 * expired, one of them calls the token endpoint and the other nine wait for
 * its answer. The token endpoint is admin-authored in the descriptor, so it is
 * exempt from the connector's own host list, but it must still be https
 * (unless the policy tolerates http), carry no user info, pass the platform
 * bound and resolve to a public address like any other host the platform
 * dials.
 */
public final class OAuth2ClientCredentials {

    /** How long a token is trusted when the endpoint does not say. */
    public static final long DEFAULT_EXPIRES_IN_SECONDS = 300;
    /** Refreshed this long before the endpoint's expiry so a token never dies mid-request. */
    public static final long EXPIRY_MARGIN_SECONDS = 60;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}/]+)}");

    private record CachedToken(String value, long expiresAtNanos) {
        boolean isFresh() {
            return System.nanoTime() < expiresAtNanos;
        }
    }

    private final HttpExchange wire;
    private final ObjectMapper json;
    private final EgressPolicy policy;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public OAuth2ClientCredentials(HttpExchange wire, ObjectMapper json, EgressPolicy policy) {
        this.wire = wire;
        this.json = json;
        this.policy = policy;
    }

    public static String cacheKey(ResolvedConnector connector) {
        return connector.id() + ":" + connector.lockVersion();
    }

    /** The current token for {@code connector}: cached while fresh, else fetched (one fetch per key at a time). */
    public String token(ResolvedConnector connector, AuthScheme auth, Deadline deadline) {
        String key = cacheKey(connector);
        CachedToken cached = tokens.get(key);
        if (cached != null && cached.isFresh()) {
            return cached.value();
        }
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            cached = tokens.get(key);
            if (cached != null && cached.isFresh()) {
                return cached.value();
            }
            CachedToken fetched = fetch(connector, auth, deadline);
            tokens.put(key, fetched);
            return fetched.value();
        }
    }

    /** Forgets the token for {@code connector}: the system answered 401 to it. */
    public void invalidate(ResolvedConnector connector) {
        tokens.remove(cacheKey(connector));
    }

    private CachedToken fetch(ResolvedConnector connector, AuthScheme auth, Deadline deadline) {
        String clientId = AuthApplier.required(connector, auth.clientIdField(), "clientIdField");
        String clientSecret = AuthApplier.required(connector, auth.clientSecretField(), "clientSecretField");
        URI tokenUrl = tokenUrl(connector, auth);
        wire.requireResolvable(tokenUrl.getHost(), "token URL");

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        if (auth.scopeField() != null && !auth.scopeField().isBlank()) {
            String scope = connector.fieldValue(auth.scopeField());
            if (scope != null && !scope.isBlank()) {
                form.put("scope", scope);
            }
        }
        HttpUriRequestBase request = new HttpUriRequestBase("POST", tokenUrl);
        request.setHeader("Accept", "application/json");
        request.setEntity(new StringEntity(RequestUrls.formEncode(form), ContentType.APPLICATION_FORM_URLENCODED));

        Duration timeout = min(CallOptions.DEFAULT_READ_TIMEOUT, deadline.remaining());
        if (timeout.isZero()) {
            throw new ConnectorException(ConnectorErrorCodes.TIMEOUT, false, null,
                    "token endpoint: no budget left to obtain a token");
        }
        ResponseReader.Raw raw;
        try {
            raw = wire.send(request, timeout, "POST token endpoint");
        } catch (ConnectorException e) {
            if (ConnectorErrorCodes.EGRESS_REFUSED.equals(e.code())) {
                throw e;
            }
            // A token endpoint that cannot be reached is an auth failure to the caller, and a transient one.
            throw new ConnectorException(ConnectorErrorCodes.AUTH_FAILED, true, null,
                    "token endpoint: " + reason(e), e.getCause());
        }
        if (raw.status() < 200 || raw.status() >= 300) {
            String body = SecretScrubber.scrub(raw.text(), connector.secretsOrEmpty().values(), 300);
            throw new ConnectorException(ConnectorErrorCodes.AUTH_FAILED, raw.status() >= 500, raw.status(),
                    "token endpoint answered " + raw.status() + (body == null || body.isBlank() ? "" : ": " + body));
        }
        return parse(raw);
    }

    private CachedToken parse(ResponseReader.Raw raw) {
        Map<?, ?> answer;
        try {
            Object parsed = json.readValue(raw.text(), Object.class);
            answer = parsed instanceof Map<?, ?> map ? map : null;
        } catch (IOException e) {
            answer = null;
        }
        Object token = answer != null ? answer.get("access_token") : null;
        if (!(token instanceof String value) || value.isBlank()) {
            throw new ConnectorException(ConnectorErrorCodes.AUTH_FAILED, false, raw.status(),
                    "token endpoint answered without an access_token");
        }
        long expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
        Object declared = answer.get("expires_in");
        if (declared instanceof Number number) {
            expiresIn = number.longValue();
        } else if (declared instanceof String text && !text.isBlank()) {
            try {
                expiresIn = Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                expiresIn = DEFAULT_EXPIRES_IN_SECONDS;
            }
        }
        long trustedFor = Math.max(0, expiresIn - EXPIRY_MARGIN_SECONDS);
        return new CachedToken(value, System.nanoTime() + Duration.ofSeconds(trustedFor).toNanos());
    }

    /**
     * The token URL with its {@code {field}} placeholders filled from the
     * connector's non-secret configuration, each value strictly encoded; then
     * checked like a base URL. A placeholder that names a secret, or a field
     * with no value, is a descriptor problem, not something to send.
     */
    public URI tokenUrl(ResolvedConnector connector, AuthScheme auth) {
        String template = auth.tokenUrl();
        if (template == null || template.isBlank()) {
            throw ConnectorException.configInvalid("auth.tokenUrl: required for oauth2-client-credentials");
        }
        Matcher placeholders = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (placeholders.find()) {
            String field = placeholders.group(1);
            if (connector.secretsOrEmpty().containsKey(field)) {
                throw ConnectorException.configInvalid("auth.tokenUrl: placeholder {" + field + "} names a secret field");
            }
            Object value = connector.configOrEmpty().get(field);
            if (value == null || String.valueOf(value).isBlank()) {
                throw ConnectorException.configInvalid("auth.tokenUrl: placeholder {" + field + "} has no value");
            }
            placeholders.appendReplacement(rendered,
                    Matcher.quoteReplacement(RequestUrls.percentEncode(String.valueOf(value))));
        }
        placeholders.appendTail(rendered);
        return RequestUrls.trusted(rendered.toString(), policy, "token URL").uri();
    }

    private static String reason(ConnectorException e) {
        String message = e.getMessage();
        int colon = message == null ? -1 : message.indexOf(": ");
        return colon >= 0 ? message.substring(colon + 2) : "could not be reached";
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
