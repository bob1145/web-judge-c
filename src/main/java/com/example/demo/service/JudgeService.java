package com.example.demo.service;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.service.MemoryMonitorService;
import com.example.demo.service.SandboxService;
import com.example.demo.config.MemoryConfiguration;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.exception.MemoryLimitExceededException;
import com.example.demo.exception.SecurityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.core.task.TaskExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.Comparator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.example.demo.dto.TestCaseDetail;
import com.example.demo.dto.TestCaseResult;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.example.demo.config.AsyncConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CompletionException;

@Service
@RequiredArgsConstructor
@Slf4j
public class JudgeService {

    private final SimpMessagingTemplate messagingTemplate;
    private final MemoryMonitorService memoryMonitorService;
    private final MemoryConfiguration memoryConfiguration;
    private final SandboxService sandboxService;
    private final SandboxConfiguration sandboxConfiguration;
    private final Map<String, Path> judgeIdToTempDir = new ConcurrentHashMap<>();
    private final Map<String, JudgeRequest> pendingJudgeTasks = new ConcurrentHashMap<>();
    private final Map<String, JudgeProgress> judgeStatusMap = new ConcurrentHashMap<>();
    private final Map<String, Boolean> activeWebSocketSessions = new ConcurrentHashMap<>();
    
    @Qualifier(AsyncConfig.TEST_CASE_EXECUTOR)
    private final ThreadPoolTaskExecutor testCaseExecutor;
    
    /**
     * 标记WebSocket会话为活跃状态
     */
    public void markSessionActive(String judgeId) {
        activeWebSocketSessions.put(judgeId, true);
        log.debug("WebSocket会话激活: {}", judgeId);
    }
    
    /**
     * 标记WebSocket会话为非活跃状态
     */
    public void markSessionInactive(String judgeId) {
        activeWebSocketSessions.put(judgeId, false);
        log.debug("WebSocket会话停用: {}", judgeId);
    }
    
    /**
     * 检查WebSocket会话是否活跃
     */
    private boolean isSessionActive(String judgeId) {
        return activeWebSocketSessions.getOrDefault(judgeId, false);
    }
    
    /**
     * 安全地发送WebSocket消息，避免向已关闭的会话发送消息
     * 同时保存状态用于轮询
     */
    private void safeSendMessage(String topic, JudgeProgress progress) {
        // 提取judgeId用于状态保存
        String judgeId = topic.substring(topic.lastIndexOf('/') + 1);
        judgeStatusMap.put(judgeId, progress);
        
        // 检查会话是否仍然活跃
        if (!isSessionActive(judgeId)) {
            log.debug("WebSocket会话已关闭，跳过消息发送: {}", topic);
            return;
        }
        
        try {
            messagingTemplate.convertAndSend(topic, progress);
        } catch (IllegalStateException e) {
            // 会话已关闭 - 这是最常见的情况
            log.debug("WebSocket会话已关闭，标记为非活跃: {} - {}", topic, e.getMessage());
            markSessionInactive(judgeId);
        } catch (org.springframework.messaging.MessageDeliveryException e) {
            // 消息传递失败
            log.debug("WebSocket消息传递失败，标记为非活跃: {} - {}", topic, e.getMessage());
            markSessionInactive(judgeId);
        } catch (Exception e) {
            // 记录其他异常但不中断执行
            log.warn("发送WebSocket消息失败: {} - {}: {}", topic, e.getClass().getSimpleName(), e.getMessage());
            // 对于其他异常，也标记会话为非活跃，避免继续发送
            markSessionInactive(judgeId);
        }
    }

