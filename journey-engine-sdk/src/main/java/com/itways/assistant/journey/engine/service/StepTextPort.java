package com.itways.assistant.journey.engine.service;

import com.itways.assistant.journey.engine.language.ConversationLanguage;
import com.itways.assistant.journey.model.StepText;

/**
 * Records machine translations of a journey's authored text.
 *
 * <p>
 * A port because the engine has no database: journey-service owns
 * {@code journey_step_translations}, and assistant-service wires the two together.
 * Same shape as {@link KnowledgeBasePort} and {@link TemplateRenderPort}.
 *
 * <p>
 * Reading translations no longer goes through this port: the version payload
 * carries them on {@link com.itways.assistant.journey.model.JourneyDefinition},
 * captured at publish — so a draft retranslation can never leak into published
 * traffic. What remains here is the author-facing write-back cache.
 */
public interface StepTextPort {

    /**
     * Records a machine translation so the next run does not pay for it again.
     *
     * <p>
     * Best-effort and must not throw: failing to cache a translation is not a
     * reason to fail the turn that produced it.
     */
    default void saveMachineTranslation(String accountId, Long stepId, ConversationLanguage language, StepText text) {
        // Default no-op: a deployment without a translation store still runs,
        // it just re-translates each time.
    }

    /** Port that knows nothing, for tests and for engine embeddings with no store. */
    StepTextPort NONE = new StepTextPort() {
    };
}
