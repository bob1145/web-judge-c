package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "judge.execution")
public class ExecutionProperties {

    private String profile = "trusted-local";
    private int maxCasesPerTask = 10_000;
    private int largeModeThreshold = 5_000;
    private int taskQueueCapacity = 10;
    private int maxConcurrentTasks = 1;
    private int maxConcurrentCasesPerTask = 4;
    private int batchSize = 100;
    private int maxFailureSamples = 100;
    private int maxSlowSamples = 20;
    private long maxDailyCasesPerUser = 200_000;
    private long maxDailyRuntimeMillisPerUser = 1_000_000_000L;
    private int maxRunningTasksPerUser = 1;
    private int maxQueuedTasksPerUser = 5;
    private Duration progressPublishInterval = Duration.ofSeconds(1);
    private int progressCacheMaxEntries = 1_024;
    private Duration progressCacheTtl = Duration.ofMinutes(30);
    private Duration cleanupInterval = Duration.ofMinutes(30);
    private Duration createdTaskScanInterval = Duration.ofMinutes(1);
    private int createdTaskScanMaxTasks = 128;
    private Duration createdTaskScanBudget = Duration.ofSeconds(1);
    private Duration completedRetention = Duration.ofHours(24);
    private Duration cancelledRetention = Duration.ofHours(24);
    private Duration failedRetention = Duration.ofHours(24);
    private Duration staleRetention = Duration.ofHours(1);
    private Duration defaultTimeLimit = Duration.ofSeconds(2);
    private Duration minTimeLimit = Duration.ofMillis(100);
    private Duration maxTimeLimit = Duration.ofSeconds(30);
    private Duration maxTaskRuntime = Duration.ofMinutes(30);
    private long minMemoryLimitBytes = 16L * 1024 * 1024;
    private long maxOutputBytesPerCase = 1024L * 1024;
    private long maxDetailPreviewBytes = 64L * 1024;
    private boolean requireSandbox = false;

    @Autowired
    void rejectBlankProgressCachePolicy(Environment environment) {
        for (String property : new String[]{"judge.execution.progress-cache-max-entries", "judge.execution.progress-cache-ttl"}) {
            String value = environment.getProperty(property);
            if (value != null && value.isBlank()) {
                throw new IllegalStateException(property + " must not be blank");
            }
        }
    }

    @PostConstruct
    public void validateProgressCachePolicy() {
        if (progressCacheMaxEntries < 1) {
            throw new IllegalStateException("progress-cache-max-entries must be positive");
        }
        if (progressCacheTtl == null || progressCacheTtl.isNegative() || progressCacheTtl.isZero()) {
            throw new IllegalStateException("progress-cache-ttl must be positive");
        }
    }

    @PostConstruct
    public void validateCreatedTaskScanPolicy() {
        if (createdTaskScanInterval == null || createdTaskScanInterval.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalStateException("created-task-scan-interval must be at least 1ms");
        }
        if (createdTaskScanMaxTasks < 1) {
            throw new IllegalStateException("created-task-scan-max-tasks must be positive");
        }
        if (createdTaskScanBudget == null || createdTaskScanBudget.isNegative() || createdTaskScanBudget.isZero()) {
            throw new IllegalStateException("created-task-scan-budget must be positive");
        }
        try {
            createdTaskScanInterval.toMillis();
            createdTaskScanBudget.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("CREATED scan duration exceeds supported range", ex);
        }
    }
}
