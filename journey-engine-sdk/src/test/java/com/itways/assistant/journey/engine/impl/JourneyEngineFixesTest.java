package com.itways.assistant.journey.engine.impl;

import static com.itways.assistant.journey.engine.impl.EngineFixture.context;
import static com.itways.assistant.journey.engine.impl.EngineFixture.journey;
import static com.itways.assistant.journey.engine.impl.EngineFixture.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.engine.context.Simulation;
import com.itways.assistant.journey.engine.handler.TriggerJourneyStepHandler;
import com.itways.assistant.journey.engine.service.JourneyLookupPort;
import com.itways.assistant.journey.model.JourneyDefinition;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for engine defects found while writing the LIB-04 tests.
 * Each fails on the unfixed code and passes with the fix beside it.
 */
class JourneyEngineFixesTest {

    private final EngineFixture f = new EngineFixture();

    /**
     * A step that ran and produced null (a SWITCH on a missing value, an API_CALL
     * answering 204, a RESPONSE with no text) was read as "never ran": every
     * child under it was skipped, the SWITCH's DEFAULT branch included.
     */
    @Test
    void aSwitchWithNoValueTakesTheDefaultBranch() {
        JourneyDefinition journey = journey("PLAN",
                step(1, "SWITCH").conditionExpression("inputs.entities.plan"),
                step(2, "RESPONSE").actionTarget("gold desk").parentOrder(1).branchName("gold"),
                step(3, "RESPONSE").actionTarget("general desk").parentOrder(1).branchName("DEFAULT"));

        assertThat(f.start(journey, Map.of()).get("message")).isEqualTo("general desk");
    }

    @Test
    void theChildOfAStepWithANullOutputStillRuns() {
        JourneyDefinition journey = journey("J", step(1, "RESPONSE"), step(2, "RECORD").parentOrder(1));

        f.start(journey, Map.of());

        assertThat(f.executed).containsExactly("s2");
    }

    /**
     * The console's rehearsal flag was copied into inputs.entities before it was
     * lifted, so a journey could read {{inputs.entities.simulate}} and branch on
     * being under test.
     */
    @Test
    void aJourneyCannotSeeThatItIsBeingRehearsed() {
        JourneyDefinition journey = journey("J",
                step(1, "CONDITION").conditionExpression("inputs.entities.simulate == true"),
                step(2, "RECORD").parentOrder(1).branchName("true"),
                step(3, "RECORD"));
        Map<String, Object> params = new HashMap<>();
        params.put(Simulation.PARAM_SIMULATE, true);

        Map<String, Object> result = f.start(journey, params);

        assertThat(Simulation.isActive(context(result))).isTrue();
        assertThat(f.executed).containsExactly("s3");
        assertThat(f.json.valueToTree(context(result).getVariables()).toString()).doesNotContain("simulate");
    }

    /**
     * The answer that resumed a parked child journey stayed in the parent's
     * inputs, so the parent's next USER_INPUT took it as its own answer and never
     * asked its question.
     */
    @Test
    void theAnswerThatResumedAChildIsNotReusedByTheParent() {
        Map<String, JourneyDefinition> journeys = new HashMap<>();
        JourneyLookupPort lookup = new JourneyLookupPort() {
            @Override
            public JourneyDefinition findByTriggerIntent(String accountId, UUID assistantId, String intent) {
                return journeys.get(intent);
            }

            @Override
            public JourneyDefinition findByVersionId(String accountId, Long versionId) {
                return journeys.values().stream().filter(j -> versionId.equals(j.getVersionId())).findFirst()
                        .orElse(null);
            }
        };
        f.with(new TriggerJourneyStepHandler(lookup, f.utils, f.variables, f.schemas, f.engine, f.messages));
        JourneyDefinition child = journey("ORDER", step(1, "USER_INPUT").message("Which order?"));
        child.setVersionId(20L);
        JourneyDefinition main = journey("MAIN", step(1, "TRIGGER_JOURNEY").actionTarget("ORDER"),
                step(2, "USER_INPUT").message("Delivery address?"),
                step(3, "RESPONSE").actionTarget("{{steps.1.output}} to {{steps.2.output}}"));
        journeys.put("ORDER", child);
        journeys.put("MAIN", main);

        Map<String, Object> first = f.start(main, Map.of());
        assertThat(first.get("message")).isEqualTo("Which order?");

        Map<String, Object> second = f.engine.resume(main, context(first), Map.of("answer", "A-17"));
        assertThat(second.get("status")).isEqualTo("WAITING");
        assertThat(second.get("message")).isEqualTo("Delivery address?");

        Map<String, Object> third = f.engine.resume(main, context(second), Map.of("answer", "Irbid"));
        assertThat(third.get("message")).isEqualTo("A-17 to Irbid");
    }
}
