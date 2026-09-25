package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.model.JourneyDefinition;

/**
 * Port for resolving a journey by its trigger intent at runtime.
 * Implemented by the host (e.g. assistant-service) so the engine can run
 * TRIGGER_JOURNEY steps without depending on journey-service directly.
 */
public interface JourneyLookupPort {

    /**
     * Finds a journey by trigger intent for the given account.
     *
     * @param accountId   account that owns the journey
     * @param assistantId product scope; null restricts the search to shared journeys
     * @param intent    trigger intent name (e.g. {@code ORDER_STATUS})
     * @return the journey including steps, or {@code null} if not found
     */
    JourneyDefinition findByTriggerIntent(String accountId, java.util.UUID assistantId, String intent);

    /**
     * Loads the immutable published version a run is pinned to.
     *
     * <p>
     * Used on resume paths — a parked child journey must come back on the
     * definition it started with, not whatever was published since.
     *
     * @return the journey content of that version, or {@code null} if the
     *         version no longer exists (pruned); callers treat that as a
     *         gracefully dead run, not an error to retry
     */
    JourneyDefinition findByVersionId(String accountId, Long versionId);
}
