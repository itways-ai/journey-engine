package com.itways.assistant.journey.model.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * One node of an operation's input or output schema: the subset of JSON Schema
 * the platform reads, plus the placement keys a REST operation needs.
 *
 * <p>
 * On an input property: {@code in} says where the value goes ({@code path},
 * {@code query}, {@code header} or {@code body}, the default), {@code as}
 * renames it on the wire ({@code $filter}), {@code template} wraps the value
 * ({@code mobilephone eq '{value}'}) and {@code default} fills it when the
 * journey maps nothing. On an output property, {@code sensitive} marks a value
 * that is masked before it enters the run context.
 *
 * @param type         {@code object}, {@code array}, {@code string}, {@code number},
 *                     {@code integer}, {@code boolean}
 * @param format       a JSON Schema format hint ({@code date}, {@code date-time}, {@code email})
 * @param description  for the builder
 * @param required     {@code object}: the properties that must be present
 * @param properties   {@code object}: its properties, by name
 * @param items        {@code array}: the item schema
 * @param enumValues   the values a string or number may take
 * @param pattern      a regular expression a string must match (whole value)
 * @param minimum      numbers: lowest allowed
 * @param maximum      numbers: highest allowed
 * @param minLength    strings: shortest allowed
 * @param maxLength    strings: longest allowed
 * @param in           inputs: {@code path}, {@code query}, {@code header} or {@code body}
 * @param as           inputs: the name used on the wire, when it differs
 * @param template     inputs: a wrapper with {@code {value}} for the rendered value
 * @param defaultValue inputs: the value used when none is mapped
 * @param sensitive    outputs: masked before storing
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SchemaNode(
        String type,
        String format,
        String description,
        List<String> required,
        Map<String, SchemaNode> properties,
        SchemaNode items,
        @JsonProperty("enum") List<Object> enumValues,
        String pattern,
        Number minimum,
        Number maximum,
        Integer minLength,
        Integer maxLength,
        String in,
        String as,
        String template,
        @JsonProperty("default") Object defaultValue,
        Boolean sensitive) {

    public static final String IN_PATH = "path";
    public static final String IN_QUERY = "query";
    public static final String IN_HEADER = "header";
    public static final String IN_BODY = "body";

    /** An empty object schema: no properties, nothing required. */
    public static final SchemaNode EMPTY_OBJECT = new SchemaNode("object", null, null, List.of(), Map.of(), null, null,
            null, null, null, null, null, null, null, null, null, null);

    /** The properties, never null. */
    public Map<String, SchemaNode> propertiesOrEmpty() {
        return properties != null ? properties : Map.of();
    }

    /** The required property names, never null. */
    public List<String> requiredOrEmpty() {
        return required != null ? required : List.of();
    }

    /** The type in lower case, {@code object} when absent. */
    public String typeOrObject() {
        return type == null || type.isBlank() ? "object" : type.toLowerCase();
    }

    /** Where an input property goes on the wire; {@code body} when unsaid. */
    public String placement() {
        return in == null || in.isBlank() ? IN_BODY : in.toLowerCase();
    }

    /** The wire name of an input property called {@code name}. */
    public String wireName(String name) {
        return as != null && !as.isBlank() ? as : name;
    }

    public boolean isSensitive() {
        return Boolean.TRUE.equals(sensitive);
    }
}
