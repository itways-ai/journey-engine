package com.itways.assistant.journey.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;


class JourneyStepGraphTest {

    private static JourneyStep step(int order, Integer parent) {
        return JourneyStep.builder().stepOrder(order).actionType("RESPONSE").parentOrder(parent).build();
    }

    private static List<Integer> orders(List<JourneyStep> steps) {
        return steps.stream().map(JourneyStep::getStepOrder).toList();
    }

    /**
     * The shape that broke in 1.0.14: a parentless closing step after a
     * CONDITION's branches. A FIFO sort ran it ahead of the branches, and the
     * engine's high-water mark then skipped both branches.
     */
    @Test
    void parentlessStepAfterBranchesRunsAfterThem() {
        List<JourneyStep> steps = List.of(step(1, null), step(2, null), step(3, 2), step(4, 2), step(5, null));
        assertThat(orders(JourneyStepGraph.sortSteps(steps))).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void readyStepsAlwaysRunLowestOrderFirst() {
        List<JourneyStep> steps = List.of(step(5, null), step(3, 1), step(1, null), step(4, 3), step(2, null));
        assertThat(orders(JourneyStepGraph.sortSteps(steps))).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void rejoinWaitsForEveryParentItHas() {
        JourneyStep rejoin = JourneyStep.builder().stepOrder(5).actionType("RESPONSE").parentOrders(List.of(3, 4)).build();
        List<JourneyStep> steps = List.of(step(1, null), step(2, 1), step(3, 2), step(4, 2), rejoin);
        List<Integer> sorted = orders(JourneyStepGraph.sortSteps(steps));
        assertThat(sorted.indexOf(5)).isGreaterThan(sorted.indexOf(3)).isGreaterThan(sorted.indexOf(2));
        assertThat(sorted.indexOf(5)).isGreaterThan(sorted.indexOf(4));
    }

    @Test
    void cycleIsRefused() {
        List<JourneyStep> steps = List.of(step(1, 2), step(2, 1));
        assertThatThrownBy(() -> JourneyStepGraph.sortSteps(steps)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void parentOrdersWinOverParentOrder() {
        JourneyStep rejoin = JourneyStep.builder().stepOrder(5).parentOrder(2).parentOrders(List.of(3, 4)).build();
        assertThat(JourneyStepGraph.resolveInboundParents(rejoin)).containsExactly(3, 4);
        assertThat(JourneyStepGraph.isRejoinStep(rejoin)).isTrue();
    }

    @Test
    void aStepWithoutAPositiveParentHasNoParents() {
        assertThat(JourneyStepGraph.resolveInboundParents(step(1, null))).isEmpty();
        assertThat(JourneyStepGraph.resolveInboundParents(step(1, 0))).isEmpty();
        assertThat(JourneyStepGraph.resolveInboundParents(step(2, 1))).containsExactly(1);
        assertThat(JourneyStepGraph.isRejoinStep(step(2, 1))).isFalse();
    }

    @Test
    void hasCycleOverOrdersOneToN() {
        Map<Integer, List<Integer>> parents = Map.of(1, List.of(), 2, List.of(1), 3, List.of(2));
        assertThat(JourneyStepGraph.hasCycle(3, parents::get)).isFalse();

        Map<Integer, List<Integer>> looped = Map.of(1, List.of(3), 2, List.of(1), 3, List.of(2));
        assertThat(JourneyStepGraph.hasCycle(3, looped::get)).isTrue();
    }

    @Test
    void hasCycleByOrderAcceptsGapsInTheNumbering() {
        Map<Integer, JourneyStep> steps = new LinkedHashMap<>();
        steps.put(1, step(1, null));
        steps.put(5, step(5, 1));
        steps.put(10, step(10, 5));
        assertThat(JourneyStepGraph.hasCycle(steps)).isFalse();

        steps.put(1, step(1, 10));
        assertThat(JourneyStepGraph.hasCycle(steps)).isTrue();
    }

    @Test
    void hasCycleByOrderIgnoresLinksToStepsThatAreNotThere() {
        Map<Integer, JourneyStep> steps = new LinkedHashMap<>();
        steps.put(1, step(1, 99));
        steps.put(2, JourneyStep.builder().stepOrder(2).parentOrders(List.of(1, 42)).build());
        assertThat(JourneyStepGraph.hasCycle(steps)).isFalse();
    }

    @Test
    void hasCycleByOrderSeesARejoinLoop() {
        Map<Integer, JourneyStep> steps = new LinkedHashMap<>();
        steps.put(1, step(1, null));
        steps.put(2, JourneyStep.builder().stepOrder(2).parentOrders(List.of(1, 3)).build());
        steps.put(3, step(3, 2));
        assertThat(JourneyStepGraph.hasCycle(steps)).isTrue();
    }
}
