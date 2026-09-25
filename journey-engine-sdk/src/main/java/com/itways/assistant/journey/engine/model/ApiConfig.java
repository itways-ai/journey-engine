package com.itways.assistant.journey.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiConfig {
    @Builder.Default
    private String method = "GET";
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();
    @Builder.Default
    private Map<String, String> queryParams = new HashMap<>();
    private Object body;
    private boolean allowMissingInputs = false;
    private boolean allowResubmit = false;
    @Builder.Default
    private String inputMode = "FREE_TEXT"; // FREE_TEXT, STRUCTURED, INTERACTIVE
    private String confirmationMessage; // INTERACTIVE: shown in phase 2 before form confirmation
    /**
     * USER_INPUT: an extracted entity that may answer this step instead of asking.
     *
     * <p>
     * Names an entry the intent classifier put in {@code inputs.entities} — so
     * {@code fillFrom: "task"} reads {@code inputs.entities.task}. When present,
     * the step offers the value back for a yes/no rather than asking cold, and
     * never accepts it silently: the author wrote that question, and skipping it
     * outright would remove a checkpoint on journeys that complete, delegate or
     * archive things.
     *
     * <p>
     * Null on every step that has not opted in, which is what keeps this inert
     * for journeys authored before it existed.
     */
    private String fillFrom;
    private Object fields;
    private Object rules;

    /**
     * TEMPLATE_RENDER: template variable name → the journey expression supplying it,
     * e.g. {@code {"firstName": "{{inputs.entities.name}}"}}. Only what is named here
     * reaches the template — the journey's variable namespaces are deliberately not
     * exposed to FreeMarker.
     */
    @Builder.Default
    private Map<String, String> bindings = new HashMap<>();

    // Elite: Knowledge Retrieval
    private String query;
    private String indexName;
    private Integer limit;
    private Double threshold;

    /**
     * KNOWLEDGE_RETRIEVAL: how this step is allowed to answer.
     *
     * <ul>
     * <li>{@code SINGLE} — return the single best-matching entry exactly as it
     * is stored, never merged and never reworded. What a policy, a price, a
     * legal clause or anything with approved wording needs.
     * <li>{@code COMPOSE} — when several entries match, combine them into one
     * answer. Right when a question is genuinely answered across two entries.
     * <li>{@code AUTO} (or null) — follow the platform default,
     * {@code nibras.knowledge.synthesis.enabled}.
     * </ul>
     *
     * <p>
     * Per step rather than per platform because the same assistant legitimately
     * needs both: a refund window explained across two entries should read as
     * one answer, while the clause underneath it must come back word for word.
     */
    private String answerMode;

    // Elite: Human Approval
    private String approvalMode; // SELF_CONFIRM | STAKEHOLDER
    private String stakeholders;
    private String instruction;
    private Integer timeout;

    // Elite: Logic Script
    private String code;
    private String language;

    // Elite: State Persistence
    private String variable;
    private String operation;
    private String source;

    // Elite: Timing
    private Integer duration;
    private String unit;
    private Boolean resumeOnEvent; // if true, any incoming message breaks the delay early

    // Elite: OCR / Document Insight
    private String strategy;
    private Boolean autoExtract;
    private String languageHint;
    private String pages;

    // Elite: Human Handoff
    /** HANDOFF: routing label for whoever picks the conversation up, e.g. "support". */
    private String queue;
    /** HANDOFF: context for the human, interpolated — supports {{variables}}. */
    private String note;
    /** HANDOFF: how long the author expects a human to take; advisory metadata only. */
    private Integer timeoutMinutes;

    // Discovery UI Persistence
    private String configMode;
    private String swaggerUrl;
    private String pasteDocs;
    private Object discoveredEndpoints;
    private Object discoveredVariables;
}
