package com.example.demo.service;

import com.example.demo.config.AsyncConfig;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.CancelJudgeResponse;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class JudgeScheduler {

    private static final String CANCELLED_MESSAGE = "Cancellation requested";
    private static final String BUDGET_MESSAGE = "Task runtime budget exceeded";
    private static final String CREATED_EXPIRED_MESSAGE = "Created task expired before start";
    private static final Duration CREATED_TASK_TTL = Duration.ofMinutes(15);

    private final ExecutionProperties executionProperties;
    private final TaskStore taskStore;
    private final Executor executor;
    private final ScheduledExecutorService budgetExecutor;
    private final Object monitor = new Object();
    private final Deque<ScheduledTask> queue = new ArrayDeque<>();
    private final Map<String, ScheduledTask> queuedTasks = new HashMap<>();
    private final Map<String, ScheduledTask> runningTasks = new HashMap<>();
    private int peakRunningCount;
    private int peakQueueSize;

    public JudgeScheduler(
            ExecutionProperties executionProperties,
            TaskStore taskStore,
            @Qualifier(AsyncConfig.JUDGE_REQUEST_EXECUTOR) Executor executor
    ) {
        this.executionProperties = executionProperties;
        this.taskStore = taskStore;
        this.executor = executor;
        this.budgetExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "JudgeBudget-");
            thread.setDaemon(true);
            return thread;
        });
    }

    public QueueSnapshot enqueue(String judgeId, ScheduledJudgeJob job) {
        Objects.requireNonNull(judgeId, "judgeId must not be null");
        Objects.requireNonNull(job, "job must not be null");

        ScheduledTask task = new ScheduledTask(judgeId, job, contextFor(judgeId));
        ScheduledTask taskToStart = null;
        QueueSnapshot result;
        synchronized (monitor) {
            rejectTerminalStart(judgeId);
            if (queuedTasks.containsKey(judgeId) || runningTasks.containsKey(judgeId)) {
                return snapshotLocked();
            }
            if (canStartLocked(task)) {
                persistStatus(judgeId, JudgeStatus.QUEUED, "Judge task queued for execution");
                runningTasks.put(judgeId, task);
                peakRunningCount = Math.max(peakRunningCount, runningTasks.size());
                taskToStart = task;
            } else {
                int capacity = queueCapacity();
                if (queue.size() >= capacity) {
                    throw new QueueFullException(capacity, queue.size());
                }
                persistStatus(judgeId, JudgeStatus.QUEUED, "Judge task queued for execution");
                queue.addLast(task);
                queuedTasks.put(judgeId, task);
                peakQueueSize = Math.max(peakQueueSize, queue.size());
            }
            result = snapshotLocked();
        }
        if (taskToStart != null) {
            execute(taskToStart);
        }
        return result;
    }

    public void requireScheduled(String judgeId) {
        Objects.requireNonNull(judgeId, "judgeId must not be null");
        synchronized (monitor) {
            rejectTerminalStart(judgeId);
            if (!queuedTasks.containsKey(judgeId) && !runningTasks.containsKey(judgeId)) {
                throw new IllegalArgumentException("Judge task is not scheduled: " + judgeId);
            }
        }
    }

    public CancelJudgeResponse cancel(String judgeId) {
        Objects.requireNonNull(judgeId, "judgeId must not be null");
        ScheduledTask runningTask = null;
        synchronized (monitor) {
            expireCreatedTaskIfNeeded(judgeId);
            ScheduledTask queuedTask = queuedTasks.get(judgeId);
            if (queuedTask != null) {
                queuedTask.context.cancellationToken().cancel();
                persistCancelled(queuedTask);
                queuedTasks.remove(judgeId);
                queue.remove(queuedTask);
                return new CancelJudgeResponse(
                        judgeId,
                        true,
                        JudgeStatus.CANCELLED.name(),
                        CANCELLED_MESSAGE,
                        false,
                        true,
                        queuedTask.context.completedCases()
                );
            } else {
                runningTask = runningTasks.get(judgeId);
                if (runningTask == null) {
                    Optional<JudgeTask> storedTask = findTask(judgeId);
                    if (storedTask.isPresent() && storedTask.get().getStatus() == JudgeStatus.CREATED) {
                        TaskContext context = contextFor(judgeId);
                        persistStoppedSummary(judgeId, context, JudgeStatus.CANCELLED, CANCELLED_MESSAGE);
                        return new CancelJudgeResponse(judgeId, true, JudgeStatus.CANCELLED.name(),
                                CANCELLED_MESSAGE, false, false, 0);
                    }
                }
            }
        }

        if (runningTask != null) {
            Optional<JudgeTask> storedTask = findTask(runningTask.judgeId);
            Optional<JudgeStatus> terminalStatus = storedTask.map(JudgeTask::getStatus)
                    .filter(JudgeStatus::isTerminal);
            if (terminalStatus.isPresent()) {
                return new CancelJudgeResponse(
                        judgeId,
                        false,
                        terminalStatus.get().name(),
                        "Task is already finished",
                        false,
                        false,
                        runningTask.context.completedCases()
                );
            }
            runningTask.context.cancellationToken().cancel();
            return new CancelJudgeResponse(
                    judgeId,
                    true,
                    storedTask.map(JudgeTask::getStatus).orElse(JudgeStatus.RUNNING).name(),
                    runningTask.context.cancellationToken().isBudgetExceeded()
                            ? BUDGET_MESSAGE
                            : CANCELLED_MESSAGE,
                    true,
                    false,
                    runningTask.context.completedCases()
            );
        }

        Optional<JudgeTask> task = findTask(judgeId);
        String status = task.map(JudgeTask::getStatus)
                .map(Enum::name)
                .orElse("NOT_FOUND");
        return new CancelJudgeResponse(
                judgeId,
                false,
                status,
                "Task is not queued or running",
                false,
                false,
                0
        );
    }

    public QueueSnapshot snapshot() {
        synchronized (monitor) {
            return snapshotLocked();
        }
    }

    Set<String> runningTaskIdsForOwner(String ownerUserId) {
        synchronized (monitor) {
            return runningTasks.values().stream()
                    .filter(task -> task.context.ownerUserId.equals(ownerUserId))
                    .map(ScheduledTask::judgeId)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    @PreDestroy
    public void shutdown() {
        budgetExecutor.shutdownNow();
    }

    private void execute(ScheduledTask task) {
        try {
            executor.execute(() -> runTask(task));
        } catch (RejectedExecutionException e) {
            log.error("Judge executor rejected task {}", task.judgeId, e);
            try {
                persistSystemError(task, "Judge executor rejected the task");
            } finally {
                releaseAndStartNextQueuedTask(task);
            }
            throw e;
        }
    }

    private void runTask(ScheduledTask task) {
        ScheduledFuture<?> budgetFuture = new CompletedScheduledFuture();
        try {
            task.context.budgetStartedNanos = System.nanoTime();
            budgetFuture = scheduleBudget(task);
            if (!task.context.cancellationToken().isCancellationRequested()) {
                task.context.markStarted();
                persistStatus(task.judgeId, JudgeStatus.PENDING, "Judge task preparation started");
                if (!task.context.cancellationToken().isCancellationRequested()) {
                    task.job.run(task.context);
                }
            }
            completeIfNeeded(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.context.cancellationToken().cancel();
            completeIfNeeded(task);
        } catch (Exception e) {
            log.error("Judge task failed: {}", task.judgeId, e);
            if (task.context.cancellationToken().isCancellationRequested()) {
                completeIfNeeded(task);
            } else {
                persistSystemError(task, "Judge task failed unexpectedly");
            }
        } finally {
            budgetFuture.cancel(false);
            releaseAndStartNextQueuedTask(task);
        }
    }

    private ScheduledFuture<?> scheduleBudget(ScheduledTask task) {
        Duration budget = task.context.maxTaskRuntime();
        if (budget == null || budget.isZero() || budget.isNegative()) {
            return new CompletedScheduledFuture();
        }
        return budgetExecutor.schedule(
                () -> task.context.cancellationToken().cancelForBudgetExceeded(),
                budget.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void releaseAndStartNextQueuedTask(ScheduledTask completedTask) {
        ScheduledTask nextTask = null;
        synchronized (monitor) {
            if (completedTask.context.cleanupPending) {
                log.warn("Retaining execution permits for judge task {} because sandbox cleanup is pending",
                        completedTask.judgeId);
                return;
            }
            if (!runningTasks.remove(completedTask.judgeId, completedTask)) {
                return;
            }
            var candidates = queue.iterator();
            while (candidates.hasNext()) {
                ScheduledTask candidate = candidates.next();
                if (canStartLocked(candidate)) {
                    candidates.remove();
                    queuedTasks.remove(candidate.judgeId);
                    runningTasks.put(candidate.judgeId, candidate);
                    peakRunningCount = Math.max(peakRunningCount, runningTasks.size());
                    nextTask = candidate;
                    break;
                }
            }
        }
        if (nextTask != null) {
            execute(nextTask);
        }
    }

    private boolean canStartLocked(ScheduledTask task) {
        if (runningTasks.size() >= maxConcurrentTasks()) {
            return false;
        }
        long ownerRunning = runningTasks.values().stream()
                .filter(running -> running.context.ownerUserId.equals(task.context.ownerUserId))
                .count();
        return ownerRunning < Math.max(0, executionProperties.getMaxRunningTasksPerUser());
    }

    private TaskContext contextFor(String judgeId) {
        JudgeTask task = findTask(judgeId)
                .orElseThrow(() -> new IllegalArgumentException("Judge task not found: " + judgeId));
        Duration maxTaskRuntime = task.getPolicy() != null && task.getPolicy().maxTaskRuntime() != null
                ? task.getPolicy().maxTaskRuntime()
                : executionProperties.getMaxTaskRuntime();
        String ownerUserId = JudgeOwnership.owner(
                task.getOwnership() == null ? null : task.getOwnership().getUserId(), null).getUserId();
        return new TaskContext(judgeId, task.getRequestedCases(), maxTaskRuntime, ownerUserId);
    }

    private Optional<JudgeTask> findTask(String judgeId) {
        try {
            return taskStore.find(judgeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read judge task " + judgeId, e);
        }
    }

    private void completeIfNeeded(ScheduledTask task) {
        if (isTerminal(task.judgeId)) {
            return;
        }
        if (task.context.cancellationToken().isBudgetExceeded()) {
            persistBudgetExceeded(task);
            return;
        }
        if (task.context.cancellationToken().isCancellationRequested()) {
            persistCancelled(task);
            return;
        }
        if (!isTerminal(task.judgeId)) {
            persistStoppedSummary(task.judgeId, task.context, JudgeStatus.COMPLETED, "Judge task completed");
        }
    }

    private boolean isTerminal(String judgeId) {
        return terminalStatus(judgeId).isPresent();
    }

    private Optional<JudgeStatus> terminalStatus(String judgeId) {
        return findTask(judgeId)
                .map(JudgeTask::getStatus)
                .filter(JudgeStatus::isTerminal);
    }

    private void rejectTerminalStart(String judgeId) {
        expireCreatedTaskIfNeeded(judgeId);
        terminalStatus(judgeId).ifPresent(status -> {
            throw new TaskAlreadyFinishedException(status);
        });
    }

    public Optional<JudgeStatus> expireCreatedTask(String judgeId, Instant now) {
        Objects.requireNonNull(judgeId, "judgeId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        synchronized (monitor) {
            expireCreatedTaskIfNeeded(judgeId, now);
            return terminalStatus(judgeId);
        }
    }

    private void expireCreatedTaskIfNeeded(String judgeId) {
        expireCreatedTaskIfNeeded(judgeId, Instant.now());
    }

    private void expireCreatedTaskIfNeeded(String judgeId, Instant now) {
        Optional<JudgeTask> stored = findTask(judgeId);
        if (stored.isEmpty() || queuedTasks.containsKey(judgeId) || runningTasks.containsKey(judgeId)) {
            return;
        }
        JudgeTask created = stored.get();
        if (created.getStatus() != JudgeStatus.CREATED || created.getCreatedAt() == null
                || created.getStartedAt() != null || created.getSandboxRunHandle() != null
                || created.getCreatedAt().plus(CREATED_TASK_TTL).isAfter(now)) {
            return;
        }
        TaskContext context = contextFor(judgeId);
        persistStoppedSummary(judgeId, context, JudgeStatus.STALE, CREATED_EXPIRED_MESSAGE);
    }

    private void persistSystemError(ScheduledTask task, String message) {
        persistStoppedSummary(task.judgeId, task.context, JudgeStatus.SYSTEM_ERROR, message);
    }

    private void persistCancelled(ScheduledTask task) {
        if (isTerminal(task.judgeId)) {
            return;
        }
        persistStoppedSummary(task.judgeId, task.context, JudgeStatus.CANCELLED, CANCELLED_MESSAGE);
    }

    private void persistBudgetExceeded(ScheduledTask task) {
        if (isTerminal(task.judgeId)) {
            return;
        }
        persistStoppedSummary(task.judgeId, task.context, JudgeStatus.BUDGET_EXCEEDED, BUDGET_MESSAGE);
    }

    private void persistStoppedSummary(String judgeId, TaskContext context, JudgeStatus status, String message) {
        try {
            Optional<JudgeProgress> persisted = taskStore.findSummary(judgeId);
            if (persisted.filter(snapshot -> JudgeStatus.fromProgressStatus(snapshot.getStatus())
                    .map(JudgeStatus::isTerminal).orElse(false)).isPresent()) {
                taskStore.finalizeTask(judgeId, persisted.get());
                return;
            }
            JudgeSummary existing = persisted.map(JudgeProgress::getSummary).orElse(null);
            if (existing == null && context.completedCases() > 0) {
                throw new IOException("Cannot reconstruct stopped summary: completed cases have no persisted aggregate");
            }
            JudgeSummary summary = existing == null
                    ? new JudgeSummary(context.totalCases(), 0, 0, 0, 0, 0, 0, 0, 0,
                            null, List.of(), List.of(), message)
                    : new JudgeSummary(existing.getTotalCases(), existing.getCompletedCases(),
                            existing.getAc(), existing.getWa(), existing.getTle(), existing.getMle(),
                            existing.getRe(), existing.getSystemError(), existing.getOutputLimitExceeded(),
                            existing.getFirstFailedCase(), existing.getFailureSamples(),
                            existing.getSlowSamples(), message);
            int progress = persisted.map(JudgeProgress::getProgress).orElse(context.totalCases() == 0 ? 100 : 0);
            taskStore.finalizeTask(judgeId, new JudgeProgress(status.name(), message, progress, null, summary));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save stopped judge summary for " + judgeId, e);
        }
    }

    private void persistStatus(String judgeId, JudgeStatus status, String message) {
        try {
            taskStore.updateStatus(judgeId, status, message);
        } catch (IllegalStateException e) {
            if (!status.isTerminal()) {
                throw e;
            }
            log.debug("Ignoring terminal status update for {} after another terminal status: {}", judgeId, e.getMessage());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update judge task status for " + judgeId, e);
        }
    }

    private QueueSnapshot snapshotLocked() {
        return new QueueSnapshot(
                runningTasks.size(),
                queue.size(),
                peakRunningCount,
                peakQueueSize,
                maxConcurrentTasks(),
                queueCapacity()
        );
    }

    private int maxConcurrentTasks() {
        return Math.max(1, executionProperties.getMaxConcurrentTasks());
    }

    private int queueCapacity() {
        return Math.max(0, executionProperties.getTaskQueueCapacity());
    }

    @FunctionalInterface
    public interface ScheduledJudgeJob {
        void run(TaskContext context) throws Exception;
    }

    public record QueueSnapshot(
            int runningCount,
            int queuedCount,
            int peakRunningCount,
            int peakQueueSize,
            int maxConcurrentTasks,
            int queueCapacity
    ) {
    }

    public static class TaskContext {

        private final String judgeId;
        private final int totalCases;
        private final Duration maxTaskRuntime;
        private final String ownerUserId;
        private final CancellationToken cancellationToken = new CancellationToken();
        private final AtomicInteger completedCases = new AtomicInteger();
        private volatile Instant startedAt;
        private volatile long budgetStartedNanos;
        private boolean cleanupPending;

        private TaskContext(String judgeId, int totalCases, Duration maxTaskRuntime, String ownerUserId) {
            this.judgeId = judgeId;
            this.totalCases = totalCases;
            this.maxTaskRuntime = maxTaskRuntime;
            this.ownerUserId = ownerUserId;
        }

        public String judgeId() {
            return judgeId;
        }

        public int totalCases() {
            return totalCases;
        }

        public Duration maxTaskRuntime() {
            return maxTaskRuntime;
        }

        public Duration remainingRuntime() {
            long elapsed = budgetStartedNanos == 0 ? 0 : System.nanoTime() - budgetStartedNanos;
            return Duration.ofNanos(Math.max(0, maxTaskRuntime.toNanos() - elapsed));
        }

        public CancellationToken cancellationToken() {
            return cancellationToken;
        }

        void markCleanupPending() {
            cleanupPending = true;
        }

        public void recordCompletedCase() {
            completedCases.incrementAndGet();
        }

        public int completedCases() {
            return completedCases.get();
        }

        public Instant startedAt() {
            return startedAt;
        }

        private void markStarted() {
            startedAt = Instant.now();
        }
    }

    public static class TaskAlreadyFinishedException extends IllegalStateException {

        private final JudgeStatus status;

        public TaskAlreadyFinishedException(JudgeStatus status) {
            super("Judge task is already finished: " + status.name());
            this.status = status;
        }

        public JudgeStatus getStatus() {
            return status;
        }
    }

    public static class QueueFullException extends RuntimeException {

        private final int queueCapacity;
        private final int currentQueueSize;

        public QueueFullException(int queueCapacity, int currentQueueSize) {
            super("Judge queue is full; capacity=" + queueCapacity
                    + ", currentQueueSize=" + currentQueueSize
                    + ". Please retry later.");
            this.queueCapacity = queueCapacity;
            this.currentQueueSize = currentQueueSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public int getCurrentQueueSize() {
            return currentQueueSize;
        }
    }

    private record ScheduledTask(String judgeId, ScheduledJudgeJob job, TaskContext context) {
    }

    private static class CompletedScheduledFuture implements ScheduledFuture<Object> {

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
