package com.example.demo.service;

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
        boolean sandboxRequired
) {
}
