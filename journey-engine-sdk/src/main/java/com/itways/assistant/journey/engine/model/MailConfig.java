package com.itways.assistant.journey.engine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A SEND_MAIL step's {@code apiConfig}. {@code password} is sealed by
 * journey-service when the step is saved ({@code ms:…}, common-lib
 * {@code MailSecrets}) and opened only by notification-service; the engine
 * passes it through as it is and never holds the key. A version published
 * before sealing existed may still carry it in plain text, which the host's
 * {@link com.itways.assistant.journey.engine.service.MailDeliveryPort} seals
 * before it leaves the process. Either way it is kept out of {@code toString}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailConfig {
    private String smtpHost;
    private Integer smtpPort;
    private String username;
    @ToString.Exclude
    private String password;
    private String from;
    private String to;
    private String subject;
    private String body;
}
