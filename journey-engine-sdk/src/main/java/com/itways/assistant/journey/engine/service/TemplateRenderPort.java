package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.model.TemplateRenderResult;
import java.util.Map;

/**
 * Port interface for rendering a stored template.
 * Implemented by conversation-service, which calls template-service over HTTP.
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
     * @param version    the template version to render (a published step's pinned version),
     *                   or null for the template's current version
     * @param model      values keyed by the names the template declares
     * @return the rendered output, or a result carrying the reason it failed
     * @throws TemplateRenderBusyException if the template service is busy (HTTP 503) and
     *         turned the render away; the caller may ask again after a short pause
     * @throws RuntimeException if the template service could not be reached
     */
    TemplateRenderResult render(String accountId, long templateId, Integer version, Map<String, Object> model);

    /** Renders the template's current version. */
    default TemplateRenderResult render(String accountId, long templateId, Map<String, Object> model) {
        return render(accountId, templateId, null, model);
    }
}
