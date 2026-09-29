package com.itways.assistant.journey.engine.service;

import com.itways.assistant.ai.dto.AiRequestConfig;

public interface AiConfigProvider {
    /**
     * Resolves the AI configuration based on the current context.
     * The implementation (in conversation-service) looks the account up.
     */

    AiRequestConfig getConfig(String accountId);
}
