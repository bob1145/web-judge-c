package com.example.demo.service;

import com.example.demo.exception.MemoryLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class DirectProcessRunner implements ProcessRunner {

    private static final String TRUSTED_LOCAL = "trusted-local";
    private static final int BUFFER_SIZE = 8192;

    private final MemoryMonitorService memoryMonitorService;

    @Override
    public ProcessResult run(Request request) throws IOException, InterruptedException {
        ProcessResult validationFailure = validate(request);
        if (validationFailure != null) {
            return validationFailure;
        }

        long started = System.nanoTime();
        ProcessBuilder processBuilder = new ProcessBuilder(request.command());
        processBuilder.directory(request.workingDirectory().toFile());
        if (request.inputFile() != null) {
            processBuilder.redirectInput(request.inputFile().toFile());
        }

        Process process = processBuilder.start();
        AtomicReference<MemoryLimitExceededException> memoryExceeded = new AtomicReference<>();
        CompletableFuture<MemoryMonitorService.MemoryUsage> memoryFuture =
                memoryMonitorService.monitorProcess(process, request.memoryLimitBytes());
        memoryFuture.whenComplete((usage, throwable) -> {
            MemoryLimitExceededException exceeded = unwrapMemoryExceeded(throwable);
            if (exceeded != null) {
                memoryExceeded.compareAndSet(null, exceeded);
                killProcessTree(process, request.killGrace());
            }
        });

        CompletableFuture<StreamCapture> stdout = CompletableFuture.supplyAsync(() ->
                captureStream(process.getInputStream(), request.outputFile(), request.maxOutputBytes(), process, request.killGrace()));
        CompletableFuture<StreamCapture> stderr = CompletableFuture.supplyAsync(() ->
                captureStream(process.getErrorStream(), null, request.maxErrorBytes(), process, request.killGrace()));

        boolean finished = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
        long executionTime = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (!finished) {
            boolean cleaned = killProcessTree(process, request.killGrace());
            StreamCapture error = awaitCapture(stderr, request.killGrace());
            String cleanupError = cleaned ? "" : " Process timed out and could not be fully terminated.";
            return new ProcessResult(
                    ProcessResult.Status.TIME_LIMIT_EXCEEDED,
                    awaitCapture(stdout, request.killGrace()).content(),
                    error.content() + cleanupError,
                    executionTime,
                    peakMemory(memoryFuture),
                    -1
            );
        }

        StreamCapture output = awaitCapture(stdout, request.killGrace());
        StreamCapture error = awaitCapture(stderr, request.killGrace());
        MemoryLimitExceededException exceeded = memoryExceeded.get();
        if (exceeded != null) {
            return new ProcessResult(
                    ProcessResult.Status.MEMORY_LIMIT_EXCEEDED,
                    output.content(),
                    "Memory limit exceeded: " + exceeded.getMessage(),
                    executionTime,
                    exceeded.getCurrentUsage(),
                    safeExitValue(process)
            );
        }

        if (output.limitExceeded() || error.limitExceeded()) {
            return new ProcessResult(
                    ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED,
                    output.content(),
                    error.content(),
                    executionTime,
                    peakMemory(memoryFuture),
                    safeExitValue(process)
            );
        }

        int exitCode = safeExitValue(process);
        ProcessResult.Status status = exitCode == 0
                ? ProcessResult.Status.SUCCESS
                : ProcessResult.Status.RUNTIME_ERROR;
        return new ProcessResult(status, output.content(), error.content(), executionTime, peakMemory(memoryFuture), exitCode);
    }

    private ProcessResult validate(Request request) {
        if (!TRUSTED_LOCAL.equals(request.profile())) {
            return ProcessResult.failure(
                    ProcessResult.Status.SECURITY_VIOLATION,
                    "Direct process execution is only allowed for trusted-local profile"
            );
        }
        if (request.command() == null || request.command().isEmpty() || request.command().get(0).isBlank()) {
            return ProcessResult.failure(ProcessResult.Status.RUNTIME_ERROR, "Process command is empty");
        }
        if (request.workingDirectory() == null || !Files.isDirectory(request.workingDirectory())) {
            return ProcessResult.failure(ProcessResult.Status.SECURITY_VIOLATION, "Invalid working directory");
        }
        if (request.inputFile() != null && !Files.isRegularFile(request.inputFile())) {
            return ProcessResult.failure(ProcessResult.Status.SECURITY_VIOLATION, "Input file is not readable");
        }
        Path outputFile = request.outputFile();
        if (outputFile != null) {
            Path parent = outputFile.toAbsolutePath().normalize().getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                return ProcessResult.failure(ProcessResult.Status.SECURITY_VIOLATION, "Output directory is invalid");
            }
        }
        return null;
    }

    private StreamCapture captureStream(
            InputStream inputStream,
            Path outputFile,
            long maxBytes,
            Process process,
            Duration killGrace
    ) {
        long limit = Math.max(0, maxBytes);
        long total = 0;
        boolean limitExceeded = false;
        ByteArrayOutputStream memory = outputFile == null ? new ByteArrayOutputStream((int) Math.min(limit, 8192)) : null;
        try (InputStream in = inputStream;
             OutputStream out = outputFile == null
                     ? memory
                     : Files.newOutputStream(outputFile,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                long remaining = limit - total;
                if (remaining <= 0) {
                    limitExceeded = true;
                    killProcessTree(process, killGrace);
                    break;
                }
                int toWrite = (int) Math.min(read, remaining);
                out.write(buffer, 0, toWrite);
                total += toWrite;
                if (toWrite < read) {
                    limitExceeded = true;
                    killProcessTree(process, killGrace);
                    break;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to capture process stream: {}", e.getMessage());
            return new StreamCapture(memory == null ? "" : memory.toString(StandardCharsets.UTF_8), true);
        }
        return new StreamCapture(memory == null ? "" : memory.toString(StandardCharsets.UTF_8), limitExceeded);
    }

    private StreamCapture awaitCapture(CompletableFuture<StreamCapture> capture, Duration grace) {
        try {
            return capture.get(Math.max(1, grace.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StreamCapture("", true);
        } catch (ExecutionException | TimeoutException e) {
            return new StreamCapture("", true);
        }
    }

    private long peakMemory(CompletableFuture<MemoryMonitorService.MemoryUsage> memoryFuture) {
        try {
            if (!memoryFuture.isDone()) {
                return 0;
            }
            return memoryFuture.get().peakMemory();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (ExecutionException e) {
            MemoryLimitExceededException exceeded = unwrapMemoryExceeded(e);
            return exceeded == null ? 0 : exceeded.getCurrentUsage();
        }
    }

    private static MemoryLimitExceededException unwrapMemoryExceeded(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof MemoryLimitExceededException exceeded) {
                return exceeded;
            }
            current = current.getCause();
        }
        return null;
    }

    private static int safeExitValue(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    private static boolean killProcessTree(Process process, Duration grace) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (Exception ignored) {
                // The result below reports if the process is still alive.
            }
        });
        process.destroyForcibly();
        try {
            process.waitFor(Math.max(1, grace.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !process.isAlive();
    }

    private record StreamCapture(String content, boolean limitExceeded) {
    }
}
