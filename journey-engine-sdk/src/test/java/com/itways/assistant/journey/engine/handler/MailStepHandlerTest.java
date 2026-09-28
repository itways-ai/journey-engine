package com.itways.assistant.journey.engine.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.Simulation;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.engine.model.MailConfig;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.MailDeliveryPort;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.model.StepStatus;

/**
 * SEND_MAIL hands the step's settings to the host's transport as they are (the
 * password sealed, SPC-04), and fails a step with no SMTP host instead of
 * publishing a message notification-service would refuse (JRN-39).
 */
class MailStepHandlerTest {

    private static final String SEALED = "ms:0a1b2c3d:bm90LXJlYWxseS1jaXBoZXJ0ZXh0";

    private final List<MailConfig> sent = new ArrayList<>();
    private final MailDeliveryPort port = (config, to, subject, body) -> sent.add(config);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MailStepHandler handler = new MailStepHandler(objectMapper, new EngineUtils(objectMapper),
            new VariableContext(), new StepOutputSchemaHelper(objectMapper), Optional.of(port));

    @Test
    void passesTheSealedPasswordThroughUntouched() {
        StepResult result = handler.execute(step("smtp.example.com"), context());

        assertThat(result.getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(sent).singleElement().satisfies(config -> {
            assertThat(config.getPassword()).isEqualTo(SEALED);
            assertThat(config.getSmtpHost()).isEqualTo("smtp.example.com");
        });
    }

    @Test
    void aStepWithoutAnSmtpHostFailsAndSendsNothing() {
        for (String host : new String[] { null, "", "   " }) {
            StepResult result = handler.execute(step(host), context());

            assertThat(result.getStatus()).isEqualTo(StepStatus.ERROR);
            assertThat(result.getMessage()).isEqualTo("Mail Send Failed: no SMTP host configured for this step");
        }
        assertThat(sent).isEmpty();
    }

    @Test
    void aRehearsalCatchesTheMissingHostToo() {
        ExecutionContext context = context();
        context.setInternal(Simulation.INTERNAL_SIMULATE, true);

        assertThat(handler.execute(step(""), context).getStatus()).isEqualTo(StepStatus.ERROR);
        assertThat(handler.execute(step("smtp.example.com"), context).getStatus()).isEqualTo(StepStatus.SUCCESS);
        assertThat(sent).isEmpty();
    }

    @Test
    void thePasswordIsNotInTheConfigsToString() {
        MailConfig config = MailConfig.builder().smtpHost("smtp.example.com").password("plain-secret").build();

        assertThat(config.toString()).doesNotContain("plain-secret").contains("smtp.example.com");
    }

    private JourneyStep step(String smtpHost) {
        MailConfig config = MailConfig.builder().smtpHost(smtpHost).smtpPort(587).username("bot@example.com")
                .password(SEALED).to("customer@example.com").subject("Hello").body("Hi").build();
        JourneyStep step = new JourneyStep();
        step.setStepName("mail");
        step.setActionType("SEND_MAIL");
        try {
            step.setApiConfig(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return step;
    }

    private static ExecutionContext context() {
        return ExecutionContext.builder().build();
    }
}
