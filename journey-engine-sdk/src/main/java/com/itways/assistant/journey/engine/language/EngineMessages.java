package com.itways.assistant.journey.engine.language;

import org.springframework.stereotype.Component;

/**
 * Every sentence the journey engine says on its own behalf.
 *
 * <p>
 * These are the prompts and refusals a step emits when the journey author wrote
 * nothing — the fallback prompt on a USER_INPUT step, the re-ask when an
 * approval reply is unclear, the "no answer" from knowledge retrieval. They
 * were English literals scattered across the handlers, which meant an Arabic
 * conversation switched language the moment it hit one.
 */
@Component
public class EngineMessages extends Messages {

    public EngineMessages() {
        super("messages/engine");
    }
}
