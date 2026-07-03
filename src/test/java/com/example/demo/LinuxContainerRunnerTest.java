package com.example.demo;

import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.sandbox.LinuxContainerRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
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

class LinuxContainerRunnerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @TempDir
    Path tempDir;

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

    @Test
    void probeRequiresLinuxEngineImageNetworkNoneNonRootCgroupAndSecurityProfile() {
        RecordingExecutor executor = new RecordingExecutor();
        executor.when(command -> command.contains("version"), result(0, "linux|25.0.0|2", ""));
        executor.when(command -> command.contains("image") && command.contains("inspect"), result(0, "linux|amd64", ""));
        executor.when(command -> command.contains("run") && command.contains("--rm"), result(0, """
                PROBE_UID=65532
                PROBE_NETWORK=disabled
                PROBE_CGROUP=present
                PROBE_SECCOMP=2
                PROBE_APPARMOR=judge-linux (enforce)
                """, ""));
        LinuxContainerRunner runner = new LinuxContainerRunner(options(), executor, objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        assertThat(capabilities.productionSafe()).isTrue();
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
        executor.when(command -> command.contains("run") && command.contains("--rm"), result(0, """
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
        assertThat(Files.readString(Path.of(spec.workDir()).resolve("sandbox-task.json")))
                .contains("\"judgeId\":\"judge-linux\"")
                .contains("\"profile\":\"linux-prod\"");

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
                .contains("/work/sandbox-task.json");
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
        Files.writeString(Path.of(spec.workDir()).resolve("events.jsonl"), objectMapper.writeValueAsString(completed) + System.lineSeparator());

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
                .profile("linux-prod")
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

    private LinuxContainerRunner.Options options() {
        return LinuxContainerRunner.Options.builder()
                .runtimeCommand("docker")
                .image("judge-runner:test")
                .containerUser("65532:65532")
                .workMount("/work")
                .runnerCommand("/opt/judge-runner/run-task")
                .taskSpecFile("sandbox-task.json")
                .eventFile("events.jsonl")
                .pidsLimit(64)
                .cpus(1.0)
                .securityProfile("seccomp:judge-linux,apparmor:judge-linux")
                .commandTimeout(Duration.ofSeconds(5))
                .build();
    }

    private SandboxTaskSpec validSpec() throws IOException {
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
                .profile("linux-prod")
                .storageBase(storageBase)
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, generator)
                .sourcePath(SandboxTaskSpec.SourceRole.USER, user)
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, oracle)
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
            if (unavailable != null) {
                throw unavailable;
            }
            return stubs.stream()
                    .filter(stub -> stub.predicate().test(command))
                    .findFirst()
                    .map(Invocation::result)
                    .orElseGet(() -> result(1, "", "unexpected command: " + command));
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
