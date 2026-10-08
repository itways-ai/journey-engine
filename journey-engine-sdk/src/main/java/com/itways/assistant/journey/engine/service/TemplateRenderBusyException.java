package com.itways.assistant.journey.engine.service;

/**
 * Thrown by a {@link TemplateRenderPort} when the template service turned the
 * render away because it is busy (HTTP 503: its render workers are all in use).
 *
 * <p>Busy is the one failure worth asking again right away: the service is up and
 * answered at once, so a moment later a worker is likely free. The port's other
 * failures (unreachable, timed out, rejected) stay plain runtime exceptions, and
 * the engine does not retry them. Kept in the SDK so the engine can tell the two
 * apart without knowing the HTTP client the port uses.
 */
public class TemplateRenderBusyException extends RuntimeException {

    public TemplateRenderBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
