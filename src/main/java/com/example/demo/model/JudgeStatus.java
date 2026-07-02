package com.example.demo.model;

import java.util.Locale;
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
    CANCELLED(true),
    STALE(true),
    BUDGET_EXCEEDED(true);

    private final boolean terminal;

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
        try {
            return Optional.of(JudgeStatus.valueOf(normalized));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
