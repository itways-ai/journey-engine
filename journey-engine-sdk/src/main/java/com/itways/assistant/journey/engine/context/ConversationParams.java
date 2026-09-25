package com.itways.assistant.journey.engine.context;

import java.util.Map;

/**
 * Carries the conversation this turn belongs to.
 *
 * <p>
 * A conversation sits <em>above</em> a journey run. {@code executionId} means
 * "a run is parked waiting for you" and is deleted the moment that run reaches
 * a terminal state, which is correct for resumption and useless for continuity:
 * once a journey finishes, nothing tied the next message to the one before it,
 * and every turn arrived as if the user had just walked in. This id is what
 * survives that, so a follow-up like "is it above average?" still has something
 * to refer back to.
 *
 * <p>
 * Rides in as a reserved start-param for the same reason the language does:
 * start-params are the only channel into a run, and threading a new positional
 * argument through every {@code execute} overload and every channel call site
 * is how one of them silently gets forgotten.
 *
 * <p>
 * Stripped before the engine sees the params. It is a memory partition key, not
 * something a journey author should be able to read or write — a step that
 * could reach it could reach another conversation's memory by writing a
 * different value.
 */
public final class ConversationParams {

    /**
     * Reserved inbound parameter key.
     *
     * <p>
     * Underscore-prefixed like {@link EndUserAuth#PARAM_USER_TOKEN} rather than
     * plain like {@code language}, because unlike the language this is not
     * something a caller sets to influence a journey — it is transport
     * bookkeeping that happens to travel in the same map.
     */
    public static final String PARAM_CONVERSATION_ID = "__nibras_conversation_id";

    private ConversationParams() {
    }

    /**
     * Reads and removes the id, so it cannot land in the variable map.
     *
     * @return the supplied id, or null when absent, blank or not a string
     */
    public static String take(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        Object raw = params.remove(PARAM_CONVERSATION_ID);
        if (raw == null) {
            return null;
        }
        String id = String.valueOf(raw).trim();
        return id.isEmpty() ? null : id;
    }
}
