package com.itways.assistant.journey.engine.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.model.JourneyStep;

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
}
