package com.itways.assistant.journey.engine.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.journey.engine.model.ExecutionContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VariableContextTest {

    /**
     * mergeInputs copies request params into inputs.entities. The end user's
     * token used to be copied with them, and from there reached the parked run
     * and run history in plain text.
     */
    @Test
    void userTokenIsNeverCopiedIntoEntities() {
        ExecutionContext context = ExecutionContext.builder().variables(new HashMap<>()).build();
        Map<String, Object> params = new HashMap<>();
        params.put(EndUserAuth.PARAM_USER_TOKEN, "secret-token");
        params.put("orderId", "A1");

        new VariableContext().mergeInputs(context, params);

        @SuppressWarnings("unchecked")
        Map<String, Object> entities = (Map<String, Object>) ((Map<String, Object>) context.getVariables().get("inputs"))
                .get("entities");
        assertThat(entities).containsEntry("orderId", "A1").doesNotContainKey(EndUserAuth.PARAM_USER_TOKEN);
        assertThat(context.getVariables().toString()).doesNotContain("secret-token");
    }
}
