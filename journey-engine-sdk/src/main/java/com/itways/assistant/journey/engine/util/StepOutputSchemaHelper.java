package com.itways.assistant.journey.engine.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.engine.model.OutputField;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public StepDefinition apiCallDefinition() {
        return StepDefinition.builder()
                .type("API_CALL")
                .category("system")
                .label("API Call")
                .icon(hgiIcon("hgi-cloud"))
                .uiComponent("step-api")
                .build();
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

    public StepOutputSchema knowledgeRetrievalSchema() {
        return StepOutputSchema.builder()
                .stepType("KNOWLEDGE_RETRIEVAL")
                .fields(List.of(
                        OutputField.of("output", "Retrieved Answer", "string"),
                        OutputField.of("found", "Knowledge Found", "boolean")))
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

    public StepDefinition knowledgeDefinition() {
        return genericDefinition("KNOWLEDGE_RETRIEVAL", "ai", "Knowledge Retrieval", "hgi-book-open-01", "step-knowledge");
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
