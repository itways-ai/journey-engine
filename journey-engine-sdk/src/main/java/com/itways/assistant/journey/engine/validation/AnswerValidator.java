package com.itways.assistant.journey.engine.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks a USER_INPUT answer against the field validations its author declared.
 *
 * <p>
 * This exists because validation used to live <em>only</em> in the browser. The
 * web SDK renders a form and enforces required/email/min/max/pattern before it
 * submits — but a Telegram or WhatsApp user never touches that form, and
 * anything POSTing the assistance endpoint directly skips it entirely. The
 * engine stored whatever arrived. So the same journey was strict in a widget
 * and wide open on every other channel, which is the wrong way round: the
 * channels with no client-side checking are exactly the ones that need the
 * server to do it.
 *
 * <p>
 * Deliberately pure and Spring-free: it returns message <em>keys</em> plus
 * their arguments rather than sentences, so the engine localizes them in the
 * conversation's language the same way it localizes everything else. That also
 * makes the whole rule set unit-testable without a message bundle.
 *
 * <p>
 * The rules mirror {@code ui/form.ts} in the web SDK field for field. Where the
 * two could drift, this side is the authority — the client is a convenience.
 */
public final class AnswerValidator {

    private AnswerValidator() {
    }

    /**
     * One failed constraint: which field, and what to tell the user.
     *
     * @param field      the field's variable name, for the client to highlight
     * @param label      the author's label where there is one, the name otherwise
     * @param messageKey a {@code step.input.error.*} key in the engine bundle
     * @param args       substitution arguments for that message
     */
    public record FieldError(String field, String label, String messageKey, Object[] args) {
    }

