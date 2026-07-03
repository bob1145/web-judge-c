package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionCleanupTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    Path storageBase;

    @TempDir
    Path outsideBase;

    @Test
    void startupReconciliationCleansResidualHandlesBeforeMarkingUnfinishedTasksStale() throws Exception {
        FileTaskStore store = store();
        RecordingSandboxRunner runner = new RecordingSandboxRunner();
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(), Optional.of(runner));
        String runningId = createTask(store, JudgeStatus.RUNNING, Instant.parse("2026-07-03T00:00:00Z"), null);
        String queuedId = createTask(store, JudgeStatus.QUEUED, Instant.parse("2026-07-03T00:00:00Z"), null);
        store.saveRunHandle(runningId, handle(runningId, "run-running"));
        store.saveRunHandle(queuedId, handle(queuedId, "run-queued"));

        List<JudgeTask> staleTasks = cleanupService.reconcileStartup();

        assertThat(runner.cleanedRunIds()).containsExactlyInAnyOrder("run-running", "run-queued");
        assertThat(staleTasks).extracting(JudgeTask::getJudgeId)
                .containsExactlyInAnyOrder(runningId, queuedId);
        assertThat(store.find(runningId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.STALE);
        assertThat(store.find(queuedId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.STALE);
    }

    @Test
    void cleanupDeletesExpiredTerminalTasksCleansResidualHandlesAndPreservesActiveRunningTasks() throws Exception {
        FileTaskStore store = store();
        RecordingSandboxRunner runner = new RecordingSandboxRunner();
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(), Optional.of(runner));
        Instant now = Instant.parse("2026-07-03T12:00:00Z");
        String completedExpired = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
        String cancelledExpired = createTask(store, JudgeStatus.CANCELLED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(21)));
        String failedExpired = createTask(store, JudgeStatus.WA, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(31)));
        String staleExpired = createTask(store, JudgeStatus.STALE, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(6)));
        String completedFresh = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(9)));
        String runningOld = createTask(store, JudgeStatus.RUNNING, now.minus(Duration.ofHours(2)), null);
        store.saveRunHandle(completedExpired, handle(completedExpired, "run-completed"));
        store.saveRunHandle(runningOld, handle(runningOld, "run-active"));

        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);

        assertThat(report.deletedJudgeIds())
                .containsExactlyInAnyOrder(completedExpired, cancelledExpired, failedExpired, staleExpired);
        assertThat(report.failedJudgeIds()).isEmpty();
        assertThat(runner.cleanedRunIds()).containsExactly("run-completed");
        assertThat(store.taskDirectory(completedExpired)).doesNotExist();
        assertThat(store.taskDirectory(cancelledExpired)).doesNotExist();
        assertThat(store.taskDirectory(failedExpired)).doesNotExist();
        assertThat(store.taskDirectory(staleExpired)).doesNotExist();
        assertThat(store.taskDirectory(completedFresh)).exists();
        assertThat(store.taskDirectory(runningOld)).exists();
        assertThat(store.find(runningOld)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.RUNNING);
    }

    @Test
    void cleanupFailureKeepsTaskDirectoryAndReportsJudgeId() throws Exception {
        FileTaskStore store = store();
        RecordingSandboxRunner runner = new RecordingSandboxRunner();
        runner.failRunId("run-fail");
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(), Optional.of(runner));
        Instant now = Instant.parse("2026-07-03T12:00:00Z");
        String judgeId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
        store.saveRunHandle(judgeId, handle(judgeId, "run-fail"));
        Path canary = store.taskDirectory(judgeId).resolve("canary.txt");
        Files.writeString(canary, "must stay");

        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);

        assertThat(report.deletedJudgeIds()).doesNotContain(judgeId);
        assertThat(report.failedJudgeIds()).containsExactly(judgeId);
        assertThat(runner.cleanedRunIds()).containsExactly("run-fail");
        assertThat(store.taskDirectory(judgeId)).exists();
        assertThat(canary).exists().hasContent("must stay");
    }

    @Test
    void fileTaskStorePersistsRunHandleInTaskMetadataAcrossRestarts() throws Exception {
        FileTaskStore store = store();
        String judgeId = createTask(store, JudgeStatus.RUNNING, Instant.parse("2026-07-03T00:00:00Z"), null);
        SandboxRunHandle handle = handle(judgeId, "run-persisted");

        store.saveRunHandle(judgeId, handle);
        FileTaskStore restarted = new FileTaskStore(objectMapper, storageBase);

        assertThat(restarted.find(judgeId)).isPresent().get()
                .extracting(task -> task.getSandboxRunHandle().runId())
                .isEqualTo("run-persisted");
    }

    @Test
    void cleanupRejectsDirectoryLinkOrReparseEscapeWithoutDeletingOutsideCanary() throws Exception {
        FileTaskStore store = store();
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties());
        Instant now = Instant.parse("2026-07-03T12:00:00Z");
        String judgeId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(11)));
        Path outsideDir = outsideBase.resolve("outside");
        Files.createDirectories(outsideDir);
        Path outsideCanary = outsideDir.resolve("canary.txt");
        Files.writeString(outsideCanary, "must stay outside");
        Path link = store.taskDirectory(judgeId).resolve("escape-dir");
        Assumptions.assumeTrue(createDirectoryLink(link, outsideDir), "directory symlink/junction unavailable");

        try {
            TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);

            assertThat(report.deletedJudgeIds()).doesNotContain(judgeId);
            assertThat(report.failedJudgeIds()).containsExactly(judgeId);
            assertThat(outsideCanary).exists().hasContent("must stay outside");
            assertThat(store.taskDirectory(judgeId)).exists();
        } finally {
            Files.deleteIfExists(link);
        }
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

    private SandboxRunHandle handle(String judgeId, String runId) {
        return SandboxRunHandle.builder()
                .judgeId(judgeId)
                .runId(runId)
                .provider("linux-container")
                .startedAt(Instant.parse("2026-07-03T00:00:00Z"))
                .eventCursor("0")
                .build();
    }

    private ResolvedTaskPolicy policy() {
        return new ResolvedTaskPolicy(
                "linux-prod",
                true,
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

    private boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception ignored) {
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                return false;
            }
            try {
                Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                        link.toString(), target.toString())
                        .redirectErrorStream(true)
                        .start();
                return process.waitFor() == 0 && Files.exists(link);
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private static final class RecordingSandboxRunner implements SandboxRunner {
        private final List<String> cleanedRunIds = new ArrayList<>();
        private final Set<String> failingRunIds = new HashSet<>();

        void failRunId(String runId) {
            failingRunIds.add(runId);
        }

        List<String> cleanedRunIds() {
            return List.copyOf(cleanedRunIds);
        }

        @Override
        public SandboxCapabilities probe() {
            return SandboxCapabilities.builder()
                    .provider("linux-container")
                    .isolation("container")
                    .productionSafe(true)
                    .networkDisabled(true)
                    .nonRoot(true)
                    .resourceLimits(true)
                    .securityProfile("seccomp:judge-linux")
                    .build();
        }

        @Override
        public SandboxRunHandle start(SandboxTaskSpec spec) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle) {
            return List.of();
        }

        @Override
        public void cancel(SandboxRunHandle handle) {
            throw new UnsupportedOperationException("cleanupResidual should be called");
        }

        @Override
        public void cleanupResidual(SandboxRunHandle handle) {
            cleanedRunIds.add(handle.runId());
            if (failingRunIds.contains(handle.runId())) {
                throw new IllegalStateException("cleanup failed for " + handle.runId());
            }
        }
    }
}
