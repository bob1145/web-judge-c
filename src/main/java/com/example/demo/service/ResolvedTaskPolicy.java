package com.example.demo.service;

import com.example.demo.dto.SandboxComparison;

import java.time.Duration;

public record ResolvedTaskPolicy(
        String profile,
        boolean highVolume,
        int maxCasesPerTask,
        int requestedCases,
        int batchSize,
        int maxConcurrentCasesPerTask,
        Duration caseTimeLimit,
        Duration maxTaskRuntime,
        long memoryLimitBytes,
        long maxOutputBytesPerCase,
        boolean sandboxRequired,
        SandboxComparison comparison
) {
    public ResolvedTaskPolicy(String profile, boolean highVolume, int maxCasesPerTask, int requestedCases,
                              int batchSize, int maxConcurrentCasesPerTask, Duration caseTimeLimit,
                              Duration maxTaskRuntime, long memoryLimitBytes, long maxOutputBytesPerCase,
                              boolean sandboxRequired) {
        this(profile, highVolume, maxCasesPerTask, requestedCases, batchSize, maxConcurrentCasesPerTask,
                caseTimeLimit, maxTaskRuntime, memoryLimitBytes, maxOutputBytesPerCase, sandboxRequired,
                SandboxComparison.decimal("0"));
    }
}
