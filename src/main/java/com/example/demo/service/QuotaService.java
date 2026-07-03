package com.example.demo.service;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class QuotaService {

    private final ExecutionProperties executionProperties;
    private final TaskStore taskStore;

    public JudgeOwnership ownershipFor(UserSession session) {
        if (session == null) {
            return JudgeOwnership.anonymous();
        }
        return JudgeOwnership.owner(stableUserId(session), session.getSessionId());
    }

    public void assertCanCreate(UserSession session, ResolvedTaskPolicy policy) {
        String userId = stableUserId(session);
        List<JudgeTask> userTasks = loadTasks().stream()
                .filter(task -> isOwnedBy(task, userId))
                .toList();

        long requestedCases = policy.requestedCases();
        long dailyCases = userTasks.stream()
                .filter(this::createdToday)
                .mapToLong(JudgeTask::getRequestedCases)
                .sum();
        if (wouldExceed(dailyCases, requestedCases, executionProperties.getMaxDailyCasesPerUser())) {
            throw QuotaExceededException.forQuota(
                    "daily case quota",
                    dailyCases,
                    requestedCases,
                    executionProperties.getMaxDailyCasesPerUser()
            );
        }

        long requestedRuntimeMillis = estimateRuntimeMillis(policy);
        long dailyRuntimeMillis = userTasks.stream()
                .filter(this::createdToday)
                .mapToLong(this::estimateRuntimeMillis)
                .sum();
        if (wouldExceed(dailyRuntimeMillis, requestedRuntimeMillis, executionProperties.getMaxDailyRuntimeMillisPerUser())) {
            throw QuotaExceededException.forQuota(
                    "daily runtime quota",
                    dailyRuntimeMillis,
                    requestedRuntimeMillis,
                    executionProperties.getMaxDailyRuntimeMillisPerUser()
            );
        }

        long runningTasks = userTasks.stream()
                .filter(task -> isRunningQuotaStatus(task.getStatus()))
                .count();
        if (wouldExceed(runningTasks, 1, executionProperties.getMaxRunningTasksPerUser())) {
            throw QuotaExceededException.forQuota(
                    "running task quota",
                    runningTasks,
                    1,
                    executionProperties.getMaxRunningTasksPerUser()
            );
        }

        long queuedTasks = userTasks.stream()
                .filter(task -> isQueuedQuotaStatus(task.getStatus()))
                .count();
        if (wouldExceed(queuedTasks, 1, executionProperties.getMaxQueuedTasksPerUser())) {
            throw QuotaExceededException.forQuota(
                    "queued task quota",
                    queuedTasks,
                    1,
                    executionProperties.getMaxQueuedTasksPerUser()
            );
        }
    }

    public boolean canAccessTask(String judgeId, UserSession session) {
        if (session == null) {
            return false;
        }
        try {
            Optional<JudgeTask> task = taskStore.find(judgeId);
            if (task.isEmpty()) {
                return false;
            }
            if (session.isAdmin()) {
                return true;
            }
            return isOwnedBy(task.get(), stableUserId(session));
        } catch (Exception ex) {
            return false;
        }
    }

    private List<JudgeTask> loadTasks() {
        try {
            return taskStore.findAll();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to evaluate judge quota", ex);
        }
    }

    private boolean isOwnedBy(JudgeTask task, String userId) {
        JudgeOwnership ownership = task.getOwnership();
        return ownership != null && ownership.isOwnedBy(userId);
    }

    private boolean createdToday(JudgeTask task) {
        Instant createdAt = task.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        LocalDate createdDate = LocalDate.ofInstant(createdAt, ZoneId.systemDefault());
        return createdDate.equals(LocalDate.now(ZoneId.systemDefault()));
    }

    private long estimateRuntimeMillis(JudgeTask task) {
        ResolvedTaskPolicy policy = task.getPolicy();
        if (policy == null) {
            return saturatedMultiply(task.getRequestedCases(), executionProperties.getDefaultTimeLimit().toMillis());
        }
        return estimateRuntimeMillis(policy);
    }

    private long estimateRuntimeMillis(ResolvedTaskPolicy policy) {
        long perCaseMillis = policy.caseTimeLimit() == null ? 0 : Math.max(0, policy.caseTimeLimit().toMillis());
        return saturatedMultiply(policy.requestedCases(), perCaseMillis);
    }

    private long saturatedMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private boolean wouldExceed(long used, long requested, long limit) {
        long effectiveLimit = Math.max(0, limit);
        if (used > effectiveLimit) {
            return true;
        }
        return requested > effectiveLimit - used;
    }

    private boolean isRunningQuotaStatus(JudgeStatus status) {
        return status == JudgeStatus.PENDING
                || status == JudgeStatus.COMPILING
                || status == JudgeStatus.RUNNING;
    }

    private boolean isQueuedQuotaStatus(JudgeStatus status) {
        return status == JudgeStatus.CREATED
                || status == JudgeStatus.QUEUED;
    }

    private String stableUserId(UserSession session) {
        if (session == null) {
            return "anonymous";
        }
        if (session.getUserId() != null && !session.getUserId().isBlank()) {
            return session.getUserId().trim();
        }
        return session.getSessionId();
    }

    @Getter
    public static class QuotaExceededException extends RuntimeException {

        private final String code = "JUDGE_QUOTA_EXCEEDED";
        private final String quota;
        private final long used;
        private final long requested;
        private final long limit;

        private QuotaExceededException(String quota, long used, long requested, long limit) {
            super(quota + " exceeded: used=" + used + ", requested=" + requested + ", limit=" + limit);
            this.quota = quota;
            this.used = used;
            this.requested = requested;
            this.limit = limit;
        }

        public static QuotaExceededException forQuota(String quota, long used, long requested, long limit) {
            return new QuotaExceededException(quota, used, requested, limit);
        }
    }
}
