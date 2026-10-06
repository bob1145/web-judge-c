package com.example.demo.service.sandbox;

import com.example.demo.dto.SandboxComparison;
import com.example.demo.dto.SandboxExecutionManifest;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.FileTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.LINUX)
class LinuxSupervisorLauncherTest {
    private static final String EXECUTOR_IMAGE = "sha256:" + "a".repeat(64);
    private static final String SUPERVISOR_IMAGE = "sha256:" + "b".repeat(64);
    private static final String HOLDER_ID = "c".repeat(64);
    private static final String SUPERVISOR_ID = "d".repeat(64);
    private static final String STAGE_ROOT = "/var/lib/wjc/volumes/test-stage/_data";
    @TempDir
    Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ServerSocketChannel socket;
    private Path workDir;
    private LinuxSupervisorOptions options;
    private RecordingRuntime runtime;
    private LinuxSupervisorLauncher launcher;

    @BeforeEach
    void prepare() throws IOException {
        assumeTrue(((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue() == 0,
                "experimental launcher requires an actual Linux root writer");
        workDir = Files.createDirectory(temporaryDirectory.resolve("task"));
        Files.setPosixFilePermissions(workDir, PosixFilePermissions.fromString("rwx------"));
        Path endpoint = temporaryDirectory.resolve("runtime.sock");
        socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        socket.bind(UnixDomainSocketAddress.of(endpoint));
        options = new LinuxSupervisorOptions("/usr/bin/docker", "unix://" + endpoint, "/usr/bin/docker",
                "unix:///run/wjc/docker.sock", SUPERVISOR_IMAGE, EXECUTOR_IMAGE, "/task-control",
                0, 0, 0, 0, 65532, 65532, 65531, 65531,
                new LinuxSupervisorOptions.ResourceLimits(256L * 1024 * 1024, 32, 0.5),
                new LinuxSupervisorOptions.ResourceLimits(16L * 1024 * 1024, 4, 0.1), Duration.ofSeconds(120),
                16, 0.5, 1024 * 1024, 4 * 1024 * 1024, 32 * 1024 * 1024, 64 * 1024,
                Duration.ofSeconds(5), Duration.ofSeconds(3));
        runtime = new RecordingRuntime();
        launcher = new LinuxSupervisorLauncher(options, runtime, mapper);
    }

    @AfterEach
    void closeSocket() throws IOException {
        if (socket != null) {
            socket.close();
        }
    }

    @Test
    void runnerStartsOnlyJournaledResourcesAndReturnsRealSupervisorHandle() throws Exception {
        LinuxContainerRunner runner = new LinuxContainerRunner(
                LinuxContainerRunner.Options.builder().supervisor(options).build(), runtime, mapper);
        SandboxRunHandle handle = runner.start(spec("trusted-local"));
        assertEquals(SUPERVISOR_ID, handle.runId());
        assertEquals("linux-container", handle.provider());
        assertEquals(SandboxExecutionManifest.VERSION, handle.protocolVersion());
        assertEquals("judge-supervisor", handle.judgeId());
        assertEquals("STARTED", retained().path("state").asText());
        assertEquals(List.of("volume", "holder", "supervisor"), runtime.createIntents);
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("run") || command.contains("--capabilities")));
        List<String> supervisorCreate = runtime.creates.get("supervisor");
        assertEquals(3, supervisorCreate.stream().filter("--mount"::equals).count());
        assertTrue(supervisorCreate.contains("type=bind,source=" + workDir + ",target=/task-control"));
        assertTrue(supervisorCreate.contains("type=volume,source=wjc-stage-volume-" + handle.attemptId()
                + ",target=" + STAGE_ROOT + ",volume-nocopy"));
        assertTrue(supervisorCreate.contains("type=bind,source=" + temporaryDirectory.resolve("runtime.sock")
                + ",target=/run/wjc/docker.sock"));
        assertEquals(1, runtime.creates.get("holder").stream().filter("--mount"::equals).count());
        Path attempt = attemptDirectory();
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(attempt));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(attempt.resolve("task-spec.json")));
        JsonNode config = mapper.readTree(attempt.resolve("supervisor-config.json").toFile());
        assertEquals(16, config.size());
        assertEquals(handle.attemptId(), config.path("attemptId").asText());
        assertEquals(HOLDER_ID, config.path("volume_holder_id").asText());
        assertEquals(STAGE_ROOT, config.path("stage_root").asText());
        assertEquals(EXECUTOR_IMAGE, config.path("executor_image").asText());
        assertEquals(65531, config.path("uid").asInt());
        assertEquals("/task-control/control/attempts/" + handle.attemptId() + "/task-spec.json",
                supervisorCreate.get(supervisorCreate.indexOf(SUPERVISOR_IMAGE) + 1));
    }

    @Test
    void launchExposesManifestScopedEventPathAndAllowsFastExit() throws Exception {
        runtime.fastExit = true;
        LinuxSupervisorLauncher.Launch launch = launcher.start(spec("trusted-local"));
        assertEquals(workDir, launch.workDir());
        assertEquals("control/attempts/" + launch.handle().attemptId() + "/runner-events.jsonl", launch.eventFile());
    }

    @ParameterizedTest
    @CsvSource({"volume,timeout", "volume,reply", "volume,inspect", "holder,timeout", "holder,reply",
            "holder,inspect", "supervisor,timeout", "supervisor,reply", "supervisor,inspect"})
    void unknownCreateIsRetainedWithoutCleanupOrSyntheticHandle(String resource, String failure) throws Exception {
        runtime.failureResource = resource;
        runtime.failure = failure;
        assertThrows(IOException.class, () -> launcher.start(spec("trusted-local")));
        JsonNode state = retained();
        assertEquals("UNKNOWN", state.path(resource).path("creationState").asText());
        assertEquals("CREATE_REQUESTED", state.path(resource).path("state").asText());
        assertFalse(state.path(resource).has("runtimeId"));
        assertEquals("CREATE_UNKNOWN", state.path("state").asText());
        assertTrue(Files.isRegularFile(attemptDirectory().resolve("task-spec.json")));
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("rm") || command.contains("stop")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"holder", "supervisor"})
    void failedStartRetainsConfirmedIdentityAndStartIntent(String resource) throws Exception {
        runtime.failureResource = resource;
        runtime.failure = "start";
        assertThrows(IOException.class, () -> launcher.start(spec("trusted-local")));
        JsonNode state = retained().path(resource);
        assertEquals("CONFIRMED", state.path("creationState").asText());
        assertEquals("START_REQUESTED", state.path("state").asText());
        assertEquals(resource.equals("holder") ? HOLDER_ID : SUPERVISOR_ID, state.path("runtimeId").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Privileged", "ReadonlyRootfs", "NetworkMode", "PidMode", "IpcMode", "CgroupnsMode",
            "Memory", "overflowMemory", "MemorySwap", "PidsLimit", "NanoCpus", "CapDrop", "CapAdd", "SecurityOpt", "LogConfig",
            "Devices", "Binds", "Tmpfs", "Mounts", "NoCopy", "User", "Entrypoint", "Cmd", "Labels", "Image", "Name", "extraMount", "mountSource"})
    void successfulCliCannotConfirmWrongSupervisorConfiguration(String mismatch) throws Exception {
        runtime.mismatch = mismatch;
        assertThrows(IOException.class, () -> launcher.start(spec("trusted-local")));
        assertEquals("UNKNOWN", retained().path("supervisor").path("creationState").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OOMKilled", "Dead", "Error", "created"})
    void unhealthySupervisorIsNotReportedStarted(String mismatch) throws Exception {
        runtime.stateMismatch = mismatch;
        assertThrows(IOException.class, () -> launcher.start(spec("trusted-local")));
        assertEquals("START_REQUESTED", retained().path("supervisor").path("state").asText());
    }

    @ParameterizedTest
    @MethodSource("malformedInspectFields")
    void malformedInspectTypesDoNotCoerceIntoSafeConfiguration(String resource, String field, String shape) throws Exception {
        runtime.inspectionResource = resource;
        runtime.inspectionField = field;
        runtime.inspectionShape = shape;
        assertThrows(IOException.class, () -> launcher.start(spec("trusted-local")));
        JsonNode state = retained().path(resource);
        assertEquals(field.equals("Error") ? "CONFIRMED" : "UNKNOWN", state.path("creationState").asText());
        assertEquals(field.equals("Error") ? "START_REQUESTED" : "CREATE_REQUESTED", state.path("state").asText());
    }

    private static Stream<Arguments> malformedInspectFields() {
        Map<String, List<String>> malformed = Map.of(
                "UTSMode", List.of("missing", "null", "object", "array", "true", "false", "number", "unknown-text"),
                "UsernsMode", List.of("missing", "null", "object", "array", "true", "false", "number", "unknown-text"),
                "OomKillDisable", List.of("missing", "object", "array", "true", "number", "empty-text", "unknown-text"),
                "ReadOnly", List.of("null", "object", "array", "true", "number", "empty-text", "unknown-text"),
                "Error", List.of("missing", "null", "object", "array", "true", "false", "number", "unknown-text"));
        return Stream.of("holder", "supervisor").flatMap(resource -> malformed.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(shape -> Arguments.of(resource, entry.getKey(), shape))));
    }

    @ParameterizedTest
    @CsvSource({"null,missing", "null,false", "false,missing", "false,false"})
    void actualDockerNullOomAndOmittedReadOnlyRemainAccepted(String oomShape, String readOnlyShape) throws Exception {
        runtime.oomShape = oomShape;
        runtime.readOnlyShape = readOnlyShape;
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        assertEquals(SUPERVISOR_ID, handle.runId());
        launcher.stop(handle, workDir);
        assertEquals("STOPPED", retained().path("supervisor").path("state").asText());
    }

    @Test
    void nonTrustedProfileIsRejectedBeforeAnyCommandOrControlWrite() throws Exception {
        assertThrows(IOException.class, () -> launcher.start(spec("production")));
        assertTrue(runtime.commands.isEmpty());
        assertFalse(Files.exists(workDir.resolve("control")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "control", "attempts", "socket"})
    void unsafeLocalPathsAreRejectedBeforeCommands(String target) throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        if (target.equals("socket")) {
            socket.close();
            Files.delete(temporaryDirectory.resolve("runtime.sock"));
            Files.writeString(temporaryDirectory.resolve("runtime.sock"), "not a socket");
        } else {
            Path unsafe = target.equals("task") ? workDir : Files.createDirectories(workDir.resolve(
                    target.equals("control") ? "control" : "control/attempts"));
            Files.setPosixFilePermissions(unsafe, PosixFilePermissions.fromString("rwxrwxrwx"));
        }
        assertThrows(IOException.class, () -> launcher.start(spec));
        assertTrue(runtime.commands.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0x00a0, 0x007f})
    void unsafeWorkDirectoryCharactersAreRejectedBeforeRuntimeOrControlCreation(int codePoint) throws Exception {
        workDir = Files.createDirectory(temporaryDirectory.resolve("task-" + String.valueOf((char) codePoint)));
        Files.setPosixFilePermissions(workDir, PosixFilePermissions.fromString("rwx------"));
        SandboxTaskSpec task = spec("trusted-local");
        assertThrows(IOException.class, () -> launcher.start(task));
        assertTrue(runtime.commands.isEmpty());
        assertFalse(Files.exists(workDir.resolve("control")));
        assertFalse(Files.exists(workDir.getParent().resolve(".task-lifecycle.lock")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x00a0, 0x007f})
    void unsafeReturnedMountpointRetainsUnknownVolumeWithoutCreatingSuccessors(int codePoint) throws Exception {
        runtime.reportedStageRoot = STAGE_ROOT + String.valueOf((char) codePoint);
        assertThrows(IOException.class, () -> launcher.start(spec("trusted-local")));
        JsonNode state = retained();
        assertEquals("CREATE_UNKNOWN", state.path("state").textValue());
        assertEquals("UNKNOWN", state.path("volume").path("creationState").textValue());
        assertFalse(state.path("volume").has("runtimeId"));
        assertFalse(state.path("volume").has("mountpoint"));
        assertEquals("NOT_REQUESTED", state.path("holder").path("creationState").textValue());
        assertEquals("NOT_REQUESTED", state.path("supervisor").path("creationState").textValue());
        assertEquals(4, runtime.commands.size());
        assertTrue(runtime.commands.stream().noneMatch(command -> command.get(3).equals("container")));
        assertEquals(List.of("volume"), runtime.createIntents);
        assertEquals(LinuxRuntimeState.SnapshotPhase.VOLUME_CREATE_UNKNOWN,
                LinuxRuntimeState.classifyUnresolvedSnapshot(state));
    }

    @Test
    void everyDurablyWrittenLauncherSnapshotMatchesClassifierContract() throws Exception {
        SnapshotRecordingMapper recordingMapper = new SnapshotRecordingMapper(workDir);
        LinuxSupervisorLauncher recordingLauncher = new LinuxSupervisorLauncher(options, runtime, recordingMapper);
        SandboxRunHandle handle = recordingLauncher.start(spec("trusted-local")).handle();
        recordingLauncher.stop(handle, workDir);
        recordingMapper.retain(retained());
        List<LinuxRuntimeState.SnapshotPhase> phases = new ArrayList<>();
        for (JsonNode snapshot : recordingMapper.snapshots) {
            phases.add(LinuxRuntimeState.classifyUnresolvedSnapshot(snapshot));
        }
        assertEquals(List.of(LinuxRuntimeState.SnapshotPhase.values()).subList(0,
                LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED.ordinal() + 1), phases);
    }

    @ParameterizedTest
    @ValueSource(strings = {"imageId", "imageOs", "imageEntrypoint", "imageVolumes"})
    void invalidImagesAreRejectedBeforeAnyCreate(String failure) throws Exception {
        runtime.failure = failure;
        assertThrows(IOException.class, () -> launcher.start(spec("trusted-local")));
        assertTrue(runtime.createIntents.isEmpty());
        assertTrue(Files.isRegularFile(workDir.resolve("control/linux-supervisor.lock")));
        assertFalse(Files.exists(workDir.resolve("control/attempts")));
    }

    @Test
    void durableConfigurationComparisonPreservesSmallAndLargeIntegerIdentity() throws Exception {
        options = new LinuxSupervisorOptions(options.hostRuntimeCommand(), options.hostRuntimeEndpoint(),
                options.supervisorRuntimeCommand(), options.supervisorRuntimeEndpoint(), options.supervisorImageId(),
                options.executorImageId(), options.workMount(), options.configWriterUid(), options.configWriterGid(),
                options.supervisorUid(), options.supervisorGid(), options.holderUid(), options.holderGid(),
                options.workloadUid(), options.workloadGid(),
                new LinuxSupervisorOptions.ResourceLimits(5_000_000_000L, 32, 0.5), options.holderLimits(),
                options.holderLifetime(), options.stagePidsLimit(), options.stageCpus(), options.inputLimitBytes(),
                options.artifactLimitBytes(), options.volumeLimitBytes(), options.controlOutputLimitBytes(),
                options.commandTimeout(), options.stageCommandTimeout());
        launcher = new LinuxSupervisorLauncher(options, runtime, mapper);
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        JsonNode configuration = retained().path("configuration");
        assertTrue(configuration.path("supervisorLimits").path("memoryBytes").isLong());
        assertTrue(configuration.path("holderLimits").path("memoryBytes").isInt());
        assertTrue(configuration.path("commandTimeoutMillis").isInt());
        assertTrue(configuration.path("stageCpus").isFloatingPointNumber());
        launcher.stop(handle, workDir);
        assertEquals("STOPPED", retained().path("supervisor").path("state").asText());
    }

    @Test
    void durableComparisonDoesNotCoerceIntegralConfigurationToFloatingPoint() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        try (LinuxRuntimeJournal journal = LinuxRuntimeJournal.openExisting(attemptDirectory(), handle.judgeId(),
                handle.attemptId(), mapper, 0, 0)) {
            ObjectNode state = journal.read();
            ((ObjectNode) state.path("configuration")).put("inputLimitBytes", (double) options.inputLimitBytes());
            journal.write(state);
        }
        runtime.commands.clear();
        assertThrows(IOException.class, () -> launcher.stop(handle, workDir));
        assertTrue(runtime.commands.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingUnknownPreventsSameAndNewLauncherRetriesBeforeCommands(boolean newLauncher) throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        runtime.failureResource = "volume";
        runtime.failure = "timeout";
        assertThrows(IOException.class, () -> launcher.start(spec));
        byte[] retained = Files.readAllBytes(attemptDirectory().resolve("runtime-state.json"));
        runtime.commands.clear();
        LinuxSupervisorLauncher retry = newLauncher ? new LinuxSupervisorLauncher(options, runtime, mapper) : launcher;
        assertThrows(IOException.class, () -> retry.start(spec));
        assertTrue(runtime.commands.isEmpty());
        assertArrayEquals(retained, Files.readAllBytes(attemptDirectory().resolve("runtime-state.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime-state.json", "runtime-state.lock", "runtime-state.json.tmp", "stage-journal", "supervisor-config.json"})
    void anyManagedAttemptMarkerBlocksRetryWithoutReadingItsClaimedState(String marker) throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        Path previous = Files.createDirectories(workDir.resolve("control/attempts").resolve(UUID.randomUUID().toString()));
        if (marker.equals("stage-journal")) {
            Files.createDirectory(previous.resolve(marker));
        } else {
            Files.writeString(previous.resolve(marker), "{\"state\":\"CLEANED\"}");
        }
        assertThrows(IOException.class, () -> launcher.start(spec));
        assertTrue(runtime.commands.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-directory", "task-spec-only", "task-spec-and-events", "runner-summary-only"})
    void earlyCrashResidueFreezesTheCreationEntryBeforeAnyRuntimeCall(String residue) throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        Path previous = Files.createDirectories(workDir.resolve("control/attempts").resolve(UUID.randomUUID().toString()));
        String retained = "early residue";
        switch (residue) {
            case "task-spec-only" -> managedFile(previous.resolve("task-spec.json"), retained);
            case "task-spec-and-events" -> {
                managedFile(previous.resolve("task-spec.json"), retained);
                managedFile(previous.resolve("runner-events.jsonl"), retained);
            }
            case "runner-summary-only" -> managedFile(previous.resolve("runner-summary.json"), retained);
            default -> {
            }
        }
        assertThrows(IOException.class, () -> launcher.start(spec));
        assertTrue(runtime.commands.isEmpty());
        assertTrue(runtime.createIntents.isEmpty());
        try (var attempts = Files.list(workDir.resolve("control/attempts"))) {
            assertEquals(1, attempts.count());
        }
        switch (residue) {
            case "task-spec-only" -> assertEquals(retained, Files.readString(previous.resolve("task-spec.json")));
            case "task-spec-and-events" -> {
                assertEquals(retained, Files.readString(previous.resolve("task-spec.json")));
                assertEquals(retained, Files.readString(previous.resolve("runner-events.jsonl")));
            }
            case "runner-summary-only" -> assertEquals(retained, Files.readString(previous.resolve("runner-summary.json")));
            default -> {
                try (var entries = Files.list(previous)) {
                    assertEquals(0, entries.count());
                }
            }
        }
    }

    @Test
    void legacyManifestOnlyAttemptAlsoRequiresRecoveryBeforeANewManagedAttempt() throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        String previousId = UUID.randomUUID().toString();
        Path previous = Files.createDirectories(workDir.resolve("control/attempts").resolve(previousId));
        managedFile(previous.resolve("task-spec.json"), "legacy fixture");
        assertThrows(IOException.class, () -> launcher.start(spec));
        assertTrue(runtime.commands.isEmpty());
        assertTrue(runtime.createIntents.isEmpty());
        try (var attempts = Files.list(workDir.resolve("control/attempts"))) {
            assertEquals(1, attempts.count());
        }
        assertEquals("legacy fixture", Files.readString(previous.resolve("task-spec.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-name", "ordinary-file", "linked-attempt"})
    void abnormalAttemptChildrenCannotBeSkipped(String kind) throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        Path attempts = Files.createDirectories(workDir.resolve("control/attempts"));
        Path previous = attempts.resolve(kind.equals("invalid-name") ? "not-an-attempt" : UUID.randomUUID().toString());
        if (kind.equals("ordinary-file")) {
            Files.writeString(previous, "not a directory");
        } else if (kind.equals("linked-attempt")) {
            Files.createSymbolicLink(previous, Files.createDirectory(temporaryDirectory.resolve("linked-target")));
        } else {
            Files.createDirectory(previous);
        }
        assertThrows(IOException.class, () -> launcher.start(spec));
        assertTrue(runtime.commands.isEmpty());
    }

    @Test
    void independentlyHeldTaskLockRejectsStartBeforeAnyCommand() throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        Path control = Files.createDirectory(workDir.resolve("control"));
        Path lock = Files.createFile(control.resolve("linux-supervisor.lock"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE); var held = channel.lock()) {
            assertThrows(IOException.class, () -> launcher.start(spec));
            assertTrue(runtime.commands.isEmpty());
        }
    }

    @Test
    void independentlyHeldStorageLifecycleLockRejectsStartBeforeTaskSideEffects() throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        Path externalLock = Files.createFile(workDir.getParent().resolve(".task-lifecycle.lock"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (FileChannel channel = FileChannel.open(externalLock, StandardOpenOption.WRITE); var held = channel.lock()) {
            assertAll(
                    () -> assertThrows(IOException.class, () -> launcher.start(spec)),
                    () -> assertTrue(runtime.commands.isEmpty()),
                    () -> assertFalse(Files.exists(workDir.resolve("control"))),
                    () -> assertTrue(runtime.createIntents.isEmpty()));
        }
        assertEquals(0, Files.size(externalLock));
    }

    @ParameterizedTest
    @ValueSource(strings = {"symbolic-link", "contents"})
    void unsafeStorageLifecycleLockRejectsStartBeforeTaskSideEffects(String failure) throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        Path externalLock = workDir.getParent().resolve(".task-lifecycle.lock");
        Path otherLock = temporaryDirectory.resolve("other-storage-lock");
        if (failure.equals("symbolic-link")) {
            Files.createFile(otherLock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.createSymbolicLink(externalLock, otherLock);
        } else {
            Files.createFile(externalLock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(externalLock, "unsafe lifecycle lock contents");
        }
        assertAll(
                () -> assertThrows(IOException.class, () -> launcher.start(spec)),
                () -> assertTrue(runtime.commands.isEmpty()),
                () -> assertFalse(Files.exists(workDir.resolve("control"))),
                () -> assertTrue(runtime.createIntents.isEmpty()));
        if (failure.equals("symbolic-link")) {
            assertEquals(otherLock, Files.readSymbolicLink(externalLock));
            assertEquals(0, Files.size(otherLock));
        } else {
            assertEquals("unsafe lifecycle lock contents", Files.readString(externalLock));
        }
    }

    @Test
    void deletionWinningBeforeStartDoesNotResurrectTaskDirectory() throws Exception {
        FileTaskStore store = new FileTaskStore(mapper, temporaryDirectory);
        Path storedWorkDir = store.taskDirectory("judge-supervisor");
        Files.move(workDir, storedWorkDir);
        workDir = storedWorkDir;
        SandboxTaskSpec spec = spec("trusted-local");
        assertTrue(store.deleteTaskDirectory(spec.judgeId()));
        assertFalse(Files.exists(workDir));
        assertThrows(IOException.class, () -> launcher.start(spec));
        assertFalse(Files.exists(workDir));
        assertTrue(runtime.commands.isEmpty());
        assertTrue(runtime.createIntents.isEmpty());
    }

    @Test
    void independentlyHeldTaskLockRejectsStopBeforeAnyCommand() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        runtime.commands.clear();
        try (FileChannel channel = FileChannel.open(workDir.resolve("control/linux-supervisor.lock"), StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertThrows(IOException.class, () -> launcher.stop(handle, workDir));
            assertTrue(runtime.commands.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mode", "hard-link", "symbolic-link", "directory", "contents"})
    void unsafeTaskLockMetadataIsRejectedBeforeCommands(String failure) throws Exception {
        SandboxTaskSpec spec = spec("trusted-local");
        Path control = Files.createDirectory(workDir.resolve("control"));
        Path lock = control.resolve("linux-supervisor.lock");
        if (failure.equals("directory")) {
            Files.createDirectory(lock);
        } else if (failure.equals("symbolic-link")) {
            Files.createSymbolicLink(lock, Files.createFile(temporaryDirectory.resolve("other-lock")));
        } else {
            Files.createFile(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            switch (failure) {
                case "mode" -> Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-r--r--"));
                case "hard-link" -> Files.createLink(temporaryDirectory.resolve("lock-alias"), lock);
                default -> Files.writeString(lock, "unexpected lock contents");
            }
        }
        assertThrows(IOException.class, () -> launcher.start(spec));
        assertTrue(runtime.commands.isEmpty());
    }

    @Test
    void recoveryReclaimsConfirmedContainersWithoutLiveHandleAndRetainsVolumeEvidence() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        Path stages = Files.createDirectory(attemptDirectory().resolve("stage-journal"));
        Files.setPosixFilePermissions(stages, PosixFilePermissions.fromString("rwx------"));
        runtime.commands.clear();
        LinuxSupervisorLauncher.ContainerReclamation result = new LinuxSupervisorLauncher(options, runtime, mapper)
                .recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId());
        assertEquals(0, result.confirmedStages());
        assertEquals(retained().path("volume").path("name").textValue(), result.retainedVolumeName());
        assertEquals("CLEANUP_PENDING", retained().path("state").textValue());
        assertEquals("REMOVED", retained().path("supervisor").path("state").textValue());
        assertEquals("REMOVED", retained().path("holder").path("state").textValue());
        assertEquals("CREATED", retained().path("volume").path("state").textValue());
        assertEquals(List.of(SUPERVISOR_ID, HOLDER_ID), runtime.commands.stream()
                .filter(command -> command.get(4).equals("rm"))
                .map(command -> command.get(command.size() - 1)).toList());
        assertTrue(Files.exists(stages.resolve("stage-admission.closed")));
        assertTrue(Files.exists(attemptDirectory().resolve("runtime-state.json")));
        assertTrue(runtime.commands.stream().noneMatch(command -> command.get(3).equals("volume")));
        runtime.commands.clear();
        new LinuxSupervisorLauncher(options, runtime, mapper)
                .recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId());
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("rm") || command.contains("stop")));
    }

    @Test
    void recoveryUsesCanonicalTimeoutOptionForEveryRecordedContainer() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        StageFixture stage = recoveryStage(handle, "USER", "execute", "CONFIRMED", "CREATED", false);
        runtime.commands.clear();
        launcher.recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId());
        List<List<String>> stops = runtime.commands.stream().filter(command -> command.get(4).equals("stop"))
                .map(command -> command.subList(3, command.size())).toList();
        assertEquals(List.of(List.of("container", "stop", "-t", "0", SUPERVISOR_ID),
                List.of("container", "stop", "-t", "0", stage.identity()),
                List.of("container", "stop", "-t", "0", HOLDER_ID)), stops);
    }

    @Test
    void existingStopUsesCanonicalTimeoutOption() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        runtime.commands.clear();
        launcher.stop(handle, workDir);
        assertEquals(List.of(List.of("container", "stop", "-t", "0", SUPERVISOR_ID)),
                runtime.commands.stream().filter(command -> command.get(4).equals("stop"))
                        .map(command -> command.subList(3, command.size())).toList());
    }

    @Test
    void recoveryDoesNotClaimStageArtifactCleanupAfterOnlyRemovingItsContainer() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        StageFixture stage = recoveryStage(handle, "USER", "execute", "CONFIRMED", "CREATED", false);
        launcher.recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId());
        assertEquals(stage.original().deepCopy().put("state", "REMOVED"), mapper.readTree(stage.path().toFile()));
        assertEquals("CREATED", retained().path("volume").path("state").textValue());
        assertEquals("CLEANUP_PENDING", retained().path("state").textValue());
        assertTrue(Files.exists(stage.path()));
        assertTrue(runtime.removed.contains(stage.identity()));
    }

    @Test
    void recoveryPreservesNeverRequestedStageWithoutClaimingArtifactCleanup() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        StageFixture stage = recoveryStage(handle, "USER", "execute", "NOT_REQUESTED", "INTENT", false);
        byte[] original = Files.readAllBytes(stage.path());
        launcher.recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId());
        assertArrayEquals(original, Files.readAllBytes(stage.path()));
        assertEquals("CREATED", retained().path("volume").path("state").textValue());
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains(stage.identity())));
    }

    @Test
    void recoveryPreservesRemovalIntentAfterActualRemovalAcknowledgementLossAndRetries() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        Path stages = Files.createDirectory(attemptDirectory().resolve("stage-journal"));
        Files.setPosixFilePermissions(stages, PosixFilePermissions.fromString("rwx------"));
        runtime.failure = "rmAckLost";
        runtime.failureResource = "supervisor";
        assertThrows(IOException.class, () -> launcher.recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId()));
        assertEquals("REMOVAL_REQUESTED", retained().path("supervisor").path("state").textValue());
        assertTrue(runtime.removed.contains(SUPERVISOR_ID));
        runtime.commands.clear();
        new LinuxSupervisorLauncher(options, runtime, mapper)
                .recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId());
        assertEquals("REMOVED", retained().path("supervisor").path("state").textValue());
        assertEquals("REMOVED", retained().path("holder").path("state").textValue());
        assertTrue(runtime.commands.stream().noneMatch(command -> command.get(4).equals("rm")
                && command.get(command.size() - 1).equals(SUPERVISOR_ID)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"launcher", "runner", "stop"})
    void recoveryRejectsVirtualFilesystemAliasesBeforeTouchingHostEvidence(String entry) throws Exception {
        workDir = Files.move(workDir, temporaryDirectory.resolve("judge-judge-supervisor"));
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        Path stages = Files.createDirectory(attemptDirectory().resolve("stage-journal"));
        Files.setPosixFilePermissions(stages, PosixFilePermissions.fromString("rwx------"));
        byte[] original = Files.readAllBytes(attemptDirectory().resolve("runtime-state.json"));
        runtime.commands.clear();
        Path archive = temporaryDirectory.resolve("foreign-filesystem.zip");
        try (var filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path foreignTask = filesystem.getPath(workDir.toString());
            assertEquals(workDir.toString(), foreignTask.toString());
            assertTrue(foreignTask.isAbsolute());
            if (entry.equals("launcher")) {
                assertThrows(IOException.class, () -> launcher.recoverConfirmedContainers(foreignTask,
                        handle.judgeId(), handle.attemptId()));
            } else if (entry.equals("runner")) {
                LinuxContainerRunner runner = new LinuxContainerRunner(
                        LinuxContainerRunner.Options.builder().supervisor(options).build(), runtime, mapper);
                assertThrows(IOException.class, () -> runner.recoverRetainedConfirmedContainers(
                        filesystem.getPath(temporaryDirectory.toString()), handle.judgeId(), handle.attemptId()));
            } else {
                assertThrows(IOException.class, () -> launcher.stop(handle, foreignTask));
            }
        }
        assertTrue(runtime.commands.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(attemptDirectory().resolve("runtime-state.json")));
        assertFalse(Files.exists(stages.resolve("stage-admission.closed")));
    }

    @ParameterizedTest
    @MethodSource("recoverableStages")
    void recoveryConsumesEveryConfirmedStageShapeAndPreservesOriginalOutcome(String role, String phase,
                                                                              String state, boolean outcome) throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        StageFixture stage = recoveryStage(handle, role, phase, "CONFIRMED", state, outcome);
        if (Set.of("REMOVED", "CLEANED").contains(state)) {
            runtime.removed.add(stage.identity());
        }
        runtime.commands.clear();
        LinuxSupervisorLauncher.ContainerReclamation result = new LinuxSupervisorLauncher(options, runtime, mapper)
                .recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId());
        assertEquals(1, result.confirmedStages());
        assertEquals(stage.original().deepCopy().put("state", "CLEANED".equals(state) ? "CLEANED" : "REMOVED"),
                mapper.readTree(stage.path().toFile()));
        assertEquals("CONFIRMED", mapper.readTree(stage.path().toFile()).path("creationState").textValue());
        List<String> removals = runtime.commands.stream().filter(command -> command.get(4).equals("rm"))
                .map(command -> command.get(command.size() - 1)).toList();
        assertEquals(Set.of("REMOVED", "CLEANED").contains(state) ? List.of(SUPERVISOR_ID, HOLDER_ID)
                : List.of(stage.identity(), SUPERVISOR_ID, HOLDER_ID), removals);
        assertEquals("CREATED", retained().path("volume").path("state").textValue());
    }

    private static Stream<Arguments> recoverableStages() {
        return Stream.of("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE").flatMap(role ->
                Stream.of("compile", "execute").flatMap(phase ->
                        Stream.concat(Stream.of("CREATED", "STARTING", "STOPPING")
                                        .map(state -> Arguments.of(role, phase, state, false)),
                                Stream.of("REMOVING", "REMOVED", "CLEANED", "CLEANUP_FAILED")
                                        .flatMap(state -> Stream.of(false, true)
                                                .map(outcome -> Arguments.of(role, phase, state, outcome))))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INTENT", "CLEANED", "CLEANUP_FAILED"})
    void recoveryClosesNeverRequestedStagesWithoutInventingAContainerOrOutcome(String state) throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        StageFixture stage = recoveryStage(handle, "USER", "execute", "NOT_REQUESTED", state, false);
        runtime.commands.clear();
        LinuxSupervisorLauncher.ContainerReclamation result = launcher
                .recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId());
        assertEquals(0, result.confirmedStages());
        assertEquals(stage.original(), mapper.readTree(stage.path().toFile()));
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains(stage.identity())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE_REQUESTED", "CREATE_UNKNOWN", "CLEANUP_PENDING", "REMOVING"})
    void recoveryRejectsUnknownStageCreationWithoutAnyRuntimeObservationOrOperation(String state) throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        StageFixture stage = recoveryStage(handle, "USER", "execute", "UNKNOWN", state, false);
        byte[] original = Files.readAllBytes(stage.path());
        runtime.commands.clear();
        assertThrows(IOException.class, () -> launcher.recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId()));
        assertTrue(runtime.commands.isEmpty());
        assertArrayEquals(original, Files.readAllBytes(stage.path()));
        assertFalse(Files.exists(stage.path().getParent().resolve("stage-admission.closed")));
    }

    @Test
    void recoveryRejectsUnrecordedAttemptContainersBeforeStoppingAnyResource() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        StageFixture stage = recoveryStage(handle, "USER", "execute", "CONFIRMED", "CREATED", false);
        Files.move(stage.path(), stage.path().getParent().getParent().resolve("retained-outside-journal.json"));
        runtime.commands.clear();
        assertThrows(IOException.class, () -> launcher.recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId()));
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("stop") || command.contains("rm")));
        assertEquals("STARTED", retained().path("supervisor").path("state").textValue());
        assertFalse(runtime.removed.contains(stage.identity()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"listFailure", "listShort", "listDuplicate", "listWhitespace", "listErrorOutput", "inspectFailure"})
    void recoveryDoesNotTurnRuntimeFailuresOrIncompleteDiscoveryIntoAbsence(String failure) throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        StageFixture stage = recoveryStage(handle, "USER", "execute", "CONFIRMED", "CREATED", false);
        runtime.failure = failure;
        runtime.commands.clear();
        assertThrows(IOException.class, () -> launcher.recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId()));
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("stop") || command.contains("rm")));
        assertEquals(stage.original(), mapper.readTree(stage.path().toFile()));
        assertEquals("STARTED", retained().path("supervisor").path("state").textValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rmAckLost", "rmNoEffect"})
    void recoveryRetriesStageRemovalFailuresWithoutLosingOutcomeOrGrantingTaskDeletion(String failure) throws Exception {
        workDir = Files.move(workDir, temporaryDirectory.resolve("judge-judge-supervisor"));
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        StageFixture stage = recoveryStage(handle, "USER", "execute", "CONFIRMED", "REMOVING", true);
        runtime.failureResource = "stage";
        runtime.failure = failure;
        assertThrows(IOException.class, () -> launcher.recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId()));
        assertEquals(stage.original().deepCopy().put("state", "REMOVING"), mapper.readTree(stage.path().toFile()));
        assertEquals("STOPPED", retained().path("supervisor").path("state").textValue());
        assertEquals("STARTED", retained().path("holder").path("state").textValue());
        runtime.failure = "";
        new LinuxSupervisorLauncher(options, runtime, mapper)
                .recoverConfirmedContainers(workDir, handle.judgeId(), handle.attemptId());
        assertEquals(stage.original().deepCopy().put("state", "REMOVED"), mapper.readTree(stage.path().toFile()));
        FileTaskStore store = new FileTaskStore(mapper, temporaryDirectory);
        assertThrows(IOException.class, () -> store.deleteTaskDirectory(handle.judgeId()));
        assertTrue(Files.exists(stage.path()));
    }

    private StageFixture recoveryStage(SandboxRunHandle handle, String role, String phase, String creation,
                                       String state, boolean outcome) throws Exception {
        Path stages = attemptDirectory().resolve("stage-journal");
        if (!Files.exists(stages)) {
            Files.createDirectory(stages);
            Files.setPosixFilePermissions(stages, PosixFilePermissions.fromString("rwx------"));
        }
        String stage = UUID.randomUUID().toString();
        String identity = "e".repeat(64);
        String volume = retained().path("volume").path("name").textValue();
        ObjectNode record = mapper.createObjectNode().put("journalVersion", 1).put("creationState", creation)
                .put("stage", stage).put("attempt", handle.attemptId()).put("role", role).put("phase", phase)
                .put("name", LinuxStageRecord.expectedName(stage)).put("volume", volume).put("state", state);
        record.set("labels", mapper.valueToTree(LinuxStageRecord.expectedLabels(handle.attemptId(), stage, role)));
        if (creation.equals("CONFIRMED") || state.equals("REMOVING")) {
            record.put("containerId", identity);
        }
        if (outcome) {
            record.set("outcome", mapper.readTree("{\"status\":\"exited\",\"exitCode\":18446744073709551617,\"oomKilled\":true}"));
        }
        Path path = stages.resolve(stage + ".json");
        managedFile(path, mapper.writeValueAsString(record));
        if (creation.equals("CONFIRMED")) {
            ObjectNode inspection;
            try (var input = getClass().getResourceAsStream("/sandbox/linux-stage-inspect-real.json")) {
                assertNotNull(input);
                inspection = (ObjectNode) mapper.readTree(input);
            }
            inspection.put("Id", identity).put("Name", "/" + LinuxStageRecord.expectedName(stage)).put("Image", EXECUTOR_IMAGE);
            ObjectNode config = (ObjectNode) inspection.get("Config");
            config.put("User", options.workloadUid() + ":" + options.workloadGid());
            config.set("Labels", record.path("labels").deepCopy());
            ObjectNode host = (ObjectNode) inspection.get("HostConfig");
            host.put("PidsLimit", options.stagePidsLimit()).put("NanoCpus", Math.round(options.stageCpus() * 1_000_000_000));
            for (JsonNode declaration : host.path("Mounts")) {
                ((ObjectNode) declaration).put("Source", volume);
                ((ObjectNode) declaration.path("VolumeOptions")).put("Subpath", stage + declaration.path("Target").textValue());
            }
            for (JsonNode mount : inspection.path("Mounts")) {
                ((ObjectNode) mount).put("Name", volume).put("Source", STAGE_ROOT);
            }
            ObjectNode running = (ObjectNode) inspection.get("State");
            running.put("Running", true).put("Pid", 123).put("OOMKilled", outcome);
            runtime.stageInspections.put(identity, inspection);
        }
        return new StageFixture(path, record, identity);
    }

    private record StageFixture(Path path, ObjectNode original, String identity) { }

    @Test
    void stopUsesDurableIdentityAndLeavesEveryResourceAndEvidencePending() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        new LinuxSupervisorLauncher(options, runtime, mapper).stop(handle, workDir);
        assertEquals("CLEANUP_PENDING", retained().path("state").asText());
        assertEquals("STOPPED", retained().path("supervisor").path("state").asText());
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("rm")));
        assertEquals(1, runtime.commands.stream().filter(command -> command.contains("stop")).count());
        assertTrue(Files.exists(attemptDirectory().resolve("supervisor-config.json")));
    }

    @Test
    void cancellationPublishesTrustedMarkerAndAllowsCooperativeExitWhileStageFlockIsHeld() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        Path stageJournal = Files.createDirectory(attemptDirectory().resolve("stage-journal"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        runtime.cancellationTimeoutChecks = true;
        runtime.cooperativeCancellation = true;
        runtime.checkStopLock = true;
        runtime.commands.clear();
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(stageJournal, 0)) {
            launcher.cancelAndStop(handle, workDir);
            lease.verifyIdentity();
        }
        assertTrue(runtime.cancellationObserved);
        assertTrue(runtime.stopLockChecked);
        assertEquals(20, runtime.supervisorExitCode);
        Path marker = attemptDirectory().resolve("cancel.requested");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(marker));
        assertEquals(0, Files.size(marker));
        assertEquals("STOPPED", retained().path("supervisor").path("state").textValue());
        assertEquals("CLEANUP_PENDING", retained().path("state").textValue());
        assertEquals(1, runtime.commands.stream().filter(command -> command.contains("stop")).count());
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("rm")));
        assertTrue(runtime.running.get("holder"));
    }

    @Test
    void runnerCancellationPublishesMarkerButKeepsOriginalPendingBoundaryOnRetry() throws Exception {
        LinuxContainerRunner runner = new LinuxContainerRunner(
                LinuxContainerRunner.Options.builder().supervisor(options).build(), runtime, mapper);
        SandboxRunHandle handle = runner.start(spec("trusted-local"));
        runtime.cancellationTimeoutChecks = true;
        runtime.cooperativeCancellation = true;
        Object identity = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            IllegalStateException pending = assertThrows(IllegalStateException.class, () -> runner.cancel(handle));
            assertNull(pending.getCause());
            assertEquals("Linux supervisor stopped; stage and volume recovery remains pending", pending.getMessage());
            Path marker = attemptDirectory().resolve("cancel.requested");
            assertTrue(Files.isRegularFile(marker));
            Object current = Files.getAttribute(marker, "basic:fileKey");
            if (identity != null) {
                assertEquals(identity, current);
            }
            identity = current;
            assertEquals("CLEANUP_PENDING", retained().path("state").textValue());
            assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("rm")));
        }
    }

    @Test
    void cancellationTimesOutWithinConfiguredBudgetAndRetainsResourceRecoveryBoundary() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        runtime.cancellationTimeoutChecks = true;
        runtime.commands.clear();
        long started = System.nanoTime();
        assertTimeout(Duration.ofSeconds(7), () -> launcher.cancelAndStop(handle, workDir));
        long elapsed = System.nanoTime() - started;
        assertTrue(elapsed >= options.commandTimeout().toNanos());
        assertFalse(runtime.cancellationObserved);
        assertEquals(0, runtime.supervisorExitCode);
        assertTrue(Files.exists(attemptDirectory().resolve("cancel.requested")));
        assertEquals("STOPPED", retained().path("supervisor").path("state").textValue());
        assertEquals("CLEANUP_PENDING", retained().path("state").textValue());
        assertEquals(1, runtime.commands.stream().filter(command -> command.contains("stop")).count());
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("rm")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mode", "contents", "symlink"})
    void cancellationRefusesUnsafeMarkerBeforeStoppingAndKeepsDurableIntent(String shape) throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        Path marker = attemptDirectory().resolve("cancel.requested");
        if (shape.equals("symlink")) {
            Files.createSymbolicLink(marker, temporaryDirectory.resolve("missing-marker"));
        } else {
            Files.createFile(marker, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            if (shape.equals("contents")) {
                Files.writeString(marker, "unsafe");
            } else {
                Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-r--r--"));
            }
        }
        runtime.commands.clear();
        assertThrows(IOException.class, () -> launcher.cancelAndStop(handle, workDir));
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("stop") || command.contains("rm")));
        assertEquals("STOP_REQUESTED", retained().path("supervisor").path("state").textValue());
        assertTrue(runtime.running.get("supervisor"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cli", "stderr", "json", "missing", "runningType", "pidType", "negativePid",
            "stoppedPid", "oom", "dead", "error", "identity"})
    void cancellationRetainsIntentWhenObservationIsUntrusted(String shape) throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        runtime.cancellationTimeoutChecks = true;
        runtime.cancellationObservationFailure = shape;
        runtime.commands.clear();
        assertThrows(IOException.class, () -> launcher.cancelAndStop(handle, workDir));
        assertTrue(Files.exists(attemptDirectory().resolve("cancel.requested")));
        assertEquals("STOP_REQUESTED", retained().path("supervisor").path("state").textValue());
        assertEquals("CLEANUP_PENDING", retained().path("state").textValue());
        assertTrue(runtime.running.get("supervisor"));
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("stop") || command.contains("rm")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shortReply", "stderr", "noEffect"})
    void cancellationRetainsIntentWhenStopAcknowledgementOrEffectIsUntrusted(String failure) throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        runtime.cancellationTimeoutChecks = true;
        runtime.cooperativeCancellation = !failure.equals("noEffect");
        runtime.cancellationStopFailure = failure;
        runtime.commands.clear();
        assertThrows(IOException.class, () -> launcher.cancelAndStop(handle, workDir));
        assertTrue(Files.exists(attemptDirectory().resolve("cancel.requested")));
        assertEquals("STOP_REQUESTED", retained().path("supervisor").path("state").textValue());
        assertEquals("CLEANUP_PENDING", retained().path("state").textValue());
        assertEquals(1, runtime.commands.stream().filter(command -> command.contains("stop")).count());
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("rm")));
        if (failure.equals("noEffect")) {
            assertTrue(runtime.running.get("supervisor"));
        }
    }

    @Test
    void cancellationInterruptionAfterPublicationLeavesIntentWithoutForcedStop() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        runtime.cancellationTimeoutChecks = true;
        runtime.interruptCancellation = true;
        runtime.commands.clear();
        try {
            assertThrows(InterruptedException.class, () -> launcher.cancelAndStop(handle, workDir));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(Files.exists(attemptDirectory().resolve("cancel.requested")));
        assertEquals("STOP_REQUESTED", retained().path("supervisor").path("state").textValue());
        assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("stop") || command.contains("rm")));
    }

    @ParameterizedTest
    @MethodSource("malformedRetainedStates")
    void stopRejectsMalformedRetainedStateBeforeRuntimeOrJournalMutation(String field, String json) throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        ObjectNode state = (ObjectNode) retained();
        String[] path = field.split("\\.");
        ObjectNode parent = state;
        for (int index = 0; index < path.length - 1; index++) {
            parent = (ObjectNode) parent.get(path[index]);
        }
        if (json.equals("MISSING")) {
            parent.remove(path[path.length - 1]);
        } else {
            parent.set(path[path.length - 1], mapper.readTree(json));
        }
        Path journal = attemptDirectory().resolve("runtime-state.json");
        Files.write(journal, mapper.writeValueAsBytes(state));
        byte[] retainedBytes = Files.readAllBytes(journal);
        runtime.commands.clear();
        assertThrows(IOException.class, () -> launcher.stop(handle, workDir));
        assertTrue(runtime.commands.isEmpty());
        assertArrayEquals(retainedBytes, Files.readAllBytes(journal));
    }

    private static Stream<Arguments> malformedRetainedStates() {
        Stream<Arguments> malformedTypes = Stream.of("state", "volume.state", "holder.state", "supervisor.state")
                .flatMap(field -> Stream.of("MISSING", "null", "false", "1", "{}", "[]", "\"BOGUS\"")
                        .map(json -> Arguments.of(field, json)));
        Stream<Arguments> impossibleCombinations = Stream.of(
                Arguments.of("state", "\"PREPARED\""),
                Arguments.of("state", "\"PREPARING\""),
                Arguments.of("state", "\"CREATE_UNKNOWN\""),
                Arguments.of("state", "\"CLEANUP_PENDING\""),
                Arguments.of("state", "\"CLEANED\""),
                Arguments.of("volume.state", "\"STARTED\""),
                Arguments.of("volume.state", "\"NOT_REQUESTED\""),
                Arguments.of("holder.state", "\"CREATED\""),
                Arguments.of("holder.state", "\"START_REQUESTED\""),
                Arguments.of("holder.state", "\"STOPPED\""),
                Arguments.of("supervisor.state", "\"CREATED\""),
                Arguments.of("supervisor.state", "\"START_REQUESTED\""),
                Arguments.of("supervisor.state", "\"STOP_REQUESTED\""),
                Arguments.of("supervisor.state", "\"STOPPED\""),
                Arguments.of("holder.runtimeId", "\"" + SUPERVISOR_ID + "\""));
        return Stream.concat(malformedTypes, impossibleCombinations);
    }

    @ParameterizedTest
    @CsvSource({"PREPARING,CREATED", "PREPARING,START_REQUESTED", "STARTED,STARTED",
            "CLEANUP_PENDING,STOP_REQUESTED", "CLEANUP_PENDING,STOPPED"})
    void stopAcceptsEveryConfirmedSupervisorPhaseAndRechecksRuntimeOnRetry(String global, String supervisor) throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        ObjectNode state = (ObjectNode) retained();
        state.put("state", global);
        ((ObjectNode) state.get("supervisor")).put("state", supervisor);
        Path journal = attemptDirectory().resolve("runtime-state.json");
        Files.write(journal, mapper.writeValueAsBytes(state));
        for (int retry = 0; retry < 2; retry++) {
            runtime.commands.clear();
            launcher.stop(handle, workDir);
            assertEquals(LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED,
                    LinuxRuntimeState.classifyUnresolvedSnapshot(retained()));
            assertEquals(3, runtime.commands.size());
            assertEquals("inspect", runtime.commands.get(0).get(4));
            assertEquals("stop", runtime.commands.get(1).get(4));
            assertEquals("inspect", runtime.commands.get(2).get(4));
            assertTrue(runtime.commands.stream().noneMatch(command -> command.contains("rm")));
        }
    }

    @Test
    void stopRejectsForgedHandleBeforeAnyRuntimeCommand() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        runtime.commands.clear();
        SandboxRunHandle wrong = new SandboxRunHandle(handle.judgeId(), "e".repeat(64), handle.provider(),
                handle.startedAt(), handle.eventCursor(), handle.protocolVersion(), handle.attemptId());
        assertThrows(IOException.class, () -> launcher.stop(wrong, workDir));
        assertTrue(runtime.commands.isEmpty());
    }

    @Test
    void stopHoldsTheDurableJournalLockAcrossRuntimeCommands() throws Exception {
        SandboxRunHandle handle = launcher.start(spec("trusted-local")).handle();
        runtime.checkStopLock = true;
        launcher.stop(handle, workDir);
        assertTrue(runtime.stopLockChecked);
    }

    private SandboxTaskSpec spec(String profile) throws IOException {
        Map<SandboxTaskSpec.SourceRole, String> sources = new LinkedHashMap<>();
        for (SandboxTaskSpec.SourceRole role : List.of(SandboxTaskSpec.SourceRole.GENERATOR,
                SandboxTaskSpec.SourceRole.USER, SandboxTaskSpec.SourceRole.ORACLE)) {
            Path source = workDir.resolve(role.name().toLowerCase() + ".cpp");
            Files.writeString(source, "int main() { return 0; }");
            sources.put(role, source.toString());
        }
        return new SandboxTaskSpec("judge-supervisor", "user", profile, workDir.toString(), sources, 1,
                Duration.ofSeconds(1), Duration.ofSeconds(10), 64L * 1024 * 1024, 1024,
                true, null, new SandboxComparison(SandboxComparison.Mode.LEGACY_TEXT, "0"));
    }

    private Path attemptDirectory() throws IOException {
        try (var attempts = Files.list(workDir.resolve("control/attempts"))) {
            return attempts.filter(path -> Files.exists(path.resolve("runtime-state.json"))).findFirst().orElseThrow();
        }
    }

    private static void managedFile(Path path, String content) throws IOException {
        Files.writeString(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private JsonNode retained() throws IOException {
        return mapper.readTree(attemptDirectory().resolve("runtime-state.json").toFile());
    }

    private static final class SnapshotRecordingMapper extends ObjectMapper {
        private final Path taskDirectory;
        private final List<JsonNode> snapshots;

        private SnapshotRecordingMapper(Path taskDirectory) {
            this.taskDirectory = taskDirectory;
            this.snapshots = new ArrayList<>();
        }

        private SnapshotRecordingMapper(SnapshotRecordingMapper source) {
            super(source);
            this.taskDirectory = source.taskDirectory;
            this.snapshots = source.snapshots;
        }

        @Override
        public ObjectMapper copy() {
            return new SnapshotRecordingMapper(this);
        }

        @Override
        public void writeValue(OutputStream output, Object value) throws IOException {
            if (value instanceof JsonNode snapshot && snapshot.has("configuration")
                    && snapshot.path("attemptId").isTextual()) {
                Path journal = taskDirectory.resolve("control/attempts").resolve(snapshot.path("attemptId").textValue())
                        .resolve("runtime-state.json");
                if (Files.exists(journal)) {
                    retain(readTree(journal.toFile()));
                }
            }
            super.writeValue(output, value);
        }

        private void retain(JsonNode snapshot) {
            snapshots.add(snapshot.deepCopy());
        }
    }

    private final class RecordingRuntime implements LinuxContainerRunner.CommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private final List<String> createIntents = new ArrayList<>();
        private final Map<String, List<String>> creates = new LinkedHashMap<>();
        private final Map<String, Boolean> running = new LinkedHashMap<>();
        private String failureResource = "";
        private String failure = "";
        private String mismatch = "";
        private String stateMismatch = "";
        private String inspectionResource = "";
        private String inspectionField = "";
        private String inspectionShape = "";
        private String oomShape = "null";
        private String reportedStageRoot = STAGE_ROOT;
        private String readOnlyShape = "false";
        private boolean fastExit;
        private boolean checkStopLock;
        private boolean stopLockChecked;
        private boolean cancellationTimeoutChecks;
        private String cancellationObservationFailure = "";
        private String cancellationStopFailure = "";
        private boolean cooperativeCancellation;
        private boolean cancellationObserved;
        private boolean interruptCancellation;
        private int supervisorExitCode;

        private final Set<String> removed = new HashSet<>();
        private final Map<String, ObjectNode> stageInspections = new LinkedHashMap<>();

        @Override
        public LinuxContainerRunner.CommandResult run(List<String> command, Duration timeout) throws IOException {
            Path externalLock = workDir.getParent().resolve(".task-lifecycle.lock");
            try (FileChannel channel = FileChannel.open(externalLock, StandardOpenOption.WRITE); var lease = channel.tryLock()) {
                assertNotNull(lease, "storage lifecycle lock must be released before runtime commands");
            }
            Path taskLock = workDir.resolve("control/linux-supervisor.lock");
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(taskLock));
            try (FileChannel channel = FileChannel.open(taskLock, StandardOpenOption.WRITE)) {
                assertThrows(java.nio.channels.OverlappingFileLockException.class, channel::tryLock);
            }
            commands.add(command);
            assertEquals(List.of(options.hostRuntimeCommand(), "--host", options.hostRuntimeEndpoint()), command.subList(0, 3));
            if (cancellationTimeoutChecks) {
                assertFalse(timeout.isNegative() || timeout.isZero());
                assertTrue(timeout.compareTo(options.commandTimeout()) <= 0);
            } else {
                assertEquals(options.commandTimeout(), timeout);
            }
            String kind = command.get(3);
            String action = command.get(4);
            if (action.equals("ls")) {
                assertEquals("container", kind);
                assertTrue(command.contains("--all") && command.contains("--quiet") && command.contains("--no-trunc"));
                List<String> identities = new ArrayList<>();
                if (failure.equals("listFailure")) {
                    return new LinuxContainerRunner.CommandResult(1, "", "runtime authentication failed");
                }
                if (failure.equals("listShort")) {
                    return success("short-id\n");
                }
                if (failure.equals("listDuplicate")) {
                    return success(HOLDER_ID + "\n" + HOLDER_ID + "\n");
                }
                if (failure.equals("listWhitespace")) {
                    return success(" \n");
                }
                if (failure.equals("listErrorOutput")) {
                    return new LinuxContainerRunner.CommandResult(0, "", "runtime enumeration incomplete");
                }
                List<String> candidates = new ArrayList<>(List.of(HOLDER_ID, SUPERVISOR_ID));
                candidates.addAll(stageInspections.keySet());
                for (String identity : candidates) {
                    String resourceName = identity.equals(HOLDER_ID) ? "holder" : "supervisor";
                    if (removed.contains(identity)) {
                        continue;
                    }
                    ObjectNode inspection = stageInspections.containsKey(identity) ? stageInspections.get(identity)
                            : containerInspection(resourceName);
                    boolean included = true;
                    for (String argument : command) {
                        if (argument.startsWith("id=")) {
                            included &= argument.substring(3).equals(identity);
                        } else if (argument.startsWith("label=")) {
                            String[] filter = argument.substring(6).split("=", 2);
                            JsonNode labels = inspection.path("Config").path("Labels");
                            included &= labels.has(filter[0]) && (filter.length == 1
                                    || filter[1].equals(labels.path(filter[0]).asText()));
                        }
                    }
                    if (included) {
                        identities.add(identity);
                    }
                }
                return success(String.join("\n", identities) + (identities.isEmpty() ? "" : "\n"));
            }
            String resource = kind.equals("volume") ? "volume"
                    : command.contains("create") ? (command.contains(SUPERVISOR_IMAGE) ? "supervisor" : "holder")
                    : command.get(command.size() - 1).equals(SUPERVISOR_ID) ? "supervisor" : "holder";
            String finalArgument = command.get(command.size() - 1);
            if (stageInspections.containsKey(finalArgument)) {
                resource = "stage";
                ObjectNode inspection = stageInspections.get(finalArgument);
                String stage = inspection.path("Config").path("Labels").path("io.webjudge.stage").textValue();
                JsonNode record = mapper.readTree(attemptDirectory().resolve("stage-journal").resolve(stage + ".json").toFile());
                if (action.equals("stop")) {
                    assertTrue(Set.of("STOPPING", "REMOVING").contains(record.path("state").textValue()));
                    ((ObjectNode) inspection.get("State")).put("Running", false).put("Pid", 0);
                    return success(finalArgument);
                }
                if (action.equals("rm")) {
                    assertEquals("REMOVING", record.path("state").textValue());
                    assertFalse(inspection.path("State").path("Running").booleanValue());
                    if (!failure.equals("rmNoEffect")) {
                        removed.add(finalArgument);
                    }
                    if (failureResource.equals("stage") && failure.equals("rmAckLost")) {
                        failure = "";
                        return new LinuxContainerRunner.CommandResult(124, "", "stage removal acknowledgement lost");
                    }
                    return success(finalArgument);
                }
                assertEquals("inspect", action);
                if (failure.equals("inspectFailure")) {
                    return new LinuxContainerRunner.CommandResult(1, "[]", "inspection permission denied");
                }
                return json(inspection);
            }
            if (kind.equals("image")) {
                String image = command.get(5);
                ObjectNode inspection = mapper.createObjectNode().put("Id", failure.equals("imageId") ? "different" : image)
                        .put("Os", failure.equals("imageOs") ? "windows" : "linux");
                ObjectNode config = inspection.putObject("Config");
                if (image.equals(SUPERVISOR_IMAGE)) {
                    config.set("Entrypoint", mapper.valueToTree(failure.equals("imageEntrypoint")
                            ? List.of("unsafe") : List.of("python3", "-B", "/opt/web-judge/linux_supervisor.py")));
                }
                if (failure.equals("imageVolumes")) {
                    config.putObject("Volumes").putObject("/unexpected");
                }
                return json(inspection);
            }
            assertNotNull(LinuxRuntimeState.classifyUnresolvedSnapshot(retained()));
            if (action.equals("create")) {
                JsonNode state = retained();
                assertEquals("UNKNOWN", state.path(resource).path("creationState").asText());
                assertFalse(state.path(resource).has("runtimeId"));
                assertEquals("CREATE_REQUESTED", state.path(resource).path("state").asText());
                createIntents.add(resource);
                creates.put(resource, command);
                if (failureResource.equals(resource) && failure.equals("timeout")) {
                    return new LinuxContainerRunner.CommandResult(124, "", "timed out");
                }
                if (failureResource.equals(resource) && failure.equals("reply")) {
                    return success("short-identity\n");
                }
                return success(resource.equals("volume") ? command.get(command.size() - 1) : resource.equals("holder") ? HOLDER_ID : SUPERVISOR_ID);
            }
            if (action.equals("start")) {
                assertEquals("START_REQUESTED", retained().path(resource).path("state").asText());
                if (failureResource.equals(resource) && failure.equals("start")) {
                    return new LinuxContainerRunner.CommandResult(124, "", "start timed out");
                }
                running.put(resource, true);
                return success(command.get(5));
            }
            if (action.equals("stop")) {
                assertEquals("CLEANUP_PENDING", retained().path("state").asText());
                if (checkStopLock) {
                    assertThrows(IOException.class, () -> LinuxRuntimeJournal.openExisting(attemptDirectory(), "judge-supervisor",
                            retained().path("attemptId").asText(), mapper, 0, 0));
                    stopLockChecked = true;
                }
                if (!cancellationStopFailure.equals("noEffect")) {
                    running.put(resource, false);
                }
                if (cancellationStopFailure.equals("shortReply")) {
                    return success("short-id\n");
                }
                if (cancellationStopFailure.equals("stderr")) {
                    return new LinuxContainerRunner.CommandResult(0, command.get(command.size() - 1) + "\n", "stop response uncertain");
                }
                return success(command.get(command.size() - 1) + (cancellationTimeoutChecks ? "\n" : ""));
            }
            if (action.equals("rm")) {
                String identity = command.get(command.size() - 1);
                assertEquals("REMOVAL_REQUESTED", retained().path(resource).path("state").textValue());
                assertFalse(running.getOrDefault(resource, false));
                removed.add(identity);
                if (failureResource.equals(resource) && failure.equals("rmAckLost")) {
                    failure = "";
                    return new LinuxContainerRunner.CommandResult(124, "", "removal acknowledgement lost");
                }
                return success(identity + "\n");
            }
            assertEquals("inspect", action);
            if (failure.equals("inspectFailure")) {
                return new LinuxContainerRunner.CommandResult(1, "[]", "inspection permission denied");
            }
            if (removed.contains(command.get(command.size() - 1))) {
                return new LinuxContainerRunner.CommandResult(1, "[]", "missing container");
            }
            if (resource.equals("supervisor") && Files.exists(attemptDirectory().resolve("cancel.requested"))
                    && "STOP_REQUESTED".equals(retained().path("supervisor").path("state").textValue())) {
                if (!cancellationObservationFailure.isEmpty()) {
                    if (cancellationObservationFailure.equals("cli")) {
                        return new LinuxContainerRunner.CommandResult(1, "[]", "inspection unavailable");
                    }
                    if (cancellationObservationFailure.equals("stderr")) {
                        return new LinuxContainerRunner.CommandResult(0, "[]", "inspection response uncertain");
                    }
                    if (cancellationObservationFailure.equals("json")) {
                        return success("{");
                    }
                    if (cancellationObservationFailure.equals("missing")) {
                        return success("[]");
                    }
                    ObjectNode observed = containerInspection(resource);
                    ObjectNode observedState = (ObjectNode) observed.get("State");
                    switch (cancellationObservationFailure) {
                        case "runningType" -> observedState.put("Running", "true");
                        case "pidType" -> observedState.put("Pid", "1");
                        case "negativePid" -> observedState.put("Pid", -1);
                        case "stoppedPid" -> observedState.put("Running", false).put("Pid", 1);
                        case "oom" -> observedState.put("OOMKilled", true);
                        case "dead" -> observedState.put("Dead", true);
                        case "error" -> observedState.put("Error", "uncertain runtime state");
                        case "identity" -> observed.put("Id", "different");
                        default -> throw new AssertionError("unknown cancellation observation shape");
                    }
                    return json(observed);
                }
                if (interruptCancellation) {
                    Thread.currentThread().interrupt();
                }
                if (cooperativeCancellation) {
                    cancellationObserved = true;
                    running.put(resource, false);
                    supervisorExitCode = 20;
                }
            }
            return json(resource.equals("volume") ? volumeInspection() : containerInspection(resource));
        }

        private ObjectNode volumeInspection() throws IOException {
            JsonNode state = retained();
            ObjectNode volume = mapper.createObjectNode().put("Name", state.path("volume").path("name").asText())
                    .put("Driver", "local").put("Scope", "local").put("Mountpoint", reportedStageRoot);
            volume.set("Labels", labels(creates.get("volume")));
            volume.putObject("Options").put("type", "tmpfs").put("device", "tmpfs")
                    .put("o", "size=" + options.volumeLimitBytes() + ",nodev,nosuid,uid=0,gid=0,mode=0755");
            if (failureResource.equals("volume") && failure.equals("inspect")) {
                volume.putObject("Labels");
            }
            return volume;
        }

        private ObjectNode containerInspection(String resource) throws IOException {
            List<String> create = creates.get(resource);
            boolean supervisor = resource.equals("supervisor");
            String image = supervisor ? SUPERVISOR_IMAGE : EXECUTOR_IMAGE;
            ObjectNode inspection = mapper.createObjectNode().put("Id", supervisor ? SUPERVISOR_ID : HOLDER_ID)
                    .put("Name", "/" + argument(create, "--name")).put("Image", image);
            ObjectNode config = inspection.putObject("Config").put("User", argument(create, "--user"));
            config.set("Labels", labels(create));
            config.set("Cmd", mapper.valueToTree(create.subList(create.indexOf(image) + 1, create.size())));
            if (supervisor) {
                config.set("Entrypoint", mapper.valueToTree(List.of("python3", "-B", "/opt/web-judge/linux_supervisor.py")));
            }
            ObjectNode host = inspection.putObject("HostConfig").put("Privileged", false).put("ReadonlyRootfs", true)
                    .put("NetworkMode", "none").put("PidMode", "").put("IpcMode", "private").put("CgroupnsMode", "private")
                    .put("UTSMode", "").put("UsernsMode", "")
                    .put("Memory", Long.parseLong(argument(create, "--memory")))
                    .put("MemorySwap", Long.parseLong(argument(create, "--memory-swap")))
                    .put("PidsLimit", Integer.parseInt(argument(create, "--pids-limit")))
                    .put("NanoCpus", Math.round(Double.parseDouble(argument(create, "--cpus")) * 1_000_000_000));
            host.putArray("CapDrop").add("ALL");
            host.putArray("SecurityOpt").add("no-new-privileges");
            host.putObject("LogConfig").put("Type", "none");
            applyShape(host, "OomKillDisable", oomShape);
            ArrayNode mounts = inspection.putArray("Mounts");
            ArrayNode declarations = host.putArray("Mounts");
            for (int index = 0; index < create.size(); index++) {
                if (!create.get(index).equals("--mount")) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                for (String entry : create.get(index + 1).split(",")) {
                    String[] pair = entry.split("=", 2);
                    values.put(pair[0], pair.length == 2 ? pair[1] : "");
                }
                boolean volume = values.get("type").equals("volume");
                ObjectNode mount = mounts.addObject().put("Type", values.get("type"))
                        .put("Source", volume ? STAGE_ROOT : values.get("source"))
                        .put("Destination", values.get("target")).put("RW", true);
                ObjectNode declaration = declarations.addObject().put("Type", values.get("type"))
                        .put("Source", values.get("source")).put("Target", values.get("target")).put("ReadOnly", false);
                applyShape(declaration, "ReadOnly", readOnlyShape);
                if (volume) {
                    mount.put("Name", values.get("source"));
                    declaration.putObject("VolumeOptions").put("NoCopy", values.containsKey("volume-nocopy"));
                } else {
                    mount.put("Propagation", "rprivate");
                }
            }
            boolean started = running.getOrDefault(resource, false);
            ObjectNode state = inspection.putObject("State").put("Running", started && !(supervisor && fastExit))
                    .put("Status", started ? supervisor && fastExit ? "exited" : "running" : "created")
                    .put("Pid", started ? 42 : 0).put("OOMKilled", false).put("Dead", false).put("Error", "")
                    .put("ExitCode", supervisor ? supervisorExitCode : 0);
            if (inspectionResource.equals(resource)) {
                ObjectNode target = inspectionField.equals("Error") ? state
                        : inspectionField.equals("ReadOnly") ? (ObjectNode) declarations.get(0) : host;
                applyShape(target, inspectionField, inspectionShape);
            }
            if (supervisor && started && !stateMismatch.isEmpty()) {
                switch (stateMismatch) {
                    case "Error" -> state.put("Error", "runtime error");
                    case "created" -> state.put("Running", false).put("Status", "created");
                    default -> state.put(stateMismatch, true);
                }
            }
            if (failureResource.equals(resource) && failure.equals("inspect")) {
                inspection.put("Id", "f".repeat(64));
            }
            if (supervisor && !mismatch.isEmpty()) {
                switch (mismatch) {
                    case "overflowMemory" -> host.put("Memory", java.math.BigInteger.valueOf(options.supervisorLimits().memoryBytes())
                            .add(java.math.BigInteger.ONE.shiftLeft(64)));
                    case "Image", "Name" -> inspection.put(mismatch, "different");
                    case "User" -> config.put("User", "0:65531");
                    case "Entrypoint", "Cmd", "Labels" -> config.put(mismatch, "different");
                    case "NoCopy" -> ((ObjectNode) declarations.get(1)).putObject("VolumeOptions").put("NoCopy", false);
                    case "extraMount" -> mounts.addObject().put("Destination", "/unexpected");
                    case "mountSource" -> ((ObjectNode) mounts.get(0)).put("Source", "/unexpected");
                    default -> host.put(mismatch, "different");
                }
            }
            return inspection;
        }

        private void applyShape(ObjectNode target, String field, String shape) {
            switch (shape) {
                case "missing" -> target.remove(field);
                case "null" -> target.putNull(field);
                case "object" -> target.putObject(field);
                case "array" -> target.putArray(field);
                case "true" -> target.put(field, true);
                case "false" -> target.put(field, false);
                case "number" -> target.put(field, 0);
                case "empty-text" -> target.put(field, "");
                case "unknown-text" -> target.put(field, "unknown");
                default -> throw new IllegalArgumentException("unexpected inspect fixture shape");
            }
        }

        private ObjectNode labels(List<String> command) {
            ObjectNode labels = mapper.createObjectNode();
            for (int index = 0; index < command.size(); index++) {
                if (command.get(index).equals("--label")) {
                    String[] pair = command.get(index + 1).split("=", 2);
                    labels.put(pair[0], pair[1]);
                }
            }
            return labels;
        }

        private String argument(List<String> command, String flag) {
            return command.get(command.indexOf(flag) + 1);
        }

        private LinuxContainerRunner.CommandResult json(JsonNode inspection) throws IOException {
            return success(mapper.writeValueAsString(List.of(inspection)));
        }

        private LinuxContainerRunner.CommandResult success(String output) {
            return new LinuxContainerRunner.CommandResult(0, output, "");
        }
    }
}
