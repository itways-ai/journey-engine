/**
 * The language a conversation is conducted in, and the engine's own text in
 * each language.
 *
 * <ul>
 * <li>Host-facing: {@link ConversationLanguage}, {@link LanguageDetector},
 * {@link LanguageParams} (the language as a reserved start-param),
 * {@link DecisionWords} (reading yes/no), {@link Messages} (a base for a
 * host's own message bundle) and {@link EngineMessages} (the engine's
 * sentences, which a host may reuse).
 * <li>Internal: {@link StepLocalizer}, which swaps a step's text for its
 * translation before the step runs.
 * </ul>
 */
package com.itways.assistant.journey.engine.language;
