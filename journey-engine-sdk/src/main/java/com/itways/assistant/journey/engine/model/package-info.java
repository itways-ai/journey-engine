/**
 * Host-facing. The data the SPI passes: {@link ExecutionContext} (a run's
 * state, which the host stores between turns and hands back to resume),
 * {@link StepResult} (what a {@code StepHandler} returns), {@link MailConfig}
 * (what {@code MailDeliveryPort} sends) and {@link TemplateRenderResult} (what
 * {@code TemplateRenderPort} returns). {@link ApiConfig} is a step's parsed
 * {@code apiConfig}, read by the handlers.
 *
 * <p>
 * The journey document, run history and step catalogue types are in
 * journey-model.
 */
package com.itways.assistant.journey.engine.model;
