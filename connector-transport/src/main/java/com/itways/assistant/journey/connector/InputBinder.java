package com.itways.assistant.journey.connector;

import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.SchemaNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates an operation's rendered inputs against its input schema and places
 * each one where the operation wants it: a path parameter, a query parameter, a
 * header or a body property.
 *
 * <p>
 * Shared by the transport (every call) and journey-service (the Try endpoint,
 * and the publish preflight's "every required input mapped" check through
 * {@link #problems}). Values arrive rendered — placeholders already replaced
 * by the engine — and are coerced to the schema type: {@code "5"} becomes 5
 * for an {@code integer}, {@code "true"} a boolean. A value that cannot be
 * coerced, a missing required input, a failed pattern, enum or range is a
 * problem; all problems are reported together as one
 * {@link ConnectorErrorCodes#INPUT_INVALID}.
 */
public final class InputBinder {

    /** An operation's inputs, validated, coerced and placed. */
    public record BoundInputs(
            Map<String, String> path,
            Map<String, String> query,
            Map<String, String> headers,
            Map<String, Object> body) {

        public static final BoundInputs NONE = new BoundInputs(Map.of(), Map.of(), Map.of(), Map.of());
    }

    private InputBinder() {
    }

    /**
     * Binds {@code inputs} to {@code operation}.
     *
     * @throws ConnectorException {@link ConnectorErrorCodes#INPUT_INVALID} listing every problem
     */
    public static BoundInputs bind(ConnectorOperation operation, Map<String, Object> inputs) {
        List<String> problems = new ArrayList<>();
        BoundInputs bound = bind(operation, inputs, problems);
        if (!problems.isEmpty()) {
            throw ConnectorException.inputInvalid(problems);
        }
        return bound;
    }

    /** The problems {@code inputs} have against {@code operation}'s schema; empty when they bind. */
    public static List<String> problems(ConnectorOperation operation, Map<String, Object> inputs) {
        List<String> problems = new ArrayList<>();
        bind(operation, inputs, problems);
        return problems;
    }

    private static BoundInputs bind(ConnectorOperation operation, Map<String, Object> inputs, List<String> problems) {
        SchemaNode schema = operation.inputOrEmpty();
        Map<String, SchemaNode> properties = schema.propertiesOrEmpty();
        Map<String, Object> given = inputs != null ? inputs : Map.of();

        Map<String, String> path = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, Object> body = new LinkedHashMap<>();

        for (String name : given.keySet()) {
            if (!properties.containsKey(name)) {
                problems.add(name + ": not an input of operation " + operation.key());
            }
        }

        for (Map.Entry<String, SchemaNode> property : properties.entrySet()) {
            String name = property.getKey();
            SchemaNode node = property.getValue() != null ? property.getValue() : SchemaNode.EMPTY_OBJECT;
            Object raw = given.get(name);
            if (isAbsent(raw)) {
                raw = node.defaultValue();
            }
            boolean required = schema.requiredOrEmpty().contains(name);
            if (isAbsent(raw)) {
                if (required) {
                    problems.add(name + ": required");
                }
                continue;
            }
            Object value = coerce(name, node, raw, problems);
            if (value == null) {
                continue;
            }
            if (node.template() != null) {
                value = node.template().replace("{value}", String.valueOf(value));
            }
            String wireName = node.wireName(name);
            switch (node.placement()) {
                case SchemaNode.IN_PATH -> path.put(name, String.valueOf(value));
                case SchemaNode.IN_QUERY -> query.put(wireName, String.valueOf(value));
                case SchemaNode.IN_HEADER -> headers.put(wireName, String.valueOf(value));
                default -> body.put(wireName, value);
            }
        }
        return new BoundInputs(Map.copyOf(path), Map.copyOf(query), Map.copyOf(headers), body);
    }

    private static boolean isAbsent(Object raw) {
        return raw == null || (raw instanceof String s && s.isEmpty());
    }

    /** The value as the schema's type, or null after recording why it cannot be. */
    static Object coerce(String name, SchemaNode node, Object raw, List<String> problems) {
        String type = node.typeOrObject();
        Object value;
        switch (type) {
            case "string" -> value = raw instanceof Map || raw instanceof List ? null : String.valueOf(raw);
            case "integer" -> value = toInteger(raw);
            case "number" -> value = toNumber(raw);
            case "boolean" -> value = toBoolean(raw);
            case "array" -> value = raw instanceof List ? raw : null;
            case "object" -> value = raw instanceof Map ? raw : null;
            default -> value = raw;
        }
        if (value == null) {
            problems.add(name + ": must be " + article(type) + type);
            return null;
        }
        if (value instanceof String text) {
            if (node.pattern() != null && !Pattern.compile(node.pattern()).matcher(text).matches()) {
                problems.add(name + ": does not match the expected format");
            }
            if (node.minLength() != null && text.length() < node.minLength()) {
                problems.add(name + ": must be at least " + node.minLength() + " characters");
            }
            if (node.maxLength() != null && text.length() > node.maxLength()) {
                problems.add(name + ": must be at most " + node.maxLength() + " characters");
            }
        }
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString());
            if (node.minimum() != null && decimal.compareTo(new BigDecimal(node.minimum().toString())) < 0) {
                problems.add(name + ": must be " + node.minimum() + " or more");
            }
            if (node.maximum() != null && decimal.compareTo(new BigDecimal(node.maximum().toString())) > 0) {
                problems.add(name + ": must be " + node.maximum() + " or less");
            }
        }
        if (node.enumValues() != null && !node.enumValues().isEmpty()) {
            String asText = String.valueOf(value);
            boolean allowed = node.enumValues().stream().anyMatch(option -> option != null
                    && String.valueOf(option).equals(asText));
            if (!allowed) {
                problems.add(name + ": must be one of " + node.enumValues());
            }
        }
        return value;
    }

    private static Object toInteger(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return raw;
        }
        if (raw instanceof Number n) {
            BigDecimal decimal = new BigDecimal(n.toString());
            return decimal.stripTrailingZeros().scale() <= 0 ? decimal.longValueExact() : null;
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Object toNumber(Object raw) {
        if (raw instanceof Number) {
            return raw;
        }
        if (raw instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Object toBoolean(Object raw) {
        if (raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof String s) {
            String text = s.trim();
            if (text.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (text.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static String article(String type) {
        return "aeiou".indexOf(type.charAt(0)) >= 0 ? "an " : "a ";
    }
}
