package com.example.demo;

import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.dto.JudgeSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.example.demo.service.sandbox.SandboxRunner;
import com.example.demo.service.sandbox.LinuxContainerRunner;
import com.example.demo.service.sandbox.WindowsHyperVContainerRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxRunnerContractTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({"LinuxContainerRunner,complete", "WindowsHyperVContainerRunner,complete",
            "LinuxContainerRunner,sleep", "WindowsHyperVContainerRunner,sleep",
            "LinuxContainerRunner,inherited-pipe", "WindowsHyperVContainerRunner,inherited-pipe"})
    void runtimeCliWaitAndCaptureShareTheSameDeadline(String provider, String mode) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(Path.of("/proc")),
                "POSIX child-pipe fixture requires Linux");
        Path script = tempDir.resolve("runtime-client.py");
        Path parentPid = tempDir.resolve("parent.pid");
        Path childPid = tempDir.resolve("child.pid");
        java.nio.file.Files.writeString(script, """
                import os, sys, time
                from pathlib import Path
                pid_file = Path(sys.argv[2])
                pid_file.write_text(str(os.getpid()))
                if sys.argv[1] == 'complete':
                    print('ready😀', flush=True)
                    print('diagnostic', file=sys.stderr, flush=True)
                elif sys.argv[1] == 'sleep':
                    time.sleep(20)
                elif os.fork() == 0:
                    pid_file.with_name('child.pid').write_text(str(os.getpid()))
                    time.sleep(20)
                else:
                    os._exit(0)
                """);
        Class<?> executorType = Class.forName("com.example.demo.service.sandbox." + provider + "$ProcessCommandExecutor");
        var constructor = executorType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object executor = constructor.newInstance();
        var execute = executorType.getMethod("run", List.class, Duration.class);
        execute.setAccessible(true);
        List<String> command = List.of(PythonInterpreter.resolve(), "-B", script.toString(),
                mode, parentPid.toString());
        Duration timeout = mode.equals("complete") ? Duration.ofSeconds(2) : Duration.ofMillis(400);
        long started = System.nanoTime();
        try {
            if (mode.equals("complete")) {
                Object result = execute.invoke(executor, command, timeout);
                assertThat((Integer) org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "exitCode")).isZero();
                assertThat((String) org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "stdout")).isEqualTo("ready😀\n");
                assertThat((String) org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "stderr")).isEqualTo("diagnostic\n");
            } else {
                assertThatThrownBy(() -> execute.invoke(executor, command, timeout))
                        .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                        .hasCauseInstanceOf(java.io.IOException.class)
                        .hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
                assertThat(System.nanoTime() - started).isLessThan(timeout.plusSeconds(1).toNanos());
                assertThat(parentPid).exists();
                if (mode.equals("inherited-pipe")) {
                    assertThat(childPid).exists();
                }
            }
        } finally {
            for (Path pidFile : List.of(parentPid, childPid)) {
                if (java.nio.file.Files.exists(pidFile)) {
                    ProcessHandle.of(Long.parseLong(java.nio.file.Files.readString(pidFile))).filter(process ->
                            process.info().commandLine().orElse("").contains(script.toString()))
                            .ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        }
    }

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
                .controlPlaneIsolated(true)
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
                .contains("controlPlaneIsolated")
                .contains("run-1")
                .doesNotContain("secret")
                .doesNotContain("token")
                .doesNotContain("password");
    }

    @Test
    void sandboxRunnerInterfaceExposesProbeStartEventsAndCancelContract() throws Exception {
        assertThat(SandboxRunner.class.getMethod("probe").getReturnType()).isEqualTo(SandboxCapabilities.class);
        assertThat(SandboxRunner.class.getMethod("start", SandboxTaskSpec.class).getReturnType()).isEqualTo(SandboxRunHandle.class);
        assertThat(SandboxRunner.class.getMethod("start", SandboxTaskSpec.class, Duration.class).getReturnType()).isEqualTo(SandboxRunHandle.class);
        assertThat(SandboxRunner.class.getMethod("pollEvents", SandboxRunHandle.class).getReturnType()).isEqualTo(List.class);
        assertThat(SandboxRunner.class.getMethod("pollEvents", SandboxRunHandle.class, java.time.Duration.class).getReturnType())
                .isEqualTo(List.class);
        assertThat(SandboxRunner.class.getMethod("pollEvents", SandboxRunHandle.class, java.time.Duration.class).isDefault()).isTrue();
        assertThat(SandboxRunner.class.getMethod("executionState", SandboxRunHandle.class, java.time.Duration.class).getReturnType())
                .isEqualTo(SandboxRunner.ExecutionState.class);
        assertThat(SandboxRunner.class.getMethod("executionState", SandboxRunHandle.class, java.time.Duration.class).isDefault()).isTrue();
        assertThat(SandboxRunner.class.getMethod("cancel", SandboxRunHandle.class).getReturnType()).isEqualTo(Void.TYPE);
        assertThat(SandboxRunner.class.getMethod("cancel", SandboxRunHandle.class, Duration.class).getReturnType()).isEqualTo(Void.TYPE);
        assertThat(SandboxRunner.class.getMethod("cleanupResidual", SandboxRunHandle.class, Duration.class).getReturnType())
                .isEqualTo(Void.TYPE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void containerCancellationAndCleanupUseTheCallersBudgetWithoutClaimingConfirmedStop(boolean windows) {
        java.util.concurrent.atomic.AtomicInteger externalCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Duration> receivedBudget = new java.util.concurrent.atomic.AtomicReference<>();
        SandboxRunner runner = windows
                ? new WindowsHyperVContainerRunner(WindowsHyperVContainerRunner.Options.builder().build(), (command, timeout) -> {
                    externalCalls.incrementAndGet();
                    receivedBudget.set(timeout);
                    return new WindowsHyperVContainerRunner.CommandResult(0, "", "");
                }, new ObjectMapper())
                : new LinuxContainerRunner(LinuxContainerRunner.Options.builder().build(), (command, timeout) -> {
                    externalCalls.incrementAndGet();
                    receivedBudget.set(timeout);
                    return new LinuxContainerRunner.CommandResult(0, "", "");
                }, new ObjectMapper());
        SandboxRunHandle handle = SandboxRunHandle.builder().judgeId("cleanup-budget").runId("cleanup-run")
                .provider(windows ? "windows-container" : "linux-container").protocolVersion(2)
                .attemptId(java.util.UUID.randomUUID().toString()).build();
        Duration budget = Duration.ofMillis(100);
        runner.cancel(handle, budget);
        assertThat(receivedBudget.get()).isEqualTo(budget);
        assertThatThrownBy(() -> runner.cleanupResidual(handle, budget))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stop is not confirmed");
        assertThat(receivedBudget.get()).isEqualTo(budget);
        assertThat(externalCalls).hasValue(2);
        assertThatThrownBy(() -> runner.cancel(handle, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(externalCalls).hasValue(2);
    }

    @Test
    void omittedControlPlaneCapabilityDefaultsToFalse() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        SandboxCapabilities legacy = objectMapper.readValue("{\"provider\":\"linux-container\",\"productionSafe\":true}",
                SandboxCapabilities.class);
        assertThat(legacy.controlPlaneIsolated()).isFalse();
        assertThat(SandboxCapabilities.builder().productionSafe(true).build().controlPlaneIsolated()).isFalse();
        SandboxCapabilities isolated = SandboxCapabilities.builder().provider("linux-container")
                .controlPlaneIsolated(true).build();
        assertThat(objectMapper.readValue(objectMapper.writeValueAsString(isolated), SandboxCapabilities.class))
                .isEqualTo(isolated);
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

    @ParameterizedTest
    @ValueSource(strings = {"fractional-version", "string-version", "fractional-sequence", "string-sequence",
            "fractional-case", "string-case", "fractional-result-case", "fractional-time", "string-time", "null-time",
            "fractional-summary", "string-summary", "numeric-type", "string-type", "unknown-type", "numeric-id", "boolean-id",
            "duplicate-key", "trailing-json"})
    void runnerEventDecoderRejectsLossyOrAmbiguousPayloads(String shape) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode event = validEventJson(objectMapper);
        ObjectNode result = (ObjectNode) event.get("result");
        switch (shape) {
            case "fractional-version" -> event.put("protocolVersion", 2.5);
            case "string-version" -> event.put("protocolVersion", "2");
            case "fractional-sequence" -> event.put("sequence", 1.5);
            case "string-sequence" -> event.put("sequence", "1");
            case "fractional-case" -> event.put("caseNumber", 1.5);
            case "string-case" -> event.put("caseNumber", "1");
            case "fractional-result-case" -> result.put("caseNumber", 1.5);
            case "fractional-time" -> result.put("timeUsed", 1.5);
            case "string-time" -> result.put("timeUsed", "1");
            case "null-time" -> result.putNull("timeUsed");
            case "fractional-summary", "string-summary" -> {
                ObjectNode summary = objectMapper.valueToTree(new JudgeSummary(1, 1, 1, 0, 0, 0, 0, 0, 0,
                        null, List.of(), List.of(), null));
                if ("fractional-summary".equals(shape)) {
                    summary.put("ac", 1.5);
                } else {
                    summary.put("ac", "1");
                }
                event.set("summary", summary);
            }
            case "numeric-type" -> event.put("type", 0);
            case "string-type" -> event.put("type", "0");
            case "unknown-type" -> event.put("type", "UNKNOWN");
            case "numeric-id" -> event.put("judgeId", 7);
            case "boolean-id" -> event.put("judgeId", true);
            case "duplicate-key", "trailing-json" -> { }
            default -> throw new IllegalArgumentException("unknown test shape");
        }
        String json = objectMapper.writeValueAsString(event);
        if ("duplicate-key".equals(shape)) {
            json = json.replace("\"protocolVersion\":2", "\"protocolVersion\":1,\"protocolVersion\":2");
        } else if ("trailing-json".equals(shape)) {
            json += "{}";
        }
        String payload = json;

        assertThatThrownBy(() -> SandboxTaskEvent.jsonReader(objectMapper).readValue(payload))
                .isInstanceOf(JsonProcessingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"result.caseNumber", "result.status", "result.timeUsed", "result.memoryUsed",
            "summary.totalCases", "summary.completedCases", "summary.ac", "summary.wa", "summary.tle", "summary.mle",
            "summary.re", "summary.systemError", "summary.outputLimitExceeded"})
    void runnerEventDecoderRejectsMissingCaseAndSummaryFields(String field) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode event = validEventJson(objectMapper);
        String[] path = field.split("\\.");
        if ("summary".equals(path[0])) {
            event.set("summary", objectMapper.valueToTree(new JudgeSummary(1, 1, 1, 0, 0, 0, 0, 0, 0,
                    null, List.of(), List.of(), null)));
        }
        ((ObjectNode) event.get(path[0])).remove(path[1]);
        String payload = objectMapper.writeValueAsString(event);

        assertThatThrownBy(() -> SandboxTaskEvent.jsonReader(objectMapper).readValue(payload))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void runnerEventDecoderPreservesExactValuesAndDoesNotMutateTheApplicationMapper() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode event = validEventJson(objectMapper);
        event.put("sequence", Long.MAX_VALUE);
        String json = objectMapper.writeValueAsString(event);

        SandboxTaskEvent decoded = SandboxTaskEvent.jsonReader(objectMapper).readValue(json);
        List<SandboxTaskEvent> batch = SandboxTaskEvent.jsonReader(objectMapper)
                .forType(new TypeReference<List<SandboxTaskEvent>>() { }).readValue("[" + json + "]");

        assertThat(decoded.protocolVersion()).isEqualTo(2);
        assertThat(decoded.sequence()).isEqualTo(Long.MAX_VALUE);
        assertThat(decoded.caseNumber()).isEqualTo(1);
        assertThat(decoded.result().getTimeUsed()).isEqualTo(1);
        assertThat(batch).containsExactly(decoded);
        event.put("protocolVersion", 2.5);
        assertThat(objectMapper.readValue(objectMapper.writeValueAsString(event), SandboxTaskEvent.class)
                .protocolVersion()).isEqualTo(2);
    }

    @Test
    void runnerEventDecoderAcceptsExplicitZeroCountersAndKeepsApplicationDefaults() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode event = validEventJson(objectMapper);
        ObjectNode result = (ObjectNode) event.get("result");
        result.put("timeUsed", 0).put("memoryUsed", 0);
        ObjectNode summary = objectMapper.valueToTree(new JudgeSummary(1, 1, 1, 0, 0, 0, 0, 0, 0,
                null, List.of(), List.of(), null));
        summary.remove(List.of("firstFailedCase", "stoppedReason"));
        event.set("summary", summary);

        SandboxTaskEvent decoded = SandboxTaskEvent.jsonReader(objectMapper)
                .readValue(objectMapper.writeValueAsString(event));

        assertThat(decoded.result().getTimeUsed()).isZero();
        assertThat(decoded.result().getMemoryUsed()).isZero();
        assertThat(decoded.summary().getOutputLimitExceeded()).isZero();
        assertThat(decoded.summary().getFailureSamples()).isEmpty();
        assertThat(decoded.summary().getFirstFailedCase()).isNull();
        assertThat(decoded.summary().getStoppedReason()).isNull();
        summary.remove("outputLimitExceeded");
        result.remove("status");
        SandboxTaskEvent applicationEvent = objectMapper.readValue(objectMapper.writeValueAsString(event),
                SandboxTaskEvent.class);
        assertThat(applicationEvent.summary().getOutputLimitExceeded()).isZero();
        assertThat(applicationEvent.result().getStatus()).isNull();
    }

    private ObjectNode validEventJson(ObjectMapper objectMapper) {
        ObjectNode event = objectMapper.createObjectNode().put("judgeId", "judge-json")
                .put("type", "RUN_FINISHED").put("caseNumber", 1).put("status", "AC")
                .put("protocolVersion", 2).put("attemptId", "00000000-0000-0000-0000-000000000001")
                .put("sequence", 1);
        event.set("result", objectMapper.createObjectNode().put("caseNumber", 1).put("status", "AC")
                .put("timeUsed", 1).put("memoryUsed", 512));
        return event;
    }
}
