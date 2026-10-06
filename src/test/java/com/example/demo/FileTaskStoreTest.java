package com.example.demo;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.TaskLifecycleGuard;
import com.example.demo.service.ResolvedTaskPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "stage-journal", "stage-journal/late.json.tmp",
            "supervisor-config.json", "runtime-state.json", "runtime-state.lock"
    })
    void managedAttemptMarkerRejectsDeletionBeforeRemovingAnyFiles(String markerName) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.COMPLETED, workDir));
        Path marker = workDir.resolve("control/attempts/not-a-uuid").resolve(markerName);
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "unfinished evidence");
        Path canary = workDir.resolve("zz-canary.txt");
        Files.writeString(canary, "must remain");
        String metadata = Files.readString(workDir.resolve("metadata.json"));
        String events = Files.readString(workDir.resolve("events.jsonl"));

        assertThatThrownBy(() -> store.deleteTaskDirectory(judgeId))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("managed attempt");

        assertThat(marker).hasContent("unfinished evidence");
        assertThat(canary).hasContent("must remain");
        assertThat(workDir.resolve("metadata.json")).hasContent(metadata);
        assertThat(workDir.resolve("events.jsonl")).hasContent(events);
        assertThat(store.find(judgeId)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{broken", "{\"state\":\"CLEANED\"}"})
    void journalContentsCannotAuthorizeTaskDirectoryDeletion(String contents) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.COMPLETED, workDir));
        Path journal = workDir.resolve("control/attempts/" + UUID.randomUUID() + "/stage-journal/stage.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, contents);

        assertThatThrownBy(() -> store.deleteTaskDirectory(judgeId))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("managed attempt");

        assertThat(journal).hasContent(contents);
        assertThat(store.find(judgeId)).isPresent();
    }

    @Test
    void emptyStageJournalStillRequiresManagedRecovery() throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.COMPLETED, workDir));
        Path journal = workDir.resolve("control/attempts/unfinished/stage-journal");
        Files.createDirectories(journal);

        assertThatThrownBy(() -> store.deleteTaskDirectory(judgeId))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("managed attempt");

        assertThat(journal).isDirectory();
        assertThat(store.find(judgeId)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"control", "control/attempts", "control/attempts/not-a-uuid"})
    void nonDirectoryManagedParentRejectsDeletion(String parentName) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.COMPLETED, workDir));
        Path parent = workDir.resolve(parentName);
        Files.createDirectories(parent.getParent());
        Files.writeString(parent, "not a directory");

        assertThatThrownBy(() -> store.deleteTaskDirectory(judgeId))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("managed attempt");

        assertThat(parent).hasContent("not a directory");
        assertThat(store.find(judgeId)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"control", "control/attempts", "control/attempts/not-a-uuid"})
    void symlinkManagedParentRejectsDeletionEvenWhenTargetIsInsideTask(String parentName) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.COMPLETED, workDir));
        Path target = Files.createDirectory(workDir.resolve("managed-target"));
        Path canary = target.resolve("canary.txt");
        Files.writeString(canary, "must remain");
        Path parent = workDir.resolve(parentName);
        Files.createDirectories(parent.getParent());
        try {
            Files.createSymbolicLink(parent, target);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            Assumptions.assumeTrue(false, "filesystem does not allow creating symlinks: " + ex.getMessage());
        }

        assertThatThrownBy(() -> store.deleteTaskDirectory(judgeId))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("managed attempt");

        assertThat(Files.isSymbolicLink(parent)).isTrue();
        assertThat(canary).hasContent("must remain");
        assertThat(store.find(judgeId)).isPresent();
    }

    @Test
    void olderUnknownAttemptIsNotHiddenByNewerLegacyManifest() throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.SYSTEM_ERROR, workDir));
        Path oldJournal = workDir.resolve("control/attempts/000-old/stage-journal/late-create.json");
        Files.createDirectories(oldJournal.getParent());
        String unknown = "{\"creationState\":\"UNKNOWN\",\"state\":\"CREATE_UNKNOWN\"}";
        Files.writeString(oldJournal, unknown);
        Path newManifest = workDir.resolve("control/attempts/999-new/manifest.json");
        Files.createDirectories(newManifest.getParent());
        Files.writeString(newManifest, "{}");

        assertThatThrownBy(() -> store.deleteTaskDirectory(judgeId))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("managed attempt");

        assertThat(oldJournal).hasContent(unknown);
        assertThat(newManifest).hasContent("{}");
        assertThat(store.find(judgeId)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "task-spec.json", "runner-events.jsonl", "runner-summary.json", "manifest.json"})
    void markerlessAttemptResidueRequiresRecoveryBeforeDeletingAnyFiles(String retainedFile) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.COMPLETED, workDir));
        Path attempt = Files.createDirectories(workDir.resolve("control/attempts/" + UUID.randomUUID()));
        if (!retainedFile.isEmpty()) {
            Files.writeString(attempt.resolve(retainedFile), "retained evidence");
        }
        Path canary = workDir.resolve("zz-canary.txt");
        Files.writeString(canary, "must remain");
        String metadata = Files.readString(workDir.resolve("metadata.json"));
        String events = Files.readString(workDir.resolve("events.jsonl"));
        BasicFileAttributes identity = Files.readAttributes(attempt, BasicFileAttributes.class);
        List<Path> before;
        try (var entries = Files.walk(workDir)) {
            before = entries.sorted().toList();
        }

        assertThatThrownBy(() -> store.deleteTaskDirectory(judgeId))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("managed attempt");

        try (var entries = Files.walk(workDir)) {
            assertThat(entries.sorted().toList()).isEqualTo(before);
        }
        BasicFileAttributes after = Files.readAttributes(attempt, BasicFileAttributes.class);
        assertThat(after.fileKey()).isEqualTo(identity.fileKey());
        assertThat(after.lastModifiedTime()).isEqualTo(identity.lastModifiedTime());
        if (!retainedFile.isEmpty()) {
            assertThat(attempt.resolve(retainedFile)).hasContent("retained evidence");
        }
        assertThat(canary).hasContent("must remain");
        assertThat(workDir.resolve("metadata.json")).hasContent(metadata);
        assertThat(workDir.resolve("events.jsonl")).hasContent(events);
        assertThat(store.find(judgeId)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"control", "control/attempts"})
    void emptyControlDirectoriesWithoutAnyAttemptRemainDeletable(String emptyDirectory) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.COMPLETED, workDir));
        Files.createDirectories(workDir.resolve(emptyDirectory));

        assertThat(store.deleteTaskDirectory(judgeId)).isTrue();

        assertThat(workDir).doesNotExist();
        assertThat(store.find(judgeId)).isEmpty();
    }

    @Test
    void supervisorStartupLockRetainsTaskBeforeAnyAttemptExists() throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.SYSTEM_ERROR, workDir));
        Path lock = workDir.resolve("control/linux-supervisor.lock");
        Files.createDirectories(lock.getParent());
        Files.writeString(lock, "");
        String metadata = Files.readString(workDir.resolve("metadata.json"));

        assertThatThrownBy(() -> store.deleteTaskDirectory(judgeId))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("managed attempt");

        assertThat(lock).exists();
        assertThat(workDir.resolve("metadata.json")).hasContent(metadata);
        assertThat(store.find(judgeId)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "handle", "summary", "delete", "startup"})
    void storageAdmissionBlocksAllMutationsBeforeTaskFilesChange(String operation) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.RUNNING, workDir));
        String metadata = Files.readString(workDir.resolve("metadata.json"));
        String events = Files.readString(workDir.resolve("events.jsonl"));
        String newJudgeId = UUID.randomUUID().toString();
        Path admission = tempDir.resolve(".task-lifecycle.lock");
        try (FileChannel channel = FileChannel.open(admission, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            if (Files.getFileStore(tempDir).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(admission, PosixFilePermissions.fromString("rw-------"));
            }
            try (var held = channel.lock()) {
                assertThatThrownBy(() -> {
                    switch (operation) {
                        case "create" -> store.create(task(newJudgeId, JudgeStatus.CREATED, store.taskDirectory(newJudgeId)));
                        case "update" -> store.updateStatus(judgeId, JudgeStatus.COMPLETED, "must not write");
                        case "handle" -> store.saveRunHandle(judgeId, SandboxRunHandle.builder().judgeId(judgeId)
                                .runId("blocked-run").provider("trusted-local").build());
                        case "summary" -> store.saveSummary(judgeId, new JudgeProgress("RUNNING", "blocked", 10));
                        case "delete" -> store.deleteTaskDirectory(judgeId);
                        case "startup" -> store.markStaleRunningTasksOnStartup();
                        default -> throw new AssertionError(operation);
                    }
                }).isInstanceOf(IOException.class);
                assertThat(workDir.resolve("metadata.json")).hasContent(metadata);
                assertThat(workDir.resolve("events.jsonl")).hasContent(events);
                assertThat(workDir.resolve("summary.json")).doesNotExist();
                assertThat(store.taskDirectory(newJudgeId)).doesNotExist();
            }
        }
    }

    @Test
    void deletionRetainsOneStableStorageLockAcrossTaskRecreation() throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.COMPLETED, workDir));
        Path admission = tempDir.resolve(".task-lifecycle.lock");
        assertThat(admission).isRegularFile();
        Object identity = Files.readAttributes(admission, BasicFileAttributes.class).fileKey();

        assertThat(store.deleteTaskDirectory(judgeId)).isTrue();
        assertThat(workDir).doesNotExist();
        assertThat(admission).isRegularFile();
        store.create(task(judgeId, JudgeStatus.CREATED, workDir));

        assertThat(Files.readAttributes(admission, BasicFileAttributes.class).fileKey()).isEqualTo(identity);
        assertThat(store.find(judgeId)).isPresent();
    }

    @Test
    void deletingFromAnAbsentStorageRootDoesNotCreateAdmissionState() throws Exception {
        Path absent = tempDir.resolve("absent-storage");
        FileTaskStore store = new FileTaskStore(objectMapper, absent);

        assertThat(store.deleteTaskDirectory("absent")).isFalse();

        assertThat(absent).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "other-task", "nested"})
    void createRequiresTheCanonicalTaskDirectory(String placement) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path supplied = switch (placement) {
            case "root" -> tempDir;
            case "other-task" -> store.taskDirectory("other");
            case "nested" -> tempDir.resolve("nested").resolve("judge-" + judgeId);
            default -> throw new AssertionError(placement);
        };

        assertThatThrownBy(() -> store.create(task(judgeId, JudgeStatus.CREATED, supplied)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(supplied.resolve("metadata.json")).doesNotExist();
        assertThat(store.taskDirectory(judgeId)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "handle", "summary"})
    void persistedTaskCannotRedirectWritesToAnotherTask(String operation) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        Path foreign = store.taskDirectory("foreign");
        store.create(task(judgeId, JudgeStatus.RUNNING, workDir));
        store.create(task("foreign", JudgeStatus.CREATED, foreign));
        String untouched = Files.readString(foreign.resolve("metadata.json"));
        var corrupted = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(workDir.resolve("metadata.json").toFile());
        corrupted.put("workDir", foreign.toString());
        objectMapper.writeValue(workDir.resolve("metadata.json").toFile(), corrupted);

        assertThatThrownBy(() -> {
            switch (operation) {
                case "update" -> store.updateStatus(judgeId, JudgeStatus.COMPLETED, "redirected");
                case "handle" -> store.saveRunHandle(judgeId, SandboxRunHandle.builder().judgeId(judgeId)
                        .runId("redirected-run").provider("trusted-local").build());
                case "summary" -> store.saveSummary(judgeId, new JudgeProgress("RUNNING", "redirected", 10));
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOf(IOException.class);

        assertThat(foreign.resolve("metadata.json")).hasContent(untouched);
        assertThat(foreign.resolve("summary.json")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "handle", "summary"})
    void lateWritesAfterAnotherStoreDeletesTheTaskDoNotRecreateIt(String operation) throws Exception {
        FileTaskStore original = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = original.taskDirectory(judgeId);
        original.create(task(judgeId, JudgeStatus.RUNNING, workDir));
        assertThat(store().deleteTaskDirectory(judgeId)).isTrue();

        assertThatThrownBy(() -> {
            switch (operation) {
                case "update" -> original.updateStatus(judgeId, JudgeStatus.COMPLETED, "late");
                case "handle" -> original.saveRunHandle(judgeId, SandboxRunHandle.builder().judgeId(judgeId)
                        .runId("late-run").provider("trusted-local").build());
                case "summary" -> original.saveSummary(judgeId, new JudgeProgress("RUNNING", "late", 10));
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOf(IllegalArgumentException.class);

        assertThat(workDir).doesNotExist();
    }

    @Test
    void waitingMutationDoesNotHoldTaskMonitorAheadOfLifecycleAdmission() throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        store.create(task(judgeId, JudgeStatus.RUNNING, store.taskDirectory(judgeId)));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch entering = new CountDownLatch(1);
        AtomicReference<Thread> updatingThread = new AtomicReference<>();
        Future<JudgeTask> update;
        try {
            try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(tempDir.toRealPath(), false)) {
                update = workers.submit(() -> {
                    updatingThread.set(Thread.currentThread());
                    entering.countDown();
                    return store.updateStatus(judgeId, JudgeStatus.COMPLETED, "after admission");
                });
                assertThat(entering.await(5, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (updatingThread.get().getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertThat(updatingThread.get().getState()).isEqualTo(Thread.State.WAITING);
                Future<Optional<JudgeTask>> read = workers.submit(() -> store.find(judgeId));
                assertThat(read.get(5, TimeUnit.SECONDS).orElseThrow().getStatus()).isEqualTo(JudgeStatus.RUNNING);
                assertThat(update.isDone()).isFalse();
            }
            assertThat(update.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(JudgeStatus.COMPLETED);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
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
