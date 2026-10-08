/**
 * The step catalogue the journey builder reads: what step types exist and what
 * each one writes. Public: the engine fills it (every step handler describes
 * itself) and the runtime host serves it to the portal, which generates its
 * types from these class names and JSON shapes.
 *
 * <ul>
 * <li>{@link StepDefinition}: one step type (category, label, icon, whether it
 * branches, waits for input or writes state, per-channel support).
 * <li>{@link StepOutputSchema} and {@link OutputField}: the variables a step
 * makes available to later steps.
 * <li>{@link ChannelVariableSchema} and {@link ChannelVariableGroup}: the
 * {@code channel.*} variables every run has, grouped by platform.
 * </ul>
 */
package com.itways.assistant.journey.model.catalog;
