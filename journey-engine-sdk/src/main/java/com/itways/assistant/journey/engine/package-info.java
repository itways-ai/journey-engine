/**
 * journey-engine-sdk: runs journeys one conversation turn at a time. It has no
 * database and no HTTP API of its own; the host application supplies storage,
 * search, rendering and mail through ports.
 *
 * <p>
 * A host enables it with {@link EnableJourneyEngine} and then works with the
 * SPI in {@code service}: it calls {@code JourneyEngine}, implements the ports
 * ({@code *Port}, {@code AiConfigProvider}, {@code TextTranslator}) and may add
 * step types by implementing {@code StepHandler}. What each package is, and
 * whether a host may use it, is in that package's {@code package-info}:
 *
 * <ul>
 * <li>Host-facing: {@code service} (the SPI), {@code model} (the data the SPI
 * passes), {@code context} and {@code language} (reserved start-params and the
 * conversation language), and {@link com.itways.assistant.journey.engine.util.VariablePath}.
 * <li>Internal: {@code config}, {@code impl}, {@code handler}, {@code util}
 * (apart from {@code VariablePath}) and {@code validation}.
 * </ul>
 *
 * <p>
 * The journey document and the step catalogue types are in journey-model
 * ({@code com.itways.assistant.journey.model}).
 */
package com.itways.assistant.journey.engine;
