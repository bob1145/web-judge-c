package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    private Duration defaultTimeLimit = Duration.ofSeconds(2);
    private Duration minTimeLimit = Duration.ofMillis(100);
    private Duration maxTimeLimit = Duration.ofSeconds(30);
    private Duration maxTaskRuntime = Duration.ofMinutes(30);
    private long minMemoryLimitBytes = 16L * 1024 * 1024;
    private long maxOutputBytesPerCase = 1024L * 1024;
    private boolean requireSandbox = false;
}
