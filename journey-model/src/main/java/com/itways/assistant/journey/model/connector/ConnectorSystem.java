package com.itways.assistant.journey.model.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One system behind a connector type (1.3.0): a group of operations that share
 * a path prefix and some headers, reached through the same connection (one base
 * URL, one secret set). A gateway that fronts a core banking system, a card
 * system and a CRM is one type with three systems; each operation names its
 * system in {@link ConnectorOperation#system()}.
 *
 * <p>
 * REST: the transport sends an operation of this system to
 * {@code baseUrl + pathPrefix + operation.path}, and adds {@code headers} after
 * the type's default headers and before the operation's own header inputs, the
 * idempotency key and the credential, so each of those wins over a system
 * header of the same name. MCP: a system only groups tools; {@code pathPrefix}
 * and {@code headers} are refused by the validator.
 *
 * @param key        stable identifier, unique within the type, e.g. {@code t24}
 * @param name       label
 * @param nameAr     Arabic label
 * @param owner      who runs the system (a team or vendor), shown to admins; free text
 * @param pathPrefix REST: a path under the base URL every operation of this system sits under,
 *                   e.g. {@code /t24/api/v1}; null or empty for none
 * @param headers    REST: static headers on every request of this system; never credentials
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectorSystem(
        String key,
        String name,
        String nameAr,
        String owner,
        String pathPrefix,
        Map<String, String> headers) {

    /** Letters, digits, underscores and dashes, starting with a letter; at most 64 characters. */
    public static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    /**
     * One or more {@code /segment}s of unreserved characters only (letters,
     * digits, {@code - . _ ~}), an optional trailing slash, at most 256
     * characters. No scheme, host, {@code @}, query, fragment, placeholder or
     * percent-encoding; dot segments are refused separately.
     */
    private static final Pattern PATH_PREFIX = Pattern.compile("^(/[A-Za-z0-9._~-]+)+/?$");

    public Map<String, String> headersOrEmpty() {
        return headers != null ? headers : Map.of();
    }

    /** The prefix without a trailing slash, empty when none is declared. Does not check it. */
    public String pathPrefixOrEmpty() {
        return normalizePathPrefix(pathPrefix);
    }

    /** Whether this system declares a non-empty path prefix. */
    public boolean hasPathPrefix() {
        return pathPrefix != null && !pathPrefix.isBlank();
    }

    /**
     * Whether {@code prefix} is a safe path prefix: absent, empty or {@code /}, or
     * {@code /segment[/segment...]} of unreserved characters, no {@code .} or
     * {@code ..} segment, at most 256 characters. The one rule the validator
     * (at save) and the transport (at call) both apply.
     */
    public static boolean isSafePathPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || prefix.equals("/")) {
            return true;
        }
        if (prefix.length() > 256 || !PATH_PREFIX.matcher(prefix).matches()) {
            return false;
        }
        for (String segment : prefix.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** {@code prefix} without its trailing slash; empty for null or blank. Does not check it. */
    public static String normalizePathPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String out = prefix.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
