package com.itways.assistant.journey.engine.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.catalog.OutputField;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.model.catalog.StepOutputSchema;
import com.itways.assistant.journey.model.connector.ConnectorOperation;
import com.itways.assistant.journey.model.connector.SchemaNode;
import com.itways.assistant.journey.model.step.ConnectorCallConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class StepOutputSchemaHelper {

    private static final String HGI_PREFIX = "hgi hgi-stroke hgi-rounded ";

    private final ObjectMapper objectMapper;

    public StepOutputSchemaHelper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Full Hugeicons CSS class string for catalog consumers (Angular builder). */
    public static String hgiIcon(String slug) {
        if (slug == null || slug.isBlank()) {
            return HGI_PREFIX + "hgi-ai-chip-02";
        }
        if (slug.startsWith("hgi ")) {
            return slug;
        }
        String name = slug.startsWith("hgi-") ? slug : "hgi-" + slug;
        return HGI_PREFIX + name;
    }

    /**
     * API_CALL is frozen since 1.1.0: published journeys keep running it, the
     * step picker hides it and offers CONNECTOR_CALL instead.
     */
    public StepDefinition apiCallDefinition() {
        return StepDefinition.builder()
                .type("API_CALL")
                .category("system")
                .label("API Call")
                .icon(hgiIcon("hgi-cloud"))
                .uiComponent("step-api")
                .deprecated(true)
                .replacedBy("CONNECTOR_CALL")
                .build();
    }

    /** CONNECTOR_CALL (1.1.0): the one step type that offers an error path. */
    public StepDefinition connectorCallDefinition() {
        StepDefinition def = genericDefinition("CONNECTOR_CALL", "connector", "Connector call",
                "hgi-plug-socket", "step-connector");
        def.setSupportsErrorBranch(true);
        return def;
    }

    /**
     * CONNECTOR_CALL publishes the shaped response, its status, latency and
     * attempts, and the error fields a fallback step reads after the error
     * branch. When the step names an operation the lookup can describe, each
     * output property is listed too ({@code output.balance}), nested objects as
     * dotted paths and arrays as one {@code array} field; a property flagged
     * {@code sensitive} is listed with {@link OutputField#isSensitive()}, since
     * a later step only ever reads it masked.
     *
     * @param operationLookup the operation a parsed config names; empty when it cannot be described
     */
    public StepOutputSchema connectorCallSchema(JourneyStep step,
            Function<ConnectorCallConfig, Optional<ConnectorOperation>> operationLookup) {
        List<OutputField> fields = new ArrayList<>(List.of(
                OutputField.of("output", "Response", "object"),
                OutputField.of("status", "HTTP Status", "number"),
                OutputField.of("latencyMs", "Latency (ms)", "number"),
                OutputField.of("attempts", "Attempts", "number"),
                OutputField.of("error.code", "Error Code", "string"),
                OutputField.of("error.message", "Error Message", "string"),
                OutputField.of("error.retryable", "Error Retryable", "boolean")));
        // getAllDefaultSchemas() describes every type with a null step.
        ConnectorCallConfig config = null;
        if (step != null && step.getApiConfig() != null && !step.getApiConfig().isBlank()) {
            try {
                config = ConnectorCallConfig.parse(step.getApiConfig());
            } catch (ConnectorCallConfig.InvalidConfigException e) {
                config = null;
            }
        }
        if (config != null && operationLookup != null) {
            Optional<ConnectorOperation> operation;
            try {
                operation = operationLookup.apply(config);
            } catch (RuntimeException e) {
                operation = Optional.empty();
            }
            if (operation != null && operation.isPresent()) {
                addOutputProperties(operation.get().outputOrEmpty(), "output", fields);
            }
        }
        return StepOutputSchema.builder().stepType("CONNECTOR_CALL").fields(fields).build();
    }

    private static void addOutputProperties(SchemaNode node, String prefix, List<OutputField> fields) {
        for (Map.Entry<String, SchemaNode> property : node.propertiesOrEmpty().entrySet()) {
            SchemaNode child = property.getValue();
            if (child == null) {
                continue;
            }
            String path = prefix + "." + property.getKey();
            String label = child.description() != null && !child.description().isBlank()
                    ? child.description()
                    : property.getKey();
            boolean nested = child.type() == null ? !child.propertiesOrEmpty().isEmpty()
                    : "object".equalsIgnoreCase(child.type());
            String type = nested ? "object" : schemaType(child.type());
            OutputField field = OutputField.dynamic(path, label, type);
            field.setSensitive(child.isSensitive());
            fields.add(field);
            // A sensitive object is masked whole: nothing below it is readable.
            if (nested && !child.isSensitive()) {
                addOutputProperties(child, path, fields);
            }
        }
    }

    public StepDefinition userInputDefinition() {
        return StepDefinition.builder()
                .type("USER_INPUT")
                .category("interaction")
                .label("User Input")
                .icon(hgiIcon("hgi-user-edit-01"))
                .uiComponent("step-user-input")
                .waitsForInput(true)
                .build();
    }

    public StepDefinition responseDefinition() {
        return StepDefinition.builder()
                .type("RESPONSE")
                .category("interaction")
                .label("Response")
                .icon(hgiIcon("hgi-message-01"))
                .uiComponent("step-response")
                .build();
    }

    public StepOutputSchema apiCallSchema(JourneyStep step) {
        List<OutputField> fields = new ArrayList<>(List.of(
                OutputField.of("output", "Response Body", "object"),
                OutputField.of("status", "HTTP Status", "number"),
                OutputField.of("headers", "Response Headers", "object")));
        fields.addAll(parseDiscoveredVariables(step));
        return StepOutputSchema.builder().stepType("API_CALL").fields(fields).build();
    }

    public StepOutputSchema userInputSchema(JourneyStep step) {
        List<OutputField> fields = new ArrayList<>();
        fields.add(OutputField.of("output", "User Answer", "string"));

        // STRUCTURED / INTERACTIVE answers come back as a map keyed by field name,
        // so each configured field is individually addressable.
        ApiConfig config = loadApiConfig(step != null ? step.getApiConfig() : null);
        String mode = config.getInputMode() != null ? config.getInputMode().toUpperCase() : "";
        if (("STRUCTURED".equals(mode) || "INTERACTIVE".equals(mode)) && config.getFields() instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Object name = map.get("name");
                if (name == null || String.valueOf(name).isBlank()) {
                    continue;
                }
                Object label = map.get("label");
                fields.add(OutputField.dynamic(
                        "output." + name,
                        label != null && !String.valueOf(label).isBlank() ? String.valueOf(label) : String.valueOf(name),
                        schemaType(map.get("type"))));
            }
        }
        return StepOutputSchema.builder().stepType("USER_INPUT").fields(fields).build();
    }

    public StepOutputSchema responseSchema(JourneyStep step) {
        return StepOutputSchema.builder()
                .stepType("RESPONSE")
                .fields(List.of(OutputField.of("output", "Response Text", "string")))
                .build();
    }

    public StepOutputSchema conditionSchema() {
        return StepOutputSchema.builder()
                .stepType("CONDITION")
                .fields(List.of(OutputField.of("output", "Condition Result", "boolean")))
                .build();
    }

    public StepOutputSchema switchSchema() {
        return StepOutputSchema.builder()
                .stepType("SWITCH")
                .fields(List.of(OutputField.of("output", "Switch Value", "string")))
                .build();
    }

    /**
     * KNOWLEDGE_RETRIEVAL publishes the answer, whether one was found, the
     * passages it was served from ({@code sources}: one citation object per
     * passage, best first; empty on a miss) and whether several were composed
     * into one answer.
     */
    public StepOutputSchema knowledgeRetrievalSchema() {
        return StepOutputSchema.builder()
                .stepType("KNOWLEDGE_RETRIEVAL")
                .fields(List.of(
                        OutputField.of("output", "Retrieved Answer", "string"),
                        OutputField.of("found", "Knowledge Found", "boolean"),
                        OutputField.of("sources", "Sources", "array"),
                        OutputField.of("composed", "Answer Composed", "boolean")))
                .build();
    }

    public StepOutputSchema stateStoreSchema(JourneyStep step) {
        ApiConfig config = loadApiConfig(step != null ? step.getApiConfig() : null);
        List<OutputField> fields = new ArrayList<>();
        fields.add(OutputField.of("output", "Stored Value", "string"));
        if (config.getVariable() != null && !config.getVariable().isBlank()) {
            // Absolute: this lands in the shared state bucket, not steps.<order>.
            fields.add(OutputField.absolute("state." + config.getVariable(), config.getVariable(), "string"));
        }
        return StepOutputSchema.builder()
                .stepType("STATE_STORE")
                .fields(fields)
                .writesToState(true)
                .build();
    }

    /**
     * DATA_MAP declares its extraction schema in {@code actionTarget} as a JSON
     * array of {@code {field, type, options, hint}} — that is what the builder
     * serialises and what the handler feeds the LLM. This previously read
     * {@code apiConfig.fields[].targetField}, which DATA_MAP never populates, so
     * every DATA_MAP step reported zero outputs and its extracted values were
     * invisible in the variable picker.
     */
    public StepOutputSchema dataMapSchema(JourneyStep step) {
        List<OutputField> fields = new ArrayList<>();
        for (Map<String, Object> entry : parseJsonObjectArray(step != null ? step.getActionTarget() : null)) {
            Object name = entry.get("field");
            if (name == null || String.valueOf(name).isBlank()) {
                continue;
            }
            fields.add(OutputField.dynamic(
                    "output." + name,
                    String.valueOf(name),
                    schemaType(entry.get("type"))));
        }
        return StepOutputSchema.builder().stepType("DATA_MAP").fields(fields).build();
    }

    /** Maps builder field types onto the coarse types the variable picker shows. */
    private static String schemaType(Object rawType) {
        String type = rawType != null ? String.valueOf(rawType).toLowerCase() : "";
        return switch (type) {
            case "number", "integer", "decimal" -> "number";
            case "boolean", "bool" -> "boolean";
            case "object" -> "object";
            case "array", "list" -> "array";
            default -> "string";
        };
    }

    private List<Map<String, Object>> parseJsonObjectArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public StepOutputSchema genericOutputSchema(String stepType, String label) {
        return genericOutputSchema(stepType, label, "string");
    }

    /** Single-output schema for steps whose {@code output} is not a string (e.g. HUMAN_APPROVAL's boolean). */
    public StepOutputSchema genericOutputSchema(String stepType, String label, String type) {
        return StepOutputSchema.builder()
                .stepType(stepType)
                .fields(List.of(OutputField.of("output", label, type)))
                .build();
    }

    public StepDefinition genericDefinition(String type, String category, String label, String iconSlug, String uiComponent) {
        return StepDefinition.builder()
                .type(type)
                .category(category)
                .label(label)
                .icon(hgiIcon(iconSlug))
                .uiComponent(uiComponent)
                .build();
    }

    public StepDefinition conditionDefinition() {
        return genericDefinition("CONDITION", "logic", "Condition", "hgi-git-branch", "step-condition");
    }

    public StepDefinition switchDefinition() {
        return genericDefinition("SWITCH", "logic", "Switch", "hgi-shuffle", "step-switch");
    }

    public StepDefinition jumpDefinition() {
        return genericDefinition("JUMP", "logic", "Jump", "hgi-route-01", "step-jump");
    }

    public StepDefinition delayDefinition() {
        return genericDefinition("DELAY", "logic", "Delay", "hgi-clock-01", "step-delay");
    }

    /**
     * The knowledge step as the builder shows it: "Knowledge answer", the
     * portal's name for it. Only the label changed (1.0.20); the type
     * {@code KNOWLEDGE_RETRIEVAL} is stored in journeys and never renamed.
     */
    public StepDefinition knowledgeDefinition() {
        return genericDefinition("KNOWLEDGE_RETRIEVAL", "ai", "Knowledge answer", "hgi-book-open-01", "step-knowledge");
    }

    public StepDefinition documentInsightDefinition() {
        return genericDefinition("DOCUMENT_INSIGHT", "ai", "Document Insight", "hgi-file-search", "step-document");
    }

    public StepDefinition templateRenderDefinition() {
        return genericDefinition("TEMPLATE_RENDER", "ai", "Template Render", "hgi-magic-wand-01", "step-template");
    }

    /** TEMPLATE_RENDER publishes the rendered text plus the template's declared type. */
    public StepOutputSchema templateRenderSchema() {
        return StepOutputSchema.builder()
                .stepType("TEMPLATE_RENDER")
                .fields(List.of(
                        OutputField.of("output", "Rendered Output", "string"),
                        OutputField.of("contentType", "Content Type", "string")))
                .build();
    }

    public StepDefinition dataMapDefinition() {
        return genericDefinition("DATA_MAP", "system", "Data Map", "hgi-database-01", "step-data-map");
    }

    public StepDefinition codeScriptDefinition() {
        return genericDefinition("CODE_SCRIPT", "system", "Code Script", "hgi-source-code-square", "step-code");
    }

    public StepDefinition stateStoreDefinition() {
        StepDefinition def = genericDefinition("STATE_STORE", "system", "State Store", "hgi-database-setting", "step-state");
        def.setWritesToState(true);
        return def;
    }

    public StepDefinition humanApprovalDefinition() {
        StepDefinition def = genericDefinition("HUMAN_APPROVAL", "interaction", "Human Approval", "hgi-user-check-01", "step-approval");
        def.setWaitsForInput(true);
        return def;
    }

    public StepDefinition triggerJourneyDefinition() {
        return genericDefinition("TRIGGER_JOURNEY", "logic", "Trigger Journey", "hgi-flash", "step-trigger");
    }

    /** HANDOFF: an interaction, like approval — it is where a person enters. */
    public StepDefinition handoffDefinition() {
        return genericDefinition("HANDOFF", "interaction", "Human Handoff", "hgi-customer-support", "step-handoff");
    }

    /** Two outputs, so a later step can branch on the escalation and name its queue. */
    public StepOutputSchema handoffSchema() {
        return StepOutputSchema.builder()
                .stepType("HANDOFF")
                .fields(List.of(
                        OutputField.of("output.handedOff", "Handed Off", "boolean"),
                        OutputField.of("output.queue", "Queue", "string")))
                .build();
    }

    public StepDefinition sendMailDefinition() {
        return genericDefinition("SEND_MAIL", "interaction", "Send Mail", "hgi-mail-send-01", "step-mail");
    }

    private List<OutputField> parseDiscoveredVariables(JourneyStep step) {
        List<OutputField> fields = new ArrayList<>();
        // getAllDefaultSchemas() describes every type with a null step, so every
        // config-reading path here has to tolerate one.
        ApiConfig config = loadApiConfig(step != null ? step.getApiConfig() : null);
        Object discovered = config.getDiscoveredVariables();
        if (discovered == null) {
            return fields;
        }
        try {
            List<Map<String, Object>> vars = objectMapper.convertValue(discovered, new TypeReference<>() {});
            for (Map<String, Object> v : vars) {
                String path = v.get("path") != null ? String.valueOf(v.get("path")) : null;
                if (path == null || path.isBlank()) {
                    continue;
                }
                String fullPath = path.startsWith("output.") ? path : "output." + path;
                String label = v.get("label") != null ? String.valueOf(v.get("label")) : path;
                fields.add(OutputField.dynamic(fullPath, label, "string"));
            }
        } catch (Exception ignored) {
        }
        return fields;
    }

    private ApiConfig loadApiConfig(String json) {
        try {
            if (json == null || json.isEmpty()) {
                return new ApiConfig();
            }
            return objectMapper.readValue(json, ApiConfig.class);
        } catch (Exception e) {
            return new ApiConfig();
        }
    }
}
