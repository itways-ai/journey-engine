package com.itways.assistant.journey.model.connector;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * Legacy: how much harm an operation was once declared to do. Since 2026-10-08
 * nothing reads it (every API is tested in UAT before production, so the
 * platform no longer gates on a declared risk): preflight does not ask for an
 * approval, the explorer's Try does not refuse, the drafters do not set one and
 * the validator does not require one. The field stays on
 * {@link ConnectorOperation} so stored definitions, seeds and imports that carry
 * it keep reading and round-trip unchanged; a value that is not one of these
 * constants reads as {@code null} instead of failing.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    /** Any case; blank or unknown (a legacy or foreign value) is {@code null}, never an error. */
    @JsonCreator
    public static RiskLevel fromJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
