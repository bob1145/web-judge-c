package com.example.demo.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum JudgeStatus {
    CREATED(false),
    QUEUED(false),
    PENDING(false),
    COMPILING(false),
    RUNNING(false),
    COMPLETED(true),
    AC(true),
    WA(true),
    TLE(true),
    MLE(true),
    RE(true),
    COMPILATION_ERROR(true),
    SYSTEM_ERROR(true),
    SECURITY_VIOLATION(true),
    SANDBOX_UNAVAILABLE(true),
    CANCELLED(true),
    STALE(true),
    BUDGET_EXCEEDED(true);

    private final boolean terminal;
    private static final Map<String, JudgeStatus> ALIASES = Map.of(
            "CE", COMPILATION_ERROR,
            "COMPILE_ERROR", COMPILATION_ERROR,
            "COMPILATION_FAILED", COMPILATION_ERROR,
            "SE", SYSTEM_ERROR
    );

    JudgeStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public static Optional<JudgeStatus> fromProgressStatus(String status) {
        if (status == null || status.isBlank()) {
            return Optional.empty();
        }
        String normalized = status.trim()
                .replace(' ', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        JudgeStatus alias = ALIASES.get(normalized);
        if (alias != null) {
            return Optional.of(alias);
        }
        try {
            return Optional.of(JudgeStatus.valueOf(normalized));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
