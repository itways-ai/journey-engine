package com.itways.assistant.journey.engine.model;

import java.util.List;

/**
 * What came back from rendering a stored template.
 *
 * <p>{@code error} set means the template itself is broken — a syntax error, or a value
 * the template refused to render without. That is distinct from the port throwing, which
 * means the template service could not be reached. Handlers need to tell those apart.
 *
 * @param output      the rendered text, or null when {@code error} is set
 * @param contentType the template's declared type (html, markdown, …)
 * @param unresolved  names the template asked for that were not supplied; populated only
 *                    when the template tolerates missing values
 * @param error       why the render failed, or null
 */
public record TemplateRenderResult(
        String output,
        String contentType,
        List<String> unresolved,
        String error) {

    public boolean failed() {
        return error != null;
    }
}
