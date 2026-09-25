package com.itways.assistant.journey.engine.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects variable paths that failed to resolve while a step executes, so the
 * engine can report them instead of silently substituting an empty string.
 *
 * <p>Scoping is a thread-local stack rather than a parameter threaded through
 * every handler: journey execution is a synchronous single-threaded loop
 * ({@code JourneyEngineImpl.execute}), and TRIGGER_JOURNEY nests a child run on
 * the same thread. Pushing a frame per step means a child journey's steps
 * record against their own frames, not the parent's TRIGGER_JOURNEY step.
 *
 * <p>Frames are opened and closed by the engine. When no frame is open,
 * recording is a no-op — placeholder resolution outside step execution (schema
 * previews, tests) costs nothing.
 */
public final class VariableDiagnostics {

    private VariableDiagnostics() {
    }

    private static final ThreadLocal<Deque<Set<String>>> FRAMES = new ThreadLocal<>();

    /** Begins collecting for one step. Always pair with {@link #close()}. */
    public static void open() {
        Deque<Set<String>> frames = FRAMES.get();
        if (frames == null) {
            frames = new ArrayDeque<>();
            FRAMES.set(frames);
        }
        frames.push(new LinkedHashSet<>());
    }

    /** Ends the innermost frame and returns the paths it collected. */
    public static List<String> close() {
        Deque<Set<String>> frames = FRAMES.get();
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<String> collected = new ArrayList<>(frames.pop());
        if (frames.isEmpty()) {
            FRAMES.remove();
        }
        return collected;
    }

    /** Records a path that did not resolve. No-op when no frame is open. */
    public static void recordUnresolved(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        Deque<Set<String>> frames = FRAMES.get();
        if (frames == null || frames.isEmpty()) {
            return;
        }
        frames.peek().add(path.trim());
    }

    /** Discards all frames on this thread. Guards against leaks on abnormal exit. */
    public static void reset() {
        FRAMES.remove();
    }
}