    /**
     * 获取判题状态，用于轮询
     */
    public JudgeProgress getJudgeStatus(String judgeId) {
        JudgeProgress status = judgeStatusMap.get(judgeId);
        if (status == null) {
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
                activeWebSocketSessions.remove(judgeId);
                log.debug("延迟清理状态信息: {}", judgeId);
            });
            
        } catch (Exception e) {
            log.error("清理判题任务失败: {} - {}", judgeId, e.getMessage());
        }
    }

    private enum RunStatus {
        SUCCESS,
        TIME_LIMIT_EXCEEDED,
        MEMORY_LIMIT_EXCEEDED,
        RUNTIME_ERROR
    }

    private record ProcessResult(RunStatus status, String output, String error, long executionTime, long memoryUsed) {}

    static class CompilationException extends RuntimeException {
        public CompilationException(String message) {
            super(message);
        }
    }

    /**
     * 创建判题任务但不立即执行，等待WebSocket连接建立
     */
    public void createJudgeTask(JudgeRequest request, String judgeId) {
        pendingJudgeTasks.put(judgeId, request);
    }

    /**
     * 启动指定的判题任务
     */
    public void startJudgeTask(String judgeId) {
        JudgeRequest request = pendingJudgeTasks.remove(judgeId);
        if (request == null) {
            throw new IllegalArgumentException("Judge task not found: " + judgeId);
        }
        // 标记WebSocket会话为活跃
        markSessionActive(judgeId);
        judge(request, judgeId);
    }

    @Async(AsyncConfig.JUDGE_REQUEST_EXECUTOR)
    public void judge(JudgeRequest request, String judgeId) {
        Path tempDir = null;
        String topic = "/topic/progress/" + judgeId;

        try {
            Path baseDir = Path.of(System.getProperty("java.io.tmpdir"), "online-judge");
            Files.createDirectories(baseDir);
            tempDir = Files.createDirectory(baseDir.resolve("judge-" + judgeId));
            judgeIdToTempDir.put(judgeId, tempDir);

            safeSendMessage(topic, new JudgeProgress("PENDING", "创建临时目录...", 0));

            try {
                safeSendMessage(topic, new JudgeProgress("COMPILING", "正在编译代码...", 5));

                Path genSource = tempDir.resolve("generator.cpp");
                Files.writeString(genSource, request.getGeneratorCode());

                Path userSource = tempDir.resolve("user.cpp");
                Files.writeString(userSource, request.getUserCode());

                CompletableFuture<Path> genFuture = CompletableFuture.supplyAsync(() -> compile(genSource, "generator"), testCaseExecutor);
                CompletableFuture<Path> userFuture = CompletableFuture.supplyAsync(() -> compile(userSource, "user"), testCaseExecutor);

                // 根据是否启用Special Judge决定编译内容
                CompletableFuture<Path> judgeExecutableFuture;
                if (request.isUseSpecialJudge() && request.getSpecialJudgeCode() != null && !request.getSpecialJudgeCode().trim().isEmpty()) {
                    // 编译Special Judge代码
                    Path spjSource = tempDir.resolve("special_judge.cpp");
                    Files.writeString(spjSource, request.getSpecialJudgeCode());
                    judgeExecutableFuture = CompletableFuture.supplyAsync(() -> compile(spjSource, "special_judge"), testCaseExecutor);
                } else {
                    // 编译Brute Force代码
                    Path bfSource = tempDir.resolve("bruteforce.cpp");
                    Files.writeString(bfSource, request.getBruteForceCode());
                    judgeExecutableFuture = CompletableFuture.supplyAsync(() -> compile(bfSource, "bruteforce"), testCaseExecutor);
                }

                CompletableFuture.allOf(genFuture, userFuture, judgeExecutableFuture).join();

                final Path genExecutable = genFuture.get();
                final Path userExecutable = userFuture.get();
                final Path judgeExecutable = judgeExecutableFuture.get();

                safeSendMessage(topic, new JudgeProgress("COMPILING", "编译成功", 15));

                List<TestCaseResult> results = new ArrayList<>();
                AtomicInteger completedCases = new AtomicInteger(0);
                AtomicReference<String> finalStatus = new AtomicReference<>("AC");
                AtomicReference<String> finalMessage = new AtomicReference<>("全部通过！");

                List<CompletableFuture<TestCaseResult>> futures = new ArrayList<>();
                
                final int totalTestCases = request.getTestCases();
                final int updateThreshold = Math.max(1, totalTestCases / 100); // Update every 1%

                for (int i = 1; i <= request.getTestCases(); i++) {
                    final int caseNum = i;
                    Path finalTempDir = tempDir;

                    CompletableFuture<TestCaseResult> future = CompletableFuture.supplyAsync(() ->
                            runTestCase(caseNum, request, finalTempDir, genExecutable, userExecutable, judgeExecutable), testCaseExecutor
                    ).whenComplete((result, ex) -> {
                        int done = completedCases.incrementAndGet();
                        if (done % updateThreshold == 0 || done == totalTestCases) {
                            int progress = 15 + (int) ((double) done / totalTestCases * 85);
                            safeSendMessage(topic, new JudgeProgress("RUNNING", String.format("已完成 %d / %d", done, totalTestCases), progress));
                        }
                    });
                    futures.add(future);
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(TestCaseResult::getCaseNumber))
                    .forEach(results::add);

                for (TestCaseResult result : results) {
                    if (!result.getStatus().equals("AC")) {
                        finalStatus.set(result.getStatus());
                        finalMessage.set(String.format("%s on Test Case #%d", result.getStatus(), result.getCaseNumber()));
                        break; 
                    }
                }
                
                safeSendMessage(topic, new JudgeProgress(finalStatus.get(), finalMessage.get(), 100, results));
                
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

    private TestCaseResult runTestCase(int caseNumber, JudgeRequest request, Path tempDir, Path genExecutable, Path userExecutable, Path judgeExecutable) {
        try {
            Path inputFile = tempDir.resolve(caseNumber + ".in");
            Path userOutputFile = tempDir.resolve(caseNumber + ".out");

            ProcessResult genResult = runProcess(genExecutable, null, inputFile, 5000, memoryConfiguration.getDefaultLimit());
            if (genResult.status() != RunStatus.SUCCESS) {
                return new TestCaseResult(caseNumber, "System Error", 0, 0);
            }

            // 使用请求中的内存限制，如果未设置则使用默认值
            long userMemoryLimit = request.getMemoryLimit() > 0 ? request.getMemoryLimit() : memoryConfiguration.getDefaultLimit();
            ProcessResult userResult = runProcess(userExecutable, inputFile, userOutputFile, request.getTimeLimit(), userMemoryLimit);
            if (userResult.status() != RunStatus.SUCCESS) {
                String statusStr = switch (userResult.status()) {
                    case TIME_LIMIT_EXCEEDED -> "TLE";
                    case MEMORY_LIMIT_EXCEEDED -> "MLE";
                    case RUNTIME_ERROR -> "RE";
                    default -> "System Error";
                };
                return new TestCaseResult(caseNumber, statusStr, userResult.executionTime(), userResult.memoryUsed() / 1024); // Convert to KB
            }

            // 根据是否启用Special Judge选择不同的判题逻辑
            if (request.isUseSpecialJudge() && request.getSpecialJudgeCode() != null && !request.getSpecialJudgeCode().trim().isEmpty()) {
                // 使用Special Judge进行判题
                return runSpecialJudge(caseNumber, request, tempDir, judgeExecutable, inputFile, userOutputFile, userResult);
            } else {
                // 使用Brute Force进行判题
                return runBruteForceJudge(caseNumber, request, tempDir, judgeExecutable, inputFile, userOutputFile, userResult);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new TestCaseResult(caseNumber, "System Error", 0, 0);
        }
    }
    
    /**
     * 使用Special Judge进行判题
     */
    private TestCaseResult runSpecialJudge(int caseNumber, JudgeRequest request, Path tempDir, Path spjExecutable, 
                                         Path inputFile, Path userOutputFile, ProcessResult userResult) throws IOException, InterruptedException {
        // Special Judge通常接受三个参数：输入文件、用户输出文件、标准输出文件（可选）
        // 这里我们只传递输入文件和用户输出文件，SPJ程序负责验证输出是否正确
        // SPJ程序应该返回退出码：0表示AC，非0表示WA或其他错误
        
        ProcessResult spjResult = runProcess(spjExecutable, null, null, request.getTimeLimit() * 2, memoryConfiguration.getDefaultLimit());
        
        // 为SPJ创建参数文件，传递必要信息
        Path spjArgsFile = tempDir.resolve(caseNumber + ".spj_args");
        String args = String.format("%s\n%s\n", inputFile.toAbsolutePath(), userOutputFile.toAbsolutePath());
        Files.writeString(spjArgsFile, args);
        
        // 重新运行SPJ，这次传递参数文件
        ProcessBuilder spjBuilder = new ProcessBuilder(spjExecutable.toAbsolutePath().toString());
        spjBuilder.directory(tempDir.toFile());
        spjBuilder.redirectInput(spjArgsFile.toFile());
        
        File spjErrorFile = Files.createTempFile(tempDir, "spj_error_", ".log").toFile();
        spjBuilder.redirectError(spjErrorFile);
        
        long startTime = System.currentTimeMillis();
        Process spjProcess = spjBuilder.start();
        
        boolean finished = spjProcess.waitFor(request.getTimeLimit() * 2, TimeUnit.MILLISECONDS);
        long spjExecutionTime = System.currentTimeMillis() - startTime;
        
        if (!finished) {
            spjProcess.destroyForcibly();
            spjProcess.waitFor(5, TimeUnit.SECONDS);
            return new TestCaseResult(caseNumber, "System Error", userResult.executionTime(), userResult.memoryUsed() / 1024);
        }
        
        int spjExitCode = spjProcess.exitValue();
        String spjError = Files.readString(spjErrorFile.toPath());
        Files.delete(spjErrorFile.toPath());
        Files.delete(spjArgsFile);
        
        log.debug("SPJ执行结果: exitCode={}, executionTime={}ms", spjExitCode, spjExecutionTime);
        
        if (spjExitCode == 0) {
            return new TestCaseResult(caseNumber, "AC", userResult.executionTime(), userResult.memoryUsed() / 1024);
        } else {
            // 根据SPJ的退出码决定结果，通常非0表示WA
            String status = switch (spjExitCode) {
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
    private TestCaseResult runBruteForceJudge(int caseNumber, JudgeRequest request, Path tempDir, Path bfExecutable, 
                                            Path inputFile, Path userOutputFile, ProcessResult userResult) throws IOException, InterruptedException {
        Path bfOutputFile = tempDir.resolve(caseNumber + ".ans");
        
        ProcessResult bfResult = runProcess(bfExecutable, inputFile, bfOutputFile, request.getTimeLimit() * 5, memoryConfiguration.getDefaultLimit() * 2);
        if (bfResult.status() != RunStatus.SUCCESS) {
            return new TestCaseResult(caseNumber, "System Error", 0, 0);
        }

        String userOutput = Files.readString(userOutputFile);
        String bfOutput = Files.readString(bfOutputFile);

        if (outputsMatch(userOutput, bfOutput, request.getPrecision())) {
            return new TestCaseResult(caseNumber, "AC", userResult.executionTime(), userResult.memoryUsed() / 1024);
        } else {
            return new TestCaseResult(caseNumber, "WA", userResult.executionTime(), userResult.memoryUsed() / 1024);
        }
    }
    
    public TestCaseDetail getTestCaseDetails(String judgeId, int caseNumber) throws IOException {
        Path tempDir = judgeIdToTempDir.get(judgeId);
        if (tempDir == null) {
            log.warn("获取测试点详情失败 - judgeId不存在或已过期: {}", judgeId);
            throw new IOException("Judge ID not found or session has expired: " + judgeId);
        }

        if (!Files.exists(tempDir)) {
            log.warn("获取测试点详情失败 - 临时目录不存在: {}", tempDir);
            throw new IOException("Temporary directory no longer exists: " + tempDir);
        }

        Path inputFile = tempDir.resolve(caseNumber + ".in");
        Path userOutputFile = tempDir.resolve(caseNumber + ".out");
        Path correctOutputFile = tempDir.resolve(caseNumber + ".ans");

        log.debug("获取测试点详情: judgeId={}, caseNumber={}, tempDir={}", judgeId, caseNumber, tempDir);

        String input = Files.exists(inputFile) ? Files.readString(inputFile) : "Input data not found.";
        String userOutput = Files.exists(userOutputFile) ? Files.readString(userOutputFile) : "User output not found.";
        String correctOutput = Files.exists(correctOutputFile) ? Files.readString(correctOutputFile) : "Correct output not found.";

        return new TestCaseDetail(input, userOutput, correctOutput);
    }

    public File getTestCaseInputFile(String judgeId, int caseNumber) throws IOException {
        Path tempDir = judgeIdToTempDir.get(judgeId);
        if (tempDir == null) {
            throw new IOException("Judge ID not found or session has expired.");
        }
        Path inputFile = tempDir.resolve(caseNumber + ".in");
        if (!Files.exists(inputFile)) {
            throw new IOException("Input file not found for test case " + caseNumber);
        }
        return inputFile.toFile();
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

    private Path compile(Path sourceFile, String executableName) {
        System.out.println("Compiling " + sourceFile.toString());
        Path executablePath = sourceFile.getParent().resolve(executableName);
        
        ProcessBuilder processBuilder = new ProcessBuilder(
                "g++",
                sourceFile.toAbsolutePath().toString(),
                "-o",
                executablePath.toAbsolutePath().toString(),
                "-O2",
                "-std=c++14"
        );

        processBuilder.directory(sourceFile.getParent().toFile());

        File compileErrorFile = null;
        try {
            compileErrorFile = Files.createTempFile(sourceFile.getParent(), "compile_error_", ".log").toFile();
            processBuilder.redirectError(compileErrorFile);

            Process process = processBuilder.start();

            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CompilationException("Compilation timed out for " + sourceFile.getFileName());
            }
    
            int exitCode = process.exitValue();
    
            if (exitCode != 0) {
                String errorOutput = Files.readString(compileErrorFile.toPath());
                throw new CompilationException("Compilation failed for " + sourceFile.getFileName() + " with exit code " + exitCode + ":\n" + errorOutput);
            }

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("System error during compilation for " + sourceFile.getFileName(), e);
        } finally {
            if (compileErrorFile != null) {
                try {
                    Files.deleteIfExists(compileErrorFile.toPath());
                } catch (IOException e) {
                    System.err.println("Failed to delete temporary compile error file: " + compileErrorFile.getAbsolutePath());
                }
            }
        }
        
        System.out.println("Compilation successful for " + sourceFile.getFileName());
        return executablePath;
    }

    private ProcessResult runProcess(Path executable, Path inputFile, Path outputFile, long timeLimit, long memoryLimit) throws IOException, InterruptedException {
        // 检查文件系统访问权限
        try {
            sandboxService.checkFileSystemAccess(executable.getParent());
            if (inputFile != null) {
                sandboxService.checkFileSystemAccess(inputFile);
            }
            if (outputFile != null) {
                sandboxService.checkFileSystemAccess(outputFile.getParent());
            }
        } catch (SecurityViolationException e) {
            return new ProcessResult(RunStatus.RUNTIME_ERROR, null, "Security violation: " + e.getMessage(), 0, 0);
        }

        if (sandboxConfiguration.isEnabled()) {
            // 使用沙箱执行
            return runProcessInSandbox(executable, inputFile, outputFile, timeLimit, memoryLimit);
        } else {
            // 直接执行（保持原有逻辑作为备选）
            return runProcessDirectly(executable, inputFile, outputFile, timeLimit, memoryLimit);
        }
    }
    
    private ProcessResult runProcessInSandbox(Path executable, Path inputFile, Path outputFile, long timeLimit, long memoryLimit) throws IOException, InterruptedException {
        String[] command = { executable.toAbsolutePath().toString() };
        
        try {
            SandboxService.SandboxResult result = sandboxService.executeInSandbox(
                command, 
                executable.getParent(), 
                inputFile, 
                outputFile, 
                timeLimit, 
                memoryLimit
            );
            
            System.out.println("沙箱执行结果: exitCode=" + result.exitCode() + 
                             ", securityViolation=" + result.securityViolation() + 
                             ", executionTime=" + result.executionTime());
            
            if (result.securityViolation()) {
                System.err.println("安全违规: " + result.violationReason());
                return new ProcessResult(RunStatus.RUNTIME_ERROR, null, "Security violation: " + result.violationReason(), result.executionTime(), 0);
            }
            
            if (result.exitCode() != 0) {
                System.err.println("进程退出码非零: " + result.exitCode() + ", 错误信息: " + result.error());
                return new ProcessResult(RunStatus.RUNTIME_ERROR, null, result.error(), result.executionTime(), 0);
            }
            
            return new ProcessResult(RunStatus.SUCCESS, result.output(), null, result.executionTime(), 0);
        } catch (Exception e) {
            System.err.println("沙箱执行异常: " + e.getMessage());
            e.printStackTrace();
            return new ProcessResult(RunStatus.RUNTIME_ERROR, null, "Sandbox execution failed: " + e.getMessage(), 0, 0);
        }
    }
    
    private ProcessResult runProcessDirectly(Path executable, Path inputFile, Path outputFile, long timeLimit, long memoryLimit) throws IOException, InterruptedException {
        if (log.isDebugEnabled()) {
            log.debug("直接执行进程: {}", executable.toAbsolutePath());
            log.debug("输入文件: {}", inputFile != null ? inputFile.toAbsolutePath() : "null");
            log.debug("输出文件: {}", outputFile != null ? outputFile.toAbsolutePath() : "null");
            log.debug("时间限制: {}ms, 内存限制: {} bytes", timeLimit, memoryLimit);
        }
        
        ProcessBuilder processBuilder = new ProcessBuilder(executable.toAbsolutePath().toString());
        processBuilder.directory(executable.getParent().toFile());

        if (inputFile != null) {
            processBuilder.redirectInput(inputFile.toFile());
        }
        if (outputFile != null) {
            processBuilder.redirectOutput(outputFile.toFile());
        }

        File errorFile = Files.createTempFile(executable.getParent(), "error_", ".log").toFile();
        processBuilder.redirectError(errorFile);

        long startTime = System.currentTimeMillis();
        Process process = processBuilder.start();
        
        // 启动内存监控
        CompletableFuture<MemoryMonitorService.MemoryUsage> memoryFuture = 
            memoryMonitorService.monitorProcess(process, memoryLimit);
        
        boolean finished = process.waitFor(timeLimit, TimeUnit.MILLISECONDS);
        long executionTime = System.currentTimeMillis() - startTime;
        
        log.debug("进程执行完成: finished={}, executionTime={}ms", finished, executionTime);

        // 获取内存使用情况
        long memoryUsed = 0;
        try {
            if (memoryFuture.isDone()) {
                MemoryMonitorService.MemoryUsage memoryUsage = memoryFuture.get();
                memoryUsed = memoryUsage.peakMemory();
                log.debug("内存使用情况: {} bytes", memoryUsed);
            }
        } catch (Exception e) {
            if (e.getCause() instanceof MemoryLimitExceededException) {
                // 进程因内存超限被终止
                String errorOutput = Files.readString(errorFile.toPath());
                Files.delete(errorFile.toPath());
                MemoryLimitExceededException mle = (MemoryLimitExceededException) e.getCause();
                log.warn("内存超限: {}", mle.getMessage());
                return new ProcessResult(RunStatus.MEMORY_LIMIT_EXCEEDED, null, "Memory limit exceeded: " + mle.getMessage(), executionTime, mle.getCurrentUsage());
            }
        }

        if (!finished) {
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                 return new ProcessResult(RunStatus.TIME_LIMIT_EXCEEDED, null, "Process timed out and could not be terminated.", executionTime, memoryUsed);
            }
            return new ProcessResult(RunStatus.TIME_LIMIT_EXCEEDED, null, "Process exceeded time limit of " + timeLimit + "ms", executionTime, memoryUsed);
        }

        int exitCode = process.exitValue();
        String errorOutput = Files.readString(errorFile.toPath());
        Files.delete(errorFile.toPath());
        
        log.debug("进程退出码: {}", exitCode);
        if (!errorOutput.trim().isEmpty()) {
            log.debug("错误输出: {}", errorOutput);
        }

        if (exitCode != 0) {
            return new ProcessResult(RunStatus.RUNTIME_ERROR, null, errorOutput, executionTime, memoryUsed);
        }

        String output = outputFile != null && Files.exists(outputFile) ? Files.readString(outputFile) : "";
        return new ProcessResult(RunStatus.SUCCESS, output, null, executionTime, memoryUsed);
    }
} 