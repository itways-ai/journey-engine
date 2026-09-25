package com.itways.assistant.journey.engine.service;

import java.util.Map;

/**
 * Receives each step's client view the moment the engine finishes that step.
 *
 * <p>
 * The engine already builds these views in order; without an observer they are
 * only handed back as one array when the whole run completes, so a caller can
 * show nothing until the slowest step (usually an LLM call) has finished. An
 * observer lets a transport publish progress as it happens.
 *
 * <p>
 * The map passed in is the same instance the engine will return in
 * {@code stepResults}. Implementations must treat it as read-only and must not
 * retain it beyond the callback.
 *
 * <p>
 * Called synchronously on the thread running the journey, so an implementation
 * that blocks slows the run down. A failing observer must never break the run:
 * the engine isolates it.
 *
 * <p>
 * Deliberately passed as a parameter rather than parked on
 * {@link com.itways.assistant.journey.engine.model.ExecutionContext}: that
 * context is serialised to Redis whenever a run parks on {@code WAITING}, and a
 * lambda would make it unserialisable.
 */
@FunctionalInterface
public interface StepObserver {

	/** No-op observer for callers that do not stream. */
	StepObserver NOOP = view -> {
	};

	void onStep(Map<String, Object> stepView);
}
