package com.example.demo.service;

import com.example.demo.config.AsyncConfig;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.CancelJudgeResponse;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class JudgeScheduler {

    private static final String CANCELLED_MESSAGE = "Cancellation requested";
    private static final String BUDGET_MESSAGE = "Task runtime budget exceeded";

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
            if (queuedTasks.containsKey(judgeId) || runningTasks.containsKey(judgeId)) {
                return snapshotLocked();
            }
            if (runningTasks.size() < maxConcurrentTasks()) {
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

    public CancelJudgeResponse cancel(String judgeId) {
        Objects.requireNonNull(judgeId, "judgeId must not be null");
        ScheduledTask queuedTask = null;
        ScheduledTask runningTask = null;
        QueueSnapshot snapshot;
        synchronized (monitor) {
            queuedTask = queuedTasks.remove(judgeId);
            if (queuedTask != null) {
                queue.remove(queuedTask);
            } else {
                runningTask = runningTasks.get(judgeId);
            }
            snapshot = snapshotLocked();
        }

        if (queuedTask != null) {
            queuedTask.context.cancellationToken().cancel();
            persistCancelled(queuedTask);
            return new CancelJudgeResponse(
                    judgeId,
                    true,
                    JudgeStatus.CANCELLED.name(),
                    CANCELLED_MESSAGE,
                    false,
                    true,
                    queuedTask.context.completedCases()
            );
        }

        if (runningTask != null) {
            Optional<JudgeStatus> terminalStatus = terminalStatus(runningTask.judgeId);
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
            if (runningTask.context.cancellationToken().isBudgetExceeded()) {
                persistBudgetExceeded(runningTask);
            } else {
                persistCancelled(runningTask);
            }
            return new CancelJudgeResponse(
                    judgeId,
                    true,
                    runningTask.context.cancellationToken().isBudgetExceeded()
                            ? JudgeStatus.BUDGET_EXCEEDED.name()
                            : JudgeStatus.CANCELLED.name(),
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
                snapshot.runningCount() > 0,
                snapshot.queuedCount() > 0,
                0
        );
    }

    public QueueSnapshot snapshot() {
        synchronized (monitor) {
            return snapshotLocked();
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
            synchronized (monitor) {
                runningTasks.remove(task.judgeId);
            }
            persistStatus(task.judgeId, JudgeStatus.SYSTEM_ERROR, "Judge executor rejected the task");
            startNextQueuedTask();
            throw e;
        }
    }

    private void runTask(ScheduledTask task) {
        ScheduledFuture<?> budgetFuture = scheduleBudget(task);
        try {
            task.context.markStarted();
            persistStatus(task.judgeId, JudgeStatus.RUNNING, "Judge task is running");
            task.job.run(task.context);
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
                persistStatus(task.judgeId, JudgeStatus.SYSTEM_ERROR, "Judge task failed unexpectedly");
            }
        } finally {
            budgetFuture.cancel(false);
            synchronized (monitor) {
                runningTasks.remove(task.judgeId);
            }
            startNextQueuedTask();
        }
    }

    private ScheduledFuture<?> scheduleBudget(ScheduledTask task) {
        Duration budget = task.context.maxTaskRuntime();
        if (budget == null || budget.isZero() || budget.isNegative()) {
            return new CompletedScheduledFuture();
        }
        return budgetExecutor.schedule(() -> {
            if (task.context.cancellationToken().cancelForBudgetExceeded()) {
                persistBudgetExceeded(task);
            }
        }, budget.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void startNextQueuedTask() {
        ScheduledTask nextTask = null;
        synchronized (monitor) {
            if (runningTasks.size() < maxConcurrentTasks() && !queue.isEmpty()) {
                nextTask = queue.removeFirst();
                queuedTasks.remove(nextTask.judgeId);
                runningTasks.put(nextTask.judgeId, nextTask);
                peakRunningCount = Math.max(peakRunningCount, runningTasks.size());
            }
        }
        if (nextTask != null) {
            execute(nextTask);
        }
    }

    private TaskContext contextFor(String judgeId) {
        JudgeTask task = findTask(judgeId)
                .orElseThrow(() -> new IllegalArgumentException("Judge task not found: " + judgeId));
        Duration maxTaskRuntime = task.getPolicy() != null && task.getPolicy().maxTaskRuntime() != null
                ? task.getPolicy().maxTaskRuntime()
                : executionProperties.getMaxTaskRuntime();
        return new TaskContext(judgeId, task.getRequestedCases(), maxTaskRuntime);
    }

    private Optional<JudgeTask> findTask(String judgeId) {
        try {
            return taskStore.find(judgeId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read judge task " + judgeId, e);
        }
    }

    private void completeIfNeeded(ScheduledTask task) {
        if (task.context.cancellationToken().isBudgetExceeded()) {
            persistBudgetExceeded(task);
            return;
        }
        if (task.context.cancellationToken().isCancellationRequested()) {
            persistCancelled(task);
            return;
        }
        if (!isTerminal(task.judgeId)) {
            persistStatus(task.judgeId, JudgeStatus.COMPLETED, "Judge task completed");
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

    private void persistCancelled(ScheduledTask task) {
        if (hasDifferentTerminalStatus(task.judgeId, JudgeStatus.CANCELLED)) {
            return;
        }
        persistStatus(task.judgeId, JudgeStatus.CANCELLED, CANCELLED_MESSAGE);
        if (hasDifferentTerminalStatus(task.judgeId, JudgeStatus.CANCELLED)) {
            return;
        }
        persistStoppedSummary(task, JudgeStatus.CANCELLED, CANCELLED_MESSAGE);
    }

    private void persistBudgetExceeded(ScheduledTask task) {
        if (hasDifferentTerminalStatus(task.judgeId, JudgeStatus.BUDGET_EXCEEDED)) {
            return;
        }
        persistStatus(task.judgeId, JudgeStatus.BUDGET_EXCEEDED, BUDGET_MESSAGE);
        if (hasDifferentTerminalStatus(task.judgeId, JudgeStatus.BUDGET_EXCEEDED)) {
            return;
        }
        persistStoppedSummary(task, JudgeStatus.BUDGET_EXCEEDED, BUDGET_MESSAGE);
    }

    private boolean hasDifferentTerminalStatus(String judgeId, JudgeStatus status) {
        return terminalStatus(judgeId)
                .map(existing -> existing != status)
                .orElse(false);
    }

    private void persistStoppedSummary(ScheduledTask task, JudgeStatus status, String message) {
        JudgeSummary summary = new JudgeSummary(
                task.context.totalCases(),
                task.context.completedCases(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                List.of(),
                List.of(),
                message
        );
        int progress = task.context.totalCases() == 0
                ? 100
                : Math.min(100, (int) ((double) task.context.completedCases() / task.context.totalCases() * 100));
        try {
            taskStore.saveSummary(task.judgeId, new JudgeProgress(status.name(), message, progress, null, summary));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save stopped judge summary for " + task.judgeId, e);
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
        private final CancellationToken cancellationToken = new CancellationToken();
        private final AtomicInteger completedCases = new AtomicInteger();
        private volatile Instant startedAt;

        private TaskContext(String judgeId, int totalCases, Duration maxTaskRuntime) {
            this.judgeId = judgeId;
            this.totalCases = totalCases;
            this.maxTaskRuntime = maxTaskRuntime;
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

        public CancellationToken cancellationToken() {
            return cancellationToken;
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
