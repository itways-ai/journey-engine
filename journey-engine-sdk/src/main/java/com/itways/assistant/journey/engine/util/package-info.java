/**
 * Internal helpers for the handlers and the run loop: placeholders and
 * variable paths ({@link Placeholders}, {@link VariablePath},
 * {@link VariableDiagnostics}, {@link StepKeyResolver}), conditions and value
 * handling ({@link EngineUtils}), output schemas and channel support for the
 * catalogue ({@link StepOutputSchemaHelper}, {@link ChannelSupport}), DATA_MAP's
 * prompt ({@link DataMapped}) and the API_CALL egress rules
 * ({@link EgressGuard}, {@link EgressDnsResolver}, {@link EgressHttpClients}).
 *
 * <p>
 * Hosts may use {@link VariablePath} to read a run's variables by the same
 * paths journeys use; everything else here may change without notice.
 */
package com.itways.assistant.journey.engine.util;
