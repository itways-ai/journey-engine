package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.model.JourneyRunLifecycleEvent;

/**
 * Host port for persisting durable journey-run lifecycle events.
 * Implementations must be idempotent on {@link JourneyRunLifecycleEvent#getExecutionId()}.
 * A failed {@code RUNNING} callback should throw so the engine does not start untracked work.
 */
public interface JourneyRunLifecyclePort {

    void onLifecycleEvent(JourneyRunLifecycleEvent event);
}
