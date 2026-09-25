package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.model.StepStatus;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ApiConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.model.TemplateRenderResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.service.TemplateRenderPort;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders a stored template and publishes the result as this step's output.
 *
 * <p>Values reach the template through {@code apiConfig.bindings}: each template variable
 * is bound to a journey expression, resolved here, and only those named values are sent.
 * The journey's variable namespaces are never handed to FreeMarker wholesale.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TemplateRenderHandler implements StepHandler {

    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;
    private final EngineUtils engineUtils;
    private final TemplateRenderPort templateRenderPort;

    @Override
    public String getType() {
        return "TEMPLATE_RENDER";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.templateRenderDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.templateRenderSchema();
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String target = step.getActionTarget();
        if (target == null || target.isBlank()) {
            return StepResult.error("TEMPLATE_RENDER: no template selected");
        }

        long templateId;
        try {
            templateId = Long.parseLong(target.trim());
        } catch (NumberFormatException e) {
            return StepResult.error("TEMPLATE_RENDER: '" + target + "' is not a template id");
        }

        ApiConfig config = engineUtils.parseApiConfig(step.getApiConfig());
        Map<String, Object> model = resolveBindings(config, context);

        TemplateRenderResult result;
        try {
            result = templateRenderPort.render(context.getAccountId(), templateId, model);
        } catch (Exception e) {
            log.error("❌ TEMPLATE_RENDER: template service unreachable for templateId={}", templateId, e);
            return StepResult.error("Template Rendering Failed: " + e.getMessage());
        }

        if (result == null) {
            return StepResult.error("Template Rendering Failed: no response from the template service");
        }
        if (result.failed()) {
            log.warn("⚠️ TEMPLATE_RENDER: template {} failed to render — {}", templateId, result.error());
            return StepResult.error("Template Rendering Failed: " + result.error());
        }

        // The template tolerated the gaps, but this step may not want to.
        if (!config.isAllowMissingInputs() && result.unresolved() != null && !result.unresolved().isEmpty()) {
            return StepResult.error("Template Rendering Failed: nothing supplied for "
                    + String.join(", ", result.unresolved()));
        }

        String rendered = result.output() != null ? result.output() : "";
        variableContext.storeOutput(context, step, rendered);
        variableContext.writeStepField(context, step, "contentType", result.contentType());

        log.info("✅ TEMPLATE_RENDER: rendered template {} ({} chars)", templateId, rendered.length());
        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .message(step.getMessage())
                .data(Map.of("renderedContent", rendered))
                .metadata(viewMetadata(result))
                .build();
    }

    /**
     * Resolves each binding against the journey's variables. A lone {@code {{path}}}
     * keeps its type, so a number stays a number for FreeMarker's formatting; anything
     * that fails to resolve is recorded by the resolver and surfaces on the step as an
     * unresolved variable.
     */
    private Map<String, Object> resolveBindings(ApiConfig config, ExecutionContext context) {
        Map<String, Object> model = new HashMap<>();
        Map<String, String> bindings = config.getBindings();
        if (bindings == null || bindings.isEmpty()) {
            return model;
        }
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            String name = binding.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            model.put(name.trim(), engineUtils.resolveSourceValue(binding.getValue(), context.getVariables()));
        }
        return model;
    }

    /**
     * {@code format} drives the renderer the assistant timeline picks when it opens the
     * output in the template viewer.
     */
    private Map<String, Object> viewMetadata(TemplateRenderResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result.contentType() != null) {
            metadata.put("format", result.contentType());
        }
        if (result.unresolved() != null && !result.unresolved().isEmpty()) {
            metadata.put("unresolvedTemplateVariables", result.unresolved());
        }
        return metadata;
    }
}
