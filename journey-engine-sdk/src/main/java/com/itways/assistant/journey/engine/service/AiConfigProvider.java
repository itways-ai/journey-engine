package com.itways.assistant.journey.engine.service;

import com.itways.assistant.ai.dto.AiRequestConfig;
import com.itways.common.exception.BusinessException;

public interface AiConfigProvider {

    /**
     * The error code of a {@link BusinessException} that {@link #getConfig} throws
     * when the account has no AI provider set up (account-service's ACC-08). A
     * setup gap, not an outage: no retry changes it until the owner adds one.
     */
    String NOT_CONFIGURED = "AI_PROVIDER_NOT_CONFIGURED";

    /**
     * Resolves the AI configuration based on the current context.
     * The implementation (in conversation-service) looks the account up.
     *
     * @throws BusinessException with error code {@value #NOT_CONFIGURED} when the
     *                           account has no AI provider; anything else when the
     *                           configuration could not be fetched
     */
    AiRequestConfig getConfig(String accountId);

    /**
     * Whether {@link #getConfig} failed because the account has no AI provider
     * ({@value #NOT_CONFIGURED}), directly or as the cause of what it threw, as
     * opposed to the configuration being unreachable.
     */
    static boolean isNotConfigured(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof BusinessException business && NOT_CONFIGURED.equals(business.getErrorCode())) {
                return true;
            }
        }
        return false;
    }
}
