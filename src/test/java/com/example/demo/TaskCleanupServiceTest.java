package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.service.QuotaService;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.TaskCleanupService;
import com.example.demo.service.sandbox.LocalFakeSandboxRunner;
import com.example.demo.service.sandbox.SandboxRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void ttlDeletionKeepsDailyQuotaUntilTheNextBillingDateAndCleanupExpiresTheReceipt() throws Exception {
        FileTaskStore store = store();
        ExecutionProperties properties = cleanupProperties();
        properties.setCancelledRetention(Duration.ZERO);
        properties.setMaxDailyCasesPerUser(12);
        TaskCleanupService cleanupService = new TaskCleanupService(store, properties);
        Instant now = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.ofInstant(now, zone);
        String judgeId = "ttl-daily-quota";
        store.create(JudgeTask.builder().judgeId(judgeId).status(JudgeStatus.CANCELLED)
                .requestedCases(12).policy(policy()).mode("trusted-local")
                .ownership(JudgeOwnership.owner("ttl-daily-owner", "raw-ttl-daily-session"))
                .workDir(store.taskDirectory(judgeId).toString()).createdAt(now).finishedAt(now).build());
        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);
        assertThat(report.deletedJudgeIds()).containsExactly(judgeId);
        assertThat(report.failedJudgeIds()).isEmpty();
        assertDeleted(store, judgeId);
        FileTaskStore reloaded = store();
        assertThat(reloaded.findDeletedQuotaCharges(today, zone)).hasSize(1);
        UserSession session = UserSession.builder().userId("ttl-daily-owner").sessionId("another-session").build();
        assertThatThrownBy(() -> new QuotaService(properties, reloaded).assertCanCreate(session, policy()))
                .isInstanceOf(QuotaService.QuotaExceededException.class)
                .hasMessageContaining("daily case quota").hasMessageContaining("used=12");

        TaskCleanupService.CleanupReport nextDay = cleanupService.cleanupExpiredTasks(
                today.plusDays(1).atStartOfDay(zone).toInstant());
        assertThat(nextDay.inspectedTasks()).isZero();
        assertThat(nextDay.deletedJudgeIds()).isEmpty();
        assertThat(nextDay.failedJudgeIds()).isEmpty();
        try (var receipts = Files.newDirectoryStream(storageBase, "quota-*.json")) {
            assertThat(receipts).isEmpty();
        }
    }

    @Test
    void startupReconciliationMarksRunningAndQueuedTasksStaleAndWritesEvents() throws Exception {
        FileTaskStore store = store();
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(),
                java.util.Optional.of(new LocalFakeSandboxRunner(List.of())));
        Instant createdAt = Instant.parse("2026-07-02T00:00:00Z");
        String runningId = createTask(store, JudgeStatus.RUNNING, createdAt, null);
        String queuedId = createTask(store, JudgeStatus.QUEUED, createdAt, null);
        store.saveRunHandle(runningId, SandboxRunHandle.builder().judgeId(runningId)
                .runId("local-running").provider("local-fake").build());
        store.saveRunHandle(queuedId, SandboxRunHandle.builder().judgeId(queuedId)
                .runId("local-queued").provider("local-fake").build());

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
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId(UUID.randomUUID().toString())
                .runId("null".equals(runIdKind) ? null : "blank".equals(runIdKind) ? "" : runIdKind)
                .provider("linux-container")
                .build();
        String managedId = createTask(store, status, now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofMinutes(31)), hasHandle ? handle : null);
        String legacyId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
        String ordinaryId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
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
            verify(runner).cleanupResidual(handle, Duration.ofSeconds(2));
            verify(runner, never()).cleanupResidual(handle);
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
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId(UUID.randomUUID().toString()).runId("confirmed-supervisor-id").provider("linux-container").build();
        String judgeId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofMinutes(11)), handle);
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
        verify(runner).cleanupResidual(handle, Duration.ofSeconds(2));
        verify(runner, never()).cleanupResidual(handle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "delete-false", "delete-error", "retained-evidence"})
    void terminalDirectoryCleanupDropsOnlyConfirmedDeletedThrottleState(String outcome) throws Exception {
        FileTaskStore store = new FileTaskStore(objectMapper, storageBase) {
            @Override
            public boolean deleteTaskDirectory(String judgeId) throws IOException {
                if (outcome.equals("delete-false")) {
                    return false;
                }
                if (outcome.equals("delete-error")) {
                    throw new IOException("test-only deletion failure");
                }
                return super.deleteTaskDirectory(judgeId);
            }
        };
        Instant now = Instant.now();
        ProgressPublisher publisher = new ProgressPublisher(mock(SimpMessagingTemplate.class), store, objectMapper,
                Clock.fixed(now, ZoneId.of("UTC")), Duration.ofSeconds(1));
        String terminalId = createTask(store, JudgeStatus.RUNNING, now, null);
        String activeId = createTask(store, JudgeStatus.RUNNING, now, null);
        publisher.publish(terminalId, new JudgeProgress("RUNNING", "previous running", 50));
        publisher.publish(activeId, new JudgeProgress("RUNNING", "active running", 10));
        store.updateStatus(terminalId, JudgeStatus.COMPLETED, "completed");
        if (outcome.equals("retained-evidence")) {
            Path manifest = store.taskDirectory(terminalId).resolve("control/attempts/" + UUID.randomUUID() + "/manifest.json");
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest, "retained evidence");
        }
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(), Optional.empty(),
                Optional.of(publisher));
        Map<?, ?> states = (Map<?, ?>) ReflectionTestUtils.getField(publisher, "lastSentByJudgeId");
        assertThat(states).isNotNull();
        assertThat(states.keySet()).isEqualTo(Set.of(terminalId, activeId));

        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now.plus(Duration.ofHours(2)));

        assertThat(report.inspectedTasks()).isEqualTo(2);
        if (outcome.equals("deleted")) {
            assertThat(report.deletedJudgeIds()).containsExactly(terminalId);
            assertThat(report.failedJudgeIds()).isEmpty();
            assertDeleted(store, terminalId);
            assertThat(states.keySet()).isEqualTo(Set.of(activeId));
            assertThatThrownBy(() -> publisher.publish(terminalId, new JudgeProgress("RUNNING", "late running", 75)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to persist judge progress");
            assertThat(states.keySet()).isEqualTo(Set.of(activeId));
            TaskCleanupService.CleanupReport repeated = cleanupService.cleanupExpiredTasks(now.plus(Duration.ofHours(2)));
            assertThat(repeated.deletedJudgeIds()).isEmpty();
            assertThat(repeated.failedJudgeIds()).isEmpty();
        } else {
            assertThat(report.deletedJudgeIds()).isEmpty();
            if (outcome.equals("delete-false")) {
                assertThat(report.failedJudgeIds()).isEmpty();
            } else {
                assertThat(report.failedJudgeIds()).containsExactly(terminalId);
            }
            assertPreserved(store, terminalId, JudgeStatus.COMPLETED);
            assertThat(states.keySet()).isEqualTo(Set.of(terminalId, activeId));
        }
        assertPreserved(store, activeId, JudgeStatus.RUNNING);
        assertThat(store.taskDirectory(activeId).resolve("case-data.txt")).hasContent(activeId);
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
        return createTask(store, status, createdAt, finishedAt, null);
    }

    private String createTask(FileTaskStore store, JudgeStatus status, Instant createdAt, Instant finishedAt,
                              SandboxRunHandle runHandle) throws Exception {
        String judgeId = runHandle == null ? UUID.randomUUID().toString() : runHandle.judgeId();
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
                .sandboxRunHandle(runHandle)
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
