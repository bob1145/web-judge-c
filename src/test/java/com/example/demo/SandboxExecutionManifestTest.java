package com.example.demo;

import com.example.demo.dto.RunnerProtocolCapabilities;
import com.example.demo.dto.SandboxComparison;
import com.example.demo.dto.SandboxExecutionManifest;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.sandbox.SandboxManifestFactory;
import com.example.demo.support.RunnerProtocolFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxExecutionManifestTest {
    @TempDir
    Path root;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void wireManifestKeepsDecimalStringAndExplicitUnitsWithoutChangingHostPaths() throws Exception {
        SandboxTaskSpec spec = spec().comparison(SandboxComparison.decimal("1.0000000000000000000000001E-9")).build();
        SandboxExecutionManifest manifest = SandboxManifestFactory.prepare(spec).manifest();
        String json = mapper.writeValueAsString(manifest);
        assertThat(manifest.caseTimeLimitMillis()).isEqualTo(1250);
        assertThat(manifest.retention().completedMillis()).isEqualTo(86400000);
        assertThat(manifest.sourcePaths()).containsEntry(SandboxTaskSpec.SourceRole.USER, "user.cpp");
        assertThat(manifest.comparison().precision()).isEqualTo("1.0000000000000000000000001E-9");
        assertThat(spec.workDir()).isEqualTo(root.toString());
        assertThat(spec.sourcePaths().get(SandboxTaskSpec.SourceRole.USER)).isEqualTo(root.resolve("user.cpp").toString());
        assertThat(json).doesNotContain(root.toString(), "workDir", "userId", "profile");
        assertThat(mapper.readValue(json, SandboxExecutionManifest.class)).isEqualTo(manifest);
        assertThat(root.resolve("control")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/outside.cpp", "../outside.cpp", "a/../user.cpp", "C:/user.cpp", "C:user.cpp",
            "\\\\server\\share\\user.cpp", "//server/share/user.cpp", "a\\user.cpp", "./user.cpp", "a//user.cpp",
            "", "control/user.cpp", "Control/user.cpp", "workspace/user.cpp", "NUL.cpp", "user.cpp.", "user.cpp ", "user.cpp:stream"})
    void wireRejectsNonPortableOrEscapingSourcePaths(String path) throws Exception {
        ObjectNode manifest = mapper.valueToTree(SandboxManifestFactory.prepare(spec().build()).manifest());
        ((ObjectNode) manifest.get("sourcePaths")).put("USER", path);
        assertThatThrownBy(() -> mapper.treeToValue(manifest, SandboxExecutionManifest.class)).isInstanceOf(Exception.class);
        assertThat(root.resolve("control")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"protocolVersion", "judgeId", "attemptId", "sourcePaths", "testCases", "comparison",
            "caseTimeLimitMillis", "maxTaskRuntimeMillis", "memoryLimitBytes", "maxOutputBytesPerCase",
            "stopOnFirstNonAc", "retention"})
    void wireRejectsMissingRequiredFields(String field) throws Exception {
        ObjectNode manifest = mapper.valueToTree(SandboxManifestFactory.prepare(spec().build()).manifest());
        manifest.remove(field);
        assertThatThrownBy(() -> mapper.treeToValue(manifest, SandboxExecutionManifest.class)).isInstanceOf(Exception.class);
    }

    @Test
    void unknownVersionAndCapabilityOmissionsFailClosed() throws Exception {
        SandboxExecutionManifest manifest = SandboxManifestFactory.prepare(spec().build()).manifest();
        ObjectNode node = mapper.valueToTree(manifest);
        node.put("protocolVersion", 99);
        assertThatThrownBy(() -> mapper.treeToValue(node, SandboxExecutionManifest.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> new RunnerProtocolCapabilities(Set.of(1), Set.of(SandboxComparison.Mode.DECIMAL_ABSOLUTE),
                RunnerProtocolCapabilities.REQUIRED_FEATURES).require(manifest)).isInstanceOf(IllegalArgumentException.class);
        for (String omitted : RunnerProtocolCapabilities.REQUIRED_FEATURES) {
            var features = new java.util.HashSet<>(RunnerProtocolCapabilities.REQUIRED_FEATURES);
            features.remove(omitted);
            assertThatThrownBy(() -> new RunnerProtocolCapabilities(Set.of(2),
                    Set.of(SandboxComparison.Mode.DECIMAL_ABSOLUTE), features).require(manifest))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        RunnerProtocolCapabilities python = mapper.readValue(RunnerProtocolFixtures.PYTHON_CAPABILITIES,
                RunnerProtocolCapabilities.class);
        assertThatThrownBy(() -> python.require(manifest)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostRejectsOtherTaskAndLinkedSourcesBeforeCreatingAnyControlFiles() throws Exception {
        SandboxTaskSpec.Builder spec = spec();
        Path otherTask = Files.createDirectory(root.resolve("other-task"));
        Path task = Files.createDirectory(root.resolve("this-task"));
        Path otherSource = Files.writeString(otherTask.resolve("user.cpp"), "outside");
        assertThatThrownBy(() -> SandboxManifestFactory.prepare(spec.workDir(task)
                .sourcePath(SandboxTaskSpec.SourceRole.USER, otherSource).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workDir");
        assertThat(task.resolve("control")).doesNotExist();
        Path linked = root.resolve("linked.cpp");
        Files.createSymbolicLink(linked, otherSource);
        assertThatThrownBy(() -> SandboxManifestFactory.prepare(spec().sourcePath(SandboxTaskSpec.SourceRole.USER, linked).build()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("links");
        assertThat(Files.readString(otherSource)).isEqualTo("outside");
        assertThat(root.resolve("control")).doesNotExist();
    }

    @Test
    void hostRejectsLinkedControlParentBeforeWritingOutsideRoot() throws Exception {
        SandboxTaskSpec spec = spec().build();
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(root.resolve("control"), outside);
        assertThatThrownBy(() -> SandboxManifestFactory.prepare(spec)).isInstanceOf(IllegalArgumentException.class);
        try (var entries = Files.list(outside)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void attemptsAreUniqueAndCannotOverwriteAnExistingManifest() throws Exception {
        SandboxTaskSpec spec = spec().build();
        var first = SandboxManifestFactory.prepare(spec);
        first.write(mapper);
        byte[] before = Files.readAllBytes(root.resolve(first.manifest().specPath()));
        var second = SandboxManifestFactory.prepare(spec);
        second.write(mapper);
        assertThat(second.manifest().attemptId()).isNotEqualTo(first.manifest().attemptId());
        assertThatThrownBy(() -> first.write(mapper)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(root.resolve(first.manifest().specPath()))).isEqualTo(before);
    }

    @Test
    void fractionalMillisecondsCannotBeSilentlyRounded() throws Exception {
        SandboxTaskSpec spec = spec().caseTimeLimit(Duration.ofNanos(1)).build();
        assertThatThrownBy(() -> SandboxManifestFactory.prepare(spec)).isInstanceOf(IllegalArgumentException.class);
        assertThat(root.resolve("control")).doesNotExist();
    }

    private SandboxTaskSpec.Builder spec() throws Exception {
        for (String source : Set.of("generator.cpp", "user.cpp", "oracle.cpp")) {
            Files.writeString(root.resolve(source), "int main(){return 0;}");
        }
        return SandboxTaskSpec.builder().judgeId("manifest-test").userId("host-only-user")
                .workDir(root).storageBase(root)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, root.resolve("generator.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.USER, root.resolve("user.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, root.resolve("oracle.cpp"))
                .testCases(2).caseTimeLimit(Duration.ofMillis(1250)).maxTaskRuntime(Duration.ofSeconds(30))
                .memoryLimitBytes(134217728).maxOutputBytesPerCase(4096);
    }
}
