package com.itways.assistant.journey.model;

import java.util.*;
import java.util.function.IntFunction;

/**
 * The parent links between a journey's steps: which steps a step branches
 * from, the order the engine runs them in, and whether the links form a cycle.
 *
 * <p>
 * Pure and stateless, so the service that stores journeys (save-time
 * validation) and the engine that runs them (execution order) read the links
 * the same way.
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
     * Topological sort of steps via Kahn's algorithm, lowest step order first
     * among the steps that are ready.
     *
     * <p>
     * The ready set is a min-heap on step order, not a FIFO queue, and that is
     * load-bearing: the engine resumes and advances on a high-water mark
     * ({@code stepOrder <= currentStepIndex} is skipped), so the execution order
     * must never run a higher order before a lower one that is already runnable.
     * With a FIFO, every parentless step was queued up front, so a parentless
     * closing step (5) ran ahead of the branches of a CONDITION (3, 4) — and once
     * it had run, the high-water mark skipped both branches.
     *
     * <p>
     * When every parent has a lower order than its child — which journey-service
     * enforces at save and publish — this returns the steps in plain ascending
     * order.
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

        Queue<Integer> queue = new PriorityQueue<>();
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

    /**
     * Returns true if the parent links of these steps form a cycle (Kahn's
     * algorithm). Unlike {@link #hasCycle(int, IntFunction)}, the orders need
     * not run 1..n: the map is keyed by whatever order the caller assigns (for
     * example a step's position when its order is unset). Parent links to an
     * order that is not a key are ignored.
     *
     * @param stepsByOrder the steps, keyed by their order
     */
    public static boolean hasCycle(Map<Integer, JourneyStep> stepsByOrder) {
        Map<Integer, List<Integer>> children = new HashMap<>();
        Map<Integer, Integer>       inDegree = new HashMap<>();
        stepsByOrder.keySet().forEach(order -> inDegree.put(order, 0));

        for (Map.Entry<Integer, JourneyStep> entry : stepsByOrder.entrySet()) {
            for (Integer parent : resolveInboundParents(entry.getValue())) {
                if (parent != null && stepsByOrder.containsKey(parent)) {
                    children.computeIfAbsent(parent, k -> new ArrayList<>()).add(entry.getKey());
                    inDegree.merge(entry.getKey(), 1, Integer::sum);
                }
            }
        }

        Queue<Integer> ready = new ArrayDeque<>();
        inDegree.forEach((order, degree) -> { if (degree == 0) ready.add(order); });

        int visited = 0;
        while (!ready.isEmpty()) {
            int order = ready.poll();
            visited++;
            for (int child : children.getOrDefault(order, Collections.emptyList())) {
                if (inDegree.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        return visited < stepsByOrder.size();
    }
}
