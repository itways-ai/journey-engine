/**
 * Typed step configurations (1.1.0). Every step stores its configuration in
 * {@code JourneyStep.apiConfig}, a JSON string; the types here are the ones
 * read strictly, by journey-service at save and publish and by the engine at
 * run time, so a bad config is refused the same way in both places instead of
 * silently becoming a default.
 *
 * <ul>
 * <li>{@link ConnectorCallConfig}: the CONNECTOR_CALL step.
 * </ul>
 */
package com.itways.assistant.journey.model.step;
