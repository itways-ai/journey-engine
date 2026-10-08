package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.model.RunHistoryEvent;

/**
 * Host port for persisting durable journey-run lifecycle events.
 *
 * <p>
 * The engine hands over the event in the shape journey-service stores it
 * ({@link RunHistoryEvent}): {@code rootExecutionId} is always set (it equals
 * {@code executionId} for a run that is not nested) and {@code contextData} is
 * the run's variables and step results as JSON. Implementations must be
 * idempotent on {@link RunHistoryEvent#executionId()}. A failed {@code RUNNING}
 * callback should throw so the engine does not start untracked work.
 */
public interface JourneyRunLifecyclePort {

    void onLifecycleEvent(RunHistoryEvent event);
}
