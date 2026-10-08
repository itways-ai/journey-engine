package com.itways.assistant.journey.connector;

import com.itways.assistant.journey.model.connector.SchemaNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shapes an operation's answer before it is stored anywhere: masks the
 * properties the output schema flags {@code sensitive} and, unless the step
 * asks for the raw response, keeps only the properties the schema declares.
 *
 * <p>
 * Masking happens before {@code VariableContext.storeOutput}, so a sensitive
 * value never reaches the run context, run history or a parked instance (whose
 * context is not redacted today). The same code shapes the explorer's Try
 * result in journey-service.
 */
public final class OutputMasker {

    public static final String MASK = SecretScrubber.MASK;

    private OutputMasker() {
    }

    /**
     * {@code body} with every sensitive property masked, descending into
     * nested objects and arrays as the schema does. Properties the schema does
     * not describe pass through unchanged. Null in, null out.
     */
    public static Object mask(SchemaNode schema, Object body) {
        return shape(schema, body, false);
    }

    /**
     * {@code body} masked, and limited to the properties the schema declares
     * (nested objects and arrays included). A body that is not an object while
     * the schema is one is returned masked but unlimited: there is nothing to
     * pick from.
     */
    public static Object maskAndLimit(SchemaNode schema, Object body) {
        return shape(schema, body, true);
    }

    @SuppressWarnings("unchecked")
    private static Object shape(SchemaNode schema, Object value, boolean limit) {
        if (value == null) {
            return null;
        }
        if (schema == null) {
            return value;
        }
        if (schema.isSensitive()) {
            return MASK;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, SchemaNode> properties = schema.propertiesOrEmpty();
            if (properties.isEmpty() && limit && "object".equals(schema.typeOrObject()) && schema.properties() != null) {
                return new LinkedHashMap<String, Object>();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<Object, Object>) map).entrySet()) {
                String key = String.valueOf(entry.getKey());
                SchemaNode child = properties.get(key);
                if (child == null) {
                    if (!limit || properties.isEmpty()) {
                        out.put(key, entry.getValue());
                    }
                    continue;
                }
                out.put(key, shape(child, entry.getValue(), limit));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            SchemaNode items = schema.items();
            if (items == null) {
                return value;
            }
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(shape(items, item, limit));
            }
            return out;
        }
        return value;
    }

    /**
     * The paths of every sensitive property of {@code schema}, dotted and
     * relative to the output ({@code iban}, {@code value.emailaddress1}); arrays
     * contribute their item paths. For the builder's lock icon.
     */
    public static List<String> sensitivePaths(SchemaNode schema) {
        List<String> paths = new ArrayList<>();
        collect(schema, "", paths);
        return paths;
    }

    private static void collect(SchemaNode node, String prefix, List<String> paths) {
        if (node == null) {
            return;
        }
        if (node.isSensitive() && !prefix.isEmpty()) {
            paths.add(prefix);
            return;
        }
        for (Map.Entry<String, SchemaNode> property : node.propertiesOrEmpty().entrySet()) {
            collect(property.getValue(), prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey(),
                    paths);
        }
        if (node.items() != null) {
            collect(node.items(), prefix, paths);
        }
    }
}
