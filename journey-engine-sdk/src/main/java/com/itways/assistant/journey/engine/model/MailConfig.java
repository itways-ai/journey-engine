package com.itways.assistant.journey.engine.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailConfig {
    private String smtpHost;
    private Integer smtpPort;
    private String username;
    private String password;
    private String from;
    private String to;
    private String subject;
    private String body;
}
