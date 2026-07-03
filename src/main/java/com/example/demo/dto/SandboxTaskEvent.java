package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SandboxTaskEvent(
        String judgeId,
        Type type,
        Instant occurredAt,
        Integer caseNumber,
        String status,
        String message,
        TestCaseResult result,
        JudgeSummary summary
) {

    public enum Type {
        COMPILE_STARTED,
        COMPILE_FINISHED,
        RUN_STARTED,
        RUN_FINISHED,
        SUMMARY,
        COMPLETED,
        CANCELLED,
        BUDGET_EXCEEDED,
        SECURITY_VIOLATION,
        SYSTEM_ERROR,
        SANDBOX_UNAVAILABLE
    }

    public static SandboxTaskEvent of(String judgeId, Type type, String message) {
        return new SandboxTaskEvent(judgeId, type, Instant.now(), null, null, message, null, null);
    }
}
