package com.itways.assistant.journey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * A parked run: what is saved when a journey waits for the user, and what a
 * resume loads back ({@code /api/journeys/instances}).
 *
 * @param id               the run's executionId
 * @param journeyVersionId the published version the run is pinned to; a resume
 *                         executes this version whatever was published since
 * @param currentStepIndex the step order the run paused on
 * @param contextData      JSON of the engine context — variables, step results,
 *                         nested-journey bookkeeping, language. Owned by the
 *                         engine's host; journey-service stores it opaquely
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JourneyInstanceState(
        UUID id,
        Long journeyId,
        Long journeyVersionId,
        Integer currentStepIndex,
        ExecutionStatus status,
        String contextData) {
}
