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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
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

    @ParameterizedTest
    @CsvSource({"511,false", "512,false", "513,false", "511,true", "512,true", "513,true"})
    void directRunnerUsesOneOutputBudgetForBothStreamsAndSpooling(int stderrBytes, boolean spool) throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);
        List<String> command = new ArrayList<>(javaCommand("mixed"));
        command.add("512");
        command.add(Integer.toString(stderrBytes));
        Path outputFile = tempDir.resolve("mixed-output.txt");

        ProcessResult result = runner.run(request("mixed")
                .command(command)
                .outputFile(spool ? outputFile : null)
                .maxOutputBytes(1024)
                .maxErrorBytes(1024)
                .build());

        boolean exceeds = 512 + stderrBytes > 1024;
        assertThat(result.status()).isEqualTo(exceeds ? ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED
                : ProcessResult.Status.SUCCESS);
        long capturedOutput = spool ? Files.size(outputFile) : result.output().getBytes(StandardCharsets.UTF_8).length;
        int capturedError = result.error().getBytes(StandardCharsets.UTF_8).length;
        assertThat(capturedOutput + capturedError).isLessThanOrEqualTo(1024);
        if (!exceeds) {
            assertThat(capturedOutput).isEqualTo(512);
            assertThat(capturedError).isEqualTo(stderrBytes);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "empty", "contents"})
    void directRunnerDeliversInputAndClosesStdinAtEof(String inputShape) throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);
        Path input = inputShape.equals("absent") ? null : tempDir.resolve("stdin.txt");
        String contents = inputShape.equals("contents") ? "汉字\n42\n" : "";
        if (input != null) {
            Files.writeString(input, contents);
        }

        ProcessResult result = runner.run(request("stdinEof").inputFile(input).build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.SUCCESS);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo(contents + "EOF\n");
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

    @ParameterizedTest
    @ValueSource(strings = {"normal", "sleep"})
    void directRunnerPropagatesCaptureIoFailureAndStopsProcess(String mode) throws Exception {
        MemoryMonitorService monitor = mock(MemoryMonitorService.class);
        when(monitor.monitorProcess(any(Process.class), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        new MemoryMonitorService.MemoryUsage(1024, 1024, 1024)));
        DirectProcessRunner runner = new DirectProcessRunner(monitor);
        Path outputFile = Files.createDirectory(tempDir.resolve("output-directory"));
        ProcessRunner.Request request = request(mode)
                .outputFile(outputFile)
                .timeout(Duration.ofSeconds(5))
                .build();

        long started = System.nanoTime();
        assertThatThrownBy(() -> runner.run(request))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(outputFile.toString());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        ArgumentCaptor<Process> process = ArgumentCaptor.forClass(Process.class);
        verify(monitor).monitorProcess(process.capture(), anyLong());
        assertThat(process.getValue().isAlive()).isFalse();
        assertThat(elapsedMillis).isLessThan(3_000);
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
    void directRunnerIncludesMonitorSetupInExecutionDeadline() throws Exception {
        MemoryMonitorService monitor = mock(MemoryMonitorService.class);
        when(monitor.monitorProcess(any(Process.class), anyLong())).thenAnswer(invocation -> {
            Thread.sleep(600);
            return CompletableFuture.completedFuture(new MemoryMonitorService.MemoryUsage(1024, 1024, 1024));
        });
        DirectProcessRunner runner = new DirectProcessRunner(monitor);

        long started = System.nanoTime();
        ProcessResult result = runner.run(request("sleep")
                .timeout(Duration.ofMillis(800))
                .killGrace(Duration.ofMillis(200))
                .build());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(result.status()).isEqualTo(ProcessResult.Status.TIME_LIMIT_EXCEEDED);
        assertThat(elapsedMillis).isLessThan(1_100);
        ArgumentCaptor<Process> process = ArgumentCaptor.forClass(Process.class);
        verify(monitor).monitorProcess(process.capture(), anyLong());
        assertThat(process.getValue().isAlive()).isFalse();
    }

    @Test
    void directRunnerSharesOneCleanupDeadlineAcrossHeldOutputPipes() throws Exception {
        MemoryMonitorService monitor = mock(MemoryMonitorService.class);
        CompletableFuture<Process> launched = new CompletableFuture<>();
        when(monitor.monitorProcess(any(Process.class), anyLong())).thenAnswer(invocation -> {
            launched.complete(invocation.getArgument(0));
            return CompletableFuture.completedFuture(new MemoryMonitorService.MemoryUsage(1024, 1024, 1024));
        });
        DirectProcessRunner runner = new DirectProcessRunner(monitor);
        Path childIdentity = tempDir.resolve("pipe-holder.txt");
        List<String> command = new ArrayList<>(javaCommand("holdPipes"));
        command.add(childIdentity.toString());
        ProcessRunner.Request request = request("holdPipes")
                .command(command)
                .timeout(Duration.ofSeconds(3))
                .killGrace(Duration.ofMillis(700))
                .build();
        FutureTask<ProcessResult> execution = new FutureTask<>(() -> runner.run(request));
        Thread caller = new Thread(execution, "direct-runner-held-pipes");
        caller.start();

        try {
            Process parent = launched.get(2, TimeUnit.SECONDS);
            assertThat(parent.waitFor(3, TimeUnit.SECONDS)).isTrue();
            assertThat(parent.exitValue()).isZero();
            assertThat(execution.isDone()).isFalse();
            long exited = System.nanoTime();
            assertThatThrownBy(() -> execution.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IOException.class)
                    .satisfies(failure -> assertThat(failure.getCause())
                            .hasMessageContaining("capture process stream"));
            assertThat(Duration.ofNanos(System.nanoTime() - exited).toMillis()).isLessThan(1_000);
        } finally {
            if (Files.exists(childIdentity)) {
                List<String> identity = Files.readAllLines(childIdentity);
                ProcessHandle child = ProcessHandle.of(Long.parseLong(identity.get(0))).orElseThrow();
                assertThat(processStartIdentity(child)).isEqualTo(identity.get(1));
                child.destroyForcibly();
                child.onExit().get(2, TimeUnit.SECONDS);
                assertThat(child.isAlive()).isFalse();
            }
            caller.interrupt();
            Process parent = launched.getNow(null);
            if (parent != null && parent.isAlive()) {
                parent.destroyForcibly();
                parent.waitFor(1, TimeUnit.SECONDS);
            }
            caller.join(1_000);
            assertThat(caller.isAlive()).isFalse();
        }
    }

    @Test
    void directRunnerStopsItsProcessWhenExecutionWaitIsInterrupted() throws Exception {
        MemoryMonitorService monitor = mock(MemoryMonitorService.class);
        AtomicReference<Process> process = new AtomicReference<>();
        CountDownLatch launched = new CountDownLatch(1);
        when(monitor.monitorProcess(any(Process.class), anyLong())).thenAnswer(invocation -> {
            process.set(invocation.getArgument(0));
            launched.countDown();
            return CompletableFuture.completedFuture(new MemoryMonitorService.MemoryUsage(1024, 1024, 1024));
        });
        DirectProcessRunner runner = new DirectProcessRunner(monitor);
        ProcessRunner.Request request = request("sleep")
                .timeout(Duration.ofSeconds(10))
                .killGrace(Duration.ofMillis(300))
                .build();
        FutureTask<ProcessResult> execution = new FutureTask<>(() -> runner.run(request));
        Thread caller = new Thread(execution, "direct-runner-interrupted-wait");
        caller.start();

        try {
            assertThat(launched.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThatThrownBy(() -> execution.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(process.get().isAlive()).isFalse();
        } finally {
            caller.interrupt();
            if (process.get() != null && process.get().isAlive()) {
                process.get().destroyForcibly();
                process.get().waitFor(1, TimeUnit.SECONDS);
            }
            caller.join(1_000);
            assertThat(caller.isAlive()).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 500})
    void directRunnerStopsInfiniteStdoutAtConfiguredByteLimit(int graceMillis) throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);

        ProcessResult result = runner.run(request("infiniteOut")
                .killGrace(Duration.ofMillis(graceMillis))
                .maxOutputBytes(4096)
                .maxErrorBytes(1024)
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED);
        assertThat(result.output().getBytes()).hasSizeLessThanOrEqualTo(4096);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 500})
    void directRunnerStopsHugeStderrAtConfiguredByteLimit(int graceMillis) throws Exception {
        DirectProcessRunner runner = directRunnerWithMemoryUsage(1024);

        ProcessResult result = runner.run(request("hugeErr")
                .killGrace(Duration.ofMillis(graceMillis))
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

    @ParameterizedTest
    @ValueSource(strings = {"normal", "nonzero"})
    void directRunnerPreservesCompletedMemoryFailureBeforeCallbackDelivery(String mode) throws Exception {
        MemoryMonitorService monitor = mock(MemoryMonitorService.class);
        CompletableFuture<MemoryMonitorService.MemoryUsage> completed = CompletableFuture.failedFuture(
                new MemoryLimitExceededException(2048, 1024));
        CompletableFuture<MemoryMonitorService.MemoryUsage> delayed = spy(completed);
        doReturn(new CompletableFuture<MemoryMonitorService.MemoryUsage>()).when(delayed).whenComplete(any());
        when(monitor.monitorProcess(any(Process.class), anyLong())).thenReturn(delayed);
        DirectProcessRunner runner = new DirectProcessRunner(monitor);

        ProcessResult result = runner.run(request(mode).memoryLimitBytes(1024).build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.MEMORY_LIMIT_EXCEEDED);
        assertThat(result.memoryUsed()).isEqualTo(2048);
        assertThat(result.error()).contains("Memory limit exceeded", "current usage 2048 bytes", "limit 1024 bytes");
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

    private static List<String> javaCommand(String mode) {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ToolProcess.class.getName());
        command.add(mode);
        return command;
    }

    private static String processStartIdentity(ProcessHandle process) throws IOException {
        if (!"Linux".equalsIgnoreCase(System.getProperty("os.name"))) {
            return process.info().startInstant().orElseThrow().toString();
        }
        String stat = Files.readString(Path.of("/proc", Long.toString(process.pid()), "stat"));
        int nameEnd = stat.lastIndexOf(')');
        if (nameEnd < 0 || !stat.startsWith(process.pid() + " (")) {
            throw new IOException("Invalid process identity record");
        }
        String[] fields = stat.substring(nameEnd + 1).trim().split("\\s+");
        if (fields.length <= 19 || !fields[19].matches("[0-9]+")) {
            throw new IOException("Missing process start ticks");
        }
        return "linux-start-ticks:" + fields[19];
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    public static class ToolProcess {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "normal" -> System.out.print("ok\n");
                case "stdinEof" -> {
                    System.in.transferTo(System.out);
                    System.out.print("EOF\n");
                }
                case "mixed" -> {
                    System.out.print("o".repeat(Integer.parseInt(args[1])));
                    System.out.flush();
                    System.err.print("e".repeat(Integer.parseInt(args[2])));
                    System.err.flush();
                }
                case "nonzero" -> {
                    System.err.println("bad exit");
                    System.exit(7);
                }
                case "sleep" -> Thread.sleep(30_000);
                case "holdPipes" -> {
                    Process child = new ProcessBuilder(javaCommand("sleep")).inheritIO().start();
                    Files.writeString(Path.of(args[1]), child.pid() + "\n"
                            + processStartIdentity(child.toHandle()));
                    Thread.sleep(200);
                }
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
