/**
 * Internal. The engine's Spring wiring: {@link JourneyConfiguration}, which
 * {@code @EnableJourneyEngine} imports. It enables ai-engine-sdk,
 * component-scans {@code com.itways.assistant.journey.engine} and defines the
 * engine's own FreeMarker configuration ({@code sdkRenderConfig}); and
 * {@link ConnectorCallConfiguration}, the CONNECTOR_CALL transport under the
 * {@code itways.connectors.*} egress settings. Hosts do not reference this
 * package; they use {@code @EnableJourneyEngine}.
 */
package com.itways.assistant.journey.engine.config;
