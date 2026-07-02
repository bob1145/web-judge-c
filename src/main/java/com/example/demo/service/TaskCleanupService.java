package com.example.demo.service;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskCleanupService {

    private final FileTaskStore taskStore;
    private final ExecutionProperties executionProperties;

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
        }
    }

    public List<JudgeTask> reconcileStartup() throws IOException {
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
        List<String> deletedJudgeIds = new ArrayList<>();
        List<String> failedJudgeIds = new ArrayList<>();
        List<JudgeTask> tasks;
        try {
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
                if (taskStore.deleteTaskDirectory(judgeId)) {
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
