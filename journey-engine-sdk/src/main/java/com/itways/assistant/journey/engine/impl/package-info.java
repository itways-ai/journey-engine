/**
 * Internal. {@link JourneyEngineImpl}, the run loop behind
 * {@code JourneyEngine}: step order, branching, jumps, pausing and resuming,
 * failures, nested runs and the lifecycle events sent to
 * {@code JourneyRunLifecyclePort}. Hosts use the {@code JourneyEngine}
 * interface, not this class.
 */
package com.itways.assistant.journey.engine.impl;
