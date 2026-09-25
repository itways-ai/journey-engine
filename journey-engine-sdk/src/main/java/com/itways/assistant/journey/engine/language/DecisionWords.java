package com.itways.assistant.journey.engine.language;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reading "yes" or "no" out of whatever a person actually typed.
 *
 * <p>
 * Extracted from {@code HumanApprovalStepHandler}, which owned it alone until a
 * second step needed to ask a yes/no question. Two copies of this would be two
 * copies of the vocabulary, and the failure that produces is subtle: a
 * translation added to one list and not the other means "موافق" approves a
 * deletion but does not confirm a pre-filled answer, in the same conversation,
 * for no reason a user could ever discover.
 *
 * <p>
 * Words are pooled across every supported language rather than filtered by the
 * conversation's own. People answer a prompt in whatever is fastest to type;
 * "ok" lands in Arabic threads constantly, and refusing it because the thread is
 * Arabic would be a regression dressed up as correctness. Sourcing them from the
 * message bundles means a new language brings its vocabulary with its
 * translation file and no code changes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DecisionWords {

	private final Set<String> affirmative = new HashSet<>();
	private final Set<String> negative = new HashSet<>();

	private final EngineMessages messages;

	/**
	 * Loads the vocabulary. Spring calls this; tests call it by hand, because
	 * {@code @PostConstruct} does not run outside a container and an unloaded
	 * instance reads every answer as unclear.
	 */
	@jakarta.annotation.PostConstruct
	public void load() {
		for (ConversationLanguage language : ConversationLanguage.values()) {
			collectInto(affirmative, messages.get(language, "step.approval.approveWords"));
			collectInto(negative, messages.get(language, "step.approval.rejectWords"));
		}
		// A word claimed by both lists is a translation bug, and silently letting
		// the affirmative win would mean a refusal performing the action it
		// refused. Dropping it from both makes the answer "unclear", which
		// re-prompts.
		Set<String> ambiguous = new HashSet<>(affirmative);
		ambiguous.retainAll(negative);
		if (!ambiguous.isEmpty()) {
			log.error("{} appear as both affirmative and negative; treating them as unclear", ambiguous);
			affirmative.removeAll(ambiguous);
			negative.removeAll(ambiguous);
		}
	}

	private static void collectInto(Set<String> target, String commaSeparated) {
		if (commaSeparated == null || commaSeparated.isBlank()) {
			return;
		}
		for (String word : commaSeparated.split(",")) {
			String trimmed = word.trim().toLowerCase();
			if (!trimmed.isEmpty()) {
				target.add(trimmed);
			}
		}
	}

	/**
	 * TRUE, FALSE, or null when the answer is neither.
	 *
	 * <p>
	 * Null is the important one and the reason this does not default: a step that
	 * read anything non-null as agreement is how "no" once granted the approval it
	 * was refusing. Callers re-prompt on null rather than guess.
	 *
	 * @param answer a Boolean from a structured form reply, or free text from a
	 *               channel
	 */
	public Boolean interpret(Object answer) {
		if (answer instanceof Boolean bool) {
			return bool;
		}
		if (answer == null) {
			return null;
		}
		String normalized = String.valueOf(answer).trim().toLowerCase();
		// Strip trailing punctuation so "yes!" and "no." still read as decisions.
		normalized = normalized.replaceAll("[\\p{Punct}\\s]+$", "");
		if (normalized.isEmpty()) {
			return null;
		}
		if (affirmative.contains(normalized)) {
			return Boolean.TRUE;
		}
		if (negative.contains(normalized)) {
			return Boolean.FALSE;
		}
		return null;
	}
}
