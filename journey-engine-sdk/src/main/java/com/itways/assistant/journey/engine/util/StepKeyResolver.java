package com.itways.assistant.journey.engine.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.itways.assistant.journey.model.JourneyStep;

/**
 * Turns references by step key into references by step order, for one run.
 *
 * <p>
 * Authors — and journey-service, which converts on save — refer to steps by
 * their stable key: {@code {{steps.ask_name.output}}}, {@code steps.ask_name.}
 * in a condition, a JUMP to {@code ask_name}. Everything the engine does at
 * run time is keyed by order: step results, the variables it writes, the
 * resume pointer. Within one immutable version the key → order mapping is
 * fixed, so resolving keys to orders just before a step runs is exact, and
 * nothing downstream needs to know keys exist.
 *
 * <p>
 * Applied per step, after localisation, because a translated message can carry
 * the same placeholders as the authored one. References by order (every
 * version published before keys existed) and names that are not keys of this
 * journey are left exactly as written.
 */
public final class StepKeyResolver {

	/** {@code {{steps.<key>} } or {@code {{ steps.<key>}} — a key always starts with a letter. */
	private static final Pattern TEMPLATE_REF = Pattern.compile("(\\{\\{\\s*steps\\.)([a-z][a-z0-9_]*)(?=[.\\s}])");

	/** {@code steps.<key>.} as a SpEL path segment; the lookbehind keeps it off longer identifiers. */
	private static final Pattern SPEL_REF = Pattern.compile("(?<![\\w.])(steps\\.)([a-z][a-z0-9_]*)(?=\\.)");

	private static final String JUMP = "JUMP";

	private static final StepKeyResolver NONE = new StepKeyResolver(Collections.emptyMap());

	private final Map<String, Integer> orderByKey;

	private StepKeyResolver(Map<String, Integer> orderByKey) {
		this.orderByKey = orderByKey;
	}

	/** The resolver for one journey's steps; a no-op when none of them has a key. */
	public static StepKeyResolver of(List<JourneyStep> steps) {
		if (steps == null || steps.isEmpty()) {
			return NONE;
		}
		Map<String, Integer> orderByKey = new HashMap<>();
		for (JourneyStep step : steps) {
			if (step != null && step.getStepKey() != null && !step.getStepKey().isBlank()) {
				orderByKey.putIfAbsent(step.getStepKey(), step.getStepOrder());
			}
		}
		return orderByKey.isEmpty() ? NONE : new StepKeyResolver(orderByKey);
	}

	/** The order a key names in this journey, or null. */
	public Integer orderOf(String key) {
		return key == null ? null : orderByKey.get(key.trim());
	}

	/**
	 * {@code step} with every key reference replaced by the order it names; the
	 * same instance when there is nothing to replace.
	 */
	public JourneyStep resolve(JourneyStep step) {
		if (step == null || orderByKey.isEmpty()) {
			return step;
		}
		String message = template(step.getMessage());
		String apiConfig = template(step.getApiConfig());
		String condition = spel(step.getConditionExpression());
		String actionTarget = JUMP.equalsIgnoreCase(step.getActionType())
				? jumpTarget(step.getActionTarget())
				: template(step.getActionTarget());
		if (same(message, step.getMessage()) && same(apiConfig, step.getApiConfig())
				&& same(condition, step.getConditionExpression()) && same(actionTarget, step.getActionTarget())) {
			return step;
		}
		JourneyStep copy = copyOf(step);
		copy.setMessage(message);
		copy.setApiConfig(apiConfig);
		copy.setConditionExpression(condition);
		copy.setActionTarget(actionTarget);
		return copy;
	}

	private String jumpTarget(String target) {
		Integer order = orderOf(target);
		return order == null ? target : String.valueOf(order);
	}

	private String template(String text) {
		return replace(text, TEMPLATE_REF);
	}

	private String spel(String text) {
		return replace(text, SPEL_REF);
	}

	private String replace(String text, Pattern pattern) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		Matcher matcher = pattern.matcher(text);
		if (!matcher.find()) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length());
		do {
			Integer order = orderByKey.get(matcher.group(2));
			String replacement = order == null ? matcher.group(0) : matcher.group(1) + order;
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		} while (matcher.find());
		matcher.appendTail(out);
		return out.toString();
	}

	private static boolean same(String a, String b) {
		return a == null ? b == null : a.equals(b);
	}

	private static JourneyStep copyOf(JourneyStep step) {
		return JourneyStep.builder()
				.id(step.getId())
				.stepKey(step.getStepKey())
				.stepOrder(step.getStepOrder())
				.stepName(step.getStepName())
				.actionType(step.getActionType())
				.actionTarget(step.getActionTarget())
				.requiredParams(step.getRequiredParams())
				.apiConfig(step.getApiConfig())
				.continueOnError(step.isContinueOnError())
				.conditionExpression(step.getConditionExpression())
				.branchName(step.getBranchName())
				.parentOrder(step.getParentOrder())
				.parentOrders(step.getParentOrders())
				.message(step.getMessage())
				.clientVisible(step.getClientVisible())
				.build();
	}
}
