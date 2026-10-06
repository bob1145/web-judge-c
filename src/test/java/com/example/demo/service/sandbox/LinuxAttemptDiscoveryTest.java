package com.example.demo.service.sandbox;

import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.TaskLifecycleGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@EnabledOnOs(OS.LINUX)
class LinuxAttemptDiscoveryTest {
    @TempDir
    Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private Path storage;
    private Path task;
    private Path attempt;
    private String attemptId;
    private LinuxSupervisorOptions options;
    private LinuxSupervisorLauncher launcher;
    private LinuxContainerRunner runner;

    @BeforeEach
    void prepare() throws Exception {
        assumeTrue(((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue() == 0,
                "existing supervisor journal contract requires an actual root writer");
        storage = temporaryDirectory.resolve("storage");
        FileTaskStore store = new FileTaskStore(mapper, storage);
        task = store.taskDirectory("discovery");
        store.create(JudgeTask.builder().judgeId("discovery").workDir(task.toString()).build());
        assertNull(store.find("discovery").orElseThrow().getSandboxRunHandle());
        Files.createDirectories(task.resolve("control/attempts"));
        privateFile(task.resolve("control/linux-supervisor.lock"), "");
        attemptId = UUID.randomUUID().toString();
        attempt = Files.createDirectory(task.resolve("control/attempts").resolve(attemptId));
        options = new LinuxSupervisorOptions("/usr/bin/docker", "unix:///run/docker.sock",
                "/usr/bin/docker", "unix:///run/runtime/docker.sock", "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), "/work", 0, 0, 0, 0, 0, 0, 65534, 65534,
                new LinuxSupervisorOptions.ResourceLimits(67108864, 64, 1.0),
                new LinuxSupervisorOptions.ResourceLimits(8388608, 8, 0.5), Duration.ofSeconds(60),
                32, 1.0, 1024, 2048, 4096, 8192, Duration.ofSeconds(1), Duration.ofSeconds(2));
        LinuxContainerRunner.CommandExecutor noRuntime = (command, timeout) -> {
            throw new AssertionError("discovery must never issue a runtime command: " + command);
        };
        launcher = new LinuxSupervisorLauncher(options, noRuntime, mapper);
        runner = new LinuxContainerRunner(LinuxContainerRunner.Options.builder().supervisor(options).build(),
                noRuntime, mapper);
    }

    @Test
    void trustedEmptyCancellationMarkerIsDiscoveredWithoutMutationOrRuntimeCalls() throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        privateFile(attempt.resolve("cancel.requested"), "");
        Map<String, String> before = inventory();
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());
        assertTrue(report.scanComplete());
        assertEquals(1, report.findings().size());
        assertEquals(LinuxAttemptDiscovery.Reason.RECOVERY_REQUIRED, report.findings().get(0).reason());
        assertEquals(before, inventory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unsafe"})
    void cancellationPublishedAfterDirectoryScanIsValidatedUnderJournalLock(String content) throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        Path marker = attempt.resolve("cancel.requested");
        ObjectMapper racingMapper = spy(mapper.copy());
        doAnswer(invocation -> {
            assertFalse(Files.exists(marker, LinkOption.NOFOLLOW_LINKS));
            privateFile(marker, content);
            return mapper.copy();
        }).when(racingMapper).copy();

        LinuxAttemptDiscovery.Report report = new LinuxAttemptDiscovery(launcher, options, racingMapper)
                .inspect(storage, generousBudget());

        verify(racingMapper).copy();
        assertEquals(content.isEmpty(), report.scanComplete());
        assertEquals(1, report.findings().size());
        assertEquals(content.isEmpty() ? LinuxAttemptDiscovery.Reason.RECOVERY_REQUIRED
                : LinuxAttemptDiscovery.Reason.JOURNAL_UNREADABLE_OR_INVALID, report.findings().get(0).reason());
        assertEquals(content, Files.readString(marker));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(marker)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mode", "contents", "hard-link", "uid", "gid", "temporary"})
    void cancellationDiscoveryRejectsUnsafeMarkerAndPreservesEvidence(String shape) throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        Path marker = attempt.resolve(shape.equals("temporary") ? "cancel.requested.tmp" : "cancel.requested");
        privateFile(marker, "");
        switch (shape) {
            case "mode" -> Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-r--r--"));
            case "contents" -> Files.writeString(marker, "unsafe");
            case "hard-link" -> Files.createLink(temporaryDirectory.resolve("marker-alias"), marker);
            case "uid" -> Files.setAttribute(marker, "unix:uid", 1);
            case "gid" -> Files.setAttribute(marker, "unix:gid", 1);
            default -> { }
        }
        Map<String, String> before = inventory();
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());
        assertFalse(report.scanComplete());
        assertTrue(report.findings().stream().noneMatch(finding -> finding.reason() == LinuxAttemptDiscovery.Reason.RECOVERY_REQUIRED));
        assertEquals(before, inventory());
    }

