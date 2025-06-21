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

@Service
@RequiredArgsConstructor
public class JudgeService {

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, Path> judgeIdToTempDir = new ConcurrentHashMap<>();
    
    @Qualifier(AsyncConfig.TEST_CASE_EXECUTOR)
    private final TaskExecutor testCaseExecutor;

    private enum RunStatus {
        SUCCESS,
        TIME_LIMIT_EXCEEDED,
        RUNTIME_ERROR
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

            Path genExecutable, bfExecutable, userExecutable;
            try {
                messagingTemplate.convertAndSend(topic, new JudgeProgress("COMPILING", "正在编译代码...", 5));

                Path genSource = tempDir.resolve("generator.cpp");
                Files.writeString(genSource, request.getGeneratorCode());
                genExecutable = compile(genSource, "generator");

                Path bfSource = tempDir.resolve("bruteforce.cpp");
                Files.writeString(bfSource, request.getBruteForceCode());
                bfExecutable = compile(bfSource, "bruteforce");

                Path userSource = tempDir.resolve("user.cpp");
                Files.writeString(userSource, request.getUserCode());
                userExecutable = compile(userSource, "user");

                messagingTemplate.convertAndSend(topic, new JudgeProgress("COMPILING", "编译成功", 15));
            } catch (CompilationException e) {
                messagingTemplate.convertAndSend(topic, new JudgeProgress("COMPILATION_ERROR", e.getMessage(), 100, null));
                return;
            }

            List<TestCaseResult> results = new ArrayList<>();
            AtomicInteger completedCases = new AtomicInteger(0);
            AtomicReference<String> finalStatus = new AtomicReference<>("AC");
            AtomicReference<String> finalMessage = new AtomicReference<>("全部通过！");

            List<CompletableFuture<TestCaseResult>> futures = new ArrayList<>();

            final Path finalGenExecutable = genExecutable;
            final Path finalBfExecutable = bfExecutable;
            final Path finalUserExecutable = userExecutable;

            for (int i = 1; i <= request.getTestCases(); i++) {
                final int caseNum = i;
                Path finalTempDir = tempDir;
                CompletableFuture<TestCaseResult> future = CompletableFuture.supplyAsync(() ->
                    runTestCase(caseNum, request, finalTempDir, finalGenExecutable, finalUserExecutable, finalBfExecutable), testCaseExecutor
                ).whenComplete((result, ex) -> {
                    int done = completedCases.incrementAndGet();
                    int progress = 15 + (int) ((double) done / request.getTestCases() * 85);
                    messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", String.format("已完成 %d / %d", done, request.getTestCases()), progress));
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

        } catch (Exception e) {
            e.printStackTrace();
            messagingTemplate.convertAndSend(topic, new JudgeProgress("SYSTEM_ERROR", e.getMessage(), 100, null));
        }
    }

    private TestCaseResult runTestCase(int caseNumber, JudgeRequest request, Path tempDir, Path genExecutable, Path userExecutable, Path bfExecutable) {
        try {
            Path inputFile = tempDir.resolve(caseNumber + ".in");
            Path userOutputFile = tempDir.resolve(caseNumber + ".out");
            Path bfOutputFile = tempDir.resolve(caseNumber + ".ans");

            ProcessResult genResult = runProcess(genExecutable, null, inputFile, 5000);
            if (genResult.status() != RunStatus.SUCCESS) {
                return new TestCaseResult(caseNumber, "System Error", 0, 0);
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

    private Path compile(Path sourceFile, String executableName) throws IOException, InterruptedException {
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

        File compileErrorFile = Files.createTempFile(sourceFile.getParent(), "compile_error_", ".log").toFile();
        processBuilder.redirectError(compileErrorFile);

        Process process = processBuilder.start();
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Compilation was interrupted", e);
        }

        if (exitCode != 0) {
            String errorOutput = Files.readString(compileErrorFile.toPath());
            Files.delete(compileErrorFile.toPath());
            throw new CompilationException("Compilation failed for " + sourceFile.getFileName() + " with exit code " + exitCode + ":\n" + errorOutput);
        }
        
        Files.delete(compileErrorFile.toPath());
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
} 