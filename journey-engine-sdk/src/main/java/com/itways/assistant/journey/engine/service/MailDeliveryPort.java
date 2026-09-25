package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.model.MailConfig;

/**
 * Sends email using per-step SMTP settings. Implemented by the host application
 * (e.g. assistant-service via RabbitMQ notification-service).
 */
public interface MailDeliveryPort {

    void send(MailConfig smtpConfig, String to, String subject, String body);
}
