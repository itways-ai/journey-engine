package com.itways.assistant.journey.connector.http;

import com.itways.assistant.journey.connector.EgressPolicy;
import com.itways.assistant.journey.connector.ConnectorException;
import com.itways.assistant.journey.model.connector.ConnectorSystem;
import com.itways.common.net.HostAllowList;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a request may go: the connector's base URL decides scheme, host and
 * port, the operation's path template decides the path under it, and nothing
 * an author or an end user supplies can move either. Shared by the transports;
 * the MCP transport uses the base alone (its endpoint is the base URL).
 *
 * <p>
 * The base URL was checked when the connector was saved, but it is checked
 * again here (same rules: absolute, https unless the policy tolerates http, a
 * host, no user info, no query, no fragment) because the transport is the last
 * line and journey-service's rules may lag behind. Path parameter values are
 * percent-encoded strictly — every byte outside unreserved ALPHA / DIGIT /
 * {@code - . _ ~} — so {@code ../}, {@code @}, {@code ?}, {@code #}, {@code /}
 * and {@code %} in a value stay inside their segment. The assembled URL is then
 * normalised and compared with the base: scheme, host and effective port must
 * be equal and the path must still lie under the base path, so any value the
 * encoder somehow let through is refused rather than sent.
 */
public final class RequestUrls {

    /** An {@code https://host[:port][/base]} and the derived values the request is checked against. */
    public record Base(URI uri, String host, int port, String path) {
    }

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^{}/]+)}");
    private static final Pattern UNRENDERED = Pattern.compile("\\{[^{}/]*}");

    private RequestUrls() {
    }

    /**
     * The base URL parsed and checked, with the connector's own host list and
     * the platform bound applied.
     *
     * @throws ConnectorException {@code EGRESS_REFUSED} when the URL may not be dialled,
     *                              {@code CONFIG_INVALID} for an allowed-hosts entry that is not a host
     */
    public static Base base(String baseUrl, Iterable<String> allowedHostEntries, EgressPolicy policy) {
        Base base = parse(baseUrl, policy, "base URL");
        HostAllowList allowed = allowList(allowedHostEntries);
        if (!allowed.isEmpty() && !allowed.allows(base.host())) {
            throw ConnectorException.egressRefused("host " + base.host() + " is not on the connector's allowed hosts");
        }
        if (!policy.withinPlatformBound(base.host())) {
            throw ConnectorException.egressRefused("host " + base.host() + " is outside the platform's allowed hosts");
        }
        return base;
    }

    /**
     * A URL the descriptor's author wrote for the type itself (the OAuth2 token
     * endpoint): the same syntax and platform rules as a base URL, but not the
     * connector's own host list, which describes the system the connector
     * dials rather than its identity provider.
     */
    public static Base trusted(String url, EgressPolicy policy, String what) {
        Base base = parse(url, policy, what);
        if (!policy.withinPlatformBound(base.host())) {
            throw ConnectorException.egressRefused(what + " host " + base.host()
                    + " is outside the platform's allowed hosts");
        }
        return base;
    }

    private static Base parse(String url, EgressPolicy policy, String what) {
        if (url == null || url.isBlank()) {
            throw ConnectorException.egressRefused(what + " is missing");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw ConnectorException.egressRefused(what + " is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !("http".equals(scheme) && policy.allowHttp())) {
            throw ConnectorException.egressRefused(what + " must use https");
        }
        String host = HostAllowList.normalize(uri.getHost());
        if (host == null) {
            throw ConnectorException.egressRefused(what + " must name a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw ConnectorException.egressRefused(what + " must not carry a user name or password");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw ConnectorException.egressRefused(what + " must not carry a query or fragment");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return new Base(uri, host, effectivePort(uri), path);
    }

    private static HostAllowList allowList(Iterable<String> entries) {
        if (entries == null) {
            return HostAllowList.EMPTY;
        }
        List<String> list = new ArrayList<>();
        entries.forEach(list::add);
        try {
            return HostAllowList.of(list);
        } catch (IllegalArgumentException e) {
            throw ConnectorException.configInvalid("connector allowedHosts: " + e.getMessage());
        }
    }

    /**
     * The URL of one request: {@code base} + the rendered path + the query,
     * checked to still be under the base.
     *
     * @param rendered the operation's path with its parameters rendered ({@link #renderPath})
     * @param query    the query parameters in the order they are sent
     * @throws ConnectorException {@code EGRESS_REFUSED} when the result would leave the base URL
     */
    public static URI request(Base base, String rendered, Map<String, String> query) {
        StringBuilder url = new StringBuilder(base.uri().getScheme()).append("://").append(base.uri().getRawAuthority())
                .append(base.path()).append(rendered);
        String queryString = formEncode(query);
        if (!queryString.isEmpty()) {
            url.append('?').append(queryString);
        }
        URI uri;
        try {
            uri = new URI(url.toString()).normalize();
        } catch (URISyntaxException e) {
            throw ConnectorException.egressRefused("request URL is not valid");
        }
        requireSameOrigin(base, uri, base.path() + rendered, queryString);
        return uri;
    }

    /**
     * The operation's path with every placeholder replaced by its strictly
     * encoded value.
     *
     * @throws ConnectorException {@code EGRESS_REFUSED} for a template that is not a relative path under
     *                              the base URL; {@code CONFIG_INVALID} for a placeholder without a value
     */
    public static String renderPath(String template, Map<String, String> pathParams) {
        if (template == null || !template.startsWith("/")) {
            throw ConnectorException.egressRefused("operation path must start with /");
        }
        if (template.contains("://") || template.startsWith("//") || template.contains("..")
                || template.contains("@") || template.contains("?") || template.contains("#")) {
            throw ConnectorException.egressRefused("operation path must be a relative path under the base URL");
        }
        Matcher params = PATH_PARAM.matcher(template);
        StringBuilder out = new StringBuilder();
        while (params.find()) {
            String value = pathParams.get(params.group(1));
            if (value == null) {
                throw ConnectorException.configInvalid("path parameter {" + params.group(1) + "} has no value");
            }
            params.appendReplacement(out, Matcher.quoteReplacement(percentEncode(value)));
        }
        params.appendTail(out);
        String rendered = out.toString();
        if (UNRENDERED.matcher(rendered).find()) {
            throw ConnectorException.egressRefused("operation path has an unrendered placeholder");
        }
        return rendered;
    }

    /**
     * A system's path prefix (1.3.0), checked and without its trailing slash;
     * empty when the operation belongs to no system or the system declares
     * none. Placed between the base path and the rendered operation path; the
     * assembled URL is then held to the same same-origin and under-the-base
     * check as any other request.
     *
     * @throws ConnectorException {@code EGRESS_REFUSED} for a prefix that is not a plain relative path
     */
    public static String pathPrefix(ConnectorSystem system) {
        if (system == null || system.pathPrefix() == null) {
            return "";
        }
        if (!ConnectorSystem.isSafePathPrefix(system.pathPrefix())) {
            throw ConnectorException.egressRefused("system " + system.key()
                    + " pathPrefix must be a relative path under the base URL");
        }
        return ConnectorSystem.normalizePathPrefix(system.pathPrefix());
    }

    private static void requireSameOrigin(Base base, URI uri, String expectedPath, String expectedQuery) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals(base.uri().getScheme().toLowerCase(Locale.ROOT))) {
            throw ConnectorException.egressRefused("request would change the scheme");
        }
        String host = HostAllowList.normalize(uri.getHost());
        if (host == null || !host.equals(base.host()) || uri.getRawUserInfo() != null) {
            throw ConnectorException.egressRefused("request would leave host " + base.host());
        }
        if (effectivePort(uri) != base.port()) {
            throw ConnectorException.egressRefused("request would change the port");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        // Normalisation changed the path only if a value smuggled a dot segment through.
        if (!path.equals(expectedPath) || !(path.equals(base.path()) || path.startsWith(base.path() + "/"))) {
            throw ConnectorException.egressRefused("request path would leave the base URL");
        }
        if (uri.getRawFragment() != null || !expectedQuery.equals(uri.getRawQuery() == null ? "" : uri.getRawQuery())) {
            throw ConnectorException.egressRefused("request would carry an unexpected query or fragment");
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    /** RFC 3986 percent-encoding that keeps only the unreserved characters: one segment in, one segment out. */
    public static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return out.toString();
    }

    /** {@code name=value&...}, every name and value form-encoded; empty for no parameters. */
    public static String formEncode(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(parameter.getValue() == null ? "" : parameter.getValue(),
                            StandardCharsets.UTF_8));
        }
        return out.toString();
    }
}
