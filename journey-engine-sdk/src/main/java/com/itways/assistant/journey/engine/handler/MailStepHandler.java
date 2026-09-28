package com.itways.assistant.journey.engine.handler;

import com.itways.assistant.journey.model.StepStatus;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itways.assistant.journey.engine.context.VariableContext;
import com.itways.assistant.journey.engine.model.MailConfig;
import com.itways.assistant.journey.engine.model.ExecutionContext;
import com.itways.assistant.journey.model.JourneyStep;
import com.itways.assistant.journey.engine.model.StepDefinition;
import com.itways.assistant.journey.engine.model.StepOutputSchema;
import com.itways.assistant.journey.engine.model.StepResult;
import com.itways.assistant.journey.engine.service.MailDeliveryPort;
import com.itways.assistant.journey.engine.service.StepHandler;
import com.itways.assistant.journey.engine.util.EngineUtils;
import com.itways.assistant.journey.engine.util.StepOutputSchemaHelper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MailStepHandler implements StepHandler {

    private final ObjectMapper objectMapper;
    private final EngineUtils engineUtils;
    private final VariableContext variableContext;
    private final StepOutputSchemaHelper schemaHelper;

    /**
     * Supplied by the host application. Empty when the host wires no mail
     * transport, in which case this step fails rather than reporting a delivery
     * that never happened.
     */
    private final Optional<MailDeliveryPort> mailDeliveryPort;

    @Override
    public String getType() {
        return "SEND_MAIL";
    }

    @Override
    public StepDefinition describe() {
        return schemaHelper.sendMailDefinition();
    }

    @Override
    public StepOutputSchema describeOutputs(JourneyStep step) {
        return schemaHelper.genericOutputSchema("SEND_MAIL", "Send Result");
    }

    @Override
    public StepResult execute(JourneyStep step, ExecutionContext context) {
        MailConfig mailConfig;
        try {
            mailConfig = objectMapper.readValue(step.getApiConfig(), MailConfig.class);
        } catch (Exception e) {
            return StepResult.error("Mail Send Failed: unreadable mail configuration - " + e.getMessage());
        }

        // JRN-39: notification-service refuses settings without a host and parks
        // the message, so the run would report a send that can never happen.
        // Checked before the rehearsal branch, so a simulated run catches it too.
        if (mailConfig.getSmtpHost() == null || mailConfig.getSmtpHost().isBlank()) {
            return StepResult.error("Mail Send Failed: no SMTP host configured for this step");
        }

        // Recipient, subject and body are author-written templates.
        String to = engineUtils.replacePlaceholders(mailConfig.getTo(), context.getVariables());
        String subject = engineUtils.replacePlaceholders(mailConfig.getSubject(), context.getVariables());
        String body = engineUtils.replacePlaceholders(mailConfig.getBody(), context.getVariables());

        if (to == null || to.isBlank()) {
            return StepResult.error("Mail Send Failed: no recipient after resolving placeholders.");
        }

        if (com.itways.assistant.journey.engine.context.Simulation.isActive(context)) {
            // Resolved first, then not sent: a rehearsal must still catch a
            // recipient that interpolated to nothing, which is the failure this
            // step actually has. Nobody receives anything.
            log.info("SEND_MAIL step '{}' SIMULATED: would send '{}' to {}", step.getStepName(), subject, to);
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put(com.itways.assistant.journey.engine.context.Simulation.META_SIMULATED, true);
            metadata.put("to", to);
            metadata.put("subject", subject);
            return StepResult.builder()
                    .status(StepStatus.SUCCESS)
                    .data(java.util.Map.of("simulated", true, "to", to, "subject", subject))
                    .message(step.getMessage())
                    .metadata(metadata)
                    .build();
        }

        if (mailDeliveryPort.isEmpty()) {
            // This step used to fabricate "Mail sent to X" and return SUCCESS with no
            // transport wired at all. Reporting a delivery that did not happen is
            // worse than failing, so say so plainly.
            log.error("SEND_MAIL step '{}' cannot run: no MailDeliveryPort is wired into this host.",
                    step.getStepName());
            return StepResult.error("Mail Send Failed: no mail transport is configured for this environment.");
        }

        try {
            // The password goes on as it is: sealed by journey-service, opened by
            // notification-service. The engine never holds the key.
            mailDeliveryPort.get().send(mailConfig, to, subject, body);
        } catch (Exception e) {
            log.error("SEND_MAIL step '{}' delivery failed for recipient '{}'", step.getStepName(), to, e);
            return StepResult.error("Mail Send Failed: " + e.getMessage());
        }

        log.info("<--- SEND_MAIL step '{}' handed off to transport for '{}'", step.getStepName(), to);
        String result = "Mail sent to " + to;
        variableContext.storeOutput(context, step, result);
        return StepResult.success(result, step.getMessage() != null ? step.getMessage() : result);
    }
}
