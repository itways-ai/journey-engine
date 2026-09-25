package com.itways.assistant.journey.engine.model;

import com.itways.assistant.journey.model.ExecutionStatus;
import com.itways.assistant.journey.engine.language.ConversationLanguage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContext {
    /** Permanent UUID for this run (also used as WAITING resume key). */
    private String executionId;
    /** Immediate parent run UUID when this run was started via TRIGGER_JOURNEY. */
    private String parentExecutionId;
    /** Top-level run UUID for the nesting tree (equals executionId for top-level runs). */
    private String rootExecutionId;
    private Long journeyId;
    private String accountId;
    /**
     * Product scope for this run. Nested TRIGGER_JOURNEY lookups and knowledge
     * retrieval stay inside it, so a shared journey invoked by one product can
     * never reach into another product's flows or knowledge.
     */
    private java.util.UUID assistantId;
    private int currentStepIndex;
    private ExecutionStatus status;
    private Date startedAt;

    /**
     * The language this conversation is being conducted in.
     *
     * <p>
     * A field on the context rather than a per-turn parameter because it has to
     * survive the run parking on {@code WAITING}: the context is what gets
     * serialised to Redis, so this is the only place a language can live and
     * still be there when the user's next message arrives. Without it every
     * resumed turn would re-detect from whatever the user just typed, and a
     * one-word answer would flip a whole journey's wording.
     *
     * <p>
     * Null only before the first resolution; {@link #resolvedLanguage()} is the
     * accessor that never returns null.
     */
    private ConversationLanguage language;

    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    @Builder.Default
    private Map<Integer, Object> stepResults = new HashMap<>();

    /**
     * Engine bookkeeping â nested-journey call stack, paused child context,
     * pending resume input. Deliberately separate from {@link #variables}: these
     * are not addressable by journey authors and must not reach run history, the
     * variable picker, the CODE_SCRIPT sandbox, or DATA_MAP's LLM prompt (all of
     * which serialise {@code variables} wholesale).
     */
    @Builder.Default
    private Map<String, Object> internal = new HashMap<>();

    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    public Object getVariable(String key) {
        return variables.get(key);
    }

    public void addStepResult(int stepOrder, Object result) {
        stepResults.put(stepOrder, result);
    }

    public void setInternal(String key, Object value) {
        internal.put(key, value);
    }

    public Object getInternal(String key) {
        return internal.get(key);
    }

    public void removeInternal(String key) {
        internal.remove(key);
    }

    /** Never null — falls back to {@link ConversationLanguage#DEFAULT}. */
    public ConversationLanguage resolvedLanguage() {
        return language != null ? language : ConversationLanguage.DEFAULT;
    }
}
