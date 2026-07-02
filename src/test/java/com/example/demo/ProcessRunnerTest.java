package com.example.demo;

import com.example.demo.config.SandboxConfiguration;
import com.example.demo.exception.MemoryLimitExceededException;
import com.example.demo.service.DirectProcessRunner;
import com.example.demo.service.MemoryMonitorService;
import com.example.demo.service.ProcessResult;
import com.example.demo.service.ProcessRunner;
import com.example.demo.service.SandboxProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcessRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void directRunnerCapturesNormalExitOutput() throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);

        ProcessResult result = runner.run(request("normal")
                .maxOutputBytes(1024)
                .maxErrorBytes(1024)
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.SUCCESS);
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.output()).isEqualTo("ok\n");
        assertThat(result.error()).isBlank();
    }

    @Test
    void directRunnerReturnsNonZeroExitWithBoundedStderr() throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);

        ProcessResult result = runner.run(request("nonzero")
                .maxOutputBytes(1024)
                .maxErrorBytes(64)
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.RUNTIME_ERROR);
        assertThat(result.exitCode()).isEqualTo(7);
        assertThat(result.error()).contains("bad exit");
        assertThat(result.error().getBytes()).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void directRunnerTimesOutAndReturnsPromptly() throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);

        long started = System.nanoTime();
        ProcessResult result = runner.run(request("sleep")
                .timeout(Duration.ofMillis(150))
                .killGrace(Duration.ofMillis(300))
                .build());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(result.status()).isEqualTo(ProcessResult.Status.TIME_LIMIT_EXCEEDED);
        assertThat(elapsedMillis).isLessThan(3_000);
    }

    @Test
    void directRunnerStopsInfiniteStdoutAtConfiguredByteLimit() throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);

        ProcessResult result = runner.run(request("infiniteOut")
                .maxOutputBytes(4096)
                .maxErrorBytes(1024)
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED);
        assertThat(result.output().getBytes()).hasSizeLessThanOrEqualTo(4096);
    }

    @Test
    void directRunnerStopsHugeStderrAtConfiguredByteLimit() throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);

        ProcessResult result = runner.run(request("hugeErr")
                .maxOutputBytes(1024)
                .maxErrorBytes(2048)
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED);
        assertThat(result.error().getBytes()).hasSizeLessThanOrEqualTo(2048);
    }

    @Test
    void directRunnerReturnsMemoryLimitExceededWhenMonitorKillsProcess() throws Exception {
        MemoryMonitorService monitor = mock(MemoryMonitorService.class);
        CompletableFuture<MemoryMonitorService.MemoryUsage> failed = new CompletableFuture<>();
        failed.completeExceptionally(new MemoryLimitExceededException(2048, 1024));
        when(monitor.monitorProcess(any(Process.class), anyLong())).thenReturn(failed);
        DirectProcessRunner runner = new DirectProcessRunner(monitor);

        ProcessResult result = runner.run(request("sleep")
                .memoryLimitBytes(1024)
                .timeout(Duration.ofSeconds(5))
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.MEMORY_LIMIT_EXCEEDED);
        assertThat(result.error()).contains("Memory limit exceeded");
    }

    @Test
    void directRunnerRejectsIllegalWorkingDirectory() throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);

        ProcessResult result = runner.run(request("normal")
                .workingDirectory(tempDir.resolve("missing"))
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.SECURITY_VIOLATION);
        assertThat(result.error()).contains("working directory");
    }

    @Test
    void directRunnerIsOnlyAllowedForTrustedLocalProfile() throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);

        ProcessResult result = runner.run(request("normal")
                .profile("intranet-large")
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.SECURITY_VIOLATION);
        assertThat(result.error()).contains("trusted-local");
    }

    @Test
    void sandboxRequiredFailsWhenSandboxIsUnavailable() throws Exception {
        SandboxConfiguration sandboxConfiguration = new SandboxConfiguration();
        sandboxConfiguration.setEnabled(false);
        ProcessRunner fallback = ignored -> {
            throw new AssertionError("sandbox-required execution must not fall back to direct runner");
        };
        SandboxProcessRunner runner = new SandboxProcessRunner(sandboxConfiguration, null, fallback);

        ProcessResult result = runner.run(request("normal")
                .profile("intranet-large")
                .requireSandbox(true)
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.SANDBOX_UNAVAILABLE);
        assertThat(result.error()).contains("Sandbox is required");
    }

    private DirectProcessRunner directRunnerWithMemoryUsage(long peakMemory) {
        MemoryMonitorService monitor = mock(MemoryMonitorService.class);
        when(monitor.monitorProcess(any(Process.class), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        new MemoryMonitorService.MemoryUsage(peakMemory, peakMemory, peakMemory)));
        return new DirectProcessRunner(monitor);
    }

    private ProcessRunner.Request.Builder request(String mode) throws Exception {
        Path workingDirectory = tempDir.resolve("work");
        Files.createDirectories(workingDirectory);
        return ProcessRunner.Request.builder()
                .command(javaCommand(mode))
                .workingDirectory(workingDirectory)
                .timeout(Duration.ofSeconds(2))
                .killGrace(Duration.ofMillis(500))
                .memoryLimitBytes(64 * 1024 * 1024L)
                .maxOutputBytes(1024)
                .maxErrorBytes(1024)
                .profile("trusted-local")
                .requireSandbox(false);
    }

    private List<String> javaCommand(String mode) {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ToolProcess.class.getName());
        command.add(mode);
        return command;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    public static class ToolProcess {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "normal" -> System.out.print("ok\n");
                case "nonzero" -> {
                    System.err.println("bad exit");
                    System.exit(7);
                }
                case "sleep" -> Thread.sleep(30_000);
                case "infiniteOut" -> {
                    while (true) {
                        System.out.print("0123456789abcdef");
                        System.out.flush();
                    }
                }
                case "hugeErr" -> {
                    for (int i = 0; i < 100_000; i++) {
                        System.err.print("0123456789abcdef");
                    }
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
