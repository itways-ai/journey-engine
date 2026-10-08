package com.itways.assistant.journey.model.connector;

/**
 * How much harm one operation can do. Preflight needs an approval step before a
 * {@code HIGH} operation (a publish error), warns on a {@code MEDIUM} one, and
 * the explorer's "Try" refuses {@code HIGH} outright.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}
