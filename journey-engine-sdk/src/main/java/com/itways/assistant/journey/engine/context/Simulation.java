package com.itways.assistant.journey.engine.context;

import java.util.Map;

import com.itways.assistant.journey.engine.model.ExecutionContext;

/**
 * Marks a run as a rehearsal: real logic, no consequences.
 *
 * <p>
 * Until this existed the only way to test a journey was to run it for real —
 * real calls to the customer's systems, real mail, real rows in run history.
 * Authors were debugging in production against live hosts, which is why the
 * builder had no test button: there was nothing safe to point one at.
 *
 * <p>
 * <b>What simulation changes, and what it deliberately does not.</b> Only the
 * steps that reach outside the conversation are stubbed — {@code API_CALL} and
 * {@code SEND_MAIL}. Everything else runs exactly as it would in production:
 * conditions branch on real data, scripts execute, templates render, knowledge
 * retrieval searches the real index, and {@code DATA_MAP} really calls the
 * model. A simulator that fakes the thinking would tell an author nothing about
 * whether their journey works.
 *
 * <p>
 * The flag lives in {@link ExecutionContext#getInternal()} rather than among
 * the variables, for the same reason the end-user token does: everything in the
 * variable map is serialised into run history, the variable picker, the script
 * sandbox and the {@code DATA_MAP} prompt. A journey author must not be able to
 * read {@code {{simulate}}} and branch on it — a journey that behaves
 * differently under test is a journey the test says nothing about.
 *
 * <p>
 * It rides {@code internal}, so it survives a WAITING park and every turn of a
 * multi-turn rehearsal, and {@code TRIGGER_JOURNEY} copies the child's
 * {@code internal} wholesale — so a simulated parent cannot spawn a child that
 * sends real mail.
 */
public final class Simulation {

    /**
     * Reserved inbound parameter. Set by the console's simulator panel, then
     * lifted out of the variable map before the first step runs.
     */
    public static final String PARAM_SIMULATE = "simulate";

    /** Key under which the flag lives in {@link ExecutionContext#getInternal()}. */
    public static final String INTERNAL_SIMULATE = "_simulate";

    /**
     * Step-view marker. Set by a handler that stubbed itself, so the console can
     * show which steps were pretend and the reply is never mistaken for proof
     * that a real API call succeeded.
     */
    public static final String META_SIMULATED = "simulated";

    private Simulation() {
    }

    /**
     * Moves the flag from the author-visible variable map into engine internals.
     * Idempotent, and a no-op for ordinary runs.
     */
    public static void lift(ExecutionContext context, Map<String, Object> params) {
        Object flag = context.getVariables().remove(PARAM_SIMULATE);
        if (!isTrue(flag) && params != null) {
            flag = params.get(PARAM_SIMULATE);
        }
        if (isTrue(flag)) {
            context.setInternal(INTERNAL_SIMULATE, true);
        }
        // Strip the raw key from every surface a journey author can reach, so a
        // journey can neither read nor branch on being under test.
        context.getVariables().remove(PARAM_SIMULATE);
        if (params != null) {
            params.remove(PARAM_SIMULATE);
        }
    }

    /** Whether this run is a rehearsal. */
    public static boolean isActive(ExecutionContext context) {
        return context != null && Boolean.TRUE.equals(context.getInternal(INTERNAL_SIMULATE));
    }

    /**
     * The console sends JSON, so the flag arrives as a boolean; a channel or a
     * hand-written request may send the string. Anything else is not a request
     * to simulate — defaulting to "real" is the safe direction.
     */
    private static boolean isTrue(Object raw) {
        return raw instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(raw));
    }
}
