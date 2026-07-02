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
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class JudgeService {

    private final MemoryConfiguration memoryConfiguration;
    private final ExecutionProperties executionProperties;
    private final SandboxProcessRunner processRunner;
    private final ProgressPublisher progressPublisher;
    private final TaskPolicyResolver taskPolicyResolver;
    private final TaskStore taskStore;
    private final CaseBatchRunner caseBatchRunner;
    private final JudgeScheduler judgeScheduler;
    private final SecurityModeStartupValidator securityModeStartupValidator;
    private final Map<String, Path> judgeIdToTempDir = new ConcurrentHashMap<>();
    private final Map<String, PendingJudgeTask> pendingJudgeTasks = new ConcurrentHashMap<>();
    private final Map<String, JudgeProgress> judgeStatusMap = new ConcurrentHashMap<>();
    
    @Qualifier(AsyncConfig.TEST_CASE_EXECUTOR)
    private final ThreadPoolTaskExecutor testCaseExecutor;
    
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
        JudgeProgress published = progressPublisher.publish(judgeId, progress);
        judgeStatusMap.put(judgeId, published);
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
        JudgeProgress status = judgeStatusMap.get(judgeId);
        if (status == null) {
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
            } catch (Exception e) {
                throw new IllegalArgumentException("Judge status not found: " + judgeId, e);
            }
            throw new IllegalArgumentException("Judge status not found: " + judgeId);
        }
        return status;
    }
    
    /**
     * 清理已完成的判题任务
     */
    public void cleanupJudgeTask(String judgeId) {
        try {
            // 标记WebSocket会话为非活跃
            markSessionInactive(judgeId);
            
            // 延迟清理临时目录，给用户时间查看测试点详情
            CompletableFuture.delayedExecutor(30, TimeUnit.MINUTES).execute(() -> {
                try {
                    Path tempDir = judgeIdToTempDir.remove(judgeId);
                    if (tempDir != null && Files.exists(tempDir)) {
                        FileUtils.deleteDirectory(tempDir.toFile());
                        log.debug("延迟清理临时目录: {}", tempDir);
                    }
                } catch (Exception e) {
                    log.error("延迟清理临时目录失败: {} - {}", judgeId, e.getMessage());
                }
            });
            
            // 延迟清理状态信息（给客户端更多时间获取最终状态）
            CompletableFuture.delayedExecutor(60, TimeUnit.MINUTES).execute(() -> {
                judgeStatusMap.remove(judgeId);
                log.debug("延迟清理状态信息: {}", judgeId);
            });
            
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
        securityModeStartupValidator.assertJudgeCreationAllowed();
        ResolvedTaskPolicy policy = taskPolicyResolver.resolve(request);
        Path workDir = taskStore.taskDirectory(judgeId);
        JudgeTask task = JudgeTask.builder()
                .judgeId(judgeId)
                .status(JudgeStatus.CREATED)
                .requestedCases(policy.requestedCases())
                .mode(policy.profile())
                .policy(policy)
                .workDir(workDir.toString())
                .createdAt(Instant.now())
                .build();
        try {
            taskStore.create(task);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create judge task", e);
        }
        pendingJudgeTasks.put(judgeId, new PendingJudgeTask(request, policy));
        return JudgeCreateResponse.created(judgeId, policy);
    }

    /**
     * 启动指定的判题任务
     */
    public void startJudgeTask(String judgeId) {
        PendingJudgeTask pendingTask = pendingJudgeTasks.get(judgeId);
        if (pendingTask == null) {
            throw new IllegalArgumentException("Judge task not found: " + judgeId);
        }
        // 标记WebSocket会话为活跃
        markSessionActive(judgeId);
        judgeScheduler.enqueue(judgeId, context -> judge(pendingTask.request(), judgeId, pendingTask.policy(), context));
        pendingJudgeTasks.remove(judgeId);
    }

    public CancelJudgeResponse cancelJudgeTask(String judgeId) {
        CancelJudgeResponse response = judgeScheduler.cancel(judgeId);
        if (response.accepted()) {
            pendingJudgeTasks.remove(judgeId);
            markSessionInactive(judgeId);
            judgeStatusMap.put(judgeId, new JudgeProgress(response.status(), response.message(), 100));
        }
        return response;
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
            Files.createDirectories(tempDir);
            judgeIdToTempDir.put(judgeId, tempDir);

            safeSendMessage(topic, new JudgeProgress("PENDING", "创建临时目录...", 0));
            if (cancellationToken.isCancellationRequested()) {
                safeSendStoppedMessage(topic, cancellationToken, 0, emptyStoppedSummary(policy.requestedCases(), schedulerContext.completedCases(), cancellationToken));
                cleanupJudgeTask(judgeId);
                return;
            }

            try {
                safeSendMessage(topic, new JudgeProgress("COMPILING", "正在编译代码...", 5));

                Path genSource = tempDir.resolve("generator.cpp");
                Files.writeString(genSource, request.getGeneratorCode());

                Path userSource = tempDir.resolve("user.cpp");
                Files.writeString(userSource, request.getUserCode());

                CompletableFuture<Path> genFuture = CompletableFuture.supplyAsync(() -> compile(genSource, "generator", policy), testCaseExecutor);
                CompletableFuture<Path> userFuture = CompletableFuture.supplyAsync(() -> compile(userSource, "user", policy), testCaseExecutor);

                // 根据是否启用Special Judge决定编译内容
                CompletableFuture<Path> judgeExecutableFuture;
                if (request.isUseSpecialJudge() && request.getSpecialJudgeCode() != null && !request.getSpecialJudgeCode().trim().isEmpty()) {
                    // 编译Special Judge代码
                    Path spjSource = tempDir.resolve("special_judge.cpp");
                    Files.writeString(spjSource, request.getSpecialJudgeCode());
                    judgeExecutableFuture = CompletableFuture.supplyAsync(() -> compile(spjSource, "special_judge", policy), testCaseExecutor);
                } else {
                    // 编译Brute Force代码
                    Path bfSource = tempDir.resolve("bruteforce.cpp");
                    Files.writeString(bfSource, request.getBruteForceCode());
                    judgeExecutableFuture = CompletableFuture.supplyAsync(() -> compile(bfSource, "bruteforce", policy), testCaseExecutor);
                }

                CompletableFuture.allOf(genFuture, userFuture, judgeExecutableFuture).join();

                final Path genExecutable = genFuture.get();
                final Path userExecutable = userFuture.get();
                final Path judgeExecutable = judgeExecutableFuture.get();

                safeSendMessage(topic, new JudgeProgress("COMPILING", "编译成功", 15));
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
                Path finalTempDir = tempDir;
                CaseBatchRunner.RunOutcome runOutcome = caseBatchRunner.run(
                        totalTestCases,
                        policy,
                        cancellationToken,
                        caseNumber -> runTestCase(caseNumber, request, policy, finalTempDir, genExecutable, userExecutable, judgeExecutable),
                        result -> {
                            resultAggregator.accept(result);
                            schedulerContext.recordCompletedCase();
                            int done = completedCases.incrementAndGet();
                            if (!cancellationToken.isCancellationRequested()
                                    && (done % updateThreshold == 0 || done == totalTestCases)) {
                                int progress = 15 + (int) ((double) done / totalTestCases * 85);
                                safeSendMessage(topic, new JudgeProgress("RUNNING", String.format("已完成 %d / %d", done, totalTestCases), progress));
                            }
                        }
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
            safeSendMessage(topic, new JudgeProgress("SYSTEM_ERROR", e.getMessage(), 100, null));
            cleanupJudgeTask(judgeId);
        }
    }

    private TestCaseResult runTestCase(int caseNumber, JudgeRequest request, ResolvedTaskPolicy policy, Path tempDir, Path genExecutable, Path userExecutable, Path judgeExecutable) {
        try {
            Path inputFile = tempDir.resolve(caseNumber + ".in");
            Path userOutputFile = tempDir.resolve(caseNumber + ".out");

            ProcessResult genResult = runProcess(genExecutable, null, inputFile, 5000, memoryConfiguration.getDefaultLimit(), policy);
            if (genResult.status() != ProcessResult.Status.SUCCESS) {
                return new TestCaseResult(caseNumber, "System Error", 0, 0);
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
                return runSpecialJudge(caseNumber, request, policy, tempDir, judgeExecutable, inputFile, userOutputFile, userResult);
            } else {
                // 使用Brute Force进行判题
                return runBruteForceJudge(caseNumber, request, policy, tempDir, judgeExecutable, inputFile, userOutputFile, userResult);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new TestCaseResult(caseNumber, "System Error", 0, 0);
        }
    }
    
    /**
     * 使用Special Judge进行判题
     */
    private TestCaseResult runSpecialJudge(int caseNumber, JudgeRequest request, ResolvedTaskPolicy policy, Path tempDir, Path spjExecutable,
                                         Path inputFile, Path userOutputFile, ProcessResult userResult) throws IOException, InterruptedException {
        // Special Judge通常接受三个参数：输入文件、用户输出文件、标准输出文件（可选）
        // 这里我们只传递输入文件和用户输出文件，SPJ程序负责验证输出是否正确
        // SPJ程序应该返回退出码：0表示AC，非0表示WA或其他错误
        
        long spjTimeLimit = policy.caseTimeLimit().toMillis() * 2;
        // 为SPJ创建参数文件，传递必要信息
        Path spjArgsFile = tempDir.resolve(caseNumber + ".spj_args");
        String args = String.format("%s\n%s\n", inputFile.toAbsolutePath(), userOutputFile.toAbsolutePath());
        Files.writeString(spjArgsFile, args);
        
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
            return new TestCaseResult(caseNumber, "System Error", userResult.executionTime(), userResult.memoryUsed() / 1024);
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
            return new TestCaseResult(caseNumber, "System Error", 0, 0);
        }

        String userOutput = readUtf8FileLimited(userOutputFile, policy.maxOutputBytesPerCase());
        String bfOutput = readUtf8FileLimited(bfOutputFile, policy.maxOutputBytesPerCase());

        if (outputsMatch(userOutput, bfOutput, request.getPrecision())) {
            return new TestCaseResult(caseNumber, "AC", userResult.executionTime(), userResult.memoryUsed() / 1024);
        } else {
            return new TestCaseResult(caseNumber, "WA", userResult.executionTime(), userResult.memoryUsed() / 1024);
        }
    }

    private Path resolveTaskWorkDir(String judgeId) throws IOException {
        Optional<JudgeTask> task = taskStore.find(judgeId);
        if (task.isPresent()) {
            return Path.of(task.get().getWorkDir()).toAbsolutePath().normalize();
        }
        return taskStore.taskDirectory(judgeId);
    }

    private boolean outputsMatch(String userOutput, String bfOutput, double precision) {
        String[] userLines = userOutput.trim().split("\\s+");
        String[] bfLines = bfOutput.trim().split("\\s+");
        if (userLines.length != bfLines.length) {
            return false;
        }
        for (int i = 0; i < userLines.length; i++) {
            try {
                double userVal = Double.parseDouble(userLines[i]);
                double bfVal = Double.parseDouble(bfLines[i]);
                if (Math.abs(userVal - bfVal) > precision) {
                    return false;
                }
            } catch (NumberFormatException e) {
                if (!userLines[i].equals(bfLines[i])) {
                    return false;
                }
            }
        }
        return true;
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
                .killGrace(java.time.Duration.ofSeconds(5))
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
            throw new CompilationException("Compilation failed for " + sourceFile.getFileName()
                    + " with status " + result.status()
                    + " and exit code " + result.exitCode()
                    + ":\n" + result.error());
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
                .killGrace(java.time.Duration.ofSeconds(5))
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
