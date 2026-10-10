package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeProgressEvent;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.dto.TestCaseResult;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionCleanupTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    Path storageBase;

    @TempDir
    Path outsideBase;

    @ParameterizedTest
    @EnumSource(value = JudgeStatus.class, names = {"CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING", "AC", "SYSTEM_ERROR"})
    void failedResidualCleanupPreventsSpringReadinessAndPreservesTaskState(JudgeStatus status) throws Exception {
        FileTaskStore store = store();
        RecordingSandboxRunner runner = new RecordingSandboxRunner();
        String runId = "failed-" + status;
        runner.failRunId(runId);
        Instant now = Instant.now();
        SandboxRunHandle handle = handle(UUID.randomUUID().toString(), runId);
        String judgeId = createTask(store, status, now, status.isTerminal() ? now : null, handle);
        Path metadata = store.taskDirectory(judgeId).resolve("metadata.json");
        byte[] metadataBefore = Files.readAllBytes(metadata);
        byte[] caseDataBefore = Files.readAllBytes(store.taskDirectory(judgeId).resolve("case-data.txt"));
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(), Optional.of(runner));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TaskCleanupService.class, () -> cleanupService);
            assertThatThrownBy(context::refresh)
                    .isInstanceOf(BeanCreationException.class)
                    .hasRootCauseMessage("Residual sandbox cleanup failed for judgeIds: " + judgeId);
            assertThat(context.isActive()).isFalse();
        }

        assertThat(runner.cleanedRunIds()).containsExactly(runId);
        assertThat(runner.cleanupBudgets()).containsExactly(Duration.ofSeconds(2));
        assertThat(store.find(judgeId).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(store.find(judgeId).orElseThrow().getSandboxRunHandle()).isEqualTo(handle);
        assertThat(Files.readAllBytes(metadata)).isEqualTo(metadataBefore);
        assertThat(Files.readAllBytes(store.taskDirectory(judgeId).resolve("case-data.txt"))).isEqualTo(caseDataBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "LOCK", "ATTEMPT"})
    void orphanManagedEvidencePreventsStartupButOrdinaryIncompleteCreationDoesNot(String evidenceType) throws Exception {
        FileTaskStore store = store();
        RecordingSandboxRunner runner = new RecordingSandboxRunner();
        Path directory = store.taskDirectory("orphan-" + evidenceType);
        Files.createDirectory(directory);
        Path caseData = directory.resolve("case-data.txt");
        Files.writeString(caseData, "retained orphan data");
        if (evidenceType.equals("LOCK")) {
            Files.createDirectory(directory.resolve("control"));
            Files.writeString(directory.resolve("control/linux-supervisor.lock"), "retained lock");
        }
        if (evidenceType.equals("ATTEMPT")) {
            Files.createDirectories(directory.resolve("control/attempts/unconfirmed"));
        }
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TaskCleanupService.class, () -> new TaskCleanupService(
                    store, cleanupProperties(), Optional.of(runner)));
            if (evidenceType.equals("NONE")) {
                context.refresh();
                assertThat(context.isActive()).isTrue();
            } else {
                assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
                        .hasStackTraceContaining(directory.getFileName().toString());
                assertThat(context.isActive()).isFalse();
            }
        }
        assertThat(runner.cleanedRunIds()).isEmpty();
        assertThat(directory.resolve("metadata.json")).doesNotExist();
        assertThat(Files.readString(caseData)).isEqualTo("retained orphan data");
        if (evidenceType.equals("LOCK")) {
            assertThat(Files.readString(directory.resolve("control/linux-supervisor.lock"))).isEqualTo("retained lock");
        }
        if (evidenceType.equals("ATTEMPT")) {
            assertThat(directory.resolve("control/attempts/unconfirmed")).isDirectory();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"VALID", "MALFORMED"})
    void orphanFinalWithoutMetadataPreventsStartupAndPreservesAllEvidence(String finalType) throws Exception {
        FileTaskStore store = store();
        RecordingSandboxRunner runner = new RecordingSandboxRunner();
        String judgeId = createTask(store, JudgeStatus.CREATED, Instant.now(), null);
        store.updateStatus(judgeId, JudgeStatus.CANCELLED, "cancelled before startup");
        Path directory = store.taskDirectory(judgeId);
        Path finalPath = directory.resolve("final.json");
        if (finalType.equals("MALFORMED")) {
            Files.writeString(finalPath, "{not-json");
        }
        Path retainedMetadata = storageBase.resolve("retained-metadata-" + judgeId + ".json");
        Files.move(directory.resolve("metadata.json"), retainedMetadata);
        byte[] finalBefore = Files.readAllBytes(finalPath);
        byte[] summaryBefore = Files.readAllBytes(directory.resolve("summary.json"));
        byte[] eventsBefore = Files.readAllBytes(directory.resolve("events.jsonl"));
        byte[] caseDataBefore = Files.readAllBytes(directory.resolve("case-data.txt"));
        byte[] metadataBefore = Files.readAllBytes(retainedMetadata);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TaskCleanupService.class, () -> new TaskCleanupService(
                    store, cleanupProperties(), Optional.of(runner)));
            assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
                    .hasStackTraceContaining("Orphan task directory with final snapshot")
                    .hasStackTraceContaining(directory.getFileName().toString());
            assertThat(context.isActive()).isFalse();
        }
        assertThat(runner.cleanedRunIds()).isEmpty();
        assertThat(directory.resolve("metadata.json")).doesNotExist();
        assertThat(Files.readAllBytes(finalPath)).isEqualTo(finalBefore);
        assertThat(Files.readAllBytes(directory.resolve("summary.json"))).isEqualTo(summaryBefore);
        assertThat(Files.readAllBytes(directory.resolve("events.jsonl"))).isEqualTo(eventsBefore);
        assertThat(Files.readAllBytes(directory.resolve("case-data.txt"))).isEqualTo(caseDataBefore);
        assertThat(Files.readAllBytes(retainedMetadata)).isEqualTo(metadataBefore);
    }

    @ParameterizedTest
    @EnumSource(value = JudgeStatus.class, names = {"CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING"})
    void startupReconciliationCleansResidualHandlesBeforeMarkingUnfinishedTasksStale(JudgeStatus status) throws Exception {
        FileTaskStore store = store();
        RecordingSandboxRunner runner = new RecordingSandboxRunner();
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(), Optional.of(runner));
        String judgeId = createTask(store, status, Instant.now(), null);
        String runId = "run-" + status;
        SandboxRunHandle runHandle = handle(judgeId, runId);
        store.saveRunHandle(judgeId, runHandle);
        List<TestCaseResult> partialResults = List.of(new TestCaseResult(1, "AC", 200, 256),
                new TestCaseResult(2, "WA", 7, 128), new TestCaseResult(3, "AC", 3, 64));
        JudgeSummary partialSummary = new JudgeSummary();
        partialSummary.setTotalCases(12);
        partialSummary.setCompletedCases(3);
        partialSummary.setAc(2);
        partialSummary.setWa(1);
        partialSummary.setFirstFailedCase(2);
        partialSummary.setFailureSamples(List.of(JudgeProgressEvent.from(partialResults.get(1))));
        partialSummary.setSlowSamples(List.of(JudgeProgressEvent.from(partialResults.get(0))));
        store.saveSummary(judgeId, new JudgeProgress(status.name(), "partial progress", 25,
                partialResults, partialSummary));
        Path workDir = store.taskDirectory(judgeId);
        byte[] caseDataBefore = Files.readAllBytes(workDir.resolve("case-data.txt"));

        assertThat(store.findSummary(judgeId).orElseThrow().getRevision()).isEqualTo(1L);
        List<JudgeTask> staleTasks = cleanupService.reconcileStartup();

        assertThat(runner.cleanedRunIds()).containsExactly(runId);
        assertThat(runner.cleanupBudgets()).containsExactly(Duration.ofSeconds(2));
        assertThat(staleTasks).extracting(JudgeTask::getJudgeId).containsExactly(judgeId);
        JudgeTask recovered = store.find(judgeId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(JudgeStatus.STALE);
        assertThat(recovered.getMessage()).contains("RESTART_INTERRUPTED");
        assertThat(recovered.getSandboxRunHandle()).isEqualTo(runHandle);
        assertThat(recovered.getFinishedAt()).isNotNull();
        assertThat(Files.readString(workDir.resolve("events.jsonl"))).contains("RESTART_INTERRUPTED");
        JudgeProgress recoveredSummary = store.findSummary(judgeId).orElseThrow();
        assertThat(recoveredSummary.getStatus()).isEqualTo("STALE");
        assertThat(recoveredSummary.getMessage()).isEqualTo(recovered.getMessage());
        assertThat(recoveredSummary.getProgress()).isEqualTo(25);
        assertThat(recoveredSummary.getResults()).isEqualTo(partialResults);
        partialSummary.setStoppedReason(recovered.getMessage());
        assertThat(recoveredSummary.getSummary()).isEqualTo(partialSummary);
        assertThat(recoveredSummary.getRevision()).isEqualTo(2L);
        assertThat(recoveredSummary.getFinalizedAt()).isNotNull();
        assertThat(objectMapper.readTree(workDir.resolve("final.json").toFile()))
                .isEqualTo(objectMapper.readTree(workDir.resolve("summary.json").toFile()));
        byte[] metadataAfter = Files.readAllBytes(workDir.resolve("metadata.json"));
        byte[] eventsAfter = Files.readAllBytes(workDir.resolve("events.jsonl"));
        byte[] summaryAfter = Files.readAllBytes(workDir.resolve("summary.json"));
        FileTaskStore restarted = store();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TaskCleanupService.class, () -> new TaskCleanupService(
                    restarted, cleanupProperties(), Optional.of(runner)));
            context.refresh();
            assertThat(context.isActive()).isTrue();
        }
        assertThat(runner.cleanedRunIds()).containsExactly(runId, runId);
        assertThat(runner.cleanupBudgets()).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertThat(Files.readAllBytes(workDir.resolve("metadata.json"))).isEqualTo(metadataAfter);
        assertThat(Files.readAllBytes(workDir.resolve("events.jsonl"))).isEqualTo(eventsAfter);
        assertThat(Files.readAllBytes(workDir.resolve("summary.json"))).isEqualTo(summaryAfter);
        assertThat(Files.readAllBytes(workDir.resolve("case-data.txt"))).isEqualTo(caseDataBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING", "STARTED_TERMINAL",
            "BLANK_RUN_ID", "MISSING_RUN_ID", "TERMINAL_ATTEMPT", "TERMINAL_LOCK"})
    void unconfirmedExecutionPreventsStartupAndPreservesTaskEvidence(String scenario) throws Exception {
        FileTaskStore store = store();
        RecordingSandboxRunner runner = new RecordingSandboxRunner();
        JudgeStatus status = switch (scenario) {
            case "STARTED_TERMINAL", "TERMINAL_ATTEMPT", "TERMINAL_LOCK" -> JudgeStatus.SYSTEM_ERROR;
            case "BLANK_RUN_ID", "MISSING_RUN_ID" -> JudgeStatus.RUNNING;
            default -> JudgeStatus.valueOf(scenario);
        };
        String judgeId = createTask(store, scenario.equals("STARTED_TERMINAL") ? JudgeStatus.CREATED : status,
                Instant.now(), status.isTerminal() ? Instant.now() : null);
        if (scenario.equals("STARTED_TERMINAL")) {
            store.updateStatus(judgeId, JudgeStatus.RUNNING, "start requested");
            store.updateStatus(judgeId, status, "unknown start outcome");
        }
        if (scenario.equals("BLANK_RUN_ID") || scenario.equals("MISSING_RUN_ID")) {
            store.saveRunHandle(judgeId, handle(judgeId, scenario.equals("BLANK_RUN_ID") ? " " : null));
        }
        Path workDir = store.taskDirectory(judgeId);
        if (scenario.equals("TERMINAL_ATTEMPT")) {
            Files.createDirectories(workDir.resolve("control/attempts/unconfirmed"));
        }
        if (scenario.equals("TERMINAL_LOCK")) {
            Files.createDirectories(workDir.resolve("control"));
            Files.writeString(workDir.resolve("control/linux-supervisor.lock"), "retained");
        }
        byte[] metadataBefore = Files.readAllBytes(workDir.resolve("metadata.json"));
        byte[] eventsBefore = Files.readAllBytes(workDir.resolve("events.jsonl"));
        byte[] caseDataBefore = Files.readAllBytes(workDir.resolve("case-data.txt"));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TaskCleanupService.class, () -> new TaskCleanupService(
                    store, cleanupProperties(), Optional.of(runner)));
            assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
                    .hasStackTraceContaining(judgeId);
            assertThat(context.isActive()).isFalse();
        }
        assertThat(runner.cleanedRunIds()).isEmpty();
        assertThat(Files.readAllBytes(workDir.resolve("metadata.json"))).isEqualTo(metadataBefore);
        assertThat(Files.readAllBytes(workDir.resolve("events.jsonl"))).isEqualTo(eventsBefore);
        assertThat(Files.readAllBytes(workDir.resolve("case-data.txt"))).isEqualTo(caseDataBefore);
        if (scenario.equals("TERMINAL_ATTEMPT")) {
            assertThat(workDir.resolve("control/attempts/unconfirmed")).isDirectory();
        }
        if (scenario.equals("TERMINAL_LOCK")) {
            assertThat(Files.readString(workDir.resolve("control/linux-supervisor.lock"))).isEqualTo("retained");
        }
    }

    @Test
    void cleanupDeletesExpiredTerminalTasksCleansResidualHandlesAndPreservesActiveRunningTasks() throws Exception {
        FileTaskStore store = store();
        RecordingSandboxRunner runner = new RecordingSandboxRunner();
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties(), Optional.of(runner));
        Instant now = Instant.parse("2026-07-03T12:00:00Z");
        String completedExpired = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofMinutes(11)), handle(UUID.randomUUID().toString(), "run-completed"));
        String cancelledExpired = createTask(store, JudgeStatus.CANCELLED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(21)));
        String failedExpired = createTask(store, JudgeStatus.WA, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(31)));
        String staleExpired = createTask(store, JudgeStatus.STALE, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(6)));
        String completedFresh = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(9)));
        String runningOld = createTask(store, JudgeStatus.RUNNING, now.minus(Duration.ofHours(2)), null);
        store.saveRunHandle(runningOld, handle(runningOld, "run-active"));

        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);

        assertThat(report.deletedJudgeIds())
                .containsExactlyInAnyOrder(completedExpired, cancelledExpired, failedExpired, staleExpired);
        assertThat(report.failedJudgeIds()).isEmpty();
        assertThat(runner.cleanedRunIds()).containsExactly("run-completed");
        assertThat(runner.cleanupBudgets()).containsExactly(Duration.ofSeconds(2));
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
        String judgeId = createTask(store, JudgeStatus.COMPLETED, now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofMinutes(11)), handle(UUID.randomUUID().toString(), "run-fail"));
        Path canary = store.taskDirectory(judgeId).resolve("canary.txt");
        Files.writeString(canary, "must stay");

        TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);

        assertThat(report.deletedJudgeIds()).doesNotContain(judgeId);
        assertThat(report.failedJudgeIds()).containsExactly(judgeId);
        assertThat(runner.cleanedRunIds()).containsExactly("run-fail");
        assertThat(runner.cleanupBudgets()).containsExactly(Duration.ofSeconds(2));
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
        private final List<Duration> cleanupBudgets = new ArrayList<>();
        private final Set<String> failingRunIds = new HashSet<>();

        void failRunId(String runId) {
            failingRunIds.add(runId);
        }

        List<String> cleanedRunIds() {
            return List.copyOf(cleanedRunIds);
        }

        List<Duration> cleanupBudgets() {
            return List.copyOf(cleanupBudgets);
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
        public void cleanupResidual(SandboxRunHandle handle, Duration timeout) {
            cleanupBudgets.add(timeout);
            cleanupResidual(handle);
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
