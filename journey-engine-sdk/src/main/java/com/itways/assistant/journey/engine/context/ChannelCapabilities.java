package com.itways.assistant.journey.engine.context;

import java.util.HashMap;
import java.util.Map;

import com.itways.assistant.journey.engine.model.ExecutionContext;

/**
 * What the channel a run is on can physically do.
 *
 * <p>
 * A web widget renders a form, opens a link and takes a file; a phone call
 * can do none of those. Handlers that would otherwise produce something the
 * channel cannot deliver ask here first — {@code USER_INPUT} collects a
 * multi-field form one question at a time when there is no form to show.
 *
 * <p>
 * Carried in {@code internal}, the way {@link Simulation} is, and for the same
 * reason: a journey must not be able to read or branch on it. A channel that
 * declares nothing is taken to be able to do everything, which keeps every
 * existing caller — the widget, the console, the eval harness — exactly as it
 * was.
 */
public final class ChannelCapabilities {

	/** Reserved start-param: a map of capability name to boolean. */
	public static final String PARAM_CAPABILITIES = "__nibras_channel_capabilities";

	/** Key under which the map lives in {@link ExecutionContext#getInternal()}. */
	public static final String INTERNAL_CAPABILITIES = "channel.capabilities";

	/** Can show a multi-field form and take it back in one reply. */
	public static final String FORM = "form";
	/** Can present a link the user can open. */
	public static final String LINKS = "links";
	/** Can accept a file from the user. */
	public static final String ATTACHMENTS = "attachments";
	/** Can hand the conversation to a person in real time. */
	public static final String TRANSFER = "transfer";

	private ChannelCapabilities() {
	}

	/**
	 * Moves the declaration from the author-visible variable map into engine
	 * internals. Idempotent, and a no-op when nothing was declared.
	 */
	@SuppressWarnings("unchecked")
	public static void lift(ExecutionContext context, Map<String, Object> params) {
		Object declared = context.getVariables().remove(PARAM_CAPABILITIES);
		if (!(declared instanceof Map) && params != null) {
			declared = params.get(PARAM_CAPABILITIES);
		}
		if (declared instanceof Map<?, ?> map) {
			Map<String, Object> copy = new HashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				copy.put(String.valueOf(entry.getKey()), isTrue(entry.getValue()));
			}
			context.setInternal(INTERNAL_CAPABILITIES, copy);
		}
		context.getVariables().remove(PARAM_CAPABILITIES);
		if (params != null) {
			params.remove(PARAM_CAPABILITIES);
		}
	}

	/** The declaration for this run; empty when the channel declared nothing. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> of(ExecutionContext context) {
		Object value = context != null ? context.getInternal(INTERNAL_CAPABILITIES) : null;
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	/**
	 * Whether the channel can do {@code capability}. True when the channel
	 * declared nothing at all, and true when it declared other things but not
	 * this one — a declaration is a list of what is <em>missing</em>.
	 */
	public static boolean supports(ExecutionContext context, String capability) {
		Object value = of(context).get(capability);
		return value == null || isTrue(value);
	}

	public static boolean supportsForm(ExecutionContext context) {
		return supports(context, FORM);
	}

	/** Passes the parent's declaration to a triggered child, which runs on the same channel. */
	public static void inherit(Map<String, Object> childParams, ExecutionContext parent) {
		Map<String, Object> declared = of(parent);
		if (!declared.isEmpty()) {
			childParams.put(PARAM_CAPABILITIES, new HashMap<>(declared));
		}
	}

	private static boolean isTrue(Object raw) {
		return raw instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(raw));
	}
}
