package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.model.StepStatus;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.model.catalog.StepDefinition;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedirectStepHandler implements StepHandler {

    /** Step-view key the Web SDK reads to navigate the host page. */
    public static final String META_REDIRECT_URL = "redirectUrl";

    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    @Override
    public String getType() {
        return "REDIRECT";
    }

    /** Without this the catalog listed REDIRECT with no label, icon or palette group. */
    @Override
    public StepDefinition describe() {
        return schemaHelper.genericDefinition("REDIRECT", "interaction", "Redirect", "hgi-share-01", "step-generic");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        String raw = step.getActionTarget();
        if (raw == null || raw.isBlank()) {
            return StepResult.error("REDIRECT: actionTarget (URL) is required");
        }

        String resolvedUrl = engineUtils.replacePlaceholders(raw, context.getVariables());
        if (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://")) {
            return StepResult.error("REDIRECT: invalid URL — must start with http:// or https://");
        }

        // Namespaced only — reachable as {{steps.<order>.output}}. This was the last
        // handler writing flat root variables (step<N>, lastStep, redirect_url).
        variableContext.storeOutput(context, step, resolvedUrl);

        log.info("↪️ REDIRECT step '{}' → {}", step.getStepName(), resolvedUrl);

        String successMessage = null;
        if (StringUtils.hasText(step.getMessage())) {
            successMessage = engineUtils.replacePlaceholders(step.getMessage(), context.getVariables());
        }

        // The URL also travels in metadata, which is the only channel that
        // actually reaches the browser: JourneyEngineImpl merges a step's
        // metadata into the client-facing view but never copies StepResult's
        // own actionTarget field, so a client had no way to tell a REDIRECT
        // apart from any other step that happens to output a string.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(META_REDIRECT_URL, resolvedUrl);

        return StepResult.builder()
                .status(StepStatus.SUCCESS)
                .data(resolvedUrl)
                .message(successMessage)
                .actionTarget(resolvedUrl)
                .metadata(metadata)
                .build();
    }
}
