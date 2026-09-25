package com.itways.assistant.journey.engine.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single resolution implementation for journey variable paths.
 *
 * <p>Replaces the two divergent traversals that previously lived in
 * {@code EngineUtils.resolveValue} and {@code VariableContext.read}, neither of
 * which could descend into a {@link List}. Supported syntax:
 *
 * <pre>
 *   inputs.text
 *   steps.3.output.email
 *   steps.3.output.items[0].name
 *   steps.3.output.items.0.name      (bare numeric segment, equivalent to [0])
 *   steps.3.output['odd-key']        (bracket-quoted, for keys with . or -)
 * </pre>
 *
 * <p>Resolution distinguishes "absent" from "present but null" via
 * {@link Resolution#found()}, so callers can report unresolved placeholders
 * instead of silently substituting an empty string.
 */
public final class VariablePath {

    private VariablePath() {
    }

    /** Outcome of a lookup. {@code found == false} means the path does not exist. */
    public record Resolution(boolean found, Object value) {

        public static final Resolution MISSING = new Resolution(false, null);

        static Resolution of(Object value) {
            return new Resolution(true, value);
        }
    }

    /** Resolves {@code path} against {@code root}, returning null when absent. */
    public static Object resolve(Object root, String path) {
        return lookup(root, path).value();
    }

    /** Resolves {@code path} against {@code root}, reporting whether it existed. */
    public static Resolution lookup(Object root, String path) {
        List<String> segments = parse(path);
        if (segments.isEmpty()) {
            return Resolution.MISSING;
        }
        Object current = root;
        for (String segment : segments) {
            Resolution next = descend(current, segment);
            if (!next.found()) {
                return Resolution.MISSING;
            }
            current = next.value();
        }
        return Resolution.of(current);
    }

    private static Resolution descend(Object current, String segment) {
        if (current instanceof Map<?, ?> map) {
            if (map.containsKey(segment)) {
                return Resolution.of(map.get(segment));
            }
            // `steps` is keyed by String while `stepResults` is keyed by Integer;
            // match on the rendered key so both traverse with the same path.
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && segment.equals(String.valueOf(entry.getKey()))) {
                    return Resolution.of(entry.getValue());
                }
            }
            return Resolution.MISSING;
        }
        if (current instanceof List<?> list) {
            Integer index = toIndex(segment);
            if (index == null || index < 0 || index >= list.size()) {
                return Resolution.MISSING;
            }
            return Resolution.of(list.get(index));
        }
        if (current instanceof Object[] array) {
            Integer index = toIndex(segment);
            if (index == null || index < 0 || index >= array.length) {
                return Resolution.MISSING;
            }
            return Resolution.of(array[index]);
        }
        return Resolution.MISSING;
    }

    /**
     * Splits a path into segments, honouring bracket notation and quoted keys.
     * Returns an empty list for null, blank, or malformed paths.
     */
    public static List<String> parse(String path) {
        List<String> segments = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return segments;
        }
        StringBuilder buffer = new StringBuilder();
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                flush(segments, buffer);
                i++;
            } else if (c == '[') {
                flush(segments, buffer);
                int close = findClosingBracket(path, i);
                if (close < 0) {
                    return List.of();
                }
                String inner = unquote(path.substring(i + 1, close).trim());
                if (inner.isEmpty()) {
                    return List.of();
                }
                segments.add(inner);
                i = close + 1;
            } else {
                buffer.append(c);
                i++;
            }
        }
        flush(segments, buffer);
        return segments;
    }

    private static void flush(List<String> segments, StringBuilder buffer) {
        if (buffer.length() > 0) {
            segments.add(buffer.toString().trim());
            buffer.setLength(0);
        }
    }

    private static int findClosingBracket(String path, int open) {
        char quote = 0;
        for (int i = open + 1; i < path.length(); i++) {
            char c = path.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2) {
            char first = raw.charAt(0);
            char last = raw.charAt(raw.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return raw.substring(1, raw.length() - 1);
            }
        }
        return raw;
    }

    private static Integer toIndex(String segment) {
        try {
            return Integer.valueOf(segment.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
