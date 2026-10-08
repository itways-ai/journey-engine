package com.itways.assistant.journey.connector;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * Removes credentials from text that will be stored or shown: the
 * connector's own secret values, {@code Authorization}-style header values,
 * bearer and JWT-looking tokens, and query strings. Applied to every message an
 * {@link ConnectorException} carries and to the Test result's message.
 */
public final class SecretScrubber {

    public static final String MASK = "********";

    private static final Pattern AUTH_HEADER = Pattern
            .compile("(?i)(authorization|proxy-authorization|x-api-key|api[-_]?key|cookie)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern BASIC = Pattern.compile("(?i)\\bbasic\\s+[A-Za-z0-9+/=]+");
    private static final Pattern JWT = Pattern.compile("\\b[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
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
}
