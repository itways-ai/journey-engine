package com.itways.assistant.journey.engine.language;

import java.util.Map;

import com.itways.assistant.journey.engine.model.ExecutionContext;

/**
 * Carries the resolved conversation language into a run.
 *
 * <p>
 * Start-params are the only channel into a run — that is true for a top-level
 * turn arriving from a controller and for a child run started by
 * {@code TRIGGER_JOURNEY} alike — so the language rides in as a reserved param
 * and is lifted onto the context before the first step executes.
 *
 * <p>
 * Lifted rather than left in the variable map because the two would then
 * disagree. {@code runtime.language} is published from the context, and an
 * author-writable {@code language} entry sitting in {@code inputs.entities}
 * would look authoritative while changing nothing.
 */
public final class LanguageParams {

    /**
     * Reserved inbound parameter key.
     *
     * <p>
     * Named plainly because it is part of the public start-params surface: the
     * web SDK and the channel layer both set it, and a caller reading a journey
     * run's parameters should recognise it. It is listed in
     * {@code VariableContext.RESERVED_KEYS} so it never lands in
     * {@code inputs.entities}.
     */
    public static final String PARAM_LANGUAGE = "language";

    private LanguageParams() {
    }

    /**
     * Moves the language from inbound params onto the context.
     *
     * <p>
     * A no-op when nothing was supplied, which leaves an already-resolved
     * language in place. That is what makes a resumed run keep the language it
     * was started in even if the caller says nothing this turn.
     */
    public static void lift(ExecutionContext context, Map<String, Object> params) {
        Object supplied = context.getVariables().remove(PARAM_LANGUAGE);
        if (supplied == null && params != null) {
            supplied = params.get(PARAM_LANGUAGE);
        }

        ConversationLanguage parsed = supplied instanceof ConversationLanguage language
                ? language
                : ConversationLanguage.parse(supplied == null ? null : String.valueOf(supplied));

        if (parsed != null) {
            context.setLanguage(parsed);
        }

        context.getVariables().remove(PARAM_LANGUAGE);
        if (params != null) {
            params.remove(PARAM_LANGUAGE);
        }
    }

    /** Start-param value for a child run, so nested journeys speak the parent's language. */
    public static void inherit(Map<String, Object> childParams, ExecutionContext parent) {
        childParams.put(PARAM_LANGUAGE, parent.resolvedLanguage().code());
    }
}
