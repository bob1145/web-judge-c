package com.example.demo.service.sandbox;

import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.TaskLifecycleGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-environment acceptance for the trusted Java recovery preparation consumer:
 * strict consumption of Python-written stage journals and byte-identical stage
 * admission closure interoperating with close_existing_stage_admission. Probes
 * are fixed python resources executed with constant argv; per-test inputs travel
 * through a configuration document written next to the probe scripts.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(60)
class LinuxStageRecoveryPreparationTest {
    private static final Path PYTHON = Path.of("/usr/bin/python3");
    private static final Path PYTHON_SOURCES = Path.of("runner", "scripts");
    private static final List<String> PROBE_SCRIPTS = List.of("probe_common.py", "writer_probe.py",
            "close_probe.py", "verify_probe.py", "canonical_probe.py", "holder_probe.py");

    @TempDir
    Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private Path storage;
    private Path task;
    private Path attempt;
    private Path stageJournal;
    private String attemptId;
    private String volumeName;
    private LinuxSupervisorLauncher launcher;
    private LinuxStageRecoveryPreparation preparation;
    private Path scriptsDirectory;

    @BeforeEach
    void prepare() throws Exception {
        assumeTrue(((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue() == 0,
                "recovery preparation requires an actual root writer");
        assumeTrue(Files.isExecutable(PYTHON));
        assumeTrue(Files.isRegularFile(PYTHON_SOURCES.resolve("linux_stage_executor.py")),
                "python stage executor sources must be available");
        temporaryDirectory = temporaryDirectory.toRealPath();
        scriptsDirectory = Files.createDirectory(temporaryDirectory.resolve("scripts"));
        for (String probe : PROBE_SCRIPTS) {
            installProbe(probe);
        }
        LinuxSupervisorOptions options = new LinuxSupervisorOptions("/usr/bin/docker", "unix:///run/docker.sock",
                "/usr/bin/docker", "unix:///run/runtime/docker.sock", "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), "/work", 0, 0, 0, 0, 0, 0, 65534, 65534,
                new LinuxSupervisorOptions.ResourceLimits(67108864, 64, 1.0),
                new LinuxSupervisorOptions.ResourceLimits(8388608, 8, 0.5), Duration.ofSeconds(60),
                32, 1.0, 1024, 2048, 4096, 8192, Duration.ofSeconds(1), Duration.ofSeconds(2));
        LinuxContainerRunner.CommandExecutor noRuntime = (command, timeout) -> {
            throw new AssertionError("recovery preparation must never issue a runtime command: " + command);
        };
        launcher = new LinuxSupervisorLauncher(options, noRuntime, mapper);
        preparation = new LinuxStageRecoveryPreparation(launcher, options, mapper);
        storage = temporaryDirectory.resolve("storage");
        FileTaskStore store = new FileTaskStore(mapper, storage);
        task = store.taskDirectory("recovery");
        store.create(JudgeTask.builder().judgeId("recovery").workDir(task.toString()).build());
        Files.createDirectories(task.resolve("control/attempts"));
        privateFile(task.resolve("control/linux-supervisor.lock"), "");
        attemptId = UUID.randomUUID().toString();
        attempt = Files.createDirectory(task.resolve("control/attempts").resolve(attemptId));
        writeSnapshot(snapshot(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
        volumeName = LinuxRuntimeState.resourceName("volume", attemptId);
        stageJournal = Files.createDirectory(attempt.resolve("stage-journal"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        writeProbeConfig();
    }

    @Test
    void preparationConsumesPythonJournalAndPublishesPythonIdenticalClosedAdmission() throws Exception {
        writePythonRecords(pythonShapes());
        Map<String, String> before = inventory();
        LinuxStageRecoveryPreparation.Preparation report = preparation.prepare(storage, "recovery", attemptId);
        assertTrue(report.admissionClosed());
        assertTrue(report.scanComplete());
        assertEquals(3, report.stages().size());
        assertTrue(report.unresolvedUnknown());
        assertTrue(report.unresolvedEntries().isEmpty());
        List<String> creationStates = report.stages().stream()
                .map(LinuxStageRecoveryPreparation.StageFinding::creationState).sorted().toList();
        assertEquals(List.of("CONFIRMED", "NOT_REQUESTED", "UNKNOWN"), creationStates);
        Path closed = stageJournal.resolve("stage-admission.closed");
        assertTrue(Files.isRegularFile(closed, LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(canonicalMarkerBytes(), Files.readAllBytes(closed));
        Map<String, String> added = new LinkedHashMap<>(inventory());
        before.keySet().forEach(added::remove);
        assertEquals(Map.of("judge-recovery/control/attempts/" + attemptId + "/stage-journal/stage-admission.closed",
                inventoryEntry(closed)), added);
        assertEquals(3, retainedRecordCount());
    }

    @Test
    void repeatedPreparationKeepsTheClosedMarkerIdentity() throws Exception {
        writePythonRecords(pythonShapes());
        LinuxStageRecoveryPreparation.Preparation first = preparation.prepare(storage, "recovery", attemptId);
        Path closed = stageJournal.resolve("stage-admission.closed");
        Map<String, Object> identity = fileIdentity(closed);
        LinuxStageRecoveryPreparation.Preparation second = preparation.prepare(storage, "recovery", attemptId);
        assertTrue(first.admissionClosed() && second.admissionClosed());
        assertEquals(identity, fileIdentity(closed));
        assertArrayEquals(canonicalMarkerBytes(), Files.readAllBytes(closed));
    }

    @Test
    void pythonPublishedClosureIsVerifiedAndNeverRewritten() throws Exception {
        writePythonRecords(pythonShapes());
        runCloseProbe();
        Path closed = stageJournal.resolve("stage-admission.closed");
        Map<String, Object> identity = fileIdentity(closed);
        LinuxStageRecoveryPreparation.Preparation report = preparation.prepare(storage, "recovery", attemptId);
        assertTrue(report.admissionClosed());
        assertEquals(identity, fileIdentity(closed));
        assertArrayEquals(canonicalMarkerBytes(), Files.readAllBytes(closed));
        assertTrue(pythonAdmissionState().startsWith("STATE REJECTED"));
    }

    @Test
    void javaPublishedClosureIsAcceptedByPythonAndRejectsStageEntry() throws Exception {
        writePythonRecords(pythonShapes());
        preparation.prepare(storage, "recovery", attemptId);
        String state = pythonAdmissionState();
        assertTrue(state.startsWith("STATE REJECTED"), state);
        assertTrue(state.contains("RECORD {\"admissionVersion\":1,\"attemptId\":\"" + attemptId + "\",\"state\":\"CLOSED\"}"),
                state);
        assertFalse(Files.exists(stageJournal.resolve("stage-admission.pending"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void busyStageDirectoryLockFailsPreparationWithoutAnyMarker() throws Exception {
        writePythonRecords(pythonShapes());
        Process holder = startHolderProbe();
        try {
            awaitReady(holder);
            Map<String, String> before = inventory();
            assertThrows(IOException.class, () -> preparation.prepare(storage, "recovery", attemptId));
            assertEquals(before, inventory());
        } finally {
            holder.getOutputStream().close();
            holder.destroyForcibly();
            holder.waitFor();
        }
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(storage, false)) {
            assertTrue(lease != null);
        }
        assertTrue(preparation.prepare(storage, "recovery", attemptId).admissionClosed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-attempt", "loose-permissions", "second-link", "extra-byte"})
    void corruptedClosedMarkerIsRejectedWithoutRepair(String corruption) throws Exception {
        writePythonRecords(pythonShapes());
        Path closed = stageJournal.resolve("stage-admission.closed");
        String payload = "{\"admissionVersion\":1,\"attemptId\":\"" + attemptId + "\",\"state\":\"CLOSED\"}";
        privateFile(closed, corruption.equals("foreign-attempt")
                ? payload.replace(attemptId, UUID.randomUUID().toString())
                : payload + (corruption.equals("extra-byte") ? " " : ""));
        if (corruption.equals("loose-permissions")) {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rw-r-----"));
        } else if (corruption.equals("second-link")) {
            Files.createLink(temporaryDirectory.resolve("closed-alias"), closed);
        }
        Map<String, String> before = inventory();
        assertThrows(IOException.class, () -> preparation.prepare(storage, "recovery", attemptId));
        assertEquals(before, inventory());
        if (corruption.equals("loose-permissions")) {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"canonical", "empty"})
    void interruptedPendingPublicationIsNeverOverwrittenOrRemoved(String kind) throws Exception {
        writePythonRecords(pythonShapes());
        Path pending = stageJournal.resolve("stage-admission.pending");
        privateFile(pending, kind.equals("canonical")
                ? "{\"admissionVersion\":1,\"attemptId\":\"" + attemptId + "\",\"state\":\"CLOSED\"}" : "");
        Map<String, String> before = inventory();
        assertThrows(IOException.class, () -> preparation.prepare(storage, "recovery", attemptId));
        assertEquals(before, inventory());
        assertFalse(Files.exists(stageJournal.resolve("stage-admission.closed"), LinkOption.NOFOLLOW_LINKS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-file", "non-uuid-json", "runtime-client-regular-file", "broken-record"})
    void unresolvableEntriesAreReportedWhileAdmissionStillCloses(String invalid) throws Exception {
        writePythonRecords(pythonShapes());
        switch (invalid) {
            case "unknown-file" -> privateFile(stageJournal.resolve("surprise"), "x");
            case "non-uuid-json" -> privateFile(stageJournal.resolve("definitely-not-a-uuid.json"), "{}");
            case "runtime-client-regular-file" -> privateFile(stageJournal.resolve("runtime-client"), "not a directory");
            default -> privateFile(stageJournal.resolve(UUID.randomUUID() + ".json"), "{\"journalVersion\":1}");
        }
        LinuxStageRecoveryPreparation.Preparation report = preparation.prepare(storage, "recovery", attemptId);
        assertTrue(report.admissionClosed());
        assertFalse(report.unresolvedEntries().isEmpty());
        assertTrue(Files.isRegularFile(stageJournal.resolve("stage-admission.closed"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(pythonAdmissionState().startsWith("STATE REJECTED"));
    }

    @Test
    void missingStageJournalDirectoryFailsWithoutCreatingIt() throws Exception {
        deleteRecursively(stageJournal);
        assertFalse(Files.exists(stageJournal, LinkOption.NOFOLLOW_LINKS));
        assertThrows(IOException.class, () -> preparation.prepare(storage, "recovery", attemptId));
        assertFalse(Files.exists(stageJournal, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void missingRuntimeJournalFailsWithoutAnyMarker() throws Exception {
        for (String name : List.of("runtime-state.json", "runtime-state.lock")) {
            Files.delete(attempt.resolve(name));
        }
        assertThrows(IOException.class, () -> preparation.prepare(storage, "recovery", attemptId));
        assertFalse(Files.exists(stageJournal.resolve("stage-admission.closed"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void retainedConfigurationMismatchIsRejectedWithoutClosure() throws Exception {
        LinuxSupervisorOptions other = new LinuxSupervisorOptions("/usr/bin/docker", "unix:///run/docker.sock",
                "/usr/bin/docker", "unix:///run/runtime/docker.sock", "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), "/work", 0, 0, 0, 0, 0, 0, 65534, 65534,
                new LinuxSupervisorOptions.ResourceLimits(67108864, 64, 1.0),
                new LinuxSupervisorOptions.ResourceLimits(8388608, 8, 0.5), Duration.ofSeconds(60),
                32, 1.0, 2048, 2048, 4096, 8192, Duration.ofSeconds(1), Duration.ofSeconds(2));
        LinuxContainerRunner.CommandExecutor noRuntime = (command, timeout) -> {
            throw new AssertionError("no runtime command expected");
        };
        LinuxStageRecoveryPreparation mismatched = new LinuxStageRecoveryPreparation(
                new LinuxSupervisorLauncher(other, noRuntime, mapper), other, mapper);
        assertThrows(IOException.class, () -> mismatched.prepare(storage, "recovery", attemptId));
        assertFalse(Files.exists(stageJournal.resolve("stage-admission.closed"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void externallyHeldTaskLockFailsPreparationImmediately() throws Exception {
        try (FileChannel channel = FileChannel.open(task.resolve("control/linux-supervisor.lock"),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertThrows(IOException.class, () -> preparation.prepare(storage, "recovery", attemptId));
        }
        assertFalse(Files.exists(stageJournal.resolve("stage-admission.closed"), LinkOption.NOFOLLOW_LINKS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt-link", "stage-journal-link"})
    void linkedEvidenceDirectoriesAreRejected(String mutation) throws Exception {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-evidence"));
        switch (mutation) {
            case "attempt-link" -> {
                Files.move(attempt, outside.resolve("attempt"));
                Files.createSymbolicLink(attempt, outside.resolve("attempt"));
            }
            default -> {
                Files.move(stageJournal, outside.resolve("journal"));
                Files.createSymbolicLink(stageJournal, outside.resolve("journal"));
            }
        }
        assertThrows(IOException.class, () -> preparation.prepare(storage, "recovery", attemptId));
        assertFalse(Files.exists(stageJournal.resolve("stage-admission.closed"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void replacedJournalDirectoryIsClosedAsTheCurrentOneAndMovedEvidenceStaysUntouched() throws Exception {
        writePythonRecords(pythonShapes());
        Path moved = temporaryDirectory.resolve("moved-journal");
        Files.move(stageJournal, moved);
        Files.createDirectory(stageJournal,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        LinuxStageRecoveryPreparation.Preparation report = preparation.prepare(storage, "recovery", attemptId);
        assertTrue(report.admissionClosed());
        assertTrue(report.stages().isEmpty());
        assertTrue(Files.isRegularFile(stageJournal.resolve("stage-admission.closed"), LinkOption.NOFOLLOW_LINKS));
        for (int index = 0; index < 3; index++) {
            try (var entries = Files.list(moved)) {
                assertEquals(3, entries.count());
            }
            break;
        }
        assertFalse(Files.exists(moved.resolve("stage-admission.closed"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void emptyJournalDirectoryStillClosesAdmission() throws Exception {
        LinuxStageRecoveryPreparation.Preparation report = preparation.prepare(storage, "recovery", attemptId);
        assertTrue(report.admissionClosed());
        assertTrue(report.stages().isEmpty());
        assertFalse(report.unresolvedUnknown());
        assertTrue(pythonAdmissionState().startsWith("STATE REJECTED"));
    }

    private List<Map<String, Object>> pythonShapes() {
        Map<String, Object> cancelledOutcome = new LinkedHashMap<>();
        cancelledOutcome.put("status", "CANCELLED");
        cancelledOutcome.put("exitCode", -1);
        cancelledOutcome.put("oomKilled", false);
        return List.of(
                Map.of("stage", UUID.randomUUID().toString(), "creationState", "NOT_REQUESTED", "state", "INTENT"),
                Map.of("stage", UUID.randomUUID().toString(), "creationState", "CONFIRMED", "state", "CLEANED",
                        "containerId", "c".repeat(64), "outcome", cancelledOutcome),
                Map.of("stage", UUID.randomUUID().toString(), "creationState", "UNKNOWN", "state", "CREATE_UNKNOWN",
                        "containerId", "d".repeat(64)));
    }

    private void writePythonRecords(List<Map<String, Object>> shapes) throws Exception {
        StringBuilder payload = new StringBuilder("[");
        for (Map<String, Object> shape : shapes) {
            if (payload.length() > 1) {
                payload.append(',');
            }
            payload.append(mapper.writeValueAsString(shape));
        }
        payload.append(']');
        Process process = startWriterProbe();
        try (var output = process.getOutputStream()) {
            output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), result);
        assertEquals(0, process.exitValue(), result);
        assertTrue(result.strip().equals("WRITTEN"), result);
    }

    private String pythonAdmissionState() throws Exception {
        Process process = startVerifyProbe();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), output);
        assertEquals(0, process.exitValue(), output);
        return output.strip();
    }

    private byte[] canonicalMarkerBytes() throws Exception {
        Process process = startCanonicalProbe();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return output;
    }

    private void runCloseProbe() throws Exception {
        Process process = startCloseProbe();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.strip().equals("CLOSED"), output);
    }

    private Process startWriterProbe() throws IOException {
        return new ProcessBuilder("/usr/bin/python3", "-B", "writer_probe.py")
                .directory(scriptsDirectory.toFile()).redirectErrorStream(true).start();
    }

    private Process startVerifyProbe() throws IOException {
        return new ProcessBuilder("/usr/bin/python3", "-B", "verify_probe.py")
                .directory(scriptsDirectory.toFile()).redirectErrorStream(true).start();
    }

    private Process startCanonicalProbe() throws IOException {
        return new ProcessBuilder("/usr/bin/python3", "-B", "canonical_probe.py")
                .directory(scriptsDirectory.toFile()).redirectErrorStream(true).start();
    }

    private Process startCloseProbe() throws IOException {
        return new ProcessBuilder("/usr/bin/python3", "-B", "close_probe.py")
                .directory(scriptsDirectory.toFile()).redirectErrorStream(true).start();
    }

    private Process startHolderProbe() throws IOException {
        return new ProcessBuilder("/usr/bin/python3", "-B", "holder_probe.py")
                .directory(scriptsDirectory.toFile()).redirectErrorStream(true).start();
    }

    private void installProbe(String probeName) throws IOException {
        try (InputStream source = getClass().getResourceAsStream("/stage-recovery-probes/" + probeName)) {
            if (source == null) {
                throw new IOException("missing probe resource: " + probeName);
            }
            Files.copy(source, scriptsDirectory.resolve(probeName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeProbeConfig() throws IOException {
        ObjectNode config = mapper.createObjectNode();
        config.put("stageJournal", stageJournal.toString());
        config.put("attempt", attemptId);
        config.put("volume", volumeName);
        config.put("ownerUid", 0);
        config.put("pythonSources", PYTHON_SOURCES.toRealPath().toString());
        Files.write(scriptsDirectory.resolve("probe-config.json"), mapper.writeValueAsBytes(config));
    }

    private void awaitReady(Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        StringBuilder output = new StringBuilder();
        while (System.nanoTime() < deadline) {
            int available = process.getInputStream().available();
            if (available > 0) {
                byte[] chunk = process.getInputStream().readNBytes(available);
                output.append(new String(chunk, StandardCharsets.UTF_8));
            }
            if (output.indexOf("READY") >= 0) {
                return;
            }
            if (!process.isAlive()) {
                fail("python holder exited early");
            }
            Thread.sleep(10);
        }
        fail("python holder never became ready");
    }

    private void writeSnapshot(ObjectNode state) throws IOException {
        try (LinuxRuntimeJournal journal = LinuxRuntimeJournal.create(attempt, "recovery", attemptId, mapper, 0, 0)) {
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
        ObjectNode state = mapper.createObjectNode().put("version", 1).put("judgeId", "recovery")
                .put("attemptId", attemptId).put("provider", "linux-container").put("state", current[0]);
        state.set("configuration", launcher.configuration(task));
        int index = 1;
        for (String role : List.of("volume", "holder", "supervisor")) {
            String resourceState = current[index++];
            String creation = resourceState.equals("NOT_REQUESTED") ? "NOT_REQUESTED"
                    : resourceState.equals("CREATE_REQUESTED") ? "UNKNOWN" : "CONFIRMED";
            String name = LinuxRuntimeState.resourceName(role, attemptId);
            ObjectNode resource = state.putObject(role).put("name", name).put("creationState", creation)
                    .put("state", resourceState);
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

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private long retainedRecordCount() throws IOException {
        try (var entries = Files.list(stageJournal)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".json")).count();
        }
    }

    private Map<String, Object> fileIdentity(Path path) throws IOException {
        Map<String, Object> attributes = new java.util.HashMap<>(Files.readAttributes(path,
                "unix:ino,mode,nlink,size", LinkOption.NOFOLLOW_LINKS));
        attributes.put("bytes", java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        return attributes;
    }

    private Map<String, String> inventory() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var entries = Files.walk(storage)) {
            for (Path path : entries.toList()) {
                result.put(storage.relativize(path).toString(), inventoryEntry(path));
            }
        }
        return result;
    }

    private String inventoryEntry(Path path) throws IOException {
        return Files.getAttribute(path, "unix:ino") + ":" + Files.getLastModifiedTime(path).toMillis() + ":"
                + (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                ? java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path)) : "DIRECTORY");
    }
}
