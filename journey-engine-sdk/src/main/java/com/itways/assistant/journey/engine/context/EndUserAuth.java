package com.itways.assistant.journey.engine.context;

import java.util.Map;

import com.itways.assistant.journey.engine.model.ExecutionContext;

/**
 * Carries the signed-in end user's token for the duration of one run.
 *
 * <p>
 * When a journey calls a customer API "as the user", the credential has to
 * reach {@code API_CALL} without ever becoming a journey variable. Variables
 * are serialised wholesale into run history, the journey variable picker, the
 * {@code CODE_SCRIPT} sandbox and {@code DATA_MAP}'s LLM prompt — see the
 * contract on {@link ExecutionContext#getInternal()}. A live bearer token must
 * travel none of those paths.
 *
 * <p>
 * So the token is lifted out of the inbound params into the context's
 * non-addressable {@code internal} map, exactly as the trigger stack is, and is
 * re-exposed only where it is needed: {@code API_CALL} request headers, under
 * the reserved {@code auth.userToken} placeholder.
 *
 * <p>
 * Deliberately <em>not</em> resolvable in a step's URL, body, or any other step
 * type. URLs are logged; bodies are persisted with the run.
 */
public final class EndUserAuth {

	/**
	 * Reserved inbound parameter key. Set by the channel layer from the
	 * {@code X-Nibras-User-Token} request header, then removed from the variable
	 * map before the first step runs.
	 */
	public static final String PARAM_USER_TOKEN = "__nibras_user_token";

	/** Key under which the token lives in {@link ExecutionContext#getInternal()}. */
	public static final String INTERNAL_USER_TOKEN = "auth.userToken";

	/** Placeholder namespace journey authors write: {@code {{auth.userToken}}}. */
	public static final String SCOPE = "auth";

	public static final String FIELD_USER_TOKEN = "userToken";

	private EndUserAuth() {
	}

	/**
	 * Moves the token from the author-visible variable map into engine internals.
	 * Idempotent, and a no-op when no token was supplied (anonymous runs).
	 */
	public static void lift(ExecutionContext context, Map<String, Object> params) {
		Object token = context.getVariables().remove(PARAM_USER_TOKEN);
		if (token == null && params != null) {
			token = params.get(PARAM_USER_TOKEN);
		}
		if (token instanceof String s && !s.isBlank()) {
			context.setInternal(INTERNAL_USER_TOKEN, s);
		}
		// Strip the raw key from every surface a journey author can reach.
		context.getVariables().remove(PARAM_USER_TOKEN);
		if (params != null) {
			params.remove(PARAM_USER_TOKEN);
		}
	}

	/** The token for this run, or {@code null} when the user is anonymous. */
	public static String token(ExecutionContext context) {
		Object value = context.getInternal(INTERNAL_USER_TOKEN);
		return value instanceof String s && !s.isBlank() ? s : null;
	}
}
