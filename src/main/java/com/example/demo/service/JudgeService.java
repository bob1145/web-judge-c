package com.example.demo.service;

import com.example.demo.config.AsyncConfig;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.MemoryConfiguration;
import com.example.demo.config.SecurityModeStartupValidator;
import com.example.demo.dto.CancelJudgeResponse;
import com.example.demo.dto.JudgeCreateResponse;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxComparison;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import com.example.demo.service.sandbox.SandboxRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class JudgeService {

    private static final long SANDBOX_POLL_NANOS = Duration.ofMillis(100).toNanos();
    private static final long SANDBOX_EXIT_FLUSH_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long SANDBOX_CLEANUP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final Pattern INTEGER_TOKEN = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern OUTPUT_TOKEN = Pattern.compile("[^ \\t\\n\\r\\f\\x0B]+");

    private final MemoryConfiguration memoryConfiguration;
    private final ExecutionProperties executionProperties;
    private final SandboxProcessRunner processRunner;
    private final ProgressPublisher progressPublisher;
    private final SandboxEventIngestor sandboxEventIngestor;
    private final TaskPolicyResolver taskPolicyResolver;
    private final QuotaService quotaService;
    private final TaskStore taskStore;
    private final CaseBatchRunner caseBatchRunner;
    private final JudgeScheduler judgeScheduler;
    private final SecurityModeStartupValidator securityModeStartupValidator;
    private final Map<String, PendingJudgeTask> pendingJudgeTasks = new ConcurrentHashMap<>();
    private final Object createdTaskScanMonitor = new Object();
    private Iterator<Map.Entry<String, PendingJudgeTask>> createdTaskScan;
    
    @Qualifier(AsyncConfig.TEST_CASE_EXECUTOR)
    private final ThreadPoolTaskExecutor testCaseExecutor;
    private final Optional<SandboxRunner> sandboxRunner;
    
    /**
     * 标记WebSocket会话为活跃状态
     */
    public void markSessionActive(String judgeId) {
        log.debug("WebSocket会话激活: {}", judgeId);
    }

    /**
     * 标记WebSocket会话为非活跃状态
     */
    public void markSessionInactive(String judgeId) {
        log.debug("WebSocket会话停用: {}", judgeId);
    }
    
    /**
     * 安全地发送WebSocket消息，避免向已关闭的会话发送消息
     * 同时保存状态用于轮询
     */
    private void safeSendMessage(String topic, JudgeProgress progress) {
        String judgeId = topic.substring(topic.lastIndexOf('/') + 1);
        progressPublisher.publish(judgeId, progress);
    }

    private void safeSendStoppedMessage(
            String topic,
            CancellationToken cancellationToken,
            int progress,
            JudgeSummary summary
    ) {
        if (cancellationToken.isBudgetExceeded()) {
            safeSendMessage(topic, new JudgeProgress(
                    "BUDGET_EXCEEDED",
                    "Task runtime budget exceeded",
                    progress,
                    null,
                    summary
            ));
        } else {
            safeSendMessage(topic, new JudgeProgress(
                    "CANCELLED",
                    "Task cancelled",
                    progress,
                    null,
                    summary
            ));
        }
    }

    private JudgeSummary emptyStoppedSummary(int totalCases, int completedCases, CancellationToken cancellationToken) {
        String stoppedReason = cancellationToken.isBudgetExceeded()
                ? "Task runtime budget exceeded"
                : "Cancellation requested";
        return new JudgeSummary(
                totalCases,
                completedCases,
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
                stoppedReason
        );
    }

    /**
     * 获取判题状态，用于轮询
     */
    public JudgeProgress getJudgeStatus(String judgeId) {
        try {
            Optional<JudgeProgress> summary = taskStore.findSummary(judgeId);
            if (summary.isPresent()) {
                return summary.get();
            }
            Optional<JudgeTask> task = taskStore.find(judgeId);
            if (task.isPresent()) {
                JudgeTask judgeTask = task.get();
                int progress = judgeTask.getStatus().isTerminal() ? 100 : 0;
                String message = judgeTask.getMessage() != null
                        ? judgeTask.getMessage()
                        : judgeTask.getStatus().name();
                return new JudgeProgress(judgeTask.getStatus().name(), message, progress);
            }
        } catch (Exception readFailure) {
            throw new IllegalArgumentException("Judge status not found: " + judgeId, readFailure);
        }
        throw new IllegalArgumentException("Judge status not found: " + judgeId);
    }
    
    /**
     * 清理已完成的判题任务
     */
    public void cleanupJudgeTask(String judgeId) {
        try {
            // 标记WebSocket会话为非活跃
            markSessionInactive(judgeId);
            
        } catch (Exception e) {
            log.error("清理判题任务失败: {} - {}", judgeId, e.getMessage());
        }
    }

    private record PendingJudgeTask(JudgeRequest request, ResolvedTaskPolicy policy) {}

    static class CompilationException extends RuntimeException {
        public CompilationException(String message) {
            super(message);
        }
    }

    /**
     * 创建判题任务但不立即执行，等待WebSocket连接建立
     */
    public JudgeCreateResponse createJudgeTask(JudgeRequest request, String judgeId) {
        return createJudgeTask(request, judgeId, null);
    }

    public JudgeCreateResponse createJudgeTask(JudgeRequest request, String judgeId, UserSession ownerSession) {
        securityModeStartupValidator.assertJudgeCreationAllowed();
        ResolvedTaskPolicy policy = taskPolicyResolver.resolve(request);
        synchronized (ownerSession == null ? this : quotaService.creationLockFor(ownerSession)) {
            JudgeOwnership ownership = ownerSession == null
                    ? JudgeOwnership.anonymous()
                    : quotaService.ownershipFor(ownerSession);
            if (ownerSession != null) {
                quotaService.assertCanCreate(ownerSession, policy,
                        judgeScheduler.runningTaskIdsForOwner(ownership.getUserId()));
            }
            Path workDir = taskStore.taskDirectory(judgeId);
            JudgeTask task = JudgeTask.builder()
                    .judgeId(judgeId)
                    .status(JudgeStatus.CREATED)
                    .requestedCases(policy.requestedCases())
                    .mode(policy.profile())
                    .policy(policy)
                    .ownership(ownership)
                    .workDir(workDir.toString())
                    .createdAt(Instant.now())
                    .build();
            PendingJudgeTask pendingTask = new PendingJudgeTask(request, policy);
            boolean created = false;
            try {
                taskStore.create(task, () -> {
                    if (pendingJudgeTasks.putIfAbsent(judgeId, pendingTask) != null) {
                        throw new IllegalStateException("Judge task is already registered");
                    }
                });
                created = true;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create judge task", e);
            } finally {
                if (!created) {
                    pendingJudgeTasks.computeIfPresent(judgeId,
                            (ignored, registered) -> registered == pendingTask ? null : registered);
                }
            }
            return JudgeCreateResponse.created(judgeId, policy);
        }
    }

    public boolean canAccessJudgeTask(String judgeId, UserSession session) {
        return quotaService.canAccessTask(judgeId, session);
    }

    /**
     * 启动指定的判题任务
     */
    public void startJudgeTask(String judgeId) {
        PendingJudgeTask pendingTask = pendingJudgeTasks.get(judgeId);
        if (pendingTask == null) {
            judgeScheduler.requireScheduled(judgeId);
            return;
        }
        // 标记WebSocket会话为活跃
        markSessionActive(judgeId);
        try {
            judgeScheduler.enqueue(judgeId, context -> judge(pendingTask.request(), judgeId, pendingTask.policy(), context));
        } catch (JudgeScheduler.TaskAlreadyFinishedException e) {
            pendingJudgeTasks.remove(judgeId, pendingTask);
            markSessionInactive(judgeId);
            throw e;
        }
        pendingJudgeTasks.remove(judgeId, pendingTask);
    }

    public CancelJudgeResponse cancelJudgeTask(String judgeId) {
        CancelJudgeResponse response = judgeScheduler.cancel(judgeId);
        if (response.accepted() || JudgeStatus.fromProgressStatus(response.status())
                .filter(JudgeStatus::isTerminal).isPresent()) {
            pendingJudgeTasks.remove(judgeId);
            markSessionInactive(judgeId);
        }
        return response;
    }

    @Scheduled(
            fixedDelayString = "${judge.execution.created-task-scan-interval:1m}",
            initialDelayString = "${judge.execution.created-task-scan-interval:1m}"
    )
    public void expireCreatedTasks() {
        expireCreatedTasks(Instant.now());
    }

    public void expireCreatedTasks(Instant now) {
        synchronized (createdTaskScanMonitor) {
            if (createdTaskScan == null || !createdTaskScan.hasNext()) {
                createdTaskScan = pendingJudgeTasks.entrySet().iterator();
            }
            long started = System.nanoTime();
            long budget = executionProperties.getCreatedTaskScanBudget().toNanos();
            for (int examined = 0; examined < executionProperties.getCreatedTaskScanMaxTasks()
                    && System.nanoTime() - started < budget && createdTaskScan.hasNext(); examined++) {
                Map.Entry<String, PendingJudgeTask> pending = createdTaskScan.next();
                String judgeId = pending.getKey();
                try {
                    if (judgeScheduler.expireCreatedTask(judgeId, now).isPresent()
                            && pendingJudgeTasks.remove(judgeId, pending.getValue())) {
                        progressPublisher.clearThrottleState(judgeId);
                        markSessionInactive(judgeId);
                    }
                } catch (RuntimeException ex) {
                    log.warn("Failed to expire CREATED task {}: {}", judgeId, ex.getMessage());
                }
            }
        }
    }

    public void judge(
            JudgeRequest request,
            String judgeId,
            ResolvedTaskPolicy policy,
            JudgeScheduler.TaskContext schedulerContext
    ) {
        Path tempDir = null;
        String topic = "/topic/progress/" + judgeId;
        CancellationToken cancellationToken = schedulerContext.cancellationToken();

        try {
            tempDir = resolveTaskWorkDir(judgeId);

            safeSendMessage(topic, new JudgeProgress("PENDING", "创建临时目录...", 0));
            if (cancellationToken.isCancellationRequested()) {
                safeSendStoppedMessage(topic, cancellationToken, 0, emptyStoppedSummary(policy.requestedCases(), schedulerContext.completedCases(), cancellationToken));
                cleanupJudgeTask(judgeId);
                return;
            }

            if (shouldUseSandboxRunner(policy)) {
                runWithSandboxRunner(request, judgeId, policy, schedulerContext, tempDir, topic);
                cleanupJudgeTask(judgeId);
                return;
            }

            try {
                safeSendMessage(topic, new JudgeProgress("COMPILING", "正在编译代码...", 5));

                Map<SandboxTaskSpec.SourceRole, Path> sources = prepareTaskSources(request, judgeId, tempDir);
                Path genSource = sources.get(SandboxTaskSpec.SourceRole.GENERATOR);
                Path userSource = sources.get(SandboxTaskSpec.SourceRole.USER);

                CompletableFuture<Path> genFuture = CompletableFuture.supplyAsync(() -> compile(genSource, "generator", policy), testCaseExecutor);
                CompletableFuture<Path> userFuture = CompletableFuture.supplyAsync(() -> compile(userSource, "user", policy), testCaseExecutor);

                // 根据是否启用Special Judge决定编译内容
                CompletableFuture<Path> judgeExecutableFuture;
                if (request.isUseSpecialJudge() && request.getSpecialJudgeCode() != null && !request.getSpecialJudgeCode().trim().isEmpty()) {
                    // 编译Special Judge代码
                    Path spjSource = sources.get(SandboxTaskSpec.SourceRole.SPECIAL_JUDGE);
                    judgeExecutableFuture = CompletableFuture.supplyAsync(() -> compile(spjSource, "special_judge", policy), testCaseExecutor);
                } else {
                    // 编译Brute Force代码
                    Path bfSource = sources.get(SandboxTaskSpec.SourceRole.ORACLE);
                    judgeExecutableFuture = CompletableFuture.supplyAsync(() -> compile(bfSource, "bruteforce", policy), testCaseExecutor);
                }

                CompletableFuture.allOf(genFuture, userFuture, judgeExecutableFuture).join();

                final Path genExecutable = genFuture.get();
                final Path userExecutable = userFuture.get();
                final Path judgeExecutable = judgeExecutableFuture.get();

                if (cancellationToken.isCancellationRequested()) {
                    safeSendStoppedMessage(topic, cancellationToken, 15, emptyStoppedSummary(policy.requestedCases(), schedulerContext.completedCases(), cancellationToken));
                    cleanupJudgeTask(judgeId);
                    return;
                }

                AtomicInteger completedCases = new AtomicInteger(0);

                final int totalTestCases = policy.requestedCases();
                final int updateThreshold = Math.max(1, totalTestCases / 100); // Update every 1%
                ResultAggregator resultAggregator = new ResultAggregator(
                        policy.highVolume(),
                        totalTestCases,
                        executionProperties.getMaxFailureSamples(),
                        executionProperties.getMaxSlowSamples()
                );
                safeSendMessage(topic, new JudgeProgress("RUNNING", "编译成功，开始运行测试...", 15,
                        null, resultAggregator.toSummary()));
                Path finalTempDir = tempDir;
                CaseBatchRunner.RunOutcome runOutcome = caseBatchRunner.run(
                        totalTestCases,
                        policy,
                        cancellationToken,
                        caseNumber -> runTestCase(caseNumber, judgeId, request, policy, finalTempDir, genExecutable, userExecutable, judgeExecutable),
                        result -> {
                            resultAggregator.accept(result);
                            schedulerContext.recordCompletedCase();
                            int done = completedCases.incrementAndGet();
                            if (!cancellationToken.isCancellationRequested()
                                    && (done % updateThreshold == 0 || done == totalTestCases)) {
                                int progress = Math.min(99, 15 + (int) ((double) done / totalTestCases * 85));
                                safeSendMessage(topic, new JudgeProgress("RUNNING", String.format("已完成 %d / %d", done, totalTestCases), progress, null, resultAggregator.toSummary()));
                            }
                        },
                        result -> request.isStopOnFirstNonAc() && !isAcceptedStatus(result.getStatus())
                );

                if (cancellationToken.isBudgetExceeded()) {
                    JudgeSummary summary = resultAggregator.toSummary();
                    summary.setStoppedReason("Task runtime budget exceeded");
                    int progress = 15 + (int) ((double) runOutcome.getCompletedCases() / totalTestCases * 85);
                    safeSendMessage(topic, new JudgeProgress("BUDGET_EXCEEDED", "Task runtime budget exceeded", progress, null, summary));
                } else if (runOutcome.isCancelled()) {
                    JudgeSummary summary = resultAggregator.toSummary();
                    summary.setStoppedReason("Cancellation requested");
                    int progress = 15 + (int) ((double) runOutcome.getCompletedCases() / totalTestCases * 85);
                    safeSendMessage(topic, new JudgeProgress("CANCELLED", "Task cancelled", progress, null, summary));
                } else if (runOutcome.isStoppedAfterResult()) {
                    JudgeProgress finalProgress = resultAggregator.toFinalProgress();
                    if (finalProgress.getSummary() != null) {
                        finalProgress.getSummary().setStoppedReason("Stopped after first non-AC test case");
                    }
                    safeSendMessage(topic, finalProgress);
                } else {
                    safeSendMessage(topic, resultAggregator.toFinalProgress());
                }
                
                // 判题完成后清理资源
                cleanupJudgeTask(judgeId);

            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CompilationException) {
                    safeSendMessage(topic, new JudgeProgress("COMPILATION_ERROR", cause.getMessage(), 100, null));
                } else {
                    e.printStackTrace();
                    safeSendMessage(topic, new JudgeProgress("SYSTEM_ERROR", "An unexpected error occurred during compilation.", 100, null));
                }
                cleanupJudgeTask(judgeId);
                return;
            } catch (Exception e) {
                e.printStackTrace();
                safeSendMessage(topic, new JudgeProgress("SYSTEM_ERROR", e.getMessage(), 100, null));
                cleanupJudgeTask(judgeId);
            }

        } catch (Exception e) {
            e.printStackTrace();
            try {
                safeSendMessage(topic, new JudgeProgress("SYSTEM_ERROR", e.getMessage(), 100, null));
            } finally {
                cleanupJudgeTask(judgeId);
            }
        }
    }

    private boolean shouldUseSandboxRunner(ResolvedTaskPolicy policy) {
        return policy.sandboxRequired() || policy.profile().endsWith("-prod");
    }

    private void runWithSandboxRunner(
            JudgeRequest request,
            String judgeId,
            ResolvedTaskPolicy policy,
            JudgeScheduler.TaskContext schedulerContext,
            Path workDir,
            String topic
    ) {
        SandboxEventIngestor.Session eventSession = sandboxEventIngestor.start(
                judgeId, policy, request.isStopOnFirstNonAc());
        if (sandboxRunner.isEmpty()) {
            eventSession.terminal(
                    JudgeStatus.SANDBOX_UNAVAILABLE.name(),
                    "SandboxRunner is not configured for profile " + policy.profile()
            );
            return;
        }

        SandboxRunHandle handle = null;
        SandboxRunner runner = sandboxRunner.get();
        boolean startRequested = false;
        long cleanupStarted = 0;
        try {
            SandboxTaskSpec spec = buildSandboxTaskSpec(request, judgeId, policy, workDir);
            Duration startupBudget = schedulerContext.remainingRuntime();
            if (startupBudget.isZero()) {
                schedulerContext.cancellationToken().cancelForBudgetExceeded();
            }
            if (schedulerContext.cancellationToken().isCancellationRequested()) {
                eventSession.terminal(schedulerContext.cancellationToken().isBudgetExceeded()
                        ? JudgeStatus.BUDGET_EXCEEDED.name() : JudgeStatus.CANCELLED.name(),
                        schedulerContext.cancellationToken().isBudgetExceeded()
                                ? "Task runtime budget exceeded" : "Cancellation requested");
                return;
            }
            startRequested = true;
            handle = runner.start(spec, startupBudget);
            persistRunnerHandle(judgeId, handle);
            eventSession.bindRunHandle(handle);
            boolean terminal = false;
            boolean exited = false;
            long exitObserved = 0;
            while (!terminal && !schedulerContext.cancellationToken().isCancellationRequested()) {
                long remaining = schedulerContext.remainingRuntime().toNanos();
                if (remaining <= 0) {
                    schedulerContext.cancellationToken().cancelForBudgetExceeded();
                    break;
                }
                if (exited && System.nanoTime() - exitObserved >= SANDBOX_EXIT_FLUSH_NANOS) {
                    break;
                }
                if (exited) {
                    remaining = Math.min(remaining, SANDBOX_EXIT_FLUSH_NANOS - (System.nanoTime() - exitObserved));
                    if (remaining <= 0) {
                        break;
                    }
                }
                List<SandboxTaskEvent> events;
                try {
                    events = runner.pollEvents(handle, Duration.ofNanos(remaining));
                } catch (IllegalStateException exception) {
                    if (!(exception.getCause() instanceof HttpTimeoutException)
                            && !(exception.getCause() instanceof ConnectException)) {
                        throw exception;
                    }
                    events = List.of();
                }
                if (events == null) {
                    throw new IllegalStateException("SandboxRunner returned a null event batch");
                }
                for (SandboxTaskEvent event : events) {
                    if (terminal) {
                        break;
                    }
                    if (schedulerContext.remainingRuntime().isZero()) {
                        schedulerContext.cancellationToken().cancelForBudgetExceeded();
                    }
                    if (schedulerContext.cancellationToken().isCancellationRequested()) {
                        break;
                    }
                    JudgeProgress progress = eventSession.accept(event);
                    terminal = eventSession.isTerminal(progress);
                }
                if (terminal || !events.isEmpty()) {
                    continue;
                }
                remaining = schedulerContext.remainingRuntime().toNanos();
                if (remaining <= 0) {
                    schedulerContext.cancellationToken().cancelForBudgetExceeded();
                    break;
                }
                if (!exited) {
                    SandboxRunner.ExecutionState state = runner.executionState(handle, Duration.ofNanos(remaining));
                    if (state == null) {
                        throw new IllegalStateException("SandboxRunner execution state is not confirmed");
                    }
                    if (state == SandboxRunner.ExecutionState.EXITED) {
                        exited = true;
                        exitObserved = System.nanoTime();
                    }
                }
                remaining = schedulerContext.remainingRuntime().toNanos();
                long waitNanos = Math.min(SANDBOX_POLL_NANOS, remaining);
                if (exited) {
                    waitNanos = Math.min(waitNanos, SANDBOX_EXIT_FLUSH_NANOS - (System.nanoTime() - exitObserved));
                }
                if (waitNanos > 0) {
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(waitNanos);
                }
            }
            if (schedulerContext.cancellationToken().isCancellationRequested()) {
                if (handle != null) {
                    cleanupStarted = System.nanoTime();
                    runner.cancel(handle, Duration.ofNanos(SANDBOX_CLEANUP_NANOS));
                }
                String status = schedulerContext.cancellationToken().isBudgetExceeded()
                        ? JudgeStatus.BUDGET_EXCEEDED.name()
                        : JudgeStatus.CANCELLED.name();
                String message = schedulerContext.cancellationToken().isBudgetExceeded()
                        ? "Task runtime budget exceeded"
                        : "Cancellation requested";
                eventSession.terminal(status, message);
            } else if (!terminal) {
                eventSession.terminal(
                        JudgeStatus.SYSTEM_ERROR.name(),
                        "SandboxRunner finished without a terminal event"
                );
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (schedulerContext.remainingRuntime().isZero()) {
                schedulerContext.cancellationToken().cancelForBudgetExceeded();
            }
            if (schedulerContext.cancellationToken().isBudgetExceeded()) {
                eventSession.terminal(JudgeStatus.BUDGET_EXCEEDED.name(), "Task runtime budget exceeded");
            } else {
                eventSession.terminal(JudgeStatus.SYSTEM_ERROR.name(), "SandboxRunner failed: " + e.getMessage());
            }
        } finally {
            if (startRequested && handle == null) {
                schedulerContext.markCleanupPending();
            }
            cleanupStartedHandle(runner, handle, schedulerContext,
                    cleanupStarted == 0 ? System.nanoTime() : cleanupStarted);
        }
    }

    private void persistRunnerHandle(String judgeId, SandboxRunHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            taskStore.saveRunHandle(judgeId, handle);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to persist sandbox run handle", ex);
        }
    }

    private void cleanupStartedHandle(
            SandboxRunner runner, SandboxRunHandle handle, JudgeScheduler.TaskContext schedulerContext, long started) {
        if (handle == null) {
            return;
        }
        try {
            long remaining = SANDBOX_CLEANUP_NANOS - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new IllegalStateException("Sandbox cleanup deadline exceeded; stop is not confirmed");
            }
            runner.cleanupResidual(handle, Duration.ofNanos(remaining));
        } catch (Exception cleanupFailure) {
            schedulerContext.markCleanupPending();
            log.warn("Failed to cleanup sandbox run for judge task {}: {}",
                    schedulerContext.judgeId(), cleanupFailure.getMessage());
        }
    }

    private SandboxTaskSpec buildSandboxTaskSpec(
            JudgeRequest request,
            String judgeId,
            ResolvedTaskPolicy policy,
            Path workDir
    ) throws IOException {
        Map<SandboxTaskSpec.SourceRole, Path> sources = prepareTaskSources(request, judgeId, workDir);

        SandboxTaskSpec.Builder builder = SandboxTaskSpec.builder()
                .judgeId(judgeId)
                .userId(userIdForTask(judgeId))
                .profile(policy.profile())
                .storageBase(storageBaseFor(workDir))
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, sources.get(SandboxTaskSpec.SourceRole.GENERATOR))
                .sourcePath(SandboxTaskSpec.SourceRole.USER, sources.get(SandboxTaskSpec.SourceRole.USER))
                .testCases(policy.requestedCases())
                .comparison(policy.comparison())
                .caseTimeLimit(policy.caseTimeLimit())
                .maxTaskRuntime(policy.maxTaskRuntime())
                .memoryLimitBytes(policy.memoryLimitBytes())
                .maxOutputBytesPerCase(policy.maxOutputBytesPerCase())
                .stopOnFirstNonAc(request.isStopOnFirstNonAc())
                .retention(new SandboxTaskSpec.Retention(
                        executionProperties.getCompletedRetention(),
                        executionProperties.getFailedRetention(),
                        executionProperties.getCancelledRetention()
                ));

        if (request.isUseSpecialJudge() && request.getSpecialJudgeCode() != null && !request.getSpecialJudgeCode().trim().isEmpty()) {
            builder.sourcePath(
                    SandboxTaskSpec.SourceRole.SPECIAL_JUDGE,
                    sources.get(SandboxTaskSpec.SourceRole.SPECIAL_JUDGE)
            );
        } else {
            builder.sourcePath(
                    SandboxTaskSpec.SourceRole.ORACLE,
                    sources.get(SandboxTaskSpec.SourceRole.ORACLE)
            );
        }
        return builder.build();
    }

    private boolean isAcceptedStatus(String status) {
        return "AC".equals(normalizeStatus(status));
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            return "";
        }
        return status.trim()
                .toUpperCase()
                .replace(' ', '_')
                .replace('-', '_');
    }

    private String userIdForTask(String judgeId) {
        try {
            return taskStore.find(judgeId)
                    .map(JudgeTask::getOwnership)
                    .map(JudgeOwnership::getUserId)
                    .filter(userId -> userId != null && !userId.isBlank())
                    .orElse("anonymous");
        } catch (IOException ex) {
            return "anonymous";
        }
    }

    private Map<SandboxTaskSpec.SourceRole, Path> prepareTaskSources(
            JudgeRequest request, String judgeId, Path workDir
    ) throws IOException {
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(workDir.getParent(), false)) {
            requireTaskWorkDir(judgeId, workDir, admission);
            Map<SandboxTaskSpec.SourceRole, Path> sources = new EnumMap<>(SandboxTaskSpec.SourceRole.class);
            sources.put(SandboxTaskSpec.SourceRole.GENERATOR,
                    writeSource(admission, workDir, "generator.cpp", request.getGeneratorCode()));
            sources.put(SandboxTaskSpec.SourceRole.USER,
                    writeSource(admission, workDir, "user.cpp", request.getUserCode()));
            if (request.isUseSpecialJudge() && request.getSpecialJudgeCode() != null
                    && !request.getSpecialJudgeCode().trim().isEmpty()) {
                sources.put(SandboxTaskSpec.SourceRole.SPECIAL_JUDGE,
                        writeSource(admission, workDir, "special_judge.cpp", request.getSpecialJudgeCode()));
            } else {
                sources.put(SandboxTaskSpec.SourceRole.ORACLE,
                        writeSource(admission, workDir, "bruteforce.cpp", request.getBruteForceCode()));
            }
            return Map.copyOf(sources);
        }
    }

    private Path writeTaskFile(String judgeId, Path workDir, String filename, String content) throws IOException {
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(workDir.getParent(), false)) {
            requireTaskWorkDir(judgeId, workDir, admission);
            return writeSource(admission, workDir, filename, content);
        }
    }

    private Path writeSource(TaskLifecycleGuard.Lease admission, Path workDir, String filename, String source) throws IOException {
        admission.requireTaskDirectory(workDir);
        Path sourcePath = workDir.resolve(filename);
        Files.writeString(sourcePath, source == null ? "" : source);
        admission.requireTaskDirectory(workDir);
        return sourcePath;
    }

    private Path storageBaseFor(Path workDir) {
        if (taskStore instanceof FileTaskStore fileTaskStore) {
            return fileTaskStore.storageBase();
        }
        Path parent = workDir.toAbsolutePath().normalize().getParent();
        return parent == null ? workDir.toAbsolutePath().normalize() : parent;
    }

    private TestCaseResult runTestCase(int caseNumber, String judgeId, JudgeRequest request, ResolvedTaskPolicy policy, Path tempDir, Path genExecutable, Path userExecutable, Path judgeExecutable) {
        try {
            Path inputFile = tempDir.resolve(caseNumber + ".in");
            Path userOutputFile = tempDir.resolve(caseNumber + ".out");

            ProcessResult genResult = runProcess(genExecutable, null, inputFile, 5000, memoryConfiguration.getDefaultLimit(), policy);
            if (genResult.status() != ProcessResult.Status.SUCCESS) {
                return new TestCaseResult(caseNumber, generatorFailureStatus(genResult.status()), 0, 0);
            }

            // 使用创建任务时解析出的策略快照，避免启动时被新配置覆盖。
            long userMemoryLimit = policy.memoryLimitBytes();
            long caseTimeLimit = policy.caseTimeLimit().toMillis();
            ProcessResult userResult = runProcess(userExecutable, inputFile, userOutputFile, caseTimeLimit, userMemoryLimit, policy);
            if (userResult.status() != ProcessResult.Status.SUCCESS) {
                String statusStr = switch (userResult.status()) {
                    case TIME_LIMIT_EXCEEDED -> "TLE";
                    case MEMORY_LIMIT_EXCEEDED -> "MLE";
                    case OUTPUT_LIMIT_EXCEEDED -> "OUTPUT_LIMIT_EXCEEDED";
                    case RUNTIME_ERROR -> "RE";
                    default -> "System Error";
                };
                return new TestCaseResult(caseNumber, statusStr, userResult.executionTime(), userResult.memoryUsed() / 1024); // Convert to KB
            }

            // 根据是否启用Special Judge选择不同的判题逻辑
            if (request.isUseSpecialJudge() && request.getSpecialJudgeCode() != null && !request.getSpecialJudgeCode().trim().isEmpty()) {
                // 使用Special Judge进行判题
                return runSpecialJudge(caseNumber, judgeId, request, policy, tempDir, judgeExecutable, inputFile, userOutputFile, userResult);
            } else {
                // 使用Brute Force进行判题
                return runBruteForceJudge(caseNumber, request, policy, tempDir, judgeExecutable, inputFile, userOutputFile, userResult);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new TestCaseResult(caseNumber, "System Error", 0, 0);
        }
    }

    private String generatorFailureStatus(ProcessResult.Status status) {
        if (status == ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED) {
            return "OUTPUT_LIMIT_EXCEEDED";
        }
        return "System Error";
    }
    
    /**
     * 使用Special Judge进行判题
     */
    private TestCaseResult runSpecialJudge(int caseNumber, String judgeId, JudgeRequest request, ResolvedTaskPolicy policy, Path tempDir, Path spjExecutable,
                                         Path inputFile, Path userOutputFile, ProcessResult userResult) throws IOException, InterruptedException {
        // Special Judge通常接受三个参数：输入文件、用户输出文件、标准输出文件（可选）
        // 这里我们只传递输入文件和用户输出文件，SPJ程序负责验证输出是否正确
        // SPJ程序应该返回退出码：0表示AC，非0表示WA或其他错误
        
        long spjTimeLimit = policy.caseTimeLimit().toMillis() * 2;
        // 为SPJ创建参数文件，传递必要信息
        String args = String.format("%s\n%s\n", inputFile.toAbsolutePath(), userOutputFile.toAbsolutePath());
        Path spjArgsFile = writeTaskFile(judgeId, tempDir, caseNumber + ".spj_args", args);
        
        // 重新运行SPJ，这次传递参数文件
        ProcessResult spjResult = runProcess(
                spjExecutable,
                spjArgsFile,
                null,
                spjTimeLimit,
                memoryConfiguration.getDefaultLimit(),
                policy
        );
        Files.deleteIfExists(spjArgsFile);

        if (spjResult.status() == ProcessResult.Status.TIME_LIMIT_EXCEEDED
                || spjResult.status() == ProcessResult.Status.MEMORY_LIMIT_EXCEEDED
                || spjResult.status() == ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED
                || spjResult.status() == ProcessResult.Status.SECURITY_VIOLATION
                || spjResult.status() == ProcessResult.Status.SANDBOX_UNAVAILABLE) {
            return new TestCaseResult(caseNumber, generatorFailureStatus(spjResult.status()), userResult.executionTime(), userResult.memoryUsed() / 1024);
        }

        int spjExitCode = spjResult.exitCode();
        long spjExecutionTime = spjResult.executionTime();
        
        log.debug("SPJ执行结果: exitCode={}, executionTime={}ms", spjExitCode, spjExecutionTime);
        
        if (spjResult.exitCode() == 0) {
            return new TestCaseResult(caseNumber, "AC", userResult.executionTime(), userResult.memoryUsed() / 1024);
        } else {
            // 根据SPJ的退出码决定结果，通常非0表示WA
            String status = switch (spjResult.exitCode()) {
                case 1 -> "WA";  // Wrong Answer
                case 2 -> "PE";  // Presentation Error
                default -> "WA"; // 默认为Wrong Answer
            };
            return new TestCaseResult(caseNumber, status, userResult.executionTime(), userResult.memoryUsed() / 1024);
        }
    }
    
    /**
     * 使用Brute Force进行判题
     */
    private TestCaseResult runBruteForceJudge(int caseNumber, JudgeRequest request, ResolvedTaskPolicy policy, Path tempDir, Path bfExecutable,
                                            Path inputFile, Path userOutputFile, ProcessResult userResult) throws IOException, InterruptedException {
        Path bfOutputFile = tempDir.resolve(caseNumber + ".ans");
        
        ProcessResult bfResult = runProcess(bfExecutable, inputFile, bfOutputFile, policy.caseTimeLimit().toMillis() * 5, memoryConfiguration.getDefaultLimit() * 2, policy);
        if (bfResult.status() != ProcessResult.Status.SUCCESS) {
            return new TestCaseResult(caseNumber, generatorFailureStatus(bfResult.status()), 0, 0);
        }

        String userOutput = readUtf8FileLimited(userOutputFile, policy.maxOutputBytesPerCase());
        String bfOutput = readUtf8FileLimited(bfOutputFile, policy.maxOutputBytesPerCase());

        if (outputsMatch(userOutput, bfOutput, policy.comparison().precision())) {
            return new TestCaseResult(caseNumber, "AC", userResult.executionTime(), userResult.memoryUsed() / 1024);
        } else {
            return new TestCaseResult(caseNumber, "WA", userResult.executionTime(), userResult.memoryUsed() / 1024);
        }
    }

    private Path resolveTaskWorkDir(String judgeId) throws IOException {
        Path workDir = taskStore.taskDirectory(judgeId);
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(workDir.getParent(), false)) {
            requireTaskWorkDir(judgeId, workDir, admission);
            return workDir;
        }
    }

    private void requireTaskWorkDir(String judgeId, Path workDir, TaskLifecycleGuard.Lease admission) throws IOException {
        if (!workDir.equals(taskStore.taskDirectory(judgeId))) {
            throw new IOException("Task work directory does not match its storage identity");
        }
        admission.requireTaskDirectory(workDir);
        JudgeTask task = taskStore.find(judgeId)
                .orElseThrow(() -> new IOException("Task must be explicitly created before execution"));
        if (!judgeId.equals(task.getJudgeId()) || task.getWorkDir() == null
                || !workDir.equals(Path.of(task.getWorkDir()))) {
            throw new IOException("Task metadata does not match its storage identity");
        }
        admission.requireTaskDirectory(workDir);
    }

    private boolean outputsMatch(String userOutput, String bfOutput, String precision) {
        BigDecimal tolerance = SandboxComparison.parseDecimal(precision);
        String[] userLines = outputTokens(userOutput);
        String[] bfLines = outputTokens(bfOutput);
        for (String token : userLines) {
            SandboxComparison.requireTokenBudget(token);
        }
        for (String token : bfLines) {
            SandboxComparison.requireTokenBudget(token);
        }
        if (userLines.length != bfLines.length) {
            return false;
        }
        for (int tokenIndex = 0; tokenIndex < userLines.length; tokenIndex++) {
            String userToken = userLines[tokenIndex];
            String expectedToken = bfLines[tokenIndex];
            if (INTEGER_TOKEN.matcher(userToken).matches() && INTEGER_TOKEN.matcher(expectedToken).matches()) {
                if (!integerTokensMatch(userToken, expectedToken)) {
                    return false;
                }
                continue;
            }
            if (!SandboxComparison.isDecimalToken(userToken) || !SandboxComparison.isDecimalToken(expectedToken)) {
                if (!userToken.equals(expectedToken)) {
                    return false;
                }
                continue;
            }
            BigDecimal userValue = SandboxComparison.parseDecimal(userToken);
            BigDecimal expectedValue = SandboxComparison.parseDecimal(expectedToken);
            if (userValue.subtract(expectedValue).abs().compareTo(tolerance) > 0) {
                return false;
            }
        }
        return true;
    }

    private String[] outputTokens(String output) {
        return OUTPUT_TOKEN.matcher(output).results().map(MatchResult::group).toArray(String[]::new);
    }

    private boolean integerTokensMatch(String userToken, String expectedToken) {
        int userOffset = userToken.charAt(0) == '+' || userToken.charAt(0) == '-' ? 1 : 0;
        int expectedOffset = expectedToken.charAt(0) == '+' || expectedToken.charAt(0) == '-' ? 1 : 0;
        while (userOffset < userToken.length() - 1 && userToken.charAt(userOffset) == '0') {
            userOffset++;
        }
        while (expectedOffset < expectedToken.length() - 1 && expectedToken.charAt(expectedOffset) == '0') {
            expectedOffset++;
        }
        boolean userNegative = userToken.charAt(0) == '-' && userToken.charAt(userOffset) != '0';
        boolean expectedNegative = expectedToken.charAt(0) == '-' && expectedToken.charAt(expectedOffset) != '0';
        int digits = userToken.length() - userOffset;
        return userNegative == expectedNegative && digits == expectedToken.length() - expectedOffset
                && userToken.regionMatches(userOffset, expectedToken, expectedOffset, digits);
    }

    private Path compile(Path sourceFile, String executableName, ResolvedTaskPolicy policy) {
        Path executablePath = sourceFile.getParent().resolve(executableName);
        ProcessRunner.Request request = ProcessRunner.Request.builder()
                .command(List.of(
                        "g++",
                        sourceFile.toAbsolutePath().toString(),
                        "-o",
                        executablePath.toAbsolutePath().toString(),
                        "-O2",
                        "-std=c++14"
                ))
                .workingDirectory(sourceFile.getParent())
                .timeout(java.time.Duration.ofSeconds(60))
                .memoryLimitBytes(memoryConfiguration.getDefaultLimit())
                .maxOutputBytes(executionProperties.getMaxOutputBytesPerCase())
                .maxErrorBytes(executionProperties.getMaxOutputBytesPerCase())
                .profile(policy.profile())
                .requireSandbox(policy.sandboxRequired())
                .build();

        try {
            ProcessResult result = processRunner.run(request);
            if (result.status() == ProcessResult.Status.SUCCESS) {
                return executablePath;
            }
            if (result.status() == ProcessResult.Status.TIME_LIMIT_EXCEEDED) {
                throw new CompilationException("Compilation timed out for " + sourceFile.getFileName());
            }
            String compilerOutput = result.error().isBlank() ? result.output() : result.error();
            String detail = compilerOutput.isBlank() ? "" : ":\n" + compilerOutput;
            throw new CompilationException("Compilation failed for " + sourceFile.getFileName() + detail);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("System error during compilation for " + sourceFile.getFileName(), e);
        }
    }

    private ProcessResult runProcess(
            Path executable,
            Path inputFile,
            Path outputFile,
            long timeLimit,
            long memoryLimit,
            ResolvedTaskPolicy policy
    ) throws IOException, InterruptedException {
        ProcessRunner.Request request = ProcessRunner.Request.builder()
                .command(List.of(executable.toAbsolutePath().toString()))
                .workingDirectory(executable.getParent())
                .inputFile(inputFile)
                .outputFile(outputFile)
                .timeout(java.time.Duration.ofMillis(timeLimit))
                .memoryLimitBytes(memoryLimit)
                .maxOutputBytes(policy.maxOutputBytesPerCase())
                .maxErrorBytes(policy.maxOutputBytesPerCase())
                .profile(policy.profile())
                .requireSandbox(policy.sandboxRequired())
                .build();
        return processRunner.run(request);
    }

    private String readUtf8FileLimited(Path file, long maxBytes) throws IOException {
        if (!Files.exists(file)) {
            return "";
        }
        if (Files.size(file) > maxBytes) {
            throw new IOException("File exceeds configured output byte limit");
        }
        return Files.readString(file);
    }
}
