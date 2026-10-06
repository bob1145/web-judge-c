package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.TaskCleanupService;
import com.example.demo.service.sandbox.SandboxRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TaskCleanupServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    Path storageBase;

    @TempDir
    Path outsideBase;

    @Test
    void deletesExpiredTerminalTasksByIndependentRetentionAndPreservesActiveTasks() throws Exception {
        FileTaskStore store = store();
        ExecutionProperties properties = cleanupProperties();
        TaskCleanupService cleanupService = new TaskCleanupService(store, properties);
        Instant now = Instant.parse("2026-07-02T12:00:00Z");

        String completedExpired = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
        String completedFresh = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(9)));
        String cancelledExpired = createTask(store, JudgeStatus.CANCELLED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(21)));
        String failedExpired = createTask(store, JudgeStatus.WA, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(31)));
        String staleExpired = createTask(store, JudgeStatus.STALE, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(6)));
        String runningOld = createTask(store, JudgeStatus.RUNNING, now.minus(Duration.ofHours(2)), null);
        String queuedOld = createTask(store, JudgeStatus.QUEUED, now.minus(Duration.ofHours(2)), null);

        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);

        assertThat(report.deletedJudgeIds())
                .containsExactlyInAnyOrder(completedExpired, cancelledExpired, failedExpired, staleExpired);
        assertThat(report.failedJudgeIds()).isEmpty();
        assertDeleted(store, completedExpired, cancelledExpired, failedExpired, staleExpired);
        assertPreserved(store, completedFresh, JudgeStatus.COMPLETED);
        assertPreserved(store, runningOld, JudgeStatus.RUNNING);
        assertPreserved(store, queuedOld, JudgeStatus.QUEUED);
    }

    @Test
    void startupReconciliationMarksRunningAndQueuedTasksStaleAndWritesEvents() throws Exception {
        FileTaskStore store = store();
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties());
        Instant createdAt = Instant.parse("2026-07-02T00:00:00Z");
        String runningId = createTask(store, JudgeStatus.RUNNING, createdAt, null);
        String queuedId = createTask(store, JudgeStatus.QUEUED, createdAt, null);

        List<JudgeTask> staleTasks = cleanupService.reconcileStartup();

        assertThat(staleTasks).extracting(JudgeTask::getJudgeId)
                .containsExactlyInAnyOrder(runningId, queuedId);
        assertPreserved(store, runningId, JudgeStatus.STALE);
        assertPreserved(store, queuedId, JudgeStatus.STALE);
        assertThat(Files.readString(store.taskDirectory(runningId).resolve("events.jsonl")))
                .contains("\"status\":\"STALE\"");
        assertThat(Files.readString(store.taskDirectory(queuedId).resolve("events.jsonl")))
                .contains("\"status\":\"STALE\"");
    }

    @Test
    void deleteTaskDirectoryRejectsTraversalJudgeId() {
        FileTaskStore store = store();

        assertThatThrownBy(() -> store.deleteTaskDirectory("../escape"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid judgeId");
    }

    @Test
    void cleanupRejectsSymlinkEscapeAndDoesNotDeleteOutsideCanary() throws Exception {
        FileTaskStore store = store();
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties());
        Instant now = Instant.parse("2026-07-02T12:00:00Z");
        String judgeId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
        Path canary = outsideBase.resolve("canary.txt");
        Files.writeString(canary, "must stay");
        Path symlink = store.taskDirectory(judgeId).resolve("escape-link");

        try {
            Files.createSymbolicLink(symlink, canary);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            Assumptions.assumeTrue(false, "filesystem does not allow creating symlinks: " + ex.getMessage());
        }

        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);

        assertThat(report.deletedJudgeIds()).doesNotContain(judgeId);
        assertThat(report.failedJudgeIds()).containsExactly(judgeId);
        assertThat(canary).exists().hasContent("must stay");
        assertThat(store.taskDirectory(judgeId)).exists();
        assertPreserved(store, judgeId, JudgeStatus.COMPLETED);
    }

    @Test
    void judgeServiceNoLongerPerformsDelayedDirectoryDeletion() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/example/demo/service/JudgeService.java"));

        assertThat(source)
                .doesNotContain("FileUtils.deleteDirectory")
                .doesNotContain("CompletableFuture.delayedExecutor(30, TimeUnit.MINUTES)");
    }

    @ParameterizedTest
    @CsvSource({
            "COMPLETED, false, absent",
            "COMPLETED, true, confirmed-supervisor-id",
            "SYSTEM_ERROR, false, absent",
            "STALE, true, null",
            "SYSTEM_ERROR, true, blank"
    })
    void expiredManagedEvidenceSurvivesMissingOrSuccessfullyCleanedHandle(
            JudgeStatus status, boolean hasHandle, String runIdKind
    ) throws Exception {
        FileTaskStore store = store();
        SandboxRunner runner = mock(SandboxRunner.class);
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(), Optional.of(runner));
        Instant now = Instant.parse("2026-07-02T12:00:00Z");
        String managedId = createTask(store, status, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(31)));
        String legacyId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
        String ordinaryId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId(managedId)
                .runId("null".equals(runIdKind) ? null : "blank".equals(runIdKind) ? "" : runIdKind)
                .provider("linux-container")
                .build();
        if (hasHandle) {
            store.saveRunHandle(managedId, handle);
        }
        Path workDir = store.taskDirectory(managedId);
        Path journal = workDir.resolve("control/attempts/not-a-uuid/stage-journal/late-create.json");
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "{\"state\":\"CREATE_UNKNOWN\"}");
        Path legacyManifest = store.taskDirectory(legacyId).resolve("control/attempts/legacy/manifest.json");
        Files.createDirectories(legacyManifest.getParent());
        Files.writeString(legacyManifest, "{}");
        String metadata = Files.readString(workDir.resolve("metadata.json"));
        String events = Files.readString(workDir.resolve("events.jsonl"));

        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);

        assertThat(report.inspectedTasks()).isEqualTo(3);
        assertThat(report.deletedJudgeIds()).containsExactly(ordinaryId);
        assertThat(report.failedJudgeIds()).containsExactlyInAnyOrder(managedId, legacyId);
        assertDeleted(store, ordinaryId);
        assertPreserved(store, legacyId, JudgeStatus.COMPLETED);
        assertThat(legacyManifest).hasContent("{}");
        assertThat(store.taskDirectory(legacyId).resolve("case-data.txt")).hasContent(legacyId);
        assertPreserved(store, managedId, status);
        assertThat(journal).hasContent("{\"state\":\"CREATE_UNKNOWN\"}");
        assertThat(workDir.resolve("metadata.json")).hasContent(metadata);
        assertThat(workDir.resolve("events.jsonl")).hasContent(events);
        assertThat(workDir.resolve("case-data.txt")).hasContent(managedId);
        if (hasHandle && handle.runId() != null && !handle.runId().isBlank()) {
            verify(runner).cleanupResidual(handle);
        } else {
            verifyNoInteractions(runner);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "task-spec.json", "manifest.json"})
    void successfulHandleCleanupDoesNotAuthorizeDeletionOfMarkerlessAttempt(String retainedFile) throws Exception {
        FileTaskStore store = store();
        SandboxRunner runner = mock(SandboxRunner.class);
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(), Optional.of(runner));
        Instant now = Instant.parse("2026-07-02T12:00:00Z");
        String judgeId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId(judgeId).runId("confirmed-supervisor-id").provider("linux-container").build();
        store.saveRunHandle(judgeId, handle);
        Path workDir = store.taskDirectory(judgeId);
        Path attempt = Files.createDirectories(workDir.resolve("control/attempts/" + UUID.randomUUID()));
        if (!retainedFile.isEmpty()) {
            Files.writeString(attempt.resolve(retainedFile), "retained evidence");
        }
        String metadata = Files.readString(workDir.resolve("metadata.json"));
        String events = Files.readString(workDir.resolve("events.jsonl"));

        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);

        assertThat(report.inspectedTasks()).isEqualTo(1);
        assertThat(report.deletedJudgeIds()).isEmpty();
        assertThat(report.failedJudgeIds()).containsExactly(judgeId);
        assertPreserved(store, judgeId, JudgeStatus.COMPLETED);
        assertThat(attempt).isDirectory();
        if (!retainedFile.isEmpty()) {
            assertThat(attempt.resolve(retainedFile)).hasContent("retained evidence");
        }
        assertThat(workDir.resolve("metadata.json")).hasContent(metadata);
        assertThat(workDir.resolve("events.jsonl")).hasContent(events);
        assertThat(workDir.resolve("case-data.txt")).hasContent(judgeId);
        verify(runner).cleanupResidual(handle);
    }

    private FileTaskStore store() {
        return new FileTaskStore(objectMapper, storageBase);
    }

    private ExecutionProperties cleanupProperties() {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setCompletedRetention(Duration.ofMinutes(10));
        properties.setCancelledRetention(Duration.ofMinutes(20));
        properties.setFailedRetention(Duration.ofMinutes(30));
        properties.setStaleRetention(Duration.ofMinutes(5));
        properties.setCleanupInterval(Duration.ofMinutes(30));
        return properties;
    }

    private String createTask(FileTaskStore store, JudgeStatus status, Instant createdAt, Instant finishedAt) throws Exception {
        String judgeId = UUID.randomUUID().toString();
        Path workDir = store.taskDirectory(judgeId);
        store.create(JudgeTask.builder()
                .judgeId(judgeId)
                .status(status)
                .requestedCases(12)
                .mode(policy().profile())
                .policy(policy())
                .workDir(workDir.toString())
                .createdAt(createdAt)
                .finishedAt(finishedAt)
                .message(status.name())
                .build());
        Files.writeString(workDir.resolve("case-data.txt"), judgeId);
        return judgeId;
    }

    private void assertDeleted(FileTaskStore store, String... judgeIds) throws Exception {
        for (String judgeId : judgeIds) {
            assertThat(store.taskDirectory(judgeId)).doesNotExist();
            assertThat(store.find(judgeId)).isEmpty();
        }
        assertThat(store.findAll()).extracting(JudgeTask::getJudgeId)
                .doesNotContain(judgeIds);
    }

    private void assertPreserved(FileTaskStore store, String judgeId, JudgeStatus status) throws Exception {
        assertThat(store.taskDirectory(judgeId)).exists();
        assertThat(store.find(judgeId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(status);
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
}
