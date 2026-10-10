package com.example.demo;

import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.support.RunnerProtocolFixtures;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.sandbox.LinuxContainerRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import com.example.demo.dto.RunnerProtocolCapabilities;
import com.example.demo.dto.SandboxComparison;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinuxContainerRunnerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"remote-worker"})
    void cancelRejectsForeignOrMissingProviderBeforeExternalActions(String provider) {
        RecordingExecutor executor = new RecordingExecutor();
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId("foreign-judge").runId("foreign-run").provider(provider).build();

        assertThatThrownBy(() -> runner.cancel(handle))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handle provider");
        assertThat(executor.commands).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,true", "NULL,false", "'',false", "remote-worker,false", "windows-container,false"}, nullValues = "NULL")
    void pollEventsRejectsForeignOrMissingHandlesWithoutConsumingEvents(String provider, boolean missingHandle) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "container-123", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        SandboxRunHandle foreignHandle = missingHandle ? null : new SandboxRunHandle(handle.judgeId(), handle.runId(),
                provider, handle.startedAt(), handle.eventCursor(), handle.protocolVersion(), handle.attemptId());
        SandboxTaskEvent event = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED,
                java.time.Instant.now(), null, "RUNNING", "started", null, null,
                handle.protocolVersion(), handle.attemptId(), 1L);
        Path eventFile = Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl");
        Files.writeString(eventFile, objectMapper.writeValueAsString(event) + "\n");
        byte[] contents = Files.readAllBytes(eventFile);
        int commandCount = executor.commands.size();

        assertThatThrownBy(() -> runner.pollEvents(foreignHandle, Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handle provider");
        assertThat(Files.readAllBytes(eventFile)).isEqualTo(contents);
        assertThat(runner.pollEvents(handle)).containsExactly(event);
        assertThat(runner.pollEvents(handle)).isEmpty();
        assertThat(executor.commands).hasSize(commandCount);
    }

    @Test
    void probeReturnsExplicitSkipReasonWhenLinuxContainerRuntimeIsUnavailable() {
        RecordingExecutor executor = RecordingExecutor.unavailable("docker executable is missing");
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        assertThat(capabilities.provider()).isEqualTo("linux-container");
        assertThat(capabilities.productionSafe()).isFalse();
        assertThat(capabilities.skipReason()).contains("container runtime unavailable");
        assertThat(capabilities.details()).contains("docker");
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "trusted-control-plane"})
    void outerContainerLimitsDoNotProveTrustedControlPlaneIsolation(String claimedFeature) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        var claimedFeatures = new java.util.HashSet<>(RunnerProtocolCapabilities.REQUIRED_FEATURES);
        if (!claimedFeature.equals("absent")) {
            claimedFeatures.add(claimedFeature);
        }
        executor.when(command -> command.contains("--capabilities"), result(0,
                objectMapper.writeValueAsString(new RunnerProtocolCapabilities(Set.of(2),
                        Set.of(SandboxComparison.Mode.LEGACY_TEXT), claimedFeatures)), ""));
        executor.when(command -> command.contains("version"), result(0, "linux|25.0.0|2", ""));
        executor.when(command -> command.contains("image") && command.contains("inspect"), result(0, "linux|amd64", ""));
        executor.when(command -> command.contains("run") && command.contains("/bin/sh"), result(0, """
                PROBE_UID=65532
                PROBE_NETWORK=disabled
                PROBE_CGROUP=present
                PROBE_SECCOMP=2
                PROBE_APPARMOR=judge-linux (enforce)
                """, ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        assertThat(capabilities.productionSafe()).isFalse();
        assertThat(capabilities.skipReason()).contains("control-plane isolation");
        assertThat(capabilities.controlPlaneIsolated()).isFalse();
        assertThat(capabilities.isolation()).isEqualTo("container");
        assertThat(capabilities.networkDisabled()).isTrue();
        assertThat(capabilities.nonRoot()).isTrue();
        assertThat(capabilities.resourceLimits()).isTrue();
        assertThat(capabilities.securityProfile()).contains("seccomp").contains("apparmor");
        assertThat(capabilities.details()).contains("image=judge-runner:test").contains("cgroup");

        List<String> probeCommand = executor.commandContaining("run", "--rm");
        assertThat(probeCommand)
                .startsWith("docker")
                .contains("--network", "none")
                .contains("--read-only")
                .contains("--cap-drop", "ALL")
                .contains("--pids-limit", "64")
                .contains("--security-opt", "no-new-privileges")
                .contains("--security-opt", "seccomp=judge-linux")
                .contains("--security-opt", "apparmor=judge-linux")
                .contains("--user", "65532:65532");
    }

    @Test
    void probeFailsClosedWhenContainerProbeShowsUnsafeCapabilities() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("version"), result(0, "linux|25.0.0|2", ""));
        executor.when(command -> command.contains("image") && command.contains("inspect"), result(0, "linux|amd64", ""));
        executor.when(command -> command.contains("run") && command.contains("/bin/sh"), result(0, """
                PROBE_UID=0
                PROBE_NETWORK=enabled:lo,eth0
                PROBE_CGROUP=missing
                PROBE_SECCOMP=0
                PROBE_APPARMOR=unconfined
                """, ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        assertThat(capabilities.productionSafe()).isFalse();
        assertThat(capabilities.networkDisabled()).isFalse();
        assertThat(capabilities.nonRoot()).isFalse();
        assertThat(capabilities.resourceLimits()).isFalse();
        assertThat(capabilities.skipReason())
                .contains("network")
                .contains("non-root")
                .contains("cgroup")
                .contains("security profile");
    }

    @Test
    void startLaunchesDetachedContainerWithLimitsAndWritesTaskSpecWithoutHostFallback() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "container-123\n", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();

        SandboxRunHandle handle = runner.start(spec);

        assertThat(handle.provider()).isEqualTo("linux-container");
        assertThat(handle.runId()).isEqualTo("container-123");
        assertThat(handle.eventCursor()).isEqualTo("0");
        assertThat(Files.readString(Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/task-spec.json")))
                .contains("\"judgeId\":\"judge-linux\"")
                .contains("\"protocolVersion\":2")
                .doesNotContain(spec.workDir(), "workDir", "userId");

        List<String> command = executor.commandContaining("run", "-d");
        assertThat(command)
                .startsWith("docker")
                .contains("--network", "none")
                .contains("--read-only")
                .contains("--tmpfs", "/tmp:rw,noexec,nosuid,size=64m")
                .contains("--memory", String.valueOf(spec.memoryLimitBytes()))
                .contains("--memory-swap", String.valueOf(spec.memoryLimitBytes()))
                .contains("--pids-limit", "64")
                .contains("--cap-drop", "ALL")
                .contains("--security-opt", "no-new-privileges")
                .contains("--mount", "type=bind,source=" + Path.of(spec.workDir()) + ",target=/work")
                .contains("judge-runner:test")
                .contains("/opt/judge-runner/run-task")
                .contains("/work/control/attempts/" + handle.attemptId() + "/task-spec.json");
        assertThat(command)
                .doesNotContain("g++")
                .doesNotContain(Path.of(spec.sourcePaths().get(SandboxTaskSpec.SourceRole.USER)).toString());
    }

    @Test
    void pollEventsReadsJsonLinesFromMountedWorkDirAndCancelRemovesContainer() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "container-456", ""));
        executor.when(command -> command.contains("rm") && command.contains("-f"), result(0, "", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        SandboxTaskEvent completed = SandboxTaskEvent.of("judge-linux", SandboxTaskEvent.Type.COMPLETED, "completed");
        Files.writeString(Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl"), objectMapper.writeValueAsString(completed) + System.lineSeparator());

        List<SandboxTaskEvent> firstPoll = runner.pollEvents(handle);
        List<SandboxTaskEvent> secondPoll = runner.pollEvents(handle);
        runner.cancel(handle);

        assertThat(firstPoll).singleElement().satisfies(event -> {
            assertThat(event.judgeId()).isEqualTo("judge-linux");
            assertThat(event.type()).isEqualTo(SandboxTaskEvent.Type.COMPLETED);
        });
        assertThat(secondPoll).isEmpty();
        assertThat(executor.commandContaining("rm", "-f")).contains("container-456");
    }

    @Test
    void startRejectsSourcePathsThatWouldNotBeInsideTheMountedWorkDir() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        Path storageBase = tempDir.resolve("storage").toAbsolutePath().normalize();
        Path workDir = storageBase.resolve("judge-linux");
        Files.createDirectories(workDir);
        Path outsideSource = storageBase.resolve("shared.cpp");
        Files.writeString(outsideSource, "int main(){return 0;}");

        SandboxTaskSpec spec = SandboxTaskSpec.builder()
                .judgeId("judge-linux")
                .userId("user-linux")
                .profile("trusted-local")
                .storageBase(storageBase)
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.USER, outsideSource)
                .testCases(1)
                .caseTimeLimit(Duration.ofSeconds(1))
                .maxTaskRuntime(Duration.ofSeconds(10))
                .memoryLimitBytes(64L * 1024 * 1024)
                .maxOutputBytesPerCase(1024)
                .build();

        assertThatThrownBy(() -> runner.start(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mounted workDir");
    }

    @Test
    void liveProbeSkipsWithEnvironmentReasonWhenLinuxRuntimeOrImageIsUnavailable() {
        LinuxContainerRunner runner = LinuxContainerRunner.withDefaults(objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        Assumptions.assumeTrue(
                capabilities.productionSafe(),
                "Linux container capability unavailable: " + capabilities.skipReason()
        );
        assertThat(capabilities.networkDisabled()).isTrue();
        assertThat(capabilities.nonRoot()).isTrue();
        assertThat(capabilities.resourceLimits()).isTrue();
        assertThat(capabilities.securityProfile()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "version", "comparison", "limits"})
    void incompatibleArtifactIsRejectedBeforeAnyManifestWrite(String mode) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        Set<Integer> versions = mode.equals("version") ? Set.of(1) : Set.of(2);
        Set<SandboxComparison.Mode> modes = mode.equals("comparison")
                ? Set.of(SandboxComparison.Mode.SPECIAL_JUDGE) : Set.of(SandboxComparison.Mode.LEGACY_TEXT);
        var features = new java.util.HashSet<>(RunnerProtocolCapabilities.REQUIRED_FEATURES);
        if (mode.equals("limits")) {
            features.remove("explicit-limits");
        }
        String response = mode.equals("legacy") ? "{}"
                : objectMapper.writeValueAsString(new RunnerProtocolCapabilities(versions, modes, features));
        executor.when(command -> command.contains("--capabilities"), result(0, response, ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        Path workDir = Path.of(spec.workDir());
        Files.writeString(workDir.resolve("events.jsonl"), "host audit");

        assertThatThrownBy(() -> runner.start(spec)).isInstanceOf(IllegalArgumentException.class);

        assertThat(workDir.resolve("control")).doesNotExist();
        assertThat(Files.readString(workDir.resolve("events.jsonl"))).isEqualTo("host audit");
        assertThat(executor.commands).noneMatch(command -> command.contains("-d"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"linux-prod", "windows-prod", "worker-prod", "intranet-large", "TRUSTED-LOCAL"})
    void nonTrustedProfilesCannotStartSharedControlPlane(String profile) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec(profile);
        Path workDir = Path.of(spec.workDir());
        String audit = "audit-1\naudit-2\naudit-3\naudit-4\n";
        Files.writeString(workDir.resolve("events.jsonl"), audit);

        assertThatThrownBy(() -> runner.start(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control-plane isolation");

        assertThat(workDir.resolve("control")).doesNotExist();
        assertThat(workDir.resolve("workspace")).doesNotExist();
        assertThat(Files.readString(workDir.resolve("events.jsonl"))).isEqualTo(audit);
        assertThat(executor.commands).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed-json", "malformed-utf8", "directory"})
    void localEventReadErrorsRemainHostFailuresWithoutFabricatedRunnerEvents(String shape) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"),
                result(0, "linux-read-failure", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        Path eventFile = Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl");
        if (shape.equals("directory")) {
            Files.createDirectory(eventFile);
        } else if (shape.equals("malformed-utf8")) {
            Files.write(eventFile, new byte[]{(byte) 0xc3, 0x28, '\n'});
        } else {
            Files.writeString(eventFile, "private-control-secret\n");
        }

        assertThatThrownBy(() -> runner.pollEvents(handle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to read linux container events")
                .hasCauseInstanceOf(IOException.class);
        assertThat(executor.commands).noneMatch(command -> command.contains("rm"));
        if (shape.equals("directory")) {
            Files.delete(eventFile);
        }
        SandboxTaskEvent event = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED,
                java.time.Instant.now(), null, "RUNNING", "running", null, null,
                handle.protocolVersion(), handle.attemptId(), 1L);
        Files.writeString(eventFile, objectMapper.writeValueAsString(event) + System.lineSeparator());
        assertThat(runner.pollEvents(handle)).containsExactly(event);
        assertThat(runner.pollEvents(handle)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"append", "truncate", "replace", "remove", "symbolic-link"})
    void eventFileMustRemainAppendOnlyWithTheSameIdentity(String mutation) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"),
                result(0, "linux-event-identity", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        Path eventFile = Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl");
        assertThat(runner.pollEvents(handle)).isEmpty();
        SandboxTaskEvent first = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED,
                java.time.Instant.now(), null, "RUNNING", "running", null, null,
                handle.protocolVersion(), handle.attemptId(), 1L);
        SandboxTaskEvent second = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.COMPILE_STARTED,
                java.time.Instant.now(), null, "RUNNING", "compiling", null, null,
                handle.protocolVersion(), handle.attemptId(), 2L);
        String firstLine = objectMapper.writeValueAsString(first) + "\n";
        String secondLine = objectMapper.writeValueAsString(second) + "\n";
        Files.writeString(eventFile, firstLine);
        assertThat(runner.pollEvents(handle)).containsExactly(first);
        assertThat(runner.pollEvents(handle)).isEmpty();

        if (mutation.equals("append")) {
            Files.writeString(eventFile, secondLine, java.nio.file.StandardOpenOption.APPEND);
            assertThat(runner.pollEvents(handle)).containsExactly(second);
            assertThat(runner.pollEvents(handle)).isEmpty();
        } else {
            if (mutation.equals("truncate")) {
                Files.writeString(eventFile, "");
            } else {
                Files.move(eventFile, eventFile.resolveSibling("previous-events.jsonl"));
                if (mutation.equals("replace")) {
                    Files.writeString(eventFile, firstLine + secondLine);
                } else if (mutation.equals("symbolic-link")) {
                    Path replacement = eventFile.resolveSibling("replacement-events.jsonl");
                    Files.writeString(replacement, firstLine + secondLine);
                    Files.createSymbolicLink(eventFile, replacement);
                }
            }
            assertThatThrownBy(() -> runner.pollEvents(handle))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Failed to read linux container events")
                    .hasCauseInstanceOf(IOException.class);
        }
        assertThat(executor.commands).noneMatch(command -> command.contains("rm"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-record", "missing-type", "null-type", "terminal-record"})
    void invalidBatchDoesNotCommitCheckpointAndTerminalReadNeverStartsCleanup(String scenario) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"),
                result(0, "linux-checkpoint-cleanup", ""));
        executor.when(command -> command.contains("rm") && command.contains("-f"), result(0, "", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        Path eventFile = Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl");
        SandboxTaskEvent first = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED,
                java.time.Instant.now(), null, "RUNNING", "running", null, null,
                handle.protocolVersion(), handle.attemptId(), 1L);
        SandboxTaskEvent second = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.SYSTEM_ERROR,
                java.time.Instant.now(), null, "SYSTEM_ERROR", "fixture failure", null, null,
                handle.protocolVersion(), handle.attemptId(), 2L);
        String firstLine = objectMapper.writeValueAsString(first) + "\n";
        String secondLine = objectMapper.writeValueAsString(second) + "\n";
        if (!scenario.equals("terminal-record")) {
            String invalidLine = "null";
            if (!scenario.equals("null-record")) {
                ObjectNode invalidEvent = objectMapper.valueToTree(second);
                if (scenario.equals("missing-type")) {
                    invalidEvent.remove("type");
                } else {
                    invalidEvent.putNull("type");
                }
                invalidLine = objectMapper.writeValueAsString(invalidEvent);
            }
            Files.writeString(eventFile, firstLine + invalidLine + "\n");
            assertThatThrownBy(() -> runner.pollEvents(handle)).isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(IOException.class);
        }
        Files.writeString(eventFile, firstLine + secondLine);
        assertThat(runner.pollEvents(handle)).containsExactly(first, second);
        assertThat(runner.pollEvents(handle)).isEmpty();
        assertThat(executor.commands).noneMatch(command -> command.contains("rm"));

        runner.cancel(handle, Duration.ofMillis(100));
        assertThat(executor.commandContaining("rm", "-f")).contains(handle.runId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"json-fragment", "utf8-fragment", "no-newline"})
    void partialEventWaitsForNewlineWithoutLosingCompletedLinesOrAdvancingCursor(String boundary) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"),
                result(0, "linux-event-framing", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        Path eventFile = Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl");
        SandboxTaskEvent first = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED,
                java.time.Instant.now(), null, "RUNNING", "running", null, null,
                handle.protocolVersion(), handle.attemptId(), 1L);
        SandboxTaskEvent second = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.COMPILE_STARTED,
                java.time.Instant.now(), null, "RUNNING", "编译😀", null, null,
                handle.protocolVersion(), handle.attemptId(), 2L);
        byte[] secondBytes = objectMapper.writeValueAsBytes(second);
        int split = boundary.equals("no-newline") ? secondBytes.length : secondBytes.length / 2;
        if (boundary.equals("utf8-fragment")) {
            split = 0;
            while ((secondBytes[split] & 0x80) == 0) {
                split++;
            }
            split++;
        }
        String firstLine = objectMapper.writeValueAsString(first) + "\n";
        Files.writeString(eventFile, firstLine);
        Files.write(eventFile, java.util.Arrays.copyOf(secondBytes, split), java.nio.file.StandardOpenOption.APPEND);
        assertThat(runner.pollEvents(handle)).containsExactly(first);
        assertThat(runner.pollEvents(handle)).isEmpty();
        Object runState = ((Map<?, ?>) ReflectionTestUtils.getField(runner, "runs")).get(handle.runId());
        Object cursor = ReflectionTestUtils.getField(runState, "cursor");
        assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue())
                .isEqualTo(firstLine.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        Object fileIdentity = Files.readAttributes(eventFile, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        byte[] rewrittenSecond = secondBytes.clone();
        int changedOffset = new String(secondBytes, java.nio.charset.StandardCharsets.UTF_8).indexOf(handle.judgeId());
        assertThat(changedOffset).isGreaterThanOrEqualTo(0).isLessThan(split);
        rewrittenSecond[changedOffset] = (byte) (rewrittenSecond[changedOffset] == 'a' ? 'b' : 'a');
        Files.writeString(eventFile, firstLine);
        Files.write(eventFile, rewrittenSecond, java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(eventFile, "\n", java.nio.file.StandardOpenOption.APPEND);
        assertThat(Files.readAttributes(eventFile, java.nio.file.attribute.BasicFileAttributes.class).fileKey())
                .isEqualTo(fileIdentity);
        assertThatThrownBy(() -> runner.pollEvents(handle)).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(java.io.IOException.class);
        assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue())
                .isEqualTo(firstLine.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        Object pendingLine = ReflectionTestUtils.getField(runState, "pendingLine");
        assertThat((byte[]) ReflectionTestUtils.invokeMethod(pendingLine, "get"))
                .isEqualTo(java.util.Arrays.copyOf(secondBytes, split));
        Files.writeString(eventFile, firstLine);
        Files.write(eventFile, java.util.Arrays.copyOf(secondBytes, split), java.nio.file.StandardOpenOption.APPEND);
        Files.write(eventFile, java.util.Arrays.copyOfRange(secondBytes, split, secondBytes.length),
                java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(eventFile, "\r\n", java.nio.file.StandardOpenOption.APPEND);
        assertThat(runner.pollEvents(handle)).containsExactly(second);
        assertThat(runner.pollEvents(handle)).isEmpty();
        assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue())
                .isEqualTo(Files.size(eventFile));
        assertThat(executor.commands).noneMatch(command -> command.contains("rm"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed-json", "malformed-utf8"})
    void invalidAppendedLineDoesNotCommitEarlierEventsFromTheSameBatch(String boundary) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"),
                result(0, "linux-batch-cursor", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        Path eventFile = Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl");
        SandboxTaskEvent first = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED,
                java.time.Instant.now(), null, "RUNNING", "running", null, null,
                handle.protocolVersion(), handle.attemptId(), 1L);
        SandboxTaskEvent second = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.COMPILE_STARTED,
                java.time.Instant.now(), null, "RUNNING", "编译😀", null, null,
                handle.protocolVersion(), handle.attemptId(), 2L);
        String firstLine = objectMapper.writeValueAsString(first) + "\n";
        Files.writeString(eventFile, firstLine);
        assertThat(runner.pollEvents(handle)).containsExactly(first);
        Object runState = ((Map<?, ?>) ReflectionTestUtils.getField(runner, "runs")).get(handle.runId());
        Object cursor = ReflectionTestUtils.getField(runState, "cursor");
        long firstOffset = firstLine.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        byte[] secondLine = (objectMapper.writeValueAsString(second) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int split = secondLine.length / 2;
        byte[] pendingBytes = java.util.Arrays.copyOf(secondLine, split);
        Files.write(eventFile, pendingBytes, java.nio.file.StandardOpenOption.APPEND);
        assertThat(runner.pollEvents(handle)).isEmpty();
        Object pendingLine = ReflectionTestUtils.getField(runState, "pendingLine");
        assertThat((byte[]) ReflectionTestUtils.invokeMethod(pendingLine, "get")).isEqualTo(pendingBytes);
        Files.write(eventFile, java.util.Arrays.copyOfRange(secondLine, split, secondLine.length),
                java.nio.file.StandardOpenOption.APPEND);
        long invalidOffset = Files.size(eventFile);
        byte[] invalidLine = boundary.equals("malformed-utf8") ? new byte[]{(byte) 0xc3, 0x28, '\n'}
                : "not-json\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(eventFile, invalidLine, java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> runner.pollEvents(handle)).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue()).isEqualTo(firstOffset);
        assertThat((byte[]) ReflectionTestUtils.invokeMethod(pendingLine, "get")).isEqualTo(pendingBytes);
        assertThat(executor.commands).noneMatch(command -> command.contains("rm"));
        java.util.Arrays.fill(invalidLine, (byte) ' ');
        invalidLine[invalidLine.length - 1] = '\n';
        try (var channel = java.nio.channels.FileChannel.open(eventFile, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            channel.position(invalidOffset);
            java.nio.ByteBuffer replacement = java.nio.ByteBuffer.wrap(invalidLine);
            while (replacement.hasRemaining()) {
                channel.write(replacement);
            }
        }
        assertThat(runner.pollEvents(handle)).containsExactly(second);
        assertThat((byte[]) ReflectionTestUtils.invokeMethod(pendingLine, "get")).isEmpty();
        assertThat(runner.pollEvents(handle)).isEmpty();
        assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue()).isEqualTo(Files.size(eventFile));
    }

    @ParameterizedTest
    @CsvSource({"-1,lf", "0,lf", "1,lf", "0,eof", "1,eof", "0,crlf", "1,crlf"})
    void eventLineByteBudgetIncludesUtf8AndRejectsOversizedUnterminatedLines(int delta, String ending) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "bounded-line", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        Path eventFile = Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl");
        SandboxTaskEvent event = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED,
                java.time.Instant.now(), null, "RUNNING", "边界😀", null, null,
                handle.protocolVersion(), handle.attemptId(), 1L);
        String json = objectMapper.writeValueAsString(event);
        int lineBytes = options().eventLineLimitBytes() + delta;
        String carriageReturn = ending.equals("crlf") ? "\r" : "";
        String line = json + " ".repeat(lineBytes - json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                - carriageReturn.length()) + carriageReturn;
        Files.writeString(eventFile, json + "\n" + line + (ending.equals("eof") ? "" : "\n"));
        Object state = ((Map<?, ?>) ReflectionTestUtils.getField(runner, "runs")).get(handle.runId());
        Object cursor = ReflectionTestUtils.getField(state, "cursor");
        if (delta > 0) {
            assertThatThrownBy(() -> runner.pollEvents(handle)).isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(IOException.class).hasRootCauseMessage("Runner event line exceeds byte limit");
            assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue()).isZero();
            assertThat(executor.commands).noneMatch(command -> command.contains("rm"));
        } else if (ending.equals("eof")) {
            assertThat(runner.pollEvents(handle)).containsExactly(event);
            assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue())
                    .isEqualTo(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1);
            Files.writeString(eventFile, "\n", java.nio.file.StandardOpenOption.APPEND);
            assertThat(runner.pollEvents(handle)).containsExactly(event);
            assertThat(runner.pollEvents(handle)).isEmpty();
        } else {
            assertThat(runner.pollEvents(handle)).containsExactly(event, event);
            assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue()).isEqualTo(Files.size(eventFile));
            assertThat(runner.pollEvents(handle)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {255, 256, 257})
    void eventCountBudgetResumesAtTheNextUnconsumedEvent(int eventCount) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "bounded-count", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        Path eventFile = Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl");
        List<SandboxTaskEvent> expected = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        long firstOffset = 0;
        for (int index = 0; index < eventCount; index++) {
            SandboxTaskEvent event = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED,
                    java.time.Instant.now(), null, "RUNNING", "分批😀", null, null,
                    handle.protocolVersion(), handle.attemptId(), (long) index + 1);
            expected.add(event);
            String line = objectMapper.writeValueAsString(event) + "\n";
            content.append(line);
            if (index < options().eventBatchLimitCount()) {
                firstOffset += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        Files.writeString(eventFile, content);
        int firstCount = Math.min(eventCount, options().eventBatchLimitCount());
        assertThat(runner.pollEvents(handle)).containsExactlyElementsOf(expected.subList(0, firstCount));
        Object state = ((Map<?, ?>) ReflectionTestUtils.getField(runner, "runs")).get(handle.runId());
        Object cursor = ReflectionTestUtils.getField(state, "cursor");
        assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue()).isEqualTo(firstOffset);
        assertThat(runner.pollEvents(handle)).containsExactlyElementsOf(expected.subList(firstCount, eventCount));
        assertThat(runner.pollEvents(handle)).isEmpty();
        assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue()).isEqualTo(Files.size(eventFile));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void eventBatchByteBudgetCommitsOnlyCompleteLinesAndResumesWithoutLoss(int delta) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "bounded-bytes", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        Path eventFile = Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl");
        SandboxTaskEvent first = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.RUN_STARTED,
                java.time.Instant.now(), null, "RUNNING", "首条😀", null, null,
                handle.protocolVersion(), handle.attemptId(), 1L);
        SandboxTaskEvent second = new SandboxTaskEvent(handle.judgeId(), SandboxTaskEvent.Type.COMPILE_STARTED,
                java.time.Instant.now(), null, "RUNNING", "续读😀", null, null,
                handle.protocolVersion(), handle.attemptId(), 2L);
        String firstJson = objectMapper.writeValueAsString(first);
        String secondJson = objectMapper.writeValueAsString(second);
        String firstLine = firstJson + " ".repeat(options().eventLineLimitBytes() - 1 + delta
                - firstJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) + "\n";
        String secondLine = secondJson + " ".repeat(options().eventLineLimitBytes() - 1
                - secondJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) + "\n";
        Files.writeString(eventFile, firstLine + secondLine);
        assertThat(Files.size(eventFile)).isEqualTo(options().eventBatchLimitBytes() + delta);
        Object state = ((Map<?, ?>) ReflectionTestUtils.getField(runner, "runs")).get(handle.runId());
        Object cursor = ReflectionTestUtils.getField(state, "cursor");
        if (delta > 0) {
            assertThat(runner.pollEvents(handle)).containsExactly(first);
            assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue())
                    .isEqualTo(firstLine.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertThat(runner.pollEvents(handle)).containsExactly(second);
        } else {
            assertThat(runner.pollEvents(handle)).containsExactly(first, second);
        }
        assertThat(runner.pollEvents(handle)).isEmpty();
        assertThat(((Number) ReflectionTestUtils.invokeMethod(cursor, "get")).longValue()).isEqualTo(Files.size(eventFile));
    }

    @ParameterizedTest
    @CsvSource({"0,262144,256", "131072,131072,256", "131072,0,256", "131072,262144,0", "-1,262144,256"})
    void eventBudgetsRejectInvalidConfiguration(int lineBytes, int batchBytes, int batchCount) {
        assertThatThrownBy(() -> LinuxContainerRunner.Options.builder().eventLineLimitBytes(lineBytes)
                .eventBatchLimitBytes(batchBytes).eventBatchLimitCount(batchCount).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event limits");
    }

    @Test
    void eventBudgetsBindExistingProductionProperties() {
        com.example.demo.config.SandboxProperties properties = new com.example.demo.config.SandboxProperties();
        assertThat(properties.getEventLineLimitBytes()).isEqualTo(128 * 1024);
        assertThat(properties.getEventBatchLimitBytes()).isEqualTo(256 * 1024);
        assertThat(properties.getEventBatchLimitCount()).isEqualTo(256);
        properties.setEventLineLimitBytes(4096);
        properties.setEventBatchLimitBytes(8192);
        properties.setEventBatchLimitCount(7);
        LinuxContainerRunner.Options bound = ReflectionTestUtils.invokeMethod(LinuxContainerRunner.Options.class, "fromProperties", properties);
        assertThat(bound.eventLineLimitBytes()).isEqualTo(4096);
        assertThat(bound.eventBatchLimitBytes()).isEqualTo(8192);
        assertThat(bound.eventBatchLimitCount()).isEqualTo(7);
    }

    @ParameterizedTest
    @CsvSource({"true|42,ACTIVE", "false|0,EXITED", "true|0,UNKNOWN", "false|42,UNKNOWN", "invalid,UNKNOWN"})
    void executionStateUsesKnownRunIdentityAndCallerBudget(String status, String expected) throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "observed-run", ""));
        executor.when(command -> command.contains("{{.State.Running}}|{{.State.Pid}}"), result(0, status + "\n", ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxRunHandle handle = runner.start(validSpec());
        Duration budget = Duration.ofMillis(25);
        assertThat(runner.executionState(handle, budget))
                .isEqualTo(com.example.demo.service.sandbox.SandboxRunner.ExecutionState.valueOf(expected));
        assertThat(executor.timeouts.get(executor.timeouts.size() - 1)).isEqualTo(budget);
        assertThat(executor.commands.get(executor.commands.size() - 1)).contains("inspect", handle.runId());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"remote-worker"})
    void executionStateRejectsForeignProviderBeforeRuntimeAccess(String provider) {
        RecordingExecutor executor = new RecordingExecutor();
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxRunHandle foreign = SandboxRunHandle.builder().judgeId("foreign").runId("foreign").provider(provider).build();
        assertThatThrownBy(() -> runner.executionState(foreign, Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handle provider");
        assertThat(executor.commands).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startupPhasesShareOneBudgetAndDoNotLaunchAfterProtocolUsesIt(boolean exhausted) throws Exception {
        List<Duration> budgets = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
        LinuxContainerRunner.CommandExecutor executor = (command, timeout) -> {
            budgets.add(timeout);
            if (command.contains("--capabilities")) {
                Thread.sleep(exhausted ? 450 : 25);
                return result(0, RunnerProtocolFixtures.PYTHON_CAPABILITIES, "");
            }
            launches.incrementAndGet();
            return result(0, "bounded-startup", "");
        };
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        Duration budget = Duration.ofMillis(400);
        if (exhausted) {
            assertThatThrownBy(() -> runner.start(spec, budget)).isInstanceOf(IllegalStateException.class)
                    .hasCauseInstanceOf(IOException.class).hasRootCauseMessage("Sandbox startup deadline exceeded");
            assertThat(launches).hasValue(0);
            assertThat((Map<?, ?>) ReflectionTestUtils.getField(runner, "runs")).isEmpty();
            assertThat(budgets).hasSize(1);
        } else {
            assertThat(runner.start(spec, budget).runId()).isEqualTo("bounded-startup");
            assertThat(launches).hasValue(1);
            assertThat(budgets).hasSize(2);
            assertThat(budgets.get(1)).isLessThan(budgets.get(0));
        }
        assertThat(budgets).allMatch(timeout -> !timeout.isZero() && !timeout.isNegative() && timeout.compareTo(budget) <= 0);
    }

    private LinuxContainerRunner.Options options() {
        return LinuxContainerRunner.Options.builder()
                .runtimeCommand("docker")
                .image("judge-runner:test")
                .containerUser("65532:65532")
                .workMount("/work")
                .runnerCommand("/opt/judge-runner/run-task")
                .taskSpecFile("task-spec.json")
                .eventFile("runner-events.jsonl")
                .pidsLimit(64)
                .cpus(1.0)
                .securityProfile("seccomp:judge-linux,apparmor:judge-linux")
                .commandTimeout(Duration.ofSeconds(5))
                .build();
    }

    private SandboxTaskSpec validSpec() throws IOException {
        return validSpec("trusted-local");
    }

    private SandboxTaskSpec validSpec(String profile) throws IOException {
        Path storageBase = tempDir.resolve("storage").toAbsolutePath().normalize();
        Path workDir = storageBase.resolve("judge-linux");
        Files.createDirectories(workDir);
        Path generator = workDir.resolve("generator.cpp");
        Path user = workDir.resolve("user.cpp");
        Path oracle = workDir.resolve("oracle.cpp");
        Files.writeString(generator, "int main(){return 0;}");
        Files.writeString(user, "int main(){return 0;}");
        Files.writeString(oracle, "int main(){return 0;}");
        return SandboxTaskSpec.builder()
                .judgeId("judge-linux")
                .userId("user-linux")
                .profile(profile)
                .storageBase(storageBase)
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, generator)
                .sourcePath(SandboxTaskSpec.SourceRole.USER, user)
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, oracle)
                .comparison(RunnerProtocolFixtures.legacyText())
                .testCases(2)
                .caseTimeLimit(Duration.ofSeconds(1))
                .maxTaskRuntime(Duration.ofSeconds(10))
                .memoryLimitBytes(64L * 1024 * 1024)
                .maxOutputBytesPerCase(1024)
                .build();
    }

    private static LinuxContainerRunner.CommandResult result(int exitCode, String stdout, String stderr) {
        return new LinuxContainerRunner.CommandResult(exitCode, stdout, stderr);
    }

    private static final class RecordingExecutor implements LinuxContainerRunner.CommandExecutor {
        private final List<Invocation> stubs = new ArrayList<>();
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Duration> timeouts = new ArrayList<>();
        private final IOException unavailable;

        private RecordingExecutor() {
            this.unavailable = null;
        }

        private RecordingExecutor(IOException unavailable) {
            this.unavailable = unavailable;
        }

        static RecordingExecutor unavailable(String message) {
            return new RecordingExecutor(new IOException(message));
        }

        void when(Predicate<List<String>> predicate, LinuxContainerRunner.CommandResult result) {
            stubs.add(new Invocation(predicate, result));
        }

        @Override
        public LinuxContainerRunner.CommandResult run(List<String> command, Duration timeout) throws IOException {
            commands.add(List.copyOf(command));
            timeouts.add(timeout);
            if (unavailable != null) {
                throw unavailable;
            }
            return stubs.stream()
                    .filter(stub -> stub.predicate().test(command))
                    .findFirst()
                    .map(Invocation::result)
                    .orElseGet(() -> command.contains("--capabilities")
                            ? result(0, RunnerProtocolFixtures.PYTHON_CAPABILITIES, "")
                            : result(1, "", "unexpected command: " + command));
        }

        List<String> commandContaining(String... tokens) {
            return commands.stream()
                    .filter(command -> List.of(tokens).stream().allMatch(command::contains))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No command containing " + List.of(tokens) + " in " + commands));
        }

        private record Invocation(Predicate<List<String>> predicate, LinuxContainerRunner.CommandResult result) {
        }
    }
}
