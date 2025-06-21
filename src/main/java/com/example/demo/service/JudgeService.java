package com.example.demo.service;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeRequest;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

@Service
@RequiredArgsConstructor
public class JudgeService {

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, Path> judgeIdToTempDir = new ConcurrentHashMap<>();

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

    @Async
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
            String finalStatus = "AC";
            String finalMessage = "全部通过！";

            for (int i = 1; i <= request.getTestCases(); i++) {
                int progress = 15 + (int) ((double) i / request.getTestCases() * 85);
                messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", "运行测试点 #" + i, progress));

                Path inputFile = tempDir.resolve(i + ".in");
                Path userOutputFile = tempDir.resolve(i + ".out");
                Path bfOutputFile = tempDir.resolve(i + ".ans");

                ProcessResult genResult = runProcess(genExecutable, null, inputFile, 5000);
                if (genResult.status() != RunStatus.SUCCESS) {
                    finalStatus = "SYSTEM_ERROR";
                    finalMessage = "数据生成器运行失败于测试点 #" + i;
                    results.add(new TestCaseResult(i, "System Error", 0, 0));
                    break;
                }

                ProcessResult userResult = runProcess(userExecutable, inputFile, userOutputFile, request.getTimeLimit());
                ProcessResult bfResult = runProcess(bfExecutable, inputFile, bfOutputFile, request.getTimeLimit() * 5);

                if (userResult.status() != RunStatus.SUCCESS) {
                    String statusStr = userResult.status() == RunStatus.TIME_LIMIT_EXCEEDED ? "TLE" : "RE";
                    results.add(new TestCaseResult(i, statusStr, userResult.executionTime(), 0));
                    if (finalStatus.equals("AC")) {
                        finalStatus = statusStr;
                        finalMessage = String.format("%s on Test Case #%d", statusStr, i);
                    }
                    continue;
                }
                
                String userOutput = Files.readString(userOutputFile);
                String bfOutput = Files.readString(bfOutputFile);

                if (outputsMatch(userOutput, bfOutput, request.getPrecision())) {
                    results.add(new TestCaseResult(i, "AC", userResult.executionTime(), 0));
                } else {
                    results.add(new TestCaseResult(i, "WA", userResult.executionTime(), 0));
                    if (finalStatus.equals("AC")) {
                        finalStatus = "WA";
                        finalMessage = "答案错误于测试点 #" + i;
                    }
                }
            }
            messagingTemplate.convertAndSend(topic, new JudgeProgress(finalStatus, finalMessage, 100, results));

        } catch (Exception e) {
            e.printStackTrace();
            messagingTemplate.convertAndSend(topic, new JudgeProgress("SYSTEM_ERROR", e.getMessage(), 100, null));
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
            process.waitFor();
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