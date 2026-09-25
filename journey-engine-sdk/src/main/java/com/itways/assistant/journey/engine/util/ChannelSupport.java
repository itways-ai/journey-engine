package com.itways.assistant.journey.engine.util;

import java.util.Map;

import com.itways.assistant.journey.engine.model.StepDefinition;

/**
 * How well each step type works on each kind of channel — the console's
 * source for the badge an author sees before publishing.
 *
 * <p>
 * Kept in one table rather than on each handler because the answer is about
 * the pairing, not the step: {@code REDIRECT} is native on a screen and
 * adapted (the link is texted) on a call. Three levels, deliberately coarse:
 * {@code NATIVE} works as authored; {@code ADAPTED} works, differently — the
 * runtime substitutes something the channel can do; {@code UNSUPPORTED} cannot
 * run and the user is told so.
 */
public final class ChannelSupport {

	public static final String VOICE = "voice";

	public static final String NATIVE = "NATIVE";
	public static final String ADAPTED = "ADAPTED";
	public static final String UNSUPPORTED = "UNSUPPORTED";

	private static final Map<String, String> VOICE_SUPPORT = Map.ofEntries(
			Map.entry("RESPONSE", NATIVE),
			Map.entry("CONDITION", NATIVE),
			Map.entry("SWITCH", NATIVE),
			Map.entry("JUMP", NATIVE),
			Map.entry("API_CALL", NATIVE),
			Map.entry("DATA_MAP", NATIVE),
			Map.entry("CODE_SCRIPT", NATIVE),
			Map.entry("KNOWLEDGE_RETRIEVAL", NATIVE),
			Map.entry("SEND_MAIL", NATIVE),
			Map.entry("TRIGGER_JOURNEY", NATIVE),
			Map.entry("STATE_STORE", NATIVE),
			Map.entry("TEMPLATE_RENDER", NATIVE),
			// A multi-field form is asked one field at a time; a file field cannot be.
			Map.entry("USER_INPUT", ADAPTED),
			// Self-confirmation is a spoken yes/no; a stakeholder gate ends the call.
			Map.entry("HUMAN_APPROVAL", ADAPTED),
			// The call is transferred to the channel's handoff number.
			Map.entry("HANDOFF", ADAPTED),
			// The link is texted to the caller.
			Map.entry("REDIRECT", ADAPTED),
			// Short waits are held on the line; long ones park the run.
			Map.entry("DELAY", ADAPTED),
			// Needs a document nobody can hand over on a call.
			Map.entry("DOCUMENT_INSIGHT", UNSUPPORTED));

	private ChannelSupport() {
	}

	/** Voice support for a step type; a type this table has never heard of is NATIVE. */
	public static String voice(String stepType) {
		return VOICE_SUPPORT.getOrDefault(stepType, NATIVE);
	}

	/** Fills {@code channels} on a definition the handler described. */
	public static StepDefinition annotate(StepDefinition definition) {
		if (definition == null) {
			return null;
		}
		definition.setChannels(Map.of(VOICE, voice(definition.getType())));
		return definition;
	}
}
