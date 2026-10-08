package com.itways.assistant.journey.connector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes credentials from text that will be stored or shown: the
 * connector's own secret values (and, for the OAuth2 scheme, the access token
 * it acquired — see {@code AuthApplier.scrubValues}), {@code Authorization}-style
 * header values, bearer and JWT-looking tokens, and query strings. Applied to
 * every message an {@link ConnectorException} carries, to the Test result's
 * message and, through {@link #scrubDeep}, to the explorer's Try output.
 */
public final class SecretScrubber {

    public static final String MASK = "********";

    private static final Pattern AUTH_HEADER = Pattern
            .compile("(?i)(authorization|proxy-authorization|x-api-key|api[-_]?key|cookie)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern BASIC = Pattern.compile("(?i)\\bbasic\\s+[A-Za-z0-9+/=]+");
    private static final Pattern JWT = Pattern.compile("\\b[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    /** A JWT by its header: base64url of {@code {"} is always {@code eyJ}, whatever the segments' lengths. */
    private static final Pattern JWT_HEADER = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern QUERY_STRING = Pattern.compile("\\?[^\\s\"'<>]+");

    private SecretScrubber() {
    }

    /**
     * {@code text} with every value in {@code secrets} (and anything that looks
     * like a credential) replaced by {@link #MASK}. Null in, null out. Short or
     * blank secret values are not matched (they would blank out ordinary text).
     */
    public static String scrub(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = credentials(text, secrets);
        out = QUERY_STRING.matcher(out).replaceAll("?" + MASK);
        return out;
    }

    /** As {@link #scrub(String, Collection)}, then cut to {@code maxLength} characters. */
    public static String scrub(String text, Collection<String> secrets, int maxLength) {
        String out = scrub(text, secrets);
        if (out != null && out.length() > maxLength) {
            return out.substring(0, Math.max(0, maxLength - 1)) + "…";
        }
        return out;
    }

    /**
     * {@code value} (a parsed JSON body: a map, a list, a scalar) with every
     * text in it scrubbed of the given secret values and of anything that
     * looks like a credential (header values, bearer, basic, JWT). Query
     * strings are left alone here: in a body they are data (a next-page link),
     * and a credential inside one is still caught by the other rules. Keys
     * are kept as they are. Null in, null out; a value that holds no text is
     * returned as it is.
     */
    @SuppressWarnings("unchecked")
    public static Object scrubDeep(Object value, Collection<String> secrets) {
        if (value instanceof String text) {
            return text.isEmpty() ? text : credentials(text, secrets);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) map).entrySet()) {
                out.put(String.valueOf(entry.getKey()), scrubDeep(entry.getValue(), secrets));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(scrubDeep(item, secrets));
            }
            return out;
        }
        return value;
    }

    private static String credentials(String text, Collection<String> secrets) {
        String out = text;
        if (secrets != null) {
            for (String secret : secrets) {
                if (secret != null && secret.length() >= 4) {
                    out = out.replace(secret, MASK);
                }
            }
        }
        out = AUTH_HEADER.matcher(out).replaceAll("$1: " + MASK);
        out = BEARER.matcher(out).replaceAll("Bearer " + MASK);
        out = BASIC.matcher(out).replaceAll("Basic " + MASK);
        out = JWT.matcher(out).replaceAll(MASK);
        out = JWT_HEADER.matcher(out).replaceAll(MASK);
        return out;
    }
}
