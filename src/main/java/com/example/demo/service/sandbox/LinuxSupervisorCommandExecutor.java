package com.example.demo.service.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public final class LinuxSupervisorCommandExecutor implements LinuxContainerRunner.CommandExecutor {

    private static final long WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final int MAX_OBSERVED_DESCENDANTS = 256;

    private final long outputLimitBytes;

    public LinuxSupervisorCommandExecutor(long outputLimitBytes) {
        if (outputLimitBytes < 1 || outputLimitBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Supervisor command output limit is invalid");
        }
        this.outputLimitBytes = outputLimitBytes;
    }

    @Override
    public LinuxContainerRunner.CommandResult run(List<String> command, Duration timeout)
            throws IOException, InterruptedException {
        List<String> arguments = validateCommand(command);
        Deadline deadline = new Deadline(System.nanoTime(), validateTimeout(timeout));
        requireNotInterrupted();
        Process process;
        try {
            process = new ProcessBuilder(arguments).start();
        } catch (IOException | SecurityException exception) {
            throw new IOException("Supervisor command could not start");
        }
        boolean completed = false;
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        OutputCapture capture = new OutputCapture(outputLimitBytes, deadline);
        try {
            try {
                process.getOutputStream().close();
            } catch (IOException exception) {
                throw new IOException("Supervisor command input could not be closed");
            }
            startDrainer(process.getInputStream(), capture.stdout, capture);
            startDrainer(process.getErrorStream(), capture.stderr, capture);
            while (true) {
                requireNotInterrupted();
                IOException failure = capture.failure.get();
                if (failure != null) {
                    throw failure;
                }
                long remaining = deadline.remaining();
                if (remaining <= 0) {
                    throw new IOException("Supervisor command timed out");
                }
                if (!process.isAlive() && capture.finished.getCount() == 0) {
                    IOException completedFailure = capture.failure.get();
                    if (completedFailure != null) {
                        throw completedFailure;
                    }
                    completed = true;
                    return new LinuxContainerRunner.CommandResult(process.exitValue(),
                            capture.stdout.toString(StandardCharsets.UTF_8),
                            capture.stderr.toString(StandardCharsets.UTF_8));
                }
                observeDescendants(process, descendants);
                long waitNanos = Math.min(deadline.remaining(), WAIT_SLICE_NANOS);
                if (waitNanos > 0) {
                    if (process.isAlive()) {
                        process.waitFor(waitNanos, TimeUnit.NANOSECONDS);
                    } else {
                        capture.finished.await(waitNanos, TimeUnit.NANOSECONDS);
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Supervisor command interrupted");
        } finally {
            if (!completed) {
                terminate(process, descendants);
            }
        }
    }

    private static List<String> validateCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Supervisor command arguments are invalid");
        }
        for (String argument : command) {
            if (argument == null || argument.indexOf(0) >= 0) {
                throw new IllegalArgumentException("Supervisor command arguments are invalid");
            }
        }
        if (command.get(0).isBlank()) {
            throw new IllegalArgumentException("Supervisor command arguments are invalid");
        }
        return List.copyOf(command);
    }

    private static long validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Supervisor command timeout is invalid");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Supervisor command timeout is invalid");
        }
    }

    private static void requireNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Supervisor command interrupted");
        }
    }

    private static void startDrainer(InputStream stream, ByteArrayOutputStream destination, OutputCapture capture) {
        Thread drainer = new Thread(() -> {
            try (stream) {
                byte[] buffer = new byte[4096];
                while (capture.failure.get() == null) {
                    if (capture.deadline.remaining() <= 0) {
                        capture.fail("Supervisor command timed out");
                        return;
                    }
                    int count = stream.read(buffer);
                    if (count < 0) {
                        return;
                    }
                    if (capture.deadline.remaining() <= 0) {
                        capture.fail("Supervisor command timed out");
                        return;
                    }
                    if (!capture.append(destination, buffer, count)) {
                        return;
                    }
                }
            } catch (IOException exception) {
                capture.fail("Supervisor command output could not be read");
            } finally {
                capture.finished.countDown();
            }
        }, "linux-supervisor-command-output");
        drainer.setDaemon(true);
        drainer.start();
    }

    private static void observeDescendants(Process process, Set<ProcessHandle> descendants) throws IOException {
        if (!process.isAlive() || descendants.size() >= MAX_OBSERVED_DESCENDANTS) {
            return;
        }
        try (Stream<ProcessHandle> children = process.descendants()) {
            children.limit(MAX_OBSERVED_DESCENDANTS - descendants.size()).forEach(descendants::add);
        } catch (RuntimeException exception) {
            throw new IOException("Supervisor command process tracking failed");
        }
    }

    private static void terminate(Process process, Set<ProcessHandle> descendants) {
        for (ProcessHandle descendant : descendants) {
            try {
                descendant.destroyForcibly();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            process.toHandle().destroyForcibly();
        } catch (RuntimeException ignored) {
        }
        Thread streamCloser = new Thread(() -> {
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
        }, "linux-supervisor-command-close");
        streamCloser.setDaemon(true);
        streamCloser.start();
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private record Deadline(long started, long timeoutNanos) {
        long remaining() {
            return timeoutNanos - (System.nanoTime() - started);
        }
    }

    private static final class OutputCapture {
        private final long limit;
        private final Deadline deadline;
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final AtomicReference<IOException> failure = new AtomicReference<>();
        private final CountDownLatch finished = new CountDownLatch(2);
        private long captured;

        private OutputCapture(long limit, Deadline deadline) {
            this.limit = limit;
            this.deadline = deadline;
        }

        private synchronized boolean append(ByteArrayOutputStream destination, byte[] buffer, int count) {
            if (failure.get() != null) {
                return false;
            }
            if (count > limit - captured) {
                fail("Supervisor command output limit exceeded");
                return false;
            }
            destination.write(buffer, 0, count);
            captured += count;
            return true;
        }

        private void fail(String message) {
            failure.compareAndSet(null, new IOException(message));
        }
    }
}