    @ParameterizedTest
    @EnumSource(LinuxRuntimeState.SnapshotPhase.class)
    void discoversAllDurablePhasesWithoutMetadataHandleOrRuntimeCalls(LinuxRuntimeState.SnapshotPhase phase) throws Exception {
        writeSnapshot(snapshot(phase));
        Files.delete(task.resolve("metadata.json"));
        Map<String, String> before = inventory();
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());
        assertTrue(report.scanComplete());
        assertEquals(1, report.findings().size());
        assertEquals(LinuxAttemptDiscovery.Reason.RECOVERY_REQUIRED, report.findings().get(0).reason());
        assertEquals(phase.name(), report.findings().get(0).phase());
        assertEquals("judge-discovery/control/attempts/" + attemptId, report.findings().get(0).relativePath());
        assertEquals(LinuxAttemptDiscovery.JOURNAL_READ_RESERVATION_BYTES, report.journalReadBytesReserved());
        assertEquals(before, inventory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"judge", "attempt", "configuration", "unknown-field", "invalid-state", "malformed-json"})
    void rejectsUntrustedJournalIdentityConfigurationAndShape(String mutation) throws Exception {
        ObjectNode state = snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STARTED);
        switch (mutation) {
            case "judge" -> state.put("judgeId", "other");
            case "attempt" -> state.put("attemptId", UUID.randomUUID().toString());
            case "configuration" -> ((ObjectNode) state.path("configuration")).put("inputLimitBytes", 1000);
            case "unknown-field" -> state.put("unexpected", true);
            case "invalid-state" -> state.put("state", "CLEANED");
            default -> { }
        }
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.PREPARED));
        Files.writeString(attempt.resolve("runtime-state.json"), mutation.equals("malformed-json") ? "{" : state.toString());
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());
        assertFalse(report.scanComplete());
        assertEquals(LinuxAttemptDiscovery.Reason.JOURNAL_UNREADABLE_OR_INVALID, report.findings().get(0).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "task", "journal"})
    void missingLocksNeverGetCreated(String missing) throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.PREPARED));
        Path lock = switch (missing) {
            case "root" -> storage.resolve(".task-lifecycle.lock");
            case "task" -> task.resolve("control/linux-supervisor.lock");
            default -> attempt.resolve("runtime-state.lock");
        };
        Files.delete(lock);
        Map<String, String> before = inventory();
        assertFalse(runner.inspectRetainedAttempts(storage, generousBudget()).scanComplete());
        assertFalse(Files.exists(lock, LinkOption.NOFOLLOW_LINKS));
        assertEquals(before, inventory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"root", "task", "journal"})
    void externallyLockedFilesProduceIncompleteReports(String busy) throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.PREPARED));
        Path lock = switch (busy) {
            case "root" -> storage.resolve(".task-lifecycle.lock");
            case "task" -> task.resolve("control/linux-supervisor.lock");
            default -> attempt.resolve("runtime-state.lock");
        };
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertFalse(runner.inspectRetainedAttempts(storage, generousBudget()).scanComplete());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "control", "attempts", "attempt", "runtime-state.json", "stage-journal"})
    void linksCannotHideOrRedirectRetainedEvidence(String linked) throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.PREPARED));
        Path path = switch (linked) {
            case "task" -> task;
            case "control" -> task.resolve("control");
            case "attempts" -> task.resolve("control/attempts");
            case "attempt" -> attempt;
            default -> attempt.resolve(linked);
        };
        Path outside = temporaryDirectory.resolve("outside");
        if (Files.exists(path)) {
            Files.move(path, outside);
        } else {
            Files.createDirectory(outside);
        }
        Files.createSymbolicLink(path, outside);
        assertFalse(runner.inspectRetainedAttempts(storage, generousBudget()).scanComplete());
        assertTrue(Files.isSymbolicLink(path));
    }

    @Test
    void absentRootAndNestedLeaseAreIncompleteWithoutCreatingAnything() throws Exception {
        Path absent = temporaryDirectory.resolve("absent");
        assertFalse(runner.inspectRetainedAttempts(absent, generousBudget()).scanComplete());
        assertFalse(Files.exists(absent));
        try (var held = TaskLifecycleGuard.acquire(storage, false)) {
            assertFalse(runner.inspectRetainedAttempts(storage, generousBudget()).scanComplete());
        }
    }

    @Test
    void stageEvidenceIsOnlyReportedAndNeverConsumedAsRecovered() throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        Path stage = Files.createDirectory(attempt.resolve("stage-journal"));
        Files.writeString(stage.resolve("unparsed-python-evidence"), "not a Java journal");
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());
        assertTrue(report.scanComplete());
        assertTrue(report.findings().get(0).stageEvidenceRequiresFreeze());
        assertEquals(LinuxAttemptDiscovery.Reason.RECOVERY_REQUIRED, report.findings().get(0).reason());
    }

    @Test
    void completedWriterLayoutRemainsDiscoverableWithoutChangingAnyEvidence() throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        privateFile(attempt.resolve("task-spec.json"), "{\"protocolVersion\":2}");
        privateFile(attempt.resolve("runner-events.jsonl"), "{\"type\":\"RUN_FINISHED\"}\n");
        privateFile(attempt.resolve("runner-summary.json"), "{\"totalCases\":1,\"completedCases\":1,\"ac\":1}");
        privateFile(attempt.resolve("supervisor-config.json"), "{\"retained\":true}");
        Path stage = Files.createDirectory(attempt.resolve("stage-journal"));
        privateFile(stage.resolve("stage-evidence.json"), "{\"journalVersion\":1}");
        Map<String, String> before = inventory();

        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());

        assertTrue(report.scanComplete());
        assertEquals(1, report.findings().size());
        assertEquals(LinuxAttemptDiscovery.Reason.RECOVERY_REQUIRED, report.findings().get(0).reason());
        assertEquals("SUPERVISOR_STOPPED", report.findings().get(0).phase());
        assertTrue(report.findings().get(0).stageEvidenceRequiresFreeze());
        assertEquals(before, inventory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"symbolic-link", "hard-link", "group-writable", "other-writable"})
    void summaryEvidenceMustRemainSingleLinkAndRejectUntrustedWriters(String invalid) throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        Path summary = attempt.resolve("runner-summary.json");
        if (invalid.endsWith("link")) {
            Path target = temporaryDirectory.resolve("summary-target.json");
            privateFile(target, "{}");
            if (invalid.equals("symbolic-link")) {
                Files.createSymbolicLink(summary, target);
            } else {
                Files.createLink(summary, target);
            }
        } else {
            privateFile(summary, "{}");
            Files.setPosixFilePermissions(summary, PosixFilePermissions.fromString(
                    invalid.equals("group-writable") ? "rw-rw----" : "rw-----w-"));
        }
        Map<String, String> before = inventory();

        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());

        assertFalse(report.scanComplete());
        assertEquals(1, report.findings().size());
        assertEquals(LinuxAttemptDiscovery.Reason.JOURNAL_UNREADABLE_OR_INVALID, report.findings().get(0).reason());
        assertEquals(0, report.journalReadBytesReserved());
        assertEquals(before, inventory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"runner-summary.json.tmp", "unknown-evidence"})
    void temporarySummaryAndUnknownFilesStillMakeDiscoveryIncomplete(String filename) throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        privateFile(attempt.resolve(filename), "{}");
        Map<String, String> before = inventory();

        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());

        assertFalse(report.scanComplete());
        assertEquals(1, report.findings().size());
        assertEquals(LinuxAttemptDiscovery.Reason.INVALID_ENTRY, report.findings().get(0).reason());
        assertEquals(0, report.journalReadBytesReserved());
        assertEquals(before, inventory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"regular-file", "symbolic-link", "missing-task-lock"})
    void existingControlCannotBeTreatedAsAnUnstartedTask(String invalid) throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        Path control = task.resolve("control");
        if (invalid.equals("missing-task-lock")) {
            Files.delete(control.resolve("linux-supervisor.lock"));
        } else {
            Path retained = temporaryDirectory.resolve("retained-control");
            Files.move(control, retained);
            if (invalid.equals("symbolic-link")) {
                Files.createSymbolicLink(control, retained);
            } else {
                privateFile(control, "not a directory");
            }
        }
        Map<String, String> before = inventory();

        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());

        assertFalse(report.scanComplete());
        assertEquals(1, report.findings().size());
        assertEquals(LinuxAttemptDiscovery.Reason.TASK_UNREADABLE_OR_LOCKED, report.findings().get(0).reason());
        assertEquals(0, report.journalReadBytesReserved());
        assertEquals(before, inventory());
    }

    @Test
    void unstartedStoredTaskDoesNotMakeExistingRetainedAttemptIncomplete() throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        FileTaskStore store = new FileTaskStore(mapper, storage);
        Path unstarted = store.taskDirectory("not-started");
        store.create(JudgeTask.builder().judgeId("not-started").workDir(unstarted.toString()).build());
        assertFalse(Files.exists(unstarted.resolve("control"), LinkOption.NOFOLLOW_LINKS));
        Map<String, String> before = inventory();

        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());

        assertTrue(report.scanComplete());
        assertEquals(1, report.findings().size());
        assertEquals(LinuxAttemptDiscovery.Reason.RECOVERY_REQUIRED, report.findings().get(0).reason());
        assertEquals("SUPERVISOR_STOPPED", report.findings().get(0).phase());
        assertEquals(before, inventory());
    }

    @Test
    void storageContainingOnlyUnstartedStoredTaskHasCompleteEnumerationWithoutCreatingControl() throws Exception {
        storage = temporaryDirectory.resolve("unstarted-only-storage");
        FileTaskStore store = new FileTaskStore(mapper, storage);
        Path unstarted = store.taskDirectory("not-started");
        store.create(JudgeTask.builder().judgeId("not-started").workDir(unstarted.toString()).build());
        assertNull(store.find("not-started").orElseThrow().getSandboxRunHandle());
        assertFalse(Files.exists(unstarted.resolve("control"), LinkOption.NOFOLLOW_LINKS));
        Map<String, String> before = inventory();

        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());

        assertTrue(report.scanComplete());
        assertEquals(LinuxAttemptDiscovery.Reason.SCAN_FINISHED, report.stopReason());
        assertTrue(report.findings().isEmpty());
        assertEquals(0, report.journalReadBytesReserved());
        assertFalse(Files.exists(unstarted.resolve("control"), LinkOption.NOFOLLOW_LINKS));
        assertEquals(before, inventory());
    }

    @Test
    void markerWithoutRuntimeJournalRemainsAnIncompleteFinding() throws Exception {
        privateFile(attempt.resolve("supervisor-config.json"), "{broken}");
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());
        assertFalse(report.scanComplete());
        assertEquals(LinuxAttemptDiscovery.Reason.JOURNAL_MISSING, report.findings().get(0).reason());
    }

    @Test
    void taskLockWithEmptyAttemptsIsNeverACompleteEmptyReport() throws Exception {
        Files.delete(attempt);
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, generousBudget());
        assertFalse(report.scanComplete());
        assertEquals(LinuxAttemptDiscovery.Reason.JOURNAL_MISSING, report.findings().get(0).reason());
    }

    @Test
    void directoryBudgetCountsUnmatchedFilesAndStopsWithoutCollectingEverything() throws Exception {
        Path empty = Files.createDirectory(temporaryDirectory.resolve("empty"));
        try (var lease = TaskLifecycleGuard.acquire(empty, false)) { }
        for (int index = 0; index < 20; index++) {
            Files.writeString(empty.resolve("unmatched-" + index), "");
        }
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(empty, new LinuxAttemptDiscovery.Budget(3, 10, 0, 128));
        assertFalse(report.scanComplete());
        assertEquals(3, report.directoryEntriesVisited());
        assertEquals(LinuxAttemptDiscovery.Reason.DIRECTORY_BUDGET_EXHAUSTED, report.stopReason());
    }

    @Test
    void journalBudgetReservesBothBoundedReadsBeforeOpening() throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.PREPARED));
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage,
                new LinuxAttemptDiscovery.Budget(100, 10, LinuxAttemptDiscovery.JOURNAL_READ_RESERVATION_BYTES - 1, 256));
        assertFalse(report.scanComplete());
        assertEquals(0, report.journalReadBytesReserved());
        assertEquals(LinuxAttemptDiscovery.Reason.JOURNAL_BUDGET_EXHAUSTED, report.stopReason());
    }

    @Test
    void reportsHaveBoundedCountPathsAndSerializedSize() throws Exception {
        for (int index = 0; index < 4; index++) {
            Files.writeString(storage.resolve("unknown-" + index + "x".repeat(180)), "");
        }
        LinuxAttemptDiscovery.Budget budget = new LinuxAttemptDiscovery.Budget(100, 1, 0, 64);
        LinuxAttemptDiscovery.Report report = runner.inspectRetainedAttempts(storage, budget);
        assertFalse(report.scanComplete());
        assertTrue(report.findings().size() <= 1);
        assertTrue(report.findings().stream().allMatch(finding -> finding.relativePath().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64));
        assertTrue(mapper.writeValueAsBytes(report).length <= budget.maximumReportJsonBytes());
    }

    @Test
    void discoveryDoesNotWalkWorkspaceArtifactsOrRequireRuntimeSocket() throws Exception {
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.PREPARED));
        Files.createSymbolicLink(task.resolve("workspace"), Path.of("/not-present-workspace"));
        Files.createSymbolicLink(task.resolve("artifacts"), Path.of("/not-present-artifacts"));
        assertTrue(runner.inspectRetainedAttempts(storage, generousBudget()).scanComplete());
    }

    @Test
    void legacyRunnerExplicitlyRejectsDiscovery() {
        LinuxContainerRunner legacy = new LinuxContainerRunner(LinuxContainerRunner.Options.builder().build(),
                (command, timeout) -> { throw new AssertionError("no legacy fallback"); }, mapper);
        assertThrows(IllegalStateException.class, () -> legacy.inspectRetainedAttempts(storage, generousBudget()));
    }

    private LinuxAttemptDiscovery.Budget generousBudget() {
        return new LinuxAttemptDiscovery.Budget(1000, 100, 10 * LinuxAttemptDiscovery.JOURNAL_READ_RESERVATION_BYTES, 512);
    }

    private void writeSnapshot(ObjectNode state) throws IOException {
        try (LinuxRuntimeJournal journal = LinuxRuntimeJournal.create(attempt, "discovery", attemptId, mapper, 0, 0)) {
            journal.write(state);
        }
    }

    private ObjectNode snapshot(LinuxRuntimeState.SnapshotPhase phase) {
        String[][] states = {
                {"PREPARED", "NOT_REQUESTED", "NOT_REQUESTED", "NOT_REQUESTED"},
                {"CREATE_UNKNOWN", "CREATE_REQUESTED", "NOT_REQUESTED", "NOT_REQUESTED"},
                {"PREPARING", "CREATED", "NOT_REQUESTED", "NOT_REQUESTED"},
                {"CREATE_UNKNOWN", "CREATED", "CREATE_REQUESTED", "NOT_REQUESTED"},
                {"PREPARING", "CREATED", "CREATED", "NOT_REQUESTED"},
                {"PREPARING", "CREATED", "START_REQUESTED", "NOT_REQUESTED"},
                {"PREPARING", "CREATED", "STARTED", "NOT_REQUESTED"},
                {"CREATE_UNKNOWN", "CREATED", "STARTED", "CREATE_REQUESTED"},
                {"PREPARING", "CREATED", "STARTED", "CREATED"},
                {"PREPARING", "CREATED", "STARTED", "START_REQUESTED"},
                {"STARTED", "CREATED", "STARTED", "STARTED"},
                {"CLEANUP_PENDING", "CREATED", "STARTED", "STOP_REQUESTED"},
                {"CLEANUP_PENDING", "CREATED", "STARTED", "STOPPED"},
                {"CLEANUP_PENDING", "CREATED", "STARTED", "REMOVAL_REQUESTED"},
                {"CLEANUP_PENDING", "CREATED", "STARTED", "REMOVED"},
                {"CLEANUP_PENDING", "CREATED", "STOP_REQUESTED", "REMOVED"},
                {"CLEANUP_PENDING", "CREATED", "STOPPED", "REMOVED"},
                {"CLEANUP_PENDING", "CREATED", "REMOVAL_REQUESTED", "REMOVED"},
                {"CLEANUP_PENDING", "CREATED", "REMOVED", "REMOVED"}};
        String[] current = states[phase.ordinal()];
        ObjectNode state = mapper.createObjectNode().put("version", 1).put("judgeId", "discovery")
                .put("attemptId", attemptId).put("provider", "linux-container").put("state", current[0]);
        state.set("configuration", launcher.configuration(task));
        int index = 1;
        for (String role : java.util.List.of("volume", "holder", "supervisor")) {
            String resourceState = current[index++];
            String creation = resourceState.equals("NOT_REQUESTED") ? "NOT_REQUESTED"
                    : resourceState.equals("CREATE_REQUESTED") ? "UNKNOWN" : "CONFIRMED";
            String name = LinuxRuntimeState.resourceName(role, attemptId);
            ObjectNode resource = state.putObject(role).put("name", name).put("creationState", creation).put("state", resourceState);
            if (creation.equals("CONFIRMED")) {
                resource.put("runtimeId", role.equals("volume") ? name : role.equals("holder") ? "c".repeat(64) : "d".repeat(64));
                if (role.equals("volume")) {
                    resource.put("mountpoint", "/var/lib/wjc/volumes/test/_data");
                }
            }
        }
        return state;
    }

    private static void privateFile(Path path, String content) throws IOException {
        Files.writeString(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private Map<String, String> inventory() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var entries = Files.walk(storage)) {
            for (Path path : entries.toList()) {
                String content = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        ? java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)) : "DIRECTORY";
                result.put(storage.relativize(path).toString(), Files.getAttribute(path, "unix:ino")
                        + ":" + Files.getLastModifiedTime(path).toMillis() + ":" + content);
            }
        }
        return result;
    }
}