    /** Catches obvious non-addresses, deliberately not RFC 5322. Mirrors the client. */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /**
     * Every declared constraint this answer breaks, empty when it is acceptable.
     *
     * @param answer       what the user sent: a map for a structured form, a
     *                     bare scalar from a plain-text channel
     * @param fieldsConfig the step's {@code apiConfig.fields}, as parsed
     * @param rulesConfig  the step's {@code apiConfig.rules}; only read to find
     *                     which fields are conditional
     */
    @SuppressWarnings("unchecked")
    public static List<FieldError> validate(Object answer, Object fieldsConfig, Object rulesConfig) {
        List<Map<String, Object>> fields = asMapList(fieldsConfig);
        if (fields.isEmpty()) {
            return List.of();
        }

        Map<String, Object> values;
        if (answer instanceof Map<?, ?> map) {
            values = (Map<String, Object>) map;
        } else if (fields.size() == 1) {
            // A one-field form answered in plain text from a messaging channel.
            // Reading it as that field's value is what the user obviously meant,
            // and refusing it would make single-question forms unusable off-widget.
            values = new HashMap<>();
            values.put(name(fields.get(0)), answer);
        } else {
            // Several fields and a scalar answer: nothing can be mapped without
            // guessing which question was being answered.
            return List.of(new FieldError(null, null, "step.input.error.structured", new Object[0]));
        }

        Set<String> conditional = conditionalFields(rulesConfig);
        List<FieldError> errors = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            FieldError error = validateField(field, values, conditional);
            if (error != null) {
                errors.add(error);
            }
        }
        return errors;
    }

    private static FieldError validateField(Map<String, Object> field, Map<String, Object> values,
            Set<String> conditional) {
        String name = name(field);
        if (name == null) {
            return null;
        }
        String label = text(field.get("label"));
        if (label == null || label.isBlank()) {
            label = name;
        }
        Map<String, Object> validations = asMap(field.get("validations"));
        String type = lower(text(field.get("type")));
        Object value = values.get(name);

        boolean empty = value == null
                || (value instanceof String s && s.isBlank())
                || ("checkbox".equals(type) && Boolean.FALSE.equals(value));

        if (empty) {
            // A field a rule can hide is never required server-side: the client
            // legitimately omits what it did not show, and the engine cannot
            // re-evaluate visibility without the form state that produced it.
            // Rejecting it here would break exactly the forms that use rules.
            if (isTrue(validations.get("required")) && !conditional.contains(name)) {
                return error(name, label, "step.input.error.required");
            }
            return null;
        }

        if ("number".equals(type)) {
            Double number = toNumber(value);
            if (number == null) {
                return error(name, label, "step.input.error.number");
            }
            Double min = toNumber(validations.get("min"));
            Double max = toNumber(validations.get("max"));
            if (min != null && number < min) {
                return error(name, label, "step.input.error.min", trim(min));
            }
            if (max != null && number > max) {
                return error(name, label, "step.input.error.max", trim(max));
            }
            return null;
        }

        String textValue = String.valueOf(value);

        if (isTrue(validations.get("email")) || "email".equals(type)) {
            if (!EMAIL.matcher(textValue).matches()) {
                return error(name, label, "step.input.error.email");
            }
        }

        Double minLength = toNumber(validations.get("minLength"));
        Double maxLength = toNumber(validations.get("maxLength"));
        if (minLength != null && textValue.length() < minLength) {
            return error(name, label, "step.input.error.minLength", trim(minLength));
        }
        if (maxLength != null && textValue.length() > maxLength) {
            return error(name, label, "step.input.error.maxLength", trim(maxLength));
        }

        String pattern = text(validations.get("pattern"));
        if (pattern != null && !pattern.isBlank() && !matches(textValue, pattern.trim())) {
            return error(name, label, "step.input.error.pattern");
        }
        return null;
    }

    /**
     * Anchored full-string match, the reading an author gets from Angular's
     * {@code Validators.pattern} — otherwise {@code [0-9]{4}} would pass on any
     * text containing four digits anywhere.
     *
     * <p>
     * An expression that will not compile counts as satisfied. The author wrote
     * something broken; refusing every answer to a question nobody can pass is
     * worse than letting the constraint go unchecked, and the builder flags the
     * bad pattern where it can actually be fixed.
     */
    private static boolean matches(String text, String pattern) {
        try {
            String anchored = pattern.startsWith("^") && pattern.endsWith("$") ? pattern : "^(?:" + pattern + ")$";
            return Pattern.compile(anchored).matcher(text).matches();
        } catch (PatternSyntaxException e) {
            return true;
        }
    }

    /** Field names any conditional rule can act on — exempt from required. */
    /**
     * The fields a channel without a form has to ask for, in declared order.
     *
     * <p>
     * Conditional fields are left out: their visibility depends on rules the
     * engine cannot evaluate, and asking a question the form would have hidden
     * is worse than leaving the answer blank — which is also what the
     * server-side validation already allows for them.
     */
    public static List<Map<String, Object>> fieldsToAsk(Object fieldsConfig, Object rulesConfig) {
        Set<String> conditional = conditionalFields(rulesConfig);
        List<Map<String, Object>> askable = new ArrayList<>();
        for (Map<String, Object> field : asMapList(fieldsConfig)) {
            String name = name(field);
            if (name != null && !conditional.contains(name)) {
                askable.add(field);
            }
        }
        return askable;
    }

    private static Set<String> conditionalFields(Object rulesConfig) {
        Set<String> names = new HashSet<>();
        for (Map<String, Object> rule : asMapList(rulesConfig)) {
            Map<String, Object> then = asMap(rule.get("then"));
            String field = text(then.get("field"));
            if (field != null && !field.isBlank()) {
                names.add(field.trim());
            }
        }
        return names;
    }

    private static FieldError error(String name, String label, String key, Object... args) {
        return new FieldError(name, label, key, args);
    }

    private static String name(Map<String, Object> field) {
        String name = text(field.get("name"));
        return name == null || name.isBlank() ? null : name.trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object raw) {
        if (!(raw instanceof Iterable<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String lower(String raw) {
        return raw == null ? null : raw.toLowerCase(Locale.ROOT);
    }

    /** Accepts the string forms the console persists as well as real numbers. */
    private static Double toNumber(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 4.0 reads as "4" in a message; 4.5 stays 4.5. */
    private static Object trim(Double value) {
        return value == Math.floor(value) && !value.isInfinite() ? String.valueOf(value.longValue()) : value;
    }

    /** The console persists booleans as strings often enough to matter. */
    private static boolean isTrue(Object raw) {
        return raw instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(raw));
    }
}
