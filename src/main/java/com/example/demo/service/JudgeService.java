package com.example.demo.service;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeRequest;
import lombok.RequiredArgsConstructor;
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
public class JudgeService {

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, Path> judgeIdToTempDir = new ConcurrentHashMap<>();
    
    @Qualifier(AsyncConfig.TEST_CASE_EXECUTOR)
    private final ThreadPoolTaskExecutor testCaseExecutor;

    private enum RunStatus {
        SUCCESS,
        TIME_LIMIT_EXCEEDED,
        RUNTIME_ERROR
    }

    private enum SpjResult {
        AC, WA, ERROR
    }

    private record ProcessResult(RunStatus status, String output, String error, long executionTime) {}

    static class CompilationException extends RuntimeException {
        public CompilationException(String message) {
            super(message);
        }
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

            messagingTemplate.convertAndSend(topic, new JudgeProgress("PENDING", "创建临时目录...", 0));

            try {
                messagingTemplate.convertAndSend(topic, new JudgeProgress("COMPILING", "正在编译代码...", 5));

                Path genSource = tempDir.resolve("generator.cpp");
                Files.writeString(genSource, request.getGeneratorCode());

                Path bfSource = tempDir.resolve("bruteforce.cpp");
                Files.writeString(bfSource, request.getBruteForceCode());

                Path userSource = tempDir.resolve("user.cpp");
                Files.writeString(userSource, request.getUserCode());

                CompletableFuture<Path> genFuture = CompletableFuture.supplyAsync(() -> compile(genSource, "generator"), testCaseExecutor);
                CompletableFuture<Path> bfFuture = CompletableFuture.supplyAsync(() -> compile(bfSource, "bruteforce"), testCaseExecutor);
                CompletableFuture<Path> userFuture = CompletableFuture.supplyAsync(() -> compile(userSource, "user"), testCaseExecutor);
                CompletableFuture<Path> spjFuture = null;
                if (request.isSpjEnabled()) {
                    Path spjSource = tempDir.resolve("spj.cpp");
                    Files.writeString(spjSource, request.getSpjCode());
                    spjFuture = CompletableFuture.supplyAsync(() -> compile(spjSource, "spj"), testCaseExecutor);
                }

                if (request.isSpjEnabled()) {
                    CompletableFuture.allOf(genFuture, bfFuture, userFuture, spjFuture).join();
                } else {
                    CompletableFuture.allOf(genFuture, bfFuture, userFuture).join();
                }

                final Path genExecutable = genFuture.get();
                final Path bfExecutable = bfFuture.get();
                final Path userExecutable = userFuture.get();
                final Path spjExecutable = request.isSpjEnabled() ? spjFuture.get() : null;

                messagingTemplate.convertAndSend(topic, new JudgeProgress("COMPILING", "编译成功", 15));

                // Run test cases in parallel
                List<TestCaseResult> results = new ArrayList<>();
                AtomicInteger completedCases = new AtomicInteger(0);
                AtomicReference<String> finalStatus = new AtomicReference<>("AC");
                AtomicReference<String> finalMessage = new AtomicReference<>("全部通过！");

                List<CompletableFuture<TestCaseResult>> futures = new ArrayList<>();
                
                final List<String> customInputs = request.getCustomTestInputs() != null ? request.getCustomTestInputs() : List.of();
                final int totalCustomCases = customInputs.size();
                final int totalGeneratedCases = request.getTestCases();
                final int totalTestCases = totalCustomCases + totalGeneratedCases;
                final int updateThreshold = Math.max(1, totalTestCases / 100); // Update every 1%

                // Phase 1: Run custom test cases
                for (int i = 0; i < totalCustomCases; i++) {
                    final int caseNum = i + 1;
                    final String customInput = customInputs.get(i);
                    Path finalTempDir = tempDir;

                    CompletableFuture<TestCaseResult> future = CompletableFuture.supplyAsync(() ->
                            runTestCase(caseNum, request, finalTempDir, genExecutable, userExecutable, bfExecutable, spjExecutable, customInput), testCaseExecutor
                    ).whenComplete((result, ex) -> {
                        int done = completedCases.incrementAndGet();
                        if (done % updateThreshold == 0 || done == totalTestCases) {
                            int progress = 15 + (int) ((double) done / totalTestCases * 85);
                            messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", String.format("已完成 %d / %d", done, totalTestCases), progress));
                        }
                    });
                    futures.add(future);
                }

                // Phase 2: Run generated test cases
                for (int i = 0; i < totalGeneratedCases; i++) {
                    final int caseNum = totalCustomCases + i + 1;
                    Path finalTempDir = tempDir;

                    CompletableFuture<TestCaseResult> future = CompletableFuture.supplyAsync(() ->
                            runTestCase(caseNum, request, finalTempDir, genExecutable, userExecutable, bfExecutable, spjExecutable, null), testCaseExecutor
                    ).whenComplete((result, ex) -> {
                        int done = completedCases.incrementAndGet();
                        if (done % updateThreshold == 0 || done == totalTestCases) {
                            int progress = 15 + (int) ((double) done / totalTestCases * 85);
                            messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", String.format("已完成 %d / %d", done, totalTestCases), progress));
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
                
                messagingTemplate.convertAndSend(topic, new JudgeProgress(finalStatus.get(), finalMessage.get(), 100, results));

            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof CompilationException) {
                    messagingTemplate.convertAndSend(topic, new JudgeProgress("COMPILATION_ERROR", cause.getMessage(), 100, null));
                } else {
                    e.printStackTrace();
                    messagingTemplate.convertAndSend(topic, new JudgeProgress("SYSTEM_ERROR", "An unexpected error occurred during compilation.", 100, null));
                }
                return;
            } catch (Exception e) {
                e.printStackTrace();
                messagingTemplate.convertAndSend(topic, new JudgeProgress("SYSTEM_ERROR", e.getMessage(), 100, null));
            }

        } catch (Exception e) {
            e.printStackTrace();
            messagingTemplate.convertAndSend(topic, new JudgeProgress("SYSTEM_ERROR", e.getMessage(), 100, null));
        }
    }

    private TestCaseResult runTestCase(int caseNumber, JudgeRequest request, Path tempDir, Path genExecutable, Path userExecutable, Path bfExecutable, Path spjExecutable, String customInput) {
        try {
            Path inputFile = tempDir.resolve(caseNumber + ".in");
            Path userOutputFile = tempDir.resolve(caseNumber + ".out");
            Path bfOutputFile = tempDir.resolve(caseNumber + ".ans");

            if (customInput != null) {
                Files.writeString(inputFile, customInput);
            } else {
                ProcessResult genResult = runProcess(genExecutable, null, inputFile, 5000);
                if (genResult.status() != RunStatus.SUCCESS) {
                    return new TestCaseResult(caseNumber, "System Error", 0, 0);
                }
            }

            ProcessResult userResult = runProcess(userExecutable, inputFile, userOutputFile, request.getTimeLimit());
            if (userResult.status() != RunStatus.SUCCESS) {
                String statusStr = userResult.status() == RunStatus.TIME_LIMIT_EXCEEDED ? "TLE" : "RE";
                return new TestCaseResult(caseNumber, statusStr, userResult.executionTime(), 0);
            }

            ProcessResult bfResult = runProcess(bfExecutable, inputFile, bfOutputFile, request.getTimeLimit() * 5);
             if (bfResult.status() != RunStatus.SUCCESS) {
                return new TestCaseResult(caseNumber, "System Error", 0, 0);
            }

            if (spjExecutable != null) {
                SpjResult spjResult = runSpjProcess(spjExecutable, inputFile, userOutputFile, bfOutputFile);
                switch (spjResult) {
                    case AC: return new TestCaseResult(caseNumber, "AC", userResult.executionTime(), 0);
                    case WA: return new TestCaseResult(caseNumber, "WA", userResult.executionTime(), 0);
                    default: return new TestCaseResult(caseNumber, "System Error", userResult.executionTime(), 0);
                }
            }

            String userOutput = Files.readString(userOutputFile);
            String bfOutput = Files.readString(bfOutputFile);

            if (outputsMatch(userOutput, bfOutput, request.getPrecision())) {
                return new TestCaseResult(caseNumber, "AC", userResult.executionTime(), 0);
            } else {
                return new TestCaseResult(caseNumber, "WA", userResult.executionTime(), 0);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new TestCaseResult(caseNumber, "System Error", 0, 0);
        }
    }
    
    public TestCaseDetail getTestCaseDetails(String judgeId, int caseNumber) throws IOException {
        Path tempDir = judgeIdToTempDir.get(judgeId);
        if (tempDir == null) {
            throw new IOException("Judge ID not found or session has expired.");
        }

        Path inputFile = tempDir.resolve(caseNumber + ".in");
        Path userOutputFile = tempDir.resolve(caseNumber + ".out");
        Path correctOutputFile = tempDir.resolve(caseNumber + ".ans");

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

    private ProcessResult runProcess(Path executable, Path inputFile, Path outputFile, long timeLimit) throws IOException, InterruptedException {
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
        
        boolean finished = process.waitFor(timeLimit, TimeUnit.MILLISECONDS);
        long executionTime = System.currentTimeMillis() - startTime;

        if (!finished) {
            process.destroyForcibly();
            // Wait a little bit for the process to die.
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                 // The process is stuck even after destroyForcibly.
                 return new ProcessResult(RunStatus.TIME_LIMIT_EXCEEDED, null, "Process timed out and could not be terminated.", executionTime);
            }
            return new ProcessResult(RunStatus.TIME_LIMIT_EXCEEDED, null, "Process exceeded time limit of " + timeLimit + "ms", executionTime);
        }

        int exitCode = process.exitValue();
        String errorOutput = Files.readString(errorFile.toPath());
        Files.delete(errorFile.toPath());

        if (exitCode != 0) {
            return new ProcessResult(RunStatus.RUNTIME_ERROR, null, errorOutput, executionTime);
        }

        String output = outputFile != null && Files.exists(outputFile) ? Files.readString(outputFile) : "";
        return new ProcessResult(RunStatus.SUCCESS, output, null, executionTime);
    }

    private SpjResult runSpjProcess(Path spjExecutable, Path inputFile, Path userOutputFile, Path correctOutputFile) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                spjExecutable.toAbsolutePath().toString(),
                inputFile.toAbsolutePath().toString(),
                userOutputFile.toAbsolutePath().toString(),
                correctOutputFile.toAbsolutePath().toString()
            );
            processBuilder.directory(spjExecutable.getParent().toFile());
            
            Process process = processBuilder.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) { // 10-second timeout for SPJ
                process.destroyForcibly();
                return SpjResult.ERROR;
            }
            return process.exitValue() == 0 ? SpjResult.AC : SpjResult.WA;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            e.printStackTrace();
            return SpjResult.ERROR;
        }
    }
} 