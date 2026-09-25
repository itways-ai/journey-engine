package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.model.TemplateRenderResult;

import java.util.Map;

/**
 * Port interface for rendering a stored template.
 * Implemented by assistant-service, which calls template-service over HTTP.
 * Kept here in the SDK so TemplateRenderHandler can depend on it without knowing
 * anything about Feign or the template service's URL.
 *
 * <p>Rendering lives on the far side of this port rather than in the SDK on purpose:
 * the console previews templates through the same service, so an author cannot see
 * one thing in the editor and get another at runtime.
 */
public interface TemplateRenderPort {

    /**
     * Renders the template against {@code model}.
     *
     * @param accountId  the account that owns the template
     * @param templateId the template to render
     * @param model      values keyed by the names the template declares
     * @return the rendered output, or a result carrying the reason it failed
     * @throws RuntimeException if the template service could not be reached
     */
    TemplateRenderResult render(String accountId, long templateId, Map<String, Object> model);
}
