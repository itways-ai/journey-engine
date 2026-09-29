/**
 * Internal. One {@code StepHandler} per built-in step type (API_CALL,
 * CODE_SCRIPT, CONDITION, DATA_MAP, DELAY, DOCUMENT_INSIGHT, HANDOFF,
 * HUMAN_APPROVAL, JUMP, KNOWLEDGE_RETRIEVAL, SEND_MAIL, REDIRECT, RESPONSE,
 * STATE_STORE, SWITCH, TEMPLATE_RENDER, TRIGGER_JOURNEY, USER_INPUT), found by
 * component scan. Each executes its step and describes itself for the
 * catalogue. {@link TemplateRender} is the engine's own FreeMarker helper for
 * DATA_MAP.
 *
 * <p>
 * Hosts do not call handlers directly: {@code JourneyEngine} runs them, and a
 * host adds a step type by registering its own {@code StepHandler} bean.
 */
package com.itways.assistant.journey.engine.handler;
