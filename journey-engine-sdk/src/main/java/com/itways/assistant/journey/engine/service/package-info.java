/**
 * The SPI: what a host calls and what it implements.
 *
 * <ul>
 * <li>Called by the host: {@link JourneyEngine} ({@code start} / {@code resume},
 * optionally with a {@link StepObserver} for streaming) and
 * {@link StepOutputRegistry} (the step catalogue for the builder).
 * <li>Implemented by the host: the ports {@link JourneyLookupPort},
 * {@link JourneyRunLifecyclePort}, {@link KnowledgeBasePort} (with its
 * {@link KnowledgeQuery} and {@link KnowledgeMiss}, and which throws
 * {@link KnowledgeIndexMissingException} for an index that does not exist),
 * {@link MailDeliveryPort}, {@link StepTextPort} and {@link TemplateRenderPort}
 * (which throws {@link TemplateRenderBusyException} when the renderer is busy),
 * plus {@link AiConfigProvider} and {@link TextTranslator}; and since 1.1.0
 * {@link ConnectorPort} for CONNECTOR_CALL (which throws
 * {@link ConnectorNotResolvableException} for a connector the run may not
 * use).
 * <li>Implemented to add a step type: {@link StepHandler}.
 * <li>Internal: {@link StepHandlerRegistry}, which finds the handler for a step
 * type.
 * </ul>
 *
 * <p>
 * Changing a signature here is a breaking SDK release for every host.
 */
package com.itways.assistant.journey.engine.service;
