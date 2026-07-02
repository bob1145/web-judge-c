package com.example.demo;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.ResolvedTaskPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTaskStoreTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    Path tempDir;

    @Test
    void createTaskWritesRecoverableMetadataAndEvents() throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);

        store.create(task(judgeId, JudgeStatus.CREATED, workDir));

        Path metadata = workDir.resolve("metadata.json");
        Path events = workDir.resolve("events.jsonl");
        assertThat(metadata).exists();
        assertThat(events).exists();

        JsonNode metadataJson = objectMapper.readTree(metadata.toFile());
        assertThat(metadataJson.path("judgeId").asText()).isEqualTo(judgeId);
        assertThat(metadataJson.path("status").asText()).isEqualTo("CREATED");
        assertThat(metadataJson.path("requestedCases").asInt()).isEqualTo(12);
        assertThat(metadataJson.path("policy").path("requestedCases").asInt()).isEqualTo(12);
        assertThat(metadataJson.path("workDir").asText()).isEqualTo(workDir.toString());
        assertThat(metadataJson.path("createdAt").asText()).isNotBlank();

        List<String> eventLines = Files.readAllLines(events);
        assertThat(eventLines).hasSize(1);
        assertThat(objectMapper.readTree(eventLines.get(0)).path("status").asText()).isEqualTo("CREATED");

        FileTaskStore reloaded = store();
        Optional<JudgeTask> recovered = reloaded.find(judgeId);
        assertThat(recovered).isPresent();
        assertThat(recovered.get().getJudgeId()).isEqualTo(judgeId);
        assertThat(recovered.get().getPolicy().requestedCases()).isEqualTo(12);
    }

    @Test
    void statusTransitionsAreControlledAndSummaryIsRecoverable() throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        store.create(task(judgeId, JudgeStatus.CREATED, store.taskDirectory(judgeId)));

        store.updateStatus(judgeId, JudgeStatus.RUNNING, "started");
        store.saveSummary(judgeId, new JudgeProgress("RUNNING", "started", 20));
        store.updateStatus(judgeId, JudgeStatus.COMPLETED, "finished");

        assertThatThrownBy(() -> store.updateStatus(judgeId, JudgeStatus.RUNNING, "illegal rollback"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("RUNNING");

        assertThat(store.findSummary(judgeId))
                .isPresent()
                .get()
                .extracting(JudgeProgress::getStatus, JudgeProgress::getProgress)
                .containsExactly("RUNNING", 20);

        String cancelledId = UUID.randomUUID().toString();
        store.create(task(cancelledId, JudgeStatus.CREATED, store.taskDirectory(cancelledId)));
        store.updateStatus(cancelledId, JudgeStatus.CANCELLED, "cancelled");
        assertThatThrownBy(() -> store.updateStatus(cancelledId, JudgeStatus.RUNNING, "illegal restart"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED")
                .hasMessageContaining("RUNNING");
    }

    @Test
    void staleRunningAndQueuedTasksAreMarkedOnStartup() throws Exception {
        FileTaskStore store = store();
        String runningId = UUID.randomUUID().toString();
        String queuedId = UUID.randomUUID().toString();
        String completedId = UUID.randomUUID().toString();
        store.create(task(runningId, JudgeStatus.RUNNING, store.taskDirectory(runningId)));
        store.create(task(queuedId, JudgeStatus.QUEUED, store.taskDirectory(queuedId)));
        store.create(task(completedId, JudgeStatus.COMPLETED, store.taskDirectory(completedId)));

        FileTaskStore restarted = store();
        List<JudgeTask> staleTasks = restarted.markStaleRunningTasksOnStartup();

        assertThat(staleTasks).extracting(JudgeTask::getJudgeId)
                .containsExactlyInAnyOrder(runningId, queuedId);
        assertThat(restarted.find(runningId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.STALE);
        assertThat(restarted.find(queuedId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.STALE);
        assertThat(restarted.find(completedId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.COMPLETED);
    }

    @Test
    void interruptedAtomicMetadataWriteKeepsPreviousMetadataReadable() throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.CREATED, workDir));
        store.updateStatus(judgeId, JudgeStatus.RUNNING, "running");

        String previousMetadata = Files.readString(workDir.resolve("metadata.json"));
        FileTaskStore failingStore = new MoveFailingFileTaskStore(objectMapper, tempDir);

        assertThatThrownBy(() -> failingStore.updateStatus(judgeId, JudgeStatus.COMPLETED, "simulate crash"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated move failure");

        assertThat(Files.readString(workDir.resolve("metadata.json"))).isEqualTo(previousMetadata);
        assertThat(store().find(judgeId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.RUNNING);
    }

    @Test
    void concurrentUpdatesDoNotCorruptMetadataJsonOrEvents() throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.CREATED, workDir));

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> updates = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int index = i;
                updates.add(() -> {
                    store.updateStatus(judgeId, JudgeStatus.RUNNING, "update-" + index);
                    store.saveSummary(judgeId, new JudgeProgress("RUNNING", "update-" + index, index));
                    return null;
                });
            }

            List<Future<Void>> futures = executor.invokeAll(updates);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(objectMapper.readTree(workDir.resolve("metadata.json").toFile()).path("judgeId").asText())
                .isEqualTo(judgeId);
        assertThat(objectMapper.readTree(workDir.resolve("summary.json").toFile()).path("status").asText())
                .isEqualTo("RUNNING");

        for (String line : Files.readAllLines(workDir.resolve("events.jsonl"))) {
            assertThat(objectMapper.readTree(line).path("judgeId").asText()).isEqualTo(judgeId);
        }
    }

    @Test
    void rejectsTaskDirectoriesOutsideStorageBase() {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path outside = tempDir.getParent().resolve("outside-" + UUID.randomUUID());

        assertThatThrownBy(() -> store.create(task(judgeId, JudgeStatus.CREATED, outside)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside storage base");

        assertThatThrownBy(() -> store.taskDirectory("../escape"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid judgeId");
    }

    private FileTaskStore store() {
        return new FileTaskStore(objectMapper, tempDir);
    }

    private JudgeTask task(String judgeId, JudgeStatus status, Path workDir) {
        return JudgeTask.builder()
                .judgeId(judgeId)
                .status(status)
                .requestedCases(12)
                .mode(policy().profile())
                .policy(policy())
                .workDir(workDir.toString())
                .createdAt(Instant.parse("2026-07-02T00:00:00Z"))
                .build();
    }

    private ResolvedTaskPolicy policy() {
        return new ResolvedTaskPolicy(
                "trusted-local",
                false,
                10_000,
                12,
                100,
                4,
                Duration.ofSeconds(2),
                Duration.ofMinutes(30),
                268_435_456L,
                1_048_576L,
                false
        );
    }

    private static class MoveFailingFileTaskStore extends FileTaskStore {

        MoveFailingFileTaskStore(ObjectMapper objectMapper, Path storageBase) {
            super(objectMapper, storageBase);
        }

        @Override
        protected void moveAtomically(Path tempFile, Path targetFile) throws IOException {
            throw new IOException("simulated move failure");
        }
    }
}
