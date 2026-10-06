package com.example.demo;

import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTaskStoreProcessTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void independentJvmUpdatePreventsDeletionUntilItsFileTransactionFinishes() throws Exception {
        Path storage = Files.createDirectory(temporaryDirectory.resolve("storage")).toRealPath();
        FileTaskStore store = store(storage);
        Path task = create(store);
        Object lockIdentity = Files.readAttributes(storage.resolve(".task-lifecycle.lock"), BasicFileAttributes.class).fileKey();
        try (ChildProcess child = launch(storage, "update")) {
            child.await("READY");
            assertThatThrownBy(() -> store.deleteTaskDirectory("process-task"))
                    .isInstanceOf(IOException.class).hasMessageContaining("already held");
            assertThat(task.resolve("metadata.json")).exists();
            assertThat(store.find("process-task").orElseThrow().getStatus()).isEqualTo(JudgeStatus.RUNNING);
            child.finish();
            assertThat(store.find("process-task").orElseThrow().getStatus()).isEqualTo(JudgeStatus.COMPLETED);
        }
        assertThat(store.deleteTaskDirectory("process-task")).isTrue();
        assertThat(task).doesNotExist();
        assertThat(Files.readAttributes(storage.resolve(".task-lifecycle.lock"), BasicFileAttributes.class).fileKey())
                .isEqualTo(lockIdentity);
    }

    @Test
    void independentJvmDeletionDoesNotAllowOldStoreToRecreateTaskOnLateUpdate() throws Exception {
        Path storage = Files.createDirectory(temporaryDirectory.resolve("storage")).toRealPath();
        FileTaskStore store = store(storage);
        Path task = create(store);
        try (ChildProcess child = launch(storage, "delete")) {
            child.await("DONE");
            child.finish();
        }
        assertThat(task).doesNotExist();
        assertThatThrownBy(() -> store.updateStatus("process-task", JudgeStatus.COMPLETED, "late"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found");
        assertThat(task).doesNotExist();
        assertThat(storage.resolve(".task-lifecycle.lock")).isRegularFile();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "task-spec.json", "manifest.json"})
    void freshJvmRetainsMarkerlessAttemptEvenWithoutSupervisorLock(String retainedFile) throws Exception {
        Path storage = Files.createDirectory(temporaryDirectory.resolve("storage")).toRealPath();
        FileTaskStore store = store(storage);
        Path task = create(store);
        Path attempt = Files.createDirectories(task.resolve("control/attempts/" + UUID.randomUUID()));
        if (!retainedFile.isEmpty()) {
            Files.writeString(attempt.resolve(retainedFile), "retained evidence");
        }
        String metadata = Files.readString(task.resolve("metadata.json"));
        Object identity = Files.readAttributes(attempt, BasicFileAttributes.class).fileKey();
        assertThat(task.resolve("control/linux-supervisor.lock")).doesNotExist();

        try (ChildProcess child = launch(storage, "delete-retained")) {
            child.await("RETAINED");
            child.finish();
        }

        assertThat(task.resolve("metadata.json")).hasContent(metadata);
        assertThat(Files.readAttributes(attempt, BasicFileAttributes.class).fileKey()).isEqualTo(identity);
        if (!retainedFile.isEmpty()) {
            assertThat(attempt.resolve(retainedFile)).hasContent("retained evidence");
        }
        assertThat(store.find("process-task")).isPresent();
    }

    private Path create(FileTaskStore store) throws IOException {
        Path task = store.taskDirectory("process-task");
        store.create(JudgeTask.builder().judgeId("process-task").status(JudgeStatus.RUNNING)
                .workDir(task.toString()).createdAt(Instant.parse("2026-10-03T00:00:00Z")).build());
        return task;
    }

    private static FileTaskStore store(Path root) {
        return new FileTaskStore(JsonMapper.builder().findAndAddModules().build(), root);
    }

    private ChildProcess launch(Path storage, String operation) throws IOException {
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path log = temporaryDirectory.resolve(operation + ".log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", binary).toString(),
                "-Xmx96m", "-XX:ActiveProcessorCount=1", "-XX:MaxDirectMemorySize=16m", "-cp",
                System.getProperty("java.class.path"), StoreProcess.class.getName(), storage.toString(), operation)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return new ChildProcess(process, log);
    }

    private record ChildProcess(Process process, Path log) implements AutoCloseable {
        void await(String marker) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                assertThat(Files.size(log)).isLessThanOrEqualTo(8192);
                String output = Files.readString(log);
                if (output.lines().anyMatch(marker::equals)) {
                    return;
                }
                assertThat(process.isAlive()).as("Child did not reach %s: %s", marker, output).isTrue();
                Thread.sleep(20);
            }
            throw new AssertionError("Independent store process did not signal readiness");
        }

        void finish() throws Exception {
            process.getOutputStream().close();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).as("Child output: %s", Files.readString(log)).isZero();
        }

        @Override
        public void close() throws Exception {
            process.getOutputStream().close();
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                }
            }
        }
    }

    public static final class StoreProcess {
        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 2) {
                throw new IllegalArgumentException("Expected trusted root and operation");
            }
            Path root = Path.of(arguments[0]);
            if ("update".equals(arguments[1])) {
                FileTaskStore store = new FileTaskStore(JsonMapper.builder().findAndAddModules().build(), root) {
                    @Override
                    protected void moveAtomically(Path temporary, Path target) throws IOException {
                        System.out.println("READY");
                        System.out.flush();
                        if (System.in.read() != -1) {
                            throw new IOException("Unexpected child input");
                        }
                        super.moveAtomically(temporary, target);
                    }
                };
                store.updateStatus("process-task", JudgeStatus.COMPLETED, "child completed");
            } else if ("delete-retained".equals(arguments[1])) {
                try {
                    store(root).deleteTaskDirectory("process-task");
                } catch (IOException rejected) {
                    if (rejected.getMessage() == null || !rejected.getMessage().contains("managed attempt")) {
                        throw rejected;
                    }
                    System.out.println("RETAINED");
                    return;
                }
                throw new IOException("Deletion unexpectedly accepted retained attempt evidence");
            } else if ("delete".equals(arguments[1])) {
                if (!store(root).deleteTaskDirectory("process-task")) {
                    throw new IOException("Expected existing private task");
                }
            } else {
                throw new IllegalArgumentException("Unsupported diagnostic operation");
            }
            System.out.println("DONE");
        }
    }
}
