package com.example.demo;

import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.sandbox.SandboxRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxRunnerContractTest {

    @TempDir
    Path tempDir;

    @Test
    void sandboxTaskSpecCannotBeBuiltWithoutIdentityWorkDirCasesAndLimits() {
        Path storageBase = tempDir.resolve("storage").toAbsolutePath().normalize();

        assertThatThrownBy(() -> validSpec(storageBase).judgeId("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("judgeId");
        assertThatThrownBy(() -> validSpec(storageBase).userId("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
        assertThatThrownBy(() -> validSpec(storageBase).workDir(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workDir");
        assertThatThrownBy(() -> validSpec(storageBase).testCases(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("testCases");
        assertThatThrownBy(() -> validSpec(storageBase).caseTimeLimit(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caseTimeLimit");
        assertThatThrownBy(() -> validSpec(storageBase).memoryLimitBytes(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryLimitBytes");
        assertThatThrownBy(() -> validSpec(storageBase).maxOutputBytesPerCase(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputBytesPerCase");
    }

    @Test
    void sandboxTaskSpecNormalizesWorkDirAndRejectsTraversalOrStorageEscape() {
        Path storageBase = tempDir.resolve("storage").toAbsolutePath().normalize();
        Path workDir = storageBase.resolve("judge-valid");

        SandboxTaskSpec spec = validSpec(storageBase).build();

        assertThat(spec.workDir()).isEqualTo(workDir.toString());
        assertThat(spec.sourcePaths())
                .containsEntry(SandboxTaskSpec.SourceRole.GENERATOR, workDir.resolve("generator.cpp").toString())
                .containsEntry(SandboxTaskSpec.SourceRole.USER, workDir.resolve("user.cpp").toString())
                .containsEntry(SandboxTaskSpec.SourceRole.ORACLE, workDir.resolve("oracle.cpp").toString());

        assertThatThrownBy(() -> validSpec(storageBase).workDir(Path.of("..", "outside")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workDir must be absolute");

        assertThatThrownBy(() -> validSpec(storageBase).workDir(storageBase.resolveSibling("outside")).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storage base");
    }

    @Test
    void sandboxTaskEventTypesCoverCompileRunSummaryCancellationBudgetAndSecurityFailures() {
        assertThat(SandboxTaskEvent.Type.values()).contains(
                SandboxTaskEvent.Type.COMPILE_STARTED,
                SandboxTaskEvent.Type.COMPILE_FINISHED,
                SandboxTaskEvent.Type.RUN_STARTED,
                SandboxTaskEvent.Type.RUN_FINISHED,
                SandboxTaskEvent.Type.SUMMARY,
                SandboxTaskEvent.Type.CANCELLED,
                SandboxTaskEvent.Type.BUDGET_EXCEEDED,
                SandboxTaskEvent.Type.SECURITY_VIOLATION,
                SandboxTaskEvent.Type.SYSTEM_ERROR,
                SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE
        );
    }

    @Test
    void sandboxDtoJsonSerializationIsStableAndDoesNotExposeSecretsOrRawCommands() throws Exception {
        Path storageBase = tempDir.resolve("storage").toAbsolutePath().normalize();
        SandboxTaskSpec spec = validSpec(storageBase)
                .judgeId("judge-json")
                .userId("user-json")
                .profile("linux-prod")
                .build();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        String json = objectMapper.writeValueAsString(spec);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.path("judgeId").asText()).isEqualTo("judge-json");
        assertThat(node.path("userId").asText()).isEqualTo("user-json");
        assertThat(node.path("profile").asText()).isEqualTo("linux-prod");
        assertThat(node.path("sourcePaths").path("GENERATOR").asText()).endsWith("generator.cpp");
        assertThat(json)
                .doesNotContain("secret")
                .doesNotContain("token")
                .doesNotContain("password")
                .doesNotContain("command");
    }

    @Test
    void sandboxCapabilitiesAndRunHandleSerializeWithoutCredentials() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SandboxCapabilities capabilities = SandboxCapabilities.builder()
                .provider("linux-container")
                .isolation("container")
                .productionSafe(true)
                .networkDisabled(true)
                .nonRoot(true)
                .resourceLimits(true)
                .securityProfile("seccomp:judge-linux")
                .details("cgroup-v2")
                .build();
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId("judge-1")
                .runId("run-1")
                .provider("linux-container")
                .eventCursor("0")
                .build();

        String json = objectMapper.writeValueAsString(List.of(capabilities, handle));

        assertThat(json)
                .contains("linux-container")
                .contains("productionSafe")
                .contains("run-1")
                .doesNotContain("secret")
                .doesNotContain("token")
                .doesNotContain("password");
    }

    @Test
    void sandboxRunnerInterfaceExposesProbeStartEventsAndCancelContract() throws Exception {
        assertThat(SandboxRunner.class.getMethod("probe").getReturnType()).isEqualTo(SandboxCapabilities.class);
        assertThat(SandboxRunner.class.getMethod("start", SandboxTaskSpec.class).getReturnType()).isEqualTo(SandboxRunHandle.class);
        assertThat(SandboxRunner.class.getMethod("pollEvents", SandboxRunHandle.class).getReturnType()).isEqualTo(List.class);
        assertThat(SandboxRunner.class.getMethod("cancel", SandboxRunHandle.class).getReturnType()).isEqualTo(Void.TYPE);
    }

    private SandboxTaskSpec.Builder validSpec(Path storageBase) {
        Path workDir = storageBase.resolve("judge-valid");
        return SandboxTaskSpec.builder()
                .judgeId("judge-valid")
                .userId("user-valid")
                .profile("trusted-local")
                .storageBase(storageBase)
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, workDir.resolve("generator.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.USER, workDir.resolve("user.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, workDir.resolve("oracle.cpp"))
                .testCases(10)
                .caseTimeLimit(Duration.ofSeconds(2))
                .maxTaskRuntime(Duration.ofMinutes(30))
                .memoryLimitBytes(256L * 1024 * 1024)
                .maxOutputBytesPerCase(1024 * 1024)
                .retention(new SandboxTaskSpec.Retention(Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(24)));
    }
}
