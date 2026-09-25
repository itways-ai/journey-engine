package com.itways.assistant.journey.engine.service;

import com.itways.assistant.ai.dto.AiRequestConfig;

public interface AiConfigProvider {
    /**
     * Resolves the AI configuration based on the current context.
     * The implementation (in assistant-service) will handle the account lookup.
     */

    AiRequestConfig getConfig(String accountId);
}
