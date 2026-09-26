package com.itways.assistant.journey.engine.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.itways.assistant.journey.model.JourneyStep;

class StepKeyResolverTest {

    private final JourneyStep ask = JourneyStep.builder().id(10L).stepKey("ask_name").stepOrder(1).actionType("USER_INPUT").build();
    private final JourneyStep lookup = JourneyStep.builder().id(11L).stepKey("get_order").stepOrder(2).actionType("API_CALL").build();

    @Test
    void placeholdersByKeyBecomePlaceholdersByOrder() {
        JourneyStep reply = JourneyStep.builder().stepKey("reply").stepOrder(3).actionType("RESPONSE")
                .actionTarget("Hi {{steps.ask_name.output.name}}, order {{ steps.get_order.output.id }}")
                .message("{{steps.get_order}}")
                .apiConfig("{\"body\":\"{{steps.ask_name.output}}\"}")
                .build();
        JourneyStep resolved = StepKeyResolver.of(List.of(ask, lookup, reply)).resolve(reply);
        assertThat(resolved.getActionTarget()).isEqualTo("Hi {{steps.1.output.name}}, order {{ steps.2.output.id }}");
        assertThat(resolved.getMessage()).isEqualTo("{{steps.2}}");
        assertThat(resolved.getApiConfig()).isEqualTo("{\"body\":\"{{steps.1.output}}\"}");
        assertThat(resolved.getStepKey()).isEqualTo("reply");
    }

    @Test
    void conditionPathsAndJumpTargetsResolveToo() {
        JourneyStep check = JourneyStep.builder().stepKey("check").stepOrder(3).actionType("CONDITION")
                .conditionExpression("steps.get_order.output.status == 'OPEN' and mysteps.get_order.x == 1").build();
        JourneyStep back = JourneyStep.builder().stepKey("back").stepOrder(4).actionType("JUMP").actionTarget("ask_name").build();
        StepKeyResolver keys = StepKeyResolver.of(List.of(ask, lookup, check, back));
        assertThat(keys.resolve(check).getConditionExpression())
                .isEqualTo("steps.2.output.status == 'OPEN' and mysteps.get_order.x == 1");
        assertThat(keys.resolve(back).getActionTarget()).isEqualTo("1");
    }

    @Test
    void referencesByOrderAndUnknownNamesAreLeftAlone() {
        JourneyStep reply = JourneyStep.builder().stepKey("reply").stepOrder(3).actionType("RESPONSE")
                .actionTarget("{{steps.1.output}} {{steps.nobody.output}} {{inputs.text}}").build();
        JourneyStep resolved = StepKeyResolver.of(List.of(ask, lookup, reply)).resolve(reply);
        assertThat(resolved).isSameAs(reply);
    }

    @Test
    void versionsWithoutKeysAreUntouched() {
        JourneyStep legacy = JourneyStep.builder().stepOrder(1).actionType("RESPONSE").actionTarget("{{steps.1.output}}").build();
        assertThat(StepKeyResolver.of(List.of(legacy)).resolve(legacy)).isSameAs(legacy);
    }
}
