package com.example.demo.service.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinuxSupervisorCommandExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void capturesBothStreamsAndClosesStandardInput() throws Exception {
        var result = new LinuxSupervisorCommandExecutor(1024).run(command("success"), Duration.ofSeconds(5));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("stdout-eof");
        assertThat(result.stderr()).isEqualTo("stderr");
    }

    @Test
    void returnsNonzeroExitCodeWithoutHidingOutput() throws Exception {
        var result = new LinuxSupervisorCommandExecutor(1024).run(command("nonzero"), Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(23);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEqualTo("failure");
    }

    @Test
    void treatsMetacharactersAsLiteralArgumentsWithoutShellExpansion() throws Exception {
        String argument = "$HOME ; | & < > \" value with spaces";
        var result = new LinuxSupervisorCommandExecutor(1024)
                .run(command("argument", argument), Duration.ofSeconds(5));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo(argument);
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void outputBudgetCountsUtf8BytesRatherThanCharacters() {
        assertThatThrownBy(() -> new LinuxSupervisorCommandExecutor(5)
                .run(command("utf8"), Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class)
                .hasMessage("Supervisor command output limit exceeded");
    }

    @Test
    void acceptsOutputExactlyAtTheCombinedLimit() throws Exception {
        var result = new LinuxSupervisorCommandExecutor(12).run(command("combined"), Duration.ofSeconds(5));

        assertThat(result.stdout()).isEqualTo("123456");
        assertThat(result.stderr()).isEqualTo("abcdef");
    }

    @Test
    void rejectsCombinedOutputBeyondTheLimitWithoutExposingIt() {
        assertThatThrownBy(() -> new LinuxSupervisorCommandExecutor(11)
                .run(command("combined"), Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class)
                .hasMessage("Supervisor command output limit exceeded")
                .hasNoCause();
    }

    @Test
    void stopsAnUnboundedProducerWithoutWaitingForItsExit() {
        long started = System.nanoTime();

        assertThatThrownBy(() -> new LinuxSupervisorCommandExecutor(32)
                .run(command("flood"), Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class)
                .hasMessage("Supervisor command output limit exceeded");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(7));
    }

    @Test
    void timesOutWithoutWaitingForSleepingProcessOrUnboundedDrainerJoin() {
        long started = System.nanoTime();

        assertThatThrownBy(() -> new LinuxSupervisorCommandExecutor(1024)
                .run(command("sleep"), Duration.ofMillis(200)))
                .isInstanceOf(IOException.class)
                .hasMessage("Supervisor command timed out")
                .hasNoCause();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void interruptionIsPreservedAndStopsTheStartedProcess() throws Exception {
        Path ready = tempDir.resolve("ready");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                new LinuxSupervisorCommandExecutor(1024).run(command("ready", ready.toString()), Duration.ofSeconds(20));
            } catch (Throwable exception) {
                failure.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.setDaemon(true);
        caller.start();
        try {
            long readyDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!Files.exists(ready) && caller.isAlive() && System.nanoTime() < readyDeadline) {
                Thread.sleep(10);
            }
            assertThat(Files.exists(ready)).isTrue();
            long processId = Long.parseLong(Files.readString(ready));
            caller.interrupt();
            caller.join(3000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedException.class)
                    .hasMessage("Supervisor command interrupted");
            assertThat(interrupted).isTrue();
            long exitDeadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)
                    && System.nanoTime() < exitDeadline) {
                Thread.sleep(10);
            }
            assertThat(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        } finally {
            caller.interrupt();
            caller.join(3000);
        }
    }

    @Test
    void preexistingInterruptionPreventsProcessStartAndIsPreserved() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new LinuxSupervisorCommandExecutor(1024)
                    .run(command("success"), Duration.ofSeconds(5)))
                    .isInstanceOf(InterruptedException.class)
                    .hasMessage("Supervisor command interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void validatesOutputBounds() {
        for (long invalid : new long[]{-1, 0, (long) Integer.MAX_VALUE + 1}) {
            assertThatThrownBy(() -> new LinuxSupervisorCommandExecutor(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        new LinuxSupervisorCommandExecutor(1);
        new LinuxSupervisorCommandExecutor(Integer.MAX_VALUE);
    }

    @Test
    void validatesArgumentsAndRepresentablePositiveTimeoutsBeforeLaunch() {
        var executor = new LinuxSupervisorCommandExecutor(1024);
        for (List<String> invalid : Arrays.asList(null, List.<String>of(), List.of(""),
                Arrays.asList("java", null), List.of("java", "secret\0value"))) {
            assertThatThrownBy(() -> executor.run(invalid, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration invalid : Arrays.asList(null, Duration.ZERO, Duration.ofNanos(-1),
                Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> executor.run(command("success"), invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void startFailureDoesNotExposeExecutableOrOperatingSystemDetails() {
        assertThatThrownBy(() -> new LinuxSupervisorCommandExecutor(1024)
                .run(List.of(tempDir.resolve("secret-missing-executable").toString()), Duration.ofSeconds(1)))
                .isInstanceOf(IOException.class)
                .hasMessage("Supervisor command could not start")
                .hasNoCause();
    }

    private static List<String> command(String... arguments) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Fixture.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    public static final class Fixture {
        public static void main(String[] arguments) throws Exception {
            switch (arguments[0]) {
                case "success" -> {
                    System.out.print(System.in.read() == -1 ? "stdout-eof" : "stdin-open");
                    System.err.print("stderr");
                }
                case "nonzero" -> {
                    System.err.print("failure");
                    System.exit(23);
                }
                case "combined" -> {
                    System.out.print("123456");
                    System.err.print("abcdef");
                }
                case "argument" -> System.out.print(arguments[1]);
                case "utf8" -> System.out.write(new byte[]{(byte) 0xe4, (byte) 0xbd, (byte) 0xa0,
                        (byte) 0xe5, (byte) 0xa5, (byte) 0xbd});
                case "flood" -> {
                    while (true) {
                        System.out.print("sensitive-output-that-must-not-escape");
                        System.err.print("sensitive-error-that-must-not-escape");
                    }
                }
                case "sleep" -> Thread.sleep(30000);
                case "ready" -> {
                    Path ready = Path.of(arguments[1]);
                    Path temporary = ready.resolveSibling(ready.getFileName() + ".tmp");
                    Files.writeString(temporary, Long.toString(ProcessHandle.current().pid()));
                    Files.move(temporary, ready, StandardCopyOption.ATOMIC_MOVE);
                    Thread.sleep(30000);
                }
                default -> throw new IllegalArgumentException("Unknown fixture");
            }
        }
    }
}
