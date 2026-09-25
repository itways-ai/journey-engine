package com.itways.assistant.journey.engine.util;

import com.itways.assistant.journey.model.JourneyStep;

import java.util.*;
import java.util.function.IntFunction;

/**
 * Stateless graph utilities for JourneyStep ordering and cycle detection.
 * Used by both the engine (execution sorting) and the service (save-time validation).
 */
public final class JourneyStepGraph {

    private JourneyStepGraph() {}

    /**
     * Returns the canonical list of parent step-orders for a step,
     * preferring {@code parentOrders} (multi-parent rejoin) over the legacy {@code parentOrder}.
     */
    public static List<Integer> resolveInboundParents(JourneyStep step) {
        if (step.getParentOrders() != null && !step.getParentOrders().isEmpty()) {
            return step.getParentOrders();
        }
        if (step.getParentOrder() != null && step.getParentOrder() > 0) {
            return List.of(step.getParentOrder());
        }
        return Collections.emptyList();
    }

    /** Returns true if this step waits for any of multiple parent branches (rejoin node). */
    public static boolean isRejoinStep(JourneyStep step) {
        return step.getParentOrders() != null && !step.getParentOrders().isEmpty();
    }

    /**
     * Topological sort of steps via Kahn's BFS algorithm.
     *
     * @throws IllegalStateException if the step graph contains a cycle
     */
    public static List<JourneyStep> sortSteps(List<JourneyStep> steps) {
        Map<Integer, JourneyStep>    stepMap   = new HashMap<>();
        Map<Integer, List<Integer>>  adj       = new HashMap<>();
        Map<Integer, Integer>        inDegree  = new HashMap<>();

        for (JourneyStep s : steps) {
            stepMap.put(s.getStepOrder(), s);
            inDegree.put(s.getStepOrder(), 0);
        }

        for (JourneyStep s : steps) {
            for (Integer parent : resolveInboundParents(s)) {
                if (parent == null || parent <= 0 || !stepMap.containsKey(parent)) continue;
                adj.computeIfAbsent(parent, k -> new ArrayList<>()).add(s.getStepOrder());
                inDegree.merge(s.getStepOrder(), 1, Integer::sum);
            }
        }

        Queue<Integer> queue = new LinkedList<>();
        for (JourneyStep s : steps) {
            if (inDegree.getOrDefault(s.getStepOrder(), 0) == 0) {
                queue.add(s.getStepOrder());
            }
        }

        List<Integer> sortedOrders = new ArrayList<>();
        while (!queue.isEmpty()) {
            int node = queue.poll();
            sortedOrders.add(node);
            for (int child : adj.getOrDefault(node, Collections.emptyList())) {
                if (inDegree.merge(child, -1, Integer::sum) == 0) {
                    queue.add(child);
                }
            }
        }

        if (sortedOrders.size() < steps.size()) {
            List<Integer> cycleNodes = new ArrayList<>();
            for (JourneyStep s : steps) {
                if (!sortedOrders.contains(s.getStepOrder())) {
                    cycleNodes.add(s.getStepOrder());
                }
            }
            throw new IllegalStateException(
                    "Journey step graph contains a cycle involving step order(s): " + cycleNodes);
        }

        List<JourneyStep> result = new ArrayList<>();
        for (int order : sortedOrders) {
            result.add(stepMap.get(order));
        }
        return result;
    }

    /**
     * Returns true if the step graph contains a cycle (detected via Kahn's algorithm).
     *
     * @param stepCount       total number of steps (orders 1..stepCount)
     * @param inboundResolver given a step order, returns its parent orders
     */
    public static boolean hasCycle(int stepCount, IntFunction<List<Integer>> inboundResolver) {
        Map<Integer, List<Integer>> adj      = new HashMap<>();
        Map<Integer, Integer>       inDegree = new HashMap<>();

        for (int i = 1; i <= stepCount; i++) {
            inDegree.put(i, 0);
        }

        for (int child = 1; child <= stepCount; child++) {
            for (Integer parent : inboundResolver.apply(child)) {
                if (parent == null || parent <= 0 || parent > stepCount) continue;
                adj.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
                inDegree.merge(child, 1, Integer::sum);
            }
        }

        Queue<Integer> queue = new LinkedList<>();
        inDegree.forEach((k, v) -> { if (v == 0) queue.add(k); });

        int visited = 0;
        while (!queue.isEmpty()) {
            int node = queue.poll();
            visited++;
            for (int child : adj.getOrDefault(node, Collections.emptyList())) {
                if (inDegree.merge(child, -1, Integer::sum) == 0) {
                    queue.add(child);
                }
            }
        }

        return visited < stepCount;
    }
}
