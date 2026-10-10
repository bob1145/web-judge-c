package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.model.DailyQuotaCharge;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import com.example.demo.security.SessionAuditId;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.QuotaService;
import com.example.demo.service.TaskLifecycleGuard;
import com.example.demo.service.ResolvedTaskPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTaskStoreTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @EnumSource(value = JudgeStatus.class, names = {"QUEUED", "PENDING", "COMPILING", "RUNNING"})
    void failedNonterminalAuditAppendRestoresMetadataAndAllowsRetry(JudgeStatus status) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path directory = store.taskDirectory(judgeId);
        JudgeTask created = store.create(task(judgeId, JudgeStatus.CREATED, directory));
        Path metadata = directory.resolve("metadata.json");
        Path events = directory.resolve("events.jsonl");
        Path parkedEvents = directory.resolve("events.parked");
        byte[] previousMetadata = Files.readAllBytes(metadata);
        byte[] previousEvents = Files.readAllBytes(events);
        Files.move(events, parkedEvents);
        Files.createDirectory(events);
        try {
            assertThatThrownBy(() -> store.updateStatus(judgeId, status, "Admission must not partially commit"))
                    .isInstanceOf(IOException.class);
            assertThat(Files.readAllBytes(metadata)).isEqualTo(previousMetadata);
            assertThat(store.find(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(created);
            assertThat(store().find(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(created);
            assertThat(Files.readAllBytes(parkedEvents)).isEqualTo(previousEvents);
            assertThat(directory.resolve("final.json")).doesNotExist();
        } finally {
            Files.move(events, directory.resolve("events.blocker"));
            Files.move(parkedEvents, events);
        }

        JudgeTask retried = store.updateStatus(judgeId, status, "Explicit retry");
        assertThat(retried.getStatus()).isEqualTo(status);
        assertThat(retried.getQuotaChargeId()).isEqualTo(created.getQuotaChargeId());
        assertThat(store().find(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(retried);
        assertThat(Files.readAllLines(events)).hasSize(2);
    }

    @Test
    void failedAuditRollbackPreservesOriginalFailureWithoutInventingSuccess() throws Exception {
        IOException rollbackFailure = new IOException("Injected metadata rollback failure");
        java.util.concurrent.atomic.AtomicInteger metadataWrites = new java.util.concurrent.atomic.AtomicInteger();
        FileTaskStore store = new FileTaskStore(objectMapper, tempDir) {
            @Override
            protected void moveAtomically(Path temporaryFile, Path targetFile) throws IOException {
                if (targetFile.getFileName().toString().equals("metadata.json") && metadataWrites.incrementAndGet() == 3) {
                    throw rollbackFailure;
                }
                super.moveAtomically(temporaryFile, targetFile);
            }
        };
        String judgeId = UUID.randomUUID().toString();
        Path directory = store.taskDirectory(judgeId);
        JudgeTask created = store.create(task(judgeId, JudgeStatus.CREATED, directory));
        Files.move(directory.resolve("events.jsonl"), directory.resolve("events.parked"));
        Files.createDirectory(directory.resolve("events.jsonl"));

        assertThatThrownBy(() -> store.updateStatus(judgeId, JudgeStatus.QUEUED, "Failed admission"))
                .isInstanceOf(IOException.class)
                .isNotSameAs(rollbackFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(rollbackFailure));
        JudgeTask retained = store().find(judgeId).orElseThrow();
        assertThat(retained.getStatus()).isEqualTo(JudgeStatus.QUEUED);
        assertThat(retained.getQuotaChargeId()).isEqualTo(created.getQuotaChargeId());
        assertThat(directory.resolve("final.json")).doesNotExist();
        assertThat(directory.resolve("summary.json")).doesNotExist();
    }

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

    @ParameterizedTest
    @ValueSource(strings = {"metadata-move", "event-append"})
    void failedCreationRemovesOnlyItsNewDirectoryAndCanBeRetried(String stage) throws Exception {
        FileTaskStore healthyStore = store();
        Path unrelated = healthyStore.taskDirectory("unrelated-create");
        healthyStore.create(task("unrelated-create", JudgeStatus.CREATED, unrelated));
        String unrelatedMetadata = Files.readString(unrelated.resolve("metadata.json"));
        String unrelatedEvents = Files.readString(unrelated.resolve("events.jsonl"));
        Path lifecycleLock = tempDir.resolve(".task-lifecycle.lock");
        Object lockIdentity = Files.readAttributes(lifecycleLock, BasicFileAttributes.class).fileKey();
        FileTaskStore failingStore = stage.equals("metadata-move")
                ? new MoveFailingFileTaskStore(objectMapper, tempDir) : eventFailingStore(false);
        String judgeId = "failed-create";
        Path workDir = healthyStore.taskDirectory(judgeId);

        assertThatThrownBy(() -> failingStore.create(task(judgeId, JudgeStatus.CREATED, workDir)))
                .isInstanceOf(IOException.class);

        assertThat(workDir).doesNotExist();
        assertThat(healthyStore.find(judgeId)).isEmpty();
        assertThat(healthyStore.findAll()).extracting(JudgeTask::getJudgeId).containsExactly("unrelated-create");
        assertThat(unrelated.resolve("metadata.json")).hasContent(unrelatedMetadata);
        assertThat(unrelated.resolve("events.jsonl")).hasContent(unrelatedEvents);
        assertThat(Files.readAttributes(lifecycleLock, BasicFileAttributes.class).fileKey()).isEqualTo(lockIdentity);
        healthyStore.create(task(judgeId, JudgeStatus.CREATED, workDir));
        assertThat(healthyStore.find(judgeId)).isPresent();
        assertThat(Files.readAllLines(workDir.resolve("events.jsonl"))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"directory", "task"})
    void creationNeverAdoptsOrOverwritesAnExistingDirectory(String shape) throws Exception {
        FileTaskStore store = store();
        String judgeId = "existing-create";
        Path workDir = store.taskDirectory(judgeId);
        if (shape.equals("task")) {
            store.create(task(judgeId, JudgeStatus.RUNNING, workDir));
        } else {
            Files.createDirectory(workDir);
        }
        Path canary = workDir.resolve("canary.txt");
        Files.writeString(canary, "existing data");
        String previousMetadata = shape.equals("task") ? Files.readString(workDir.resolve("metadata.json")) : null;
        String previousEvents = shape.equals("task") ? Files.readString(workDir.resolve("events.jsonl")) : null;

        assertThatThrownBy(() -> store.create(task(judgeId, JudgeStatus.CREATED, workDir)))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);

        assertThat(canary).hasContent("existing data");
        if (shape.equals("task")) {
            assertThat(workDir.resolve("metadata.json")).hasContent(previousMetadata);
            assertThat(workDir.resolve("events.jsonl")).hasContent(previousEvents);
        } else {
            assertThat(workDir.resolve("metadata.json")).doesNotExist();
            assertThat(workDir.resolve("events.jsonl")).doesNotExist();
        }
    }

    @Test
    void failedCreationRollbackRemainsBoundToItsOriginalTaskIdentity() throws Exception {
        FileTaskStore healthyStore = store();
        Path unrelated = healthyStore.taskDirectory("unrelated-identity");
        healthyStore.create(task("unrelated-identity", JudgeStatus.RUNNING, unrelated));
        String metadata = Files.readString(unrelated.resolve("metadata.json"));
        String events = Files.readString(unrelated.resolve("events.jsonl"));
        Path workDir = healthyStore.taskDirectory("identity-failure");
        JudgeTask mutableTask = task("identity-failure", JudgeStatus.CREATED, workDir);
        FileTaskStore failingStore = new FileTaskStore(objectMapper, tempDir) {
            @Override
            protected void moveAtomically(Path tempFile, Path targetFile) throws IOException {
                super.moveAtomically(tempFile, targetFile);
                mutableTask.setJudgeId("unrelated-identity");
                Files.createDirectory(targetFile.getParent().resolve("events.jsonl"));
            }
        };

        assertThatThrownBy(() -> failingStore.create(mutableTask)).isInstanceOf(IOException.class);

        assertThat(workDir).doesNotExist();
        assertThat(unrelated.resolve("metadata.json")).hasContent(metadata);
        assertThat(unrelated.resolve("events.jsonl")).hasContent(events);
    }

    @Test
    void failedCreationRetainsManagedAttemptEvidenceAndReportsRollbackFailure() throws Exception {
        String judgeId = "managed-create-failure";
        Path workDir = store().taskDirectory(judgeId);
        IOException failure = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> eventFailingStore(true).create(task(judgeId, JudgeStatus.CREATED, workDir)));

        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(IOException.class).hasMessageContaining("managed attempt");
        assertThat(workDir.resolve("control/attempts/unknown/evidence.json")).hasContent("must remain");
        assertThat(workDir.resolve("metadata.json")).exists();
        assertThat(workDir.resolve("events.jsonl")).isDirectory();
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
                .containsExactly("COMPLETED", 20);

        String cancelledId = UUID.randomUUID().toString();
        store.create(task(cancelledId, JudgeStatus.CREATED, store.taskDirectory(cancelledId)));
        store.updateStatus(cancelledId, JudgeStatus.CANCELLED, "cancelled");
        assertThatThrownBy(() -> store.updateStatus(cancelledId, JudgeStatus.RUNNING, "illegal restart"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED")
                .hasMessageContaining("RUNNING");
    }

    @ParameterizedTest
    @EnumSource(value = JudgeStatus.class, names = {"AC", "CANCELLED", "BUDGET_EXCEEDED"})
    void duplicateTerminalStatusDoesNotRewriteMetadataOrEvents(JudgeStatus status) throws Exception {
        FileTaskStore store = store();
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.RUNNING, workDir));
        JudgeTask first = store.updateStatus(judgeId, status, "first terminal message");
        byte[] metadataBefore = Files.readAllBytes(workDir.resolve("metadata.json"));
        byte[] eventsBefore = Files.readAllBytes(workDir.resolve("events.jsonl"));

        JudgeTask duplicate = store.updateStatus(judgeId, status, "late replacement message");

        assertThat(duplicate.getMessage()).isEqualTo(first.getMessage());
        assertThat(duplicate.getFinishedAt()).isEqualTo(first.getFinishedAt());
        assertThat(Files.readAllBytes(workDir.resolve("metadata.json"))).isEqualTo(metadataBefore);
        assertThat(Files.readAllBytes(workDir.resolve("events.jsonl"))).isEqualTo(eventsBefore);
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
        store.saveRunHandle(runningId, SandboxRunHandle.builder().judgeId(runningId)
                .runId("local-running").provider("local-fake").build());
        store.saveRunHandle(queuedId, SandboxRunHandle.builder().judgeId(queuedId)
                .runId("local-queued").provider("local-fake").build());

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

    @ParameterizedTest
    @ValueSource(strings = {"metadata-move", "event-append", "pending"})
    void failedCreationNeverPersistsADeletedDailyCharge(String stage) throws Exception {
        FileTaskStore failingStore = switch (stage) {
            case "metadata-move" -> new MoveFailingFileTaskStore(objectMapper, tempDir);
            case "event-append" -> eventFailingStore(false);
            case "pending" -> store();
            default -> throw new AssertionError(stage);
        };
        JudgeTask task = billableTask("failed-daily-charge");
        assertThatThrownBy(() -> failingStore.create(task, () -> {
            throw new IllegalStateException("simulated pending failure");
        })).isInstanceOf(stage.equals("pending") ? IllegalStateException.class : IOException.class);

        FileTaskStore healthyStore = store();
        assertThat(healthyStore.taskDirectory(task.getJudgeId())).doesNotExist();
        assertThat(healthyStore.findAll()).isEmpty();
        assertThat(healthyStore.findDeletedQuotaCharges(LocalDate.now(), ZoneId.systemDefault())).isEmpty();
        QuotaService quota = new QuotaService(quotaProperties(12), healthyStore);
        quota.assertCanCreate(dailySession(), policy());
        healthyStore.create(task);
        assertThat(healthyStore.find(task.getJudgeId())).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"move", "malformed", "symlink"})
    void aFailedOrUnsafeChargeWriteCannotDeleteTheTask(String failure) throws Exception {
        FileTaskStore healthyStore = store();
        JudgeTask task = healthyStore.create(billableTask("retained-charge-failure"));
        Path directory = healthyStore.taskDirectory(task.getJudgeId());
        String metadata = Files.readString(directory.resolve("metadata.json"));
        String events = Files.readString(directory.resolve("events.jsonl"));
        String digest = SessionAuditId.from(task.getQuotaChargeId()).substring("session-sha256:".length());
        Path receipt = tempDir.resolve("quota-" + digest + ".json");
        Path canary = tempDir.resolve("receipt-canary.txt");
        Files.writeString(canary, "must remain");
        if (failure.equals("malformed")) {
            Files.writeString(receipt, "{broken");
        } else if (failure.equals("symlink")) {
            Files.createSymbolicLink(receipt, canary);
        }
        FileTaskStore deletingStore = failure.equals("move")
                ? new MoveFailingFileTaskStore(objectMapper, tempDir) : healthyStore;
        assertThatThrownBy(() -> deletingStore.deleteTaskDirectory(task.getJudgeId()))
                .isInstanceOf(IOException.class);
        assertThat(directory.resolve("metadata.json")).hasContent(metadata);
        assertThat(directory.resolve("events.jsonl")).hasContent(events);
        assertThat(canary).hasContent("must remain");
        if (failure.equals("move")) {
            assertThat(receipt).doesNotExist();
        } else {
            assertThatThrownBy(() -> store().findDeletedQuotaCharges(LocalDate.now(), ZoneId.systemDefault()))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void publishedChargesAreDeduplicatedButTaskRecreationIsChargedSeparately() throws Exception {
        FileTaskStore healthyStore = store();
        JudgeTask original = healthyStore.create(billableTask("recreated-daily-charge"));
        FileTaskStore postMoveFailure = new FileTaskStore(objectMapper, tempDir) {
            @Override
            protected void moveAtomically(Path tempFile, Path targetFile) throws IOException {
                super.moveAtomically(tempFile, targetFile);
                throw new IOException("simulated failure after publishing charge");
            }
        };
        assertThatThrownBy(() -> postMoveFailure.deleteTaskDirectory(original.getJudgeId()))
                .isInstanceOf(IOException.class);
        assertThat(healthyStore.findAll()).hasSize(1);
        List<DailyQuotaCharge> charges = store().findDeletedQuotaCharges(LocalDate.now(), ZoneId.systemDefault());
        assertThat(charges).containsExactly(DailyQuotaCharge.fromTask(original));
        assertThat(objectMapper.writeValueAsString(charges))
                .doesNotContain("raw-daily-session-secret").doesNotContain("sessionId");
        QuotaService quota = new QuotaService(quotaProperties(24), store());
        quota.assertCanCreate(dailySession(), policy());
        assertThat(healthyStore.deleteTaskDirectory(original.getJudgeId())).isTrue();
        assertThat(healthyStore.deleteTaskDirectory(original.getJudgeId())).isFalse();
        assertThat(healthyStore.findAll()).isEmpty();
        quota.assertCanCreate(dailySession(), policy());

        JudgeTask recreated = healthyStore.create(original);
        assertThat(recreated.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(recreated.getQuotaChargeId()).isNotEqualTo(original.getQuotaChargeId());
        assertThatThrownBy(() -> quota.assertCanCreate(dailySession(), policy()))
                .isInstanceOf(QuotaService.QuotaExceededException.class)
                .hasMessageContaining("daily case quota").hasMessageContaining("used=24");
        assertThat(healthyStore.deleteTaskDirectory(recreated.getJudgeId())).isTrue();
        assertThat(store().findAll()).isEmpty();
        assertThat(store().findDeletedQuotaCharges(LocalDate.now(), ZoneId.systemDefault())).hasSize(2);
        assertThatThrownBy(() -> new QuotaService(quotaProperties(24), store())
                .assertCanCreate(dailySession(), policy()))
                .isInstanceOf(QuotaService.QuotaExceededException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyChargesPreserveRuntimeAndExpireAtTheSystemDateBoundary(boolean missingPolicy) throws Exception {
        FileTaskStore healthyStore = store();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        JudgeTask task = billableTask("legacy-daily-charge");
        task.setCreatedAt(today.atStartOfDay(zone).plusMinutes(1).toInstant());
        if (missingPolicy) {
            task.setPolicy(null);
        }
        healthyStore.create(task);
        Path metadataFile = healthyStore.taskDirectory(task.getJudgeId()).resolve("metadata.json");
        ObjectNode metadata = (ObjectNode) objectMapper.readTree(metadataFile.toFile());
        metadata.remove("quotaChargeId");
        objectMapper.writeValue(metadataFile.toFile(), metadata);
        ExecutionProperties properties = quotaProperties(Long.MAX_VALUE);
        properties.setDefaultTimeLimit(Duration.ofSeconds(5));
        long expectedRuntime = missingPolicy ? 60_000 : 24_000;
        properties.setMaxDailyRuntimeMillisPerUser(expectedRuntime);
        QuotaService quota = new QuotaService(properties, store());
        assertThatThrownBy(() -> quota.assertCanCreate(dailySession(), policy()))
                .hasMessageContaining("daily runtime quota").hasMessageContaining("used=" + expectedRuntime);
        assertThat(healthyStore.deleteTaskDirectory(task.getJudgeId())).isTrue();
        assertThat(store().findAll()).isEmpty();
        assertThatThrownBy(() -> new QuotaService(properties, store()).assertCanCreate(dailySession(), policy()))
                .hasMessageContaining("daily runtime quota").hasMessageContaining("used=" + expectedRuntime);
        assertThat(store().findDeletedQuotaCharges(today, zone)).hasSize(1);
        assertThat(store().findDeletedQuotaCharges(today.plusDays(1), zone)).isEmpty();
        try (var receipts = Files.newDirectoryStream(tempDir, "quota-*.json")) {
            assertThat(receipts).isEmpty();
        }
    }

    @Test
    void accumulatedRuntimeCannotOverflowAndBypassTheDailyLimit() throws Exception {
        FileTaskStore healthyStore = store();
        ResolvedTaskPolicy basePolicy = policy();
        for (String judgeId : List.of("overflow-first", "overflow-second")) {
            JudgeTask task = billableTask(judgeId);
            task.setPolicy(new ResolvedTaskPolicy(basePolicy.profile(), basePolicy.highVolume(),
                    basePolicy.maxCasesPerTask(), basePolicy.requestedCases(), basePolicy.batchSize(),
                    basePolicy.maxConcurrentCasesPerTask(), Duration.ofMillis(Long.MAX_VALUE),
                    basePolicy.maxTaskRuntime(), basePolicy.memoryLimitBytes(), basePolicy.maxOutputBytesPerCase(),
                    basePolicy.sandboxRequired(), basePolicy.comparison()));
            healthyStore.create(task);
        }
        QuotaService quota = new QuotaService(quotaProperties(Long.MAX_VALUE), healthyStore);
        assertThatThrownBy(() -> quota.assertCanCreate(dailySession(), policy()))
                .isInstanceOf(QuotaService.QuotaExceededException.class)
                .hasMessageContaining("daily runtime quota").hasMessageContaining("used=" + Long.MAX_VALUE);
    }

    private JudgeTask billableTask(String judgeId) {
        JudgeTask task = task(judgeId, JudgeStatus.CANCELLED, store().taskDirectory(judgeId));
        task.setCreatedAt(Instant.now());
        task.setOwnership(JudgeOwnership.owner("raw-daily-session-secret", "raw-daily-session-secret"));
        return task;
    }

    private UserSession dailySession() {
        return UserSession.builder().sessionId("raw-daily-session-secret").build();
    }

    private ExecutionProperties quotaProperties(long dailyCases) {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setMaxDailyCasesPerUser(dailyCases);
        properties.setMaxDailyRuntimeMillisPerUser(Long.MAX_VALUE);
        return properties;
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-move", "unsupported-atomic", "after-move", "directory-force",
            "metadata-projection", "summary-projection"})
    void finalCommitFaultsKeepOneAuthoritativeSnapshotAndRecoverProjections(String boundary) throws Exception {
        AtomicBoolean inject = new AtomicBoolean();
        FileTaskStore faultStore = new FileTaskStore(objectMapper, tempDir) {
            @Override
            protected void moveAtomically(Path temporary, Path target) throws IOException {
                String name = target.getFileName().toString();
                if (inject.get() && name.equals("final.json") && boundary.equals("unsupported-atomic")) {
                    throw new AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "injected");
                }
                if (inject.get() && ((name.equals("final.json") && boundary.equals("before-move"))
                        || (name.equals("metadata.json") && boundary.equals("metadata-projection"))
                        || (name.equals("summary.json") && boundary.equals("summary-projection")))) {
                    throw new IOException("injected " + boundary);
                }
                super.moveAtomically(temporary, target);
                if (inject.get() && name.equals("final.json") && boundary.equals("after-move")) {
                    throw new IOException("injected after-move");
                }
            }

            @Override
            protected void forceFinalDirectory(Path directory) throws IOException {
                if (inject.get() && boundary.equals("directory-force")) {
                    throw new IOException("injected directory-force");
                }
                super.forceFinalDirectory(directory);
            }
        };
        String judgeId = "atomic-final";
        Path directory = faultStore.taskDirectory(judgeId);
        faultStore.create(task(judgeId, JudgeStatus.RUNNING, directory));
        faultStore.saveRunHandle(judgeId, SandboxRunHandle.builder().judgeId(judgeId)
                .provider("local-fake").runId("test-run").attemptId("persisted-attempt").build());
        JudgeProgress terminal = terminalProgress();
        faultStore.saveSummary(judgeId, new JudgeProgress("RUNNING", "partial", 75, null, terminal.getSummary()));
        assertThat(faultStore.findSummary(judgeId).orElseThrow().getRevision()).isEqualTo(1L);
        byte[] metadata = Files.readAllBytes(directory.resolve("metadata.json"));
        byte[] summary = Files.readAllBytes(directory.resolve("summary.json"));
        inject.set(true);
        if (boundary.endsWith("projection")) {
            assertThat(faultStore.finalizeTask(judgeId, terminal).getRevision()).isEqualTo(2L);
        } else {
            assertThatThrownBy(() -> faultStore.finalizeTask(judgeId, terminal)).isInstanceOf(IOException.class);
        }
        inject.set(false);
        FileTaskStore cold = store();
        if (boundary.equals("before-move") || boundary.equals("unsupported-atomic")) {
            assertThat(directory.resolve("final.json")).doesNotExist();
            assertThat(Files.readAllBytes(directory.resolve("metadata.json"))).isEqualTo(metadata);
            assertThat(Files.readAllBytes(directory.resolve("summary.json"))).isEqualTo(summary);
            assertThat(cold.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.RUNNING);
            cold.finalizeTask(judgeId, terminal);
        } else {
            assertThat(cold.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.CANCELLED);
            assertThat(cold.findAll()).singleElement().extracting(JudgeTask::getStatus).isEqualTo(JudgeStatus.CANCELLED);
            assertThat(cold.findSummary(judgeId).orElseThrow()).usingRecursiveComparison()
                    .ignoringFields("attemptId", "revision", "finalizedAt").isEqualTo(terminal);
        }
        JudgeProgress committed = cold.findSummary(judgeId).orElseThrow();
        assertThat(committed.getAttemptId()).isEqualTo("persisted-attempt");
        assertThat(committed.getRevision()).isEqualTo(2L);
        assertThat(committed.getFinalizedAt()).isNotNull();
        byte[] finalBytes = Files.readAllBytes(directory.resolve("final.json"));
        cold.markStaleRunningTasksOnStartup();
        JudgeProgress conflict = new JudgeProgress("AC", "late conflict", 100);
        assertThat(cold.finalizeTask(judgeId, conflict)).usingRecursiveComparison().isEqualTo(committed);
        assertThat(Files.readAllBytes(directory.resolve("final.json"))).isEqualTo(finalBytes);
        assertThat(objectMapper.readTree(directory.resolve("metadata.json").toFile()).path("status").asText())
                .isEqualTo("CANCELLED");
        assertThat(objectMapper.readTree(directory.resolve("summary.json").toFile()))
                .isEqualTo(objectMapper.readTree(finalBytes));
        try (var entries = Files.list(directory)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
        terminal.getSummary().setAc(0);
        assertThat(cold.findSummary(judgeId).orElseThrow().getSummary().getAc()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{not-json", "{\"status\":\"RUNNING\"}"})
    void startupRepairsInvalidSummaryOnlyFromValidatedFinal(String damagedSummary) throws Exception {
        FileTaskStore store = store();
        String judgeId = "invalid-summary";
        Path directory = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.CREATED, directory));
        JudgeProgress committed = store.finalizeTask(judgeId, terminalProgress());
        byte[] finalBytes = Files.readAllBytes(directory.resolve("final.json"));
        byte[] metadataBytes = Files.readAllBytes(directory.resolve("metadata.json"));
        byte[] eventsBytes = Files.readAllBytes(directory.resolve("events.jsonl"));
        Files.writeString(directory.resolve("summary.json"), damagedSummary);

        FileTaskStore cold = store();
        assertThat(cold.markStaleRunningTasksOnStartup()).isEmpty();
        assertThat(cold.findSummary(judgeId).orElseThrow()).isEqualTo(committed);
        assertThat(objectMapper.readTree(directory.resolve("summary.json").toFile()))
                .isEqualTo(objectMapper.readTree(directory.resolve("final.json").toFile()));
        assertThat(Files.readAllBytes(directory.resolve("final.json"))).isEqualTo(finalBytes);
        assertThat(Files.readAllBytes(directory.resolve("metadata.json"))).isEqualTo(metadataBytes);
        assertThat(Files.readAllBytes(directory.resolve("events.jsonl"))).isEqualTo(eventsBytes);
        byte[] repairedSummary = Files.readAllBytes(directory.resolve("summary.json"));
        assertThat(store().markStaleRunningTasksOnStartup()).isEmpty();
        assertThat(Files.readAllBytes(directory.resolve("summary.json"))).isEqualTo(repairedSummary);
        assertThat(Files.readAllBytes(directory.resolve("final.json"))).isEqualTo(finalBytes);
        assertThat(Files.readAllBytes(directory.resolve("events.jsonl"))).isEqualTo(eventsBytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aggregate", "revision", "attempt", "missing-metadata"})
    void invalidFinalSnapshotsFailClosedInsteadOfFallingBackToProjections(String invalid) throws Exception {
        FileTaskStore store = store();
        String judgeId = "invalid-final";
        Path directory = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.CREATED, directory));
        JudgeProgress finalProgress = store.finalizeTask(judgeId, terminalProgress());
        switch (invalid) {
            case "aggregate" -> finalProgress.getSummary().setWa(0);
            case "revision" -> finalProgress.setRevision(null);
            case "attempt" -> finalProgress.setAttemptId("foreign-attempt");
            case "missing-metadata" -> Files.delete(directory.resolve("metadata.json"));
            default -> throw new AssertionError(invalid);
        }
        objectMapper.writeValue(directory.resolve("final.json").toFile(), finalProgress);
        assertThatThrownBy(() -> store().findSummary(judgeId)).isInstanceOf(IOException.class);
    }

    @Test
    void legacyValidSummaryIsPreservedWithoutBackfillingFinalFromHistory() throws Exception {
        FileTaskStore store = store();
        String judgeId = "legacy-summary";
        Path directory = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.CREATED, directory));
        JudgeProgress legacy = terminalProgress();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("summary.json").toFile(), legacy);
        byte[] summary = Files.readAllBytes(directory.resolve("summary.json"));
        byte[] metadata = Files.readAllBytes(directory.resolve("metadata.json"));
        assertThat(store().findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(legacy);
        assertThat(store().find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.CANCELLED);
        assertThat(store().findAll()).singleElement().extracting(JudgeTask::getStatus).isEqualTo(JudgeStatus.CANCELLED);
        assertThatThrownBy(() -> store.updateStatus(judgeId, JudgeStatus.RUNNING, "must not resurrect"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.finalizeTask(judgeId, new JudgeProgress("AC", "conflict", 100)))
                .usingRecursiveComparison().isEqualTo(legacy);
        assertThat(directory.resolve("final.json")).doesNotExist();
        assertThat(Files.readAllBytes(directory.resolve("summary.json"))).isEqualTo(summary);
        assertThat(Files.readAllBytes(directory.resolve("metadata.json"))).isEqualTo(metadata);
    }

    @Test
    void nonterminalAndFinalRevisionsFollowPersistedSnapshotsAcrossColdReads() throws Exception {
        FileTaskStore store = store();
        String judgeId = "revision-sequence";
        Path directory = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.RUNNING, directory));
        store.saveRunHandle(judgeId, SandboxRunHandle.builder().judgeId(judgeId)
                .provider("local-fake").runId("revision-run").attemptId("persisted-attempt").build());
        JudgeProgress running = new JudgeProgress("RUNNING", "partial", 75, null, terminalProgress().getSummary());
        running.setRevision(Long.MAX_VALUE);
        running.setAttemptId("untrusted-caller-attempt");
        running.setFinalizedAt(Instant.EPOCH);
        store.saveSummary(judgeId, running);
        JudgeProgress first = store().findSummary(judgeId).orElseThrow();
        assertThat(first.getRevision()).isEqualTo(1L);
        assertThat(first.getAttemptId()).isEqualTo("persisted-attempt");
        assertThat(first.getFinalizedAt()).isNull();
        assertThat(running.getRevision()).isEqualTo(Long.MAX_VALUE);
        assertThat(running.getAttemptId()).isEqualTo("untrusted-caller-attempt");

        store().saveSummary(judgeId, running);
        JudgeProgress second = store().findSummary(judgeId).orElseThrow();
        assertThat(second.getRevision()).isEqualTo(2L);
        assertThat(second).usingRecursiveComparison().ignoringFields("revision").isEqualTo(first);
        JudgeProgress committed = store().finalizeTask(judgeId, terminalProgress());
        assertThat(committed.getRevision()).isEqualTo(3L);
        assertThat(committed.getAttemptId()).isEqualTo(first.getAttemptId());
        assertThat(committed.getFinalizedAt()).isNotNull();
        assertThat(committed.getSummary()).usingRecursiveComparison().isEqualTo(first.getSummary());
        byte[] finalBytes = Files.readAllBytes(directory.resolve("final.json"));
        store().saveSummary(judgeId, running);
        assertThat(store().finalizeTask(judgeId, new JudgeProgress("AC", "late conflict", 100)))
                .usingRecursiveComparison().isEqualTo(committed);
        assertThat(store().findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(committed);
        assertThat(Files.readAllBytes(directory.resolve("final.json"))).isEqualTo(finalBytes);
    }

    @Test
    void failedNonterminalCommitDoesNotConsumeOrPublishARevision() throws Exception {
        AtomicBoolean inject = new AtomicBoolean();
        FileTaskStore store = new FileTaskStore(objectMapper, tempDir) {
            @Override
            protected void moveAtomically(Path temporary, Path target) throws IOException {
                if (inject.get() && target.getFileName().toString().equals("summary.json")) {
                    throw new IOException("injected summary failure");
                }
                super.moveAtomically(temporary, target);
            }
        };
        String judgeId = "revision-commit-failure";
        Path directory = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.RUNNING, directory));
        store.saveSummary(judgeId, new JudgeProgress("RUNNING", "first", 25));
        JudgeProgress first = store().findSummary(judgeId).orElseThrow();
        assertThat(first.getRevision()).isEqualTo(1L);
        byte[] summaryBytes = Files.readAllBytes(directory.resolve("summary.json"));
        inject.set(true);
        assertThatThrownBy(() -> store.saveSummary(judgeId, new JudgeProgress("RUNNING", "second", 50)))
                .isInstanceOf(IOException.class).hasMessageContaining("injected");
        assertThat(Files.readAllBytes(directory.resolve("summary.json"))).isEqualTo(summaryBytes);
        assertThat(store().findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(first);
        inject.set(false);
        store.saveSummary(judgeId, new JudgeProgress("RUNNING", "second", 50));
        JudgeProgress retried = store().findSummary(judgeId).orElseThrow();
        assertThat(retried.getRevision()).isEqualTo(2L);
        assertThat(retried.getProgress()).isEqualTo(50);
        assertThat(directory.resolve("final.json")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "CANCELLED"})
    void exhaustedRevisionsRejectFurtherWritesWithoutChangingSnapshots(String status) throws Exception {
        FileTaskStore store = store();
        String judgeId = "revision-exhausted";
        Path directory = store.taskDirectory(judgeId);
        store.create(task(judgeId, JudgeStatus.RUNNING, directory));
        JudgeProgress previous = new JudgeProgress("RUNNING", "retained", 75, null, terminalProgress().getSummary());
        previous.setRevision(Long.MAX_VALUE);
        Files.writeString(directory.resolve("summary.json"), objectMapper.writeValueAsString(previous));
        byte[] summaryBytes = Files.readAllBytes(directory.resolve("summary.json"));
        byte[] metadataBytes = Files.readAllBytes(directory.resolve("metadata.json"));
        JudgeProgress next = new JudgeProgress(status, "must not wrap revision", 75, null, previous.getSummary());
        assertThatThrownBy(() -> store.saveSummary(judgeId, next))
                .isInstanceOf(IOException.class).hasMessageContaining("revision");
        assertThat(Files.readAllBytes(directory.resolve("summary.json"))).isEqualTo(summaryBytes);
        assertThat(Files.readAllBytes(directory.resolve("metadata.json"))).isEqualTo(metadataBytes);
        assertThat(directory.resolve("final.json")).doesNotExist();
        assertThat(store().findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(previous);
    }

    private JudgeProgress terminalProgress() {
        return new JudgeProgress("CANCELLED", "cancelled after real results", 75, null,
                new JudgeSummary(4, 3, 2, 1, 0, 0, 0, 0, 0, 2, List.of(), List.of(), "cancelled"));
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

    private FileTaskStore eventFailingStore(boolean managed) {
        return new FileTaskStore(objectMapper, tempDir) {
            @Override
            protected void moveAtomically(Path tempFile, Path targetFile) throws IOException {
                super.moveAtomically(tempFile, targetFile);
                if (managed) {
                    Path evidence = targetFile.getParent().resolve("control/attempts/unknown/evidence.json");
                    Files.createDirectories(evidence.getParent());
                    Files.writeString(evidence, "must remain");
                }
                Files.createDirectory(targetFile.getParent().resolve("events.jsonl"));
            }
        };
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
