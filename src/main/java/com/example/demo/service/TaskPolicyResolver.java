package com.example.demo.service;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.MemoryConfiguration;
import com.example.demo.dto.JudgeRequest;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TaskPolicyResolver {

    private final ExecutionProperties executionProperties;
    private final MemoryConfiguration memoryConfiguration;

    public ResolvedTaskPolicy resolve(JudgeRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        int requestedCases = request.getTestCases();
        int maxCasesPerTask = executionProperties.getMaxCasesPerTask();
        if (maxCasesPerTask < 1) {
            throw reject(requestedCases, "maxCasesPerTask must be at least 1 for profile " + profile());
        }
        if (requestedCases < 1) {
            throw reject(requestedCases, "testCases must be at least 1; submitted " + requestedCases);
        }
        if (requestedCases > maxCasesPerTask) {
            throw reject(requestedCases, "Current profile " + profile()
                    + " allows at most " + maxCasesPerTask
                    + " test cases; submitted " + requestedCases);
        }

        Duration caseTimeLimit = resolveTimeLimit(request);
        long memoryLimitBytes = resolveMemoryLimit(request);
        boolean highVolume = requestedCases >= Math.max(1, executionProperties.getLargeModeThreshold());

        return new ResolvedTaskPolicy(
                profile(),
                highVolume,
                maxCasesPerTask,
                requestedCases,
                executionProperties.getBatchSize(),
                executionProperties.getMaxConcurrentCasesPerTask(),
                caseTimeLimit,
                executionProperties.getMaxTaskRuntime(),
                memoryLimitBytes,
                executionProperties.getMaxOutputBytesPerCase(),
                executionProperties.isRequireSandbox()
        );
    }

    public void validate(JudgeRequest request, ResolvedTaskPolicy policy) {
        ResolvedTaskPolicy resolved = resolve(request);
        if (!resolved.equals(policy)) {
            throw reject(request.getTestCases(), "Resolved task policy does not match supplied policy snapshot");
        }
    }

    private Duration resolveTimeLimit(JudgeRequest request) {
        long submittedMillis = request.getTimeLimit();
        if (submittedMillis == 0) {
            Duration defaultLimit = executionProperties.getDefaultTimeLimit();
            validateTimeRange(request.getTestCases(), defaultLimit, "default timeLimit");
            return defaultLimit;
        }
        if (submittedMillis < 0) {
            throw reject(request.getTestCases(), "timeLimit must be positive or 0 to use the default; submitted " + submittedMillis);
        }

        Duration submitted = Duration.ofMillis(submittedMillis);
        validateTimeRange(request.getTestCases(), submitted, "timeLimit");
        return submitted;
    }

    private void validateTimeRange(int submittedCases, Duration value, String fieldName) {
        Duration min = executionProperties.getMinTimeLimit();
        Duration max = executionProperties.getMaxTimeLimit();
        if (value.compareTo(min) < 0) {
            throw reject(submittedCases, fieldName + " must be at least " + min.toMillis() + " ms");
        }
        if (value.compareTo(max) > 0) {
            throw reject(submittedCases, fieldName + " must be at most " + max.toMillis() + " ms");
        }
    }

    private long resolveMemoryLimit(JudgeRequest request) {
        long submittedBytes = request.getMemoryLimit();
        if (submittedBytes == 0) {
            long defaultLimit = memoryConfiguration.getDefaultLimit();
            validateMemoryRange(request.getTestCases(), defaultLimit, "default memoryLimit");
            return defaultLimit;
        }
        if (submittedBytes < 0) {
            throw reject(request.getTestCases(), "memoryLimit must be positive or 0 to use the default; submitted " + submittedBytes);
        }

        validateMemoryRange(request.getTestCases(), submittedBytes, "memoryLimit");
        return submittedBytes;
    }

    private void validateMemoryRange(int submittedCases, long value, String fieldName) {
        long min = executionProperties.getMinMemoryLimitBytes();
        long max = memoryConfiguration.getMaxLimit();
        if (value < min) {
            throw reject(submittedCases, fieldName + " must be at least " + min + " bytes");
        }
        if (value > max) {
            throw reject(submittedCases, fieldName + " must be at most judge.memory.max-limit "
                    + max + " bytes");
        }
    }

    private PolicyValidationException reject(int submittedCases, String message) {
        return new PolicyValidationException(submittedCases, executionProperties.getMaxCasesPerTask(), profile(), message);
    }

    private String profile() {
        return executionProperties.getProfile();
    }

    @Getter
    public static class PolicyValidationException extends IllegalArgumentException {

        private final int submittedCases;
        private final int maxCasesPerTask;
        private final String profile;

        public PolicyValidationException(int submittedCases, int maxCasesPerTask, String profile, String message) {
            super(message);
            this.submittedCases = submittedCases;
            this.maxCasesPerTask = maxCasesPerTask;
            this.profile = profile;
        }
    }
}
