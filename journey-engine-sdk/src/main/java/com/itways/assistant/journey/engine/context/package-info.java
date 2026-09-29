/**
 * What a run knows about the turn it is on, kept out of the variables a
 * journey can read.
 *
 * <ul>
 * <li>Host-facing: the reserved start-params a host passes into a run and the
 * engine lifts into {@code ExecutionContext.internal} before the first step:
 * {@link EndUserAuth} (the end user's token), {@link Simulation} (a rehearsal),
 * {@link ChannelCapabilities} (what the channel can show) and
 * {@link ConversationParams} (the conversation the turn belongs to).
 * <li>Internal: {@link VariableContext}, the variable buckets ({@code inputs},
 * {@code steps}, {@code state}, {@code runtime}, {@code channel}) that handlers
 * read and write.
 * </ul>
 */
package com.itways.assistant.journey.engine.context;
