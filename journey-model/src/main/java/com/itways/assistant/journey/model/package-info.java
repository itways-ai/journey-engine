/**
 * The journey document and the record of its runs, shared by the service that
 * stores journeys (journey-service) and the engine that runs them
 * (journey-engine-sdk). Plain data, no Spring. Public: both sides depend on it.
 *
 * <ul>
 * <li>The journey: {@link JourneyDefinition} (a published version as the engine
 * executes it), {@link JourneyStep}, {@link StepText} and
 * {@link MachineTranslation} (per-language step text).
 * <li>The parent links between steps: {@link JourneyStepGraph} (parents,
 * execution order, cycle check).
 * <li>Runs: {@link JourneyInstanceState} (a paused run), {@link RunHistoryEvent}
 * (one lifecycle event, as the engine's host reports it to run history),
 * {@link RunStepLog}, and the statuses {@link ExecutionStatus},
 * {@link RunStatus} and {@link StepStatus}.
 * <li>{@link EngineSearchResult}: one knowledge-base hit.
 * </ul>
 *
 * <p>
 * The step catalogue the journey builder reads is in {@code catalog}.
 */
package com.itways.assistant.journey.model;
