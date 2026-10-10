package com.example.demo.service;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.ProductionSecurityStartupValidator;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.sandbox.SandboxRunner;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class TaskCleanupService {

    private final FileTaskStore taskStore;
    private final ExecutionProperties executionProperties;
    private final Optional<SandboxRunner> sandboxRunner;
    private final Optional<ProgressPublisher> progressPublisher;

    public TaskCleanupService(FileTaskStore taskStore, ExecutionProperties executionProperties) {
        this(taskStore, executionProperties, Optional.empty(), Optional.empty());
    }

    public TaskCleanupService(
            FileTaskStore taskStore,
            ExecutionProperties executionProperties,
            Optional<SandboxRunner> sandboxRunner
    ) {
        this(taskStore, executionProperties, sandboxRunner, Optional.empty());
    }

    @Autowired
    public TaskCleanupService(
            FileTaskStore taskStore,
            ExecutionProperties executionProperties,
            Optional<SandboxRunner> sandboxRunner,
            Optional<ProgressPublisher> progressPublisher
    ) {
        this.taskStore = taskStore;
        this.executionProperties = executionProperties;
        this.sandboxRunner = sandboxRunner == null ? Optional.empty() : sandboxRunner;
        this.progressPublisher = progressPublisher == null ? Optional.empty() : progressPublisher;
    }

    @Autowired
    void validateStartupPolicy(ObjectProvider<ProductionSecurityStartupValidator> validators) {
        validators.ifAvailable(ProductionSecurityStartupValidator::rejectUnverifiedProvidersBeforeRecovery);
    }

    @PostConstruct
    public void reconcileAndCleanupOnStartup() {
        try {
            List<JudgeTask> staleTasks = reconcileStartup();
            if (!staleTasks.isEmpty()) {
                log.warn("Marked {} unfinished judge tasks as STALE during startup", staleTasks.size());
            }
            cleanupExpiredTasks(Instant.now());
        } catch (Exception ex) {
            log.warn("Judge task startup cleanup failed: {}", ex.getMessage());
            throw new IllegalStateException("Judge task startup reconciliation failed", ex);
        }
    }

    public List<JudgeTask> reconcileStartup() throws IOException {
        List<String> residualCleanupFailures = cleanupResidualHandles(taskStore.findAll());
        if (!residualCleanupFailures.isEmpty()) {
            throw new IOException("Residual sandbox cleanup failed for judgeIds: "
                    + String.join(",", residualCleanupFailures));
        }
        return taskStore.markStaleRunningTasksOnStartup();
    }

    @Scheduled(
            fixedDelayString = "${judge.execution.cleanup-interval:30m}",
            initialDelayString = "${judge.execution.cleanup-interval:30m}"
    )
    public CleanupReport cleanupExpiredTasks() {
        return cleanupExpiredTasks(Instant.now());
    }

    public CleanupReport cleanupExpiredTasks(Instant now) {
        progressPublisher.ifPresent(ProgressPublisher::expireThrottleState);
        List<String> deletedJudgeIds = new ArrayList<>();
        List<String> failedJudgeIds = new ArrayList<>();
        List<JudgeTask> tasks;
        try {
            ZoneId zone = ZoneId.systemDefault();
            taskStore.findDeletedQuotaCharges(LocalDate.ofInstant(now, zone), zone);
            tasks = taskStore.findAll();
        } catch (IOException ex) {
            log.warn("Judge task cleanup failed to list storage base: {}", ex.getMessage());
            return new CleanupReport(0, deletedJudgeIds, failedJudgeIds);
        }

        for (JudgeTask task : tasks) {
            if (!isExpired(task, now)) {
                continue;
            }

            String judgeId = task.getJudgeId();
            String relativePath = relativeTaskPath(judgeId);
            try {
                cleanupResidualHandle(task);
                if (taskStore.deleteTaskDirectory(judgeId)) {
                    progressPublisher.ifPresent(publisher -> publisher.clearThrottleState(judgeId));
                    deletedJudgeIds.add(judgeId);
                    log.info("Cleaned expired judge task {} at {}", judgeId, relativePath);
                }
            } catch (Exception ex) {
                failedJudgeIds.add(judgeId);
                log.warn("Failed to cleanup judge task {} at {}: {}", judgeId, relativePath, ex.getMessage());
            }
        }

        return new CleanupReport(tasks.size(), deletedJudgeIds, failedJudgeIds);
    }

    private List<String> cleanupResidualHandles(List<JudgeTask> tasks) {
        List<String> failedJudgeIds = new ArrayList<>();
        for (JudgeTask task : tasks) {
            try {
                cleanupResidualHandle(task);
            } catch (Exception ex) {
                failedJudgeIds.add(task.getJudgeId());
                log.warn("Failed to cleanup residual sandbox run for judge task {}: {}",
                        task.getJudgeId(), ex.getMessage());
            }
        }
        return failedJudgeIds;
    }

    private void cleanupResidualHandle(JudgeTask task) {
        SandboxRunHandle handle = task.getSandboxRunHandle();
        if (handle == null) {
            return;
        }
        if (handle.runId() == null || handle.runId().isBlank()) {
            throw new IllegalStateException("Sandbox runId is missing for judgeId: " + task.getJudgeId());
        }
        SandboxRunner runner = sandboxRunner.orElseThrow(() ->
                new IllegalStateException("SandboxRunner is required to cleanup residual run " + handle.runId()));
        runner.cleanupResidual(handle, Duration.ofSeconds(2));
    }

    private boolean isExpired(JudgeTask task, Instant now) {
        if (task.getStatus() == null || !task.getStatus().isTerminal()) {
            return false;
        }
        Instant referenceTime = task.getFinishedAt() != null ? task.getFinishedAt() : task.getCreatedAt();
        if (referenceTime == null) {
            return false;
        }
        return !referenceTime.plus(retentionFor(task.getStatus())).isAfter(now);
    }

    private Duration retentionFor(JudgeStatus status) {
        return switch (status) {
            case COMPLETED, AC -> executionProperties.getCompletedRetention();
            case CANCELLED -> executionProperties.getCancelledRetention();
            case STALE -> executionProperties.getStaleRetention();
            default -> executionProperties.getFailedRetention();
        };
    }

    private String relativeTaskPath(String judgeId) {
        try {
            return taskStore.relativeTaskPath(judgeId);
        } catch (Exception ex) {
            return "<invalid-task-path>";
        }
    }

    public record CleanupReport(int inspectedTasks, List<String> deletedJudgeIds, List<String> failedJudgeIds) {

        public CleanupReport {
            deletedJudgeIds = List.copyOf(deletedJudgeIds);
            failedJudgeIds = List.copyOf(failedJudgeIds);
        }
    }
}
