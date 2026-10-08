package com.itways.assistant.journey.engine.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder substitution for journey step configuration.
 *
 * <p>{@code {{ path }}} is the only supported syntax. The single-brace
 * {@code {path}} and {@code <%path%>} forms were removed: the former collided
 * with FreeMarker's {@code ${...}} and with literal braces in JSON request
 * bodies, and the latter had no users.
 *
 * <p>The inner path is handed to {@link VariablePath}, so anything that
 * resolver understands — list indexing, bracket-quoted keys — works inside a
 * placeholder.
 */
public final class Placeholders {

    private Placeholders() {
    }

    private static final Pattern PATTERN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    /** Matches a string that is exactly one placeholder and nothing else. */
    private static final Pattern LONE = Pattern.compile("^\\{\\{\\s*([^{}]+?)\\s*}}$");

    public static boolean contains(String text) {
        return text != null && text.contains("{{");
    }

    /**
     * Substitutes every placeholder, rendering absent paths as an empty string
     * and reporting them to {@link VariableDiagnostics} so the engine can flag
     * them on the step. A path that exists but holds null is a legitimate empty
     * value and is not reported.
     */
    public static String replace(String text, Object root) {
        if (!contains(text)) {
            return text;
        }
        StringBuilder result = new StringBuilder();
        Matcher matcher = PATTERN.matcher(text);
        while (matcher.find()) {
            String path = matcher.group(1);
            VariablePath.Resolution resolution = VariablePath.lookup(root, path);
            if (!resolution.found()) {
                VariableDiagnostics.recordUnresolved(path);
            }
            Object value = resolution.value();
            String rendered = value != null ? String.valueOf(value) : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(rendered));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolves {@code text} preserving the original type when it consists of a
     * single placeholder — {@code "{{steps.2.output.score}}"} yields an Integer
     * rather than {@code "8"}. Mixed templates still return a String.
     */
    public static Object resolve(String text, Object root) {
        if (!contains(text)) {
            return text;
        }
        Matcher lone = LONE.matcher(text.trim());
        if (lone.matches()) {
            String path = lone.group(1);
            VariablePath.Resolution resolution = VariablePath.lookup(root, path);
            if (!resolution.found()) {
                VariableDiagnostics.recordUnresolved(path);
            }
            return resolution.value();
        }
        return replace(text, root);
    }

    /** Every distinct path referenced by {@code text}, in order of appearance. */
    public static List<String> referencedPaths(String text) {
        if (!contains(text)) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Matcher matcher = PATTERN.matcher(text);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return new ArrayList<>(paths);
    }

    /** Referenced paths that do not exist under {@code root}. */
    public static List<String> unresolvedPaths(String text, Object root) {
        List<String> missing = new ArrayList<>();
        for (String path : referencedPaths(text)) {
            if (!VariablePath.lookup(root, path).found()) {
                missing.add(path);
            }
        }
        return missing;
    }
}
