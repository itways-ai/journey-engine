package com.itways.assistant.journey.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A journey as the engine executes it: one published version's steps, the
 * translations captured with it, and the few live settings a run reads.
 *
 * <p>
 * journey-service builds it ({@code GET /api/journeys/versions/{id}/content})
 * and conversation-service runs it; both compile against this class, so the document
 * cannot drift between the service that stores it and the one that executes it.
 *
 * <p>
 * Not the authoring model. journey-service's {@code Journey} carries what the
 * builder edits — category, pinning, lock version, details — and none of that
 * affects a run. Unknown properties are ignored so the authoring endpoints,
 * which speech also reads for configuration, still bind to this.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JourneyDefinition {
    private Long id;

    /**
     * The immutable published version this definition was loaded from.
     *
     * <p>
     * Runs pin to it: a paused conversation resumes against the version it
     * started on, never against a draft edited since — resuming against a
     * renumbered definition used to land the user's answer on the wrong step.
     * Null only for definitions that predate versioning.
     */
    private Long versionId;

    private String name;
    private String triggerIntent;

    /**
     * Translations captured at publish, keyed {@code locale → stepId → text}.
     *
     * <p>
     * Carried on the version payload rather than fetched live so a draft
     * retranslation can never leak into published traffic, and so a resume turn
     * costs no extra lookup. Null or missing locale simply means "serve the
     * authored text".
     */
    private Map<String, Map<Long, StepText>> translations;

    /**
     * Variables this journey is allowed to leave in conversation memory.
     *
     * <p>
     * Opt-in, and deliberately not "remember everything the run produced". The
     * variable map holds whatever every step fetched from every upstream system,
     * and the run result already strips it before the reply leaves the service
     * for exactly that reason; copying it wholesale into a store that is then
     * replayed into an LLM prompt would undo that in one line.
     *
     * <p>
     * Each entry is {@code [profile:][name=]path}:
     *
     * <ul>
     * <li>{@code inputs.entities.projectId} — remembered under its last segment,
     * {@code projectId}.
     * <li>{@code openTasks=steps.3.output.count} — an explicit name, which is
     * what step-output paths want: {@code count} is a true label and a useless
     * one to a model reading it a turn later.
     * <li>{@code profile:team=steps.2.output.team} — promoted to the end user's
     * durable profile instead of expiring with the conversation, which is how
     * "my team is Platform" outlives the session that established it.
     * </ul>
     *
     * <p>
     * The profile marker is a colon rather than a dot because a dot is already
     * the path separator: {@code profile.team} could not be told apart from a
     * variable genuinely nested under {@code profile}.
     *
     * <p>
     * Null or empty means this journey contributes no structured facts. Its
     * reply text is still remembered — that comes from the turn log and needs no
     * authoring.
     */
    private List<String> memoryKeys;

    private List<JourneyStep> steps;
}
