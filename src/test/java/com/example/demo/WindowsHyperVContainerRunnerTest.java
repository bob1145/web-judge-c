package com.example.demo;

import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.support.RunnerProtocolFixtures;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.sandbox.WindowsHyperVContainerRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.demo.dto.RunnerProtocolCapabilities;
import com.example.demo.dto.SandboxComparison;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowsHyperVContainerRunnerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    Path tempDir;

    @Test
    void probeReturnsExplicitSkipReasonWhenWindowsContainerRuntimeIsUnavailable() {
        RecordingExecutor executor = RecordingExecutor.unavailable("docker executable is missing");
        WindowsHyperVContainerRunner runner = new WindowsHyperVContainerRunner(options(), executor, objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        assertThat(capabilities.provider()).isEqualTo("windows-container");
        assertThat(capabilities.productionSafe()).isFalse();
        assertThat(capabilities.skipReason()).contains("container runtime unavailable");
        assertThat(capabilities.details()).contains("docker");
    }

    @Test
    void probeRequiresWindowsEngineImageHyperVNetworkNoneJobObjectAndCleanup() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("version"), result(0, "windows|25.0.0", ""));
        executor.when(command -> command.contains("image") && command.contains("inspect"), result(0, "windows|amd64", ""));
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "win-container-1", ""));
        executor.when(command -> command.contains("inspect"), result(0, "hyperv|none|268435456|0", ""));
        executor.when(command -> command.contains("logs"), result(0, """
                PROBE_JOB_OBJECT=enabled
                PROBE_PROCESS_TREE_KILL=enabled
                PROBE_OUTPUT_LIMIT=enabled
                """, ""));
        executor.when(command -> command.contains("rm") && command.contains("-f"), result(0, "", ""));
        WindowsHyperVContainerRunner runner = new WindowsHyperVContainerRunner(options(), executor, objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        assertThat(capabilities.productionSafe()).isTrue();
        assertThat(capabilities.isolation()).isEqualTo("hyperv");
        assertThat(capabilities.networkDisabled()).isTrue();
        assertThat(capabilities.resourceLimits()).isTrue();
        assertThat(capabilities.securityProfile()).contains("job-object");
        assertThat(capabilities.details()).contains("containerId=win-container-1").contains("cleanup=confirmed");

        List<String> command = executor.commandContaining("run", "-d");
        assertThat(command)
                .startsWith("docker")
                .contains("--isolation", "hyperv")
                .contains("--network", "none")
                .contains("--memory", "268435456")
                .contains("--cpus", "1")
                .contains("--user", "ContainerUser")
                .contains("cpp-judge-runner-windows:test")
                .contains("C:\\judge-runner\\probe.cmd");
    }

    @Test
    void probeFailsClosedWhenRuntimeReportsProcessIsolationOrNetwork() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("version"), result(0, "windows|25.0.0", ""));
        executor.when(command -> command.contains("image") && command.contains("inspect"), result(0, "windows|amd64", ""));
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "win-container-2", ""));
        executor.when(command -> command.contains("inspect"), result(0, "process|nat|0|0", ""));
        executor.when(command -> command.contains("logs"), result(0, """
                PROBE_JOB_OBJECT=missing
                PROBE_PROCESS_TREE_KILL=missing
                PROBE_OUTPUT_LIMIT=missing
                """, ""));
        executor.when(command -> command.contains("rm") && command.contains("-f"), result(0, "", ""));
        WindowsHyperVContainerRunner runner = new WindowsHyperVContainerRunner(options(), executor, objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        assertThat(capabilities.productionSafe()).isFalse();
        assertThat(capabilities.networkDisabled()).isFalse();
        assertThat(capabilities.resourceLimits()).isFalse();
        assertThat(capabilities.skipReason())
                .contains("hyper-v")
                .contains("network")
                .contains("memory")
                .contains("job object");
    }

    @Test
    void startLaunchesDetachedHyperVContainerWithLimitsAndNoHostFallback() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "win-run-123\n", ""));
        WindowsHyperVContainerRunner runner = new WindowsHyperVContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();

        SandboxRunHandle handle = runner.start(spec);

        assertThat(handle.provider()).isEqualTo("windows-container");
        assertThat(handle.runId()).isEqualTo("win-run-123");
        assertThat(handle.eventCursor()).isEqualTo("0");
        assertThat(Files.readString(Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/task-spec.json")))
                .contains("\"judgeId\":\"judge-windows\"")
                .contains("\"protocolVersion\":2")
                .doesNotContain(spec.workDir(), "workDir", "userId");

        List<String> command = executor.commandContaining("run", "-d");
        assertThat(command)
                .startsWith("docker")
                .contains("--isolation", "hyperv")
                .contains("--network", "none")
                .contains("--memory", String.valueOf(spec.memoryLimitBytes()))
                .contains("--cpus", "1")
                .contains("--env", "JUDGE_JOB_OBJECT_REQUIRED=true")
                .contains("--env", "JUDGE_MAX_OUTPUT_BYTES_PER_CASE=" + spec.maxOutputBytesPerCase())
                .contains("--mount", "type=bind,source=" + Path.of(spec.workDir()) + ",target=C:\\work")
                .contains("cpp-judge-runner-windows:test")
                .contains("C:\\judge-runner\\run-task.exe")
                .contains("C:\\work\\control\\attempts\\" + handle.attemptId() + "\\task-spec.json");
        assertThat(command)
                .doesNotContain("g++")
                .doesNotContain(Path.of(spec.sourcePaths().get(SandboxTaskSpec.SourceRole.USER)).toString());
    }

    @Test
    void pollEventsReadsJsonLinesFromMountedWorkDirAndCancelRemovesContainer() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("run") && command.contains("-d"), result(0, "win-run-456", ""));
        executor.when(command -> command.contains("rm") && command.contains("-f"), result(0, "", ""));
        WindowsHyperVContainerRunner runner = new WindowsHyperVContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        SandboxRunHandle handle = runner.start(spec);
        SandboxTaskEvent completed = SandboxTaskEvent.of("judge-windows", SandboxTaskEvent.Type.COMPLETED, "completed");
        Files.writeString(Path.of(spec.workDir()).resolve("control/attempts/" + handle.attemptId() + "/runner-events.jsonl"), objectMapper.writeValueAsString(completed) + System.lineSeparator());

        List<SandboxTaskEvent> firstPoll = runner.pollEvents(handle);
        List<SandboxTaskEvent> secondPoll = runner.pollEvents(handle);
        runner.cancel(handle);

        assertThat(firstPoll).singleElement().satisfies(event -> {
            assertThat(event.judgeId()).isEqualTo("judge-windows");
            assertThat(event.type()).isEqualTo(SandboxTaskEvent.Type.COMPLETED);
        });
        assertThat(secondPoll).isEmpty();
        assertThat(executor.commandContaining("rm", "-f")).contains("win-run-456");
    }

    @Test
    void startRejectsSourcePathsThatWouldNotBeInsideTheMountedWorkDir() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        WindowsHyperVContainerRunner runner = new WindowsHyperVContainerRunner(options(), executor, objectMapper);
        Path storageBase = tempDir.resolve("storage").toAbsolutePath().normalize();
        Path workDir = storageBase.resolve("judge-windows");
        Files.createDirectories(workDir);
        Path outsideSource = storageBase.resolve("shared.cpp");
        Files.writeString(outsideSource, "int main(){return 0;}");

        SandboxTaskSpec spec = SandboxTaskSpec.builder()
                .judgeId("judge-windows")
                .userId("user-windows")
                .profile("windows-prod")
                .storageBase(storageBase)
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.USER, outsideSource)
                .testCases(1)
                .caseTimeLimit(Duration.ofSeconds(1))
                .maxTaskRuntime(Duration.ofSeconds(10))
                .memoryLimitBytes(128L * 1024 * 1024)
                .maxOutputBytesPerCase(1024)
                .build();

        assertThatThrownBy(() -> runner.start(spec))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mounted workDir");
    }

    @Test
    void liveProbeSkipsWithEnvironmentReasonWhenHyperVRuntimeOrImageIsUnavailable() {
        WindowsHyperVContainerRunner runner = WindowsHyperVContainerRunner.withDefaults(objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        Assumptions.assumeTrue(
                capabilities.productionSafe(),
                "Windows Hyper-V container capability unavailable: " + capabilities.skipReason()
        );
        assertThat(capabilities.isolation()).isEqualTo("hyperv");
        assertThat(capabilities.networkDisabled()).isTrue();
        assertThat(capabilities.resourceLimits()).isTrue();
        assertThat(capabilities.securityProfile()).contains("job-object");
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
        WindowsHyperVContainerRunner runner = new WindowsHyperVContainerRunner(options(), executor, objectMapper);
        SandboxTaskSpec spec = validSpec();
        Path workDir = Path.of(spec.workDir());
        Files.writeString(workDir.resolve("events.jsonl"), "host audit");

        assertThatThrownBy(() -> runner.start(spec)).isInstanceOf(IllegalArgumentException.class);

        assertThat(workDir.resolve("control")).doesNotExist();
        assertThat(Files.readString(workDir.resolve("events.jsonl"))).isEqualTo("host audit");
        assertThat(executor.commands).noneMatch(command -> command.contains("-d"));
    }

    private WindowsHyperVContainerRunner.Options options() {
        return WindowsHyperVContainerRunner.Options.builder()
                .runtimeCommand("docker")
                .image("cpp-judge-runner-windows:test")
                .containerUser("ContainerUser")
                .workMount("C:\\work")
                .runnerCommand("C:\\judge-runner\\run-task.exe")
                .probeCommand("C:\\judge-runner\\probe.cmd")
                .taskSpecFile("task-spec.json")
                .eventFile("runner-events.jsonl")
                .cpus(1.0)
                .probeMemoryBytes(256L * 1024 * 1024)
                .securityProfile("hyper-v;job-object")
                .commandTimeout(Duration.ofSeconds(5))
                .build();
    }

    private SandboxTaskSpec validSpec() throws IOException {
        Path storageBase = tempDir.resolve("storage").toAbsolutePath().normalize();
        Path workDir = storageBase.resolve("judge-windows");
        Files.createDirectories(workDir);
        Path generator = workDir.resolve("generator.cpp");
        Path user = workDir.resolve("user.cpp");
        Path oracle = workDir.resolve("oracle.cpp");
        Files.writeString(generator, "int main(){return 0;}");
        Files.writeString(user, "int main(){return 0;}");
        Files.writeString(oracle, "int main(){return 0;}");
        return SandboxTaskSpec.builder()
                .judgeId("judge-windows")
                .userId("user-windows")
                .profile("windows-prod")
                .storageBase(storageBase)
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, generator)
                .sourcePath(SandboxTaskSpec.SourceRole.USER, user)
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, oracle)
                .comparison(RunnerProtocolFixtures.legacyText())
                .testCases(2)
                .caseTimeLimit(Duration.ofSeconds(1))
                .maxTaskRuntime(Duration.ofSeconds(10))
                .memoryLimitBytes(128L * 1024 * 1024)
                .maxOutputBytesPerCase(4096)
                .build();
    }

    private static WindowsHyperVContainerRunner.CommandResult result(int exitCode, String stdout, String stderr) {
        return new WindowsHyperVContainerRunner.CommandResult(exitCode, stdout, stderr);
    }

    private static final class RecordingExecutor implements WindowsHyperVContainerRunner.CommandExecutor {
        private final List<Invocation> stubs = new ArrayList<>();
        private final List<List<String>> commands = new ArrayList<>();
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

        void when(Predicate<List<String>> predicate, WindowsHyperVContainerRunner.CommandResult result) {
            stubs.add(new Invocation(predicate, result));
        }

        @Override
        public WindowsHyperVContainerRunner.CommandResult run(List<String> command, Duration timeout) throws IOException {
            commands.add(List.copyOf(command));
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

        private record Invocation(Predicate<List<String>> predicate, WindowsHyperVContainerRunner.CommandResult result) {
        }
    }
}
