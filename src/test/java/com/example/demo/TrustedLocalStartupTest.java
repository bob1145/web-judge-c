package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.TaskCleanupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedLocalStartupTest {

    @TempDir
    Path storageBase;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    @ParameterizedTest
    @EnumSource(value = JudgeStatus.class, names = {"AC", "WA", "SYSTEM_ERROR", "CANCELLED"})
    void completedLocalTasksSurviveRepeatedStartupWithoutChangingRecords(JudgeStatus status) throws Exception {
        FileTaskStore original = store();
        String judgeId = "completed-local";
        Path directory = createStartedTask(original, judgeId, "trusted-local");
        JudgeProgress committed = original.finalizeTask(judgeId, new JudgeProgress(status.name(), "finished", 100));
        Map<String, byte[]> before = snapshot(directory);

        for (int restart = 0; restart < 2; restart++) {
            FileTaskStore restarted = store();
            new TaskCleanupService(restarted, new ExecutionProperties()).reconcileAndCleanupOnStartup();

            JudgeTask task = restarted.find(judgeId).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(status);
            assertThat(task.getStartedAt()).isNotNull();
            assertThat(task.getSandboxRunHandle()).isNull();
            assertThat(restarted.findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(committed);
            assertUnchanged(directory, before);
        }
    }

    @ParameterizedTest
    @EnumSource(value = JudgeStatus.class, names = {"CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING"})
    void unfinishedLocalTasksStillRequireExecutionReconciliation(JudgeStatus status) throws Exception {
        FileTaskStore original = store();
        String judgeId = "unfinished-local";
        Path directory = original.taskDirectory(judgeId);
        original.create(task(judgeId, directory, "trusted-local", status));
        Map<String, byte[]> before = snapshot(directory);

        assertThatThrownBy(() -> store().markStaleRunningTasksOnStartup())
                .isInstanceOf(IOException.class).hasMessageContaining("Execution stop is unconfirmed");
        assertUnchanged(directory, before);
        assertThat(directory.resolve("final.json")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows-prod", "linux-prod", "worker-prod", "local-large"})
    void completedTasksFromOtherProfilesStillRequireTheirExecutionHandle(String profile) throws Exception {
        FileTaskStore original = store();
        String judgeId = "missing-handle";
        Path directory = createStartedTask(original, judgeId, profile);
        original.finalizeTask(judgeId, new JudgeProgress("WA", "finished", 100));
        Map<String, byte[]> before = snapshot(directory);

        assertThatThrownBy(() -> store().markStaleRunningTasksOnStartup())
                .isInstanceOf(IOException.class).hasMessageContaining("Execution stop is unconfirmed");
        assertUnchanged(directory, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "status", "revision", "finalizedAt", "attemptId"})
    void invalidOrMissingFinalCannotAuthorizeCompletedLocalRecovery(String damage) throws Exception {
        FileTaskStore original = store();
        String judgeId = "invalid-final";
        Path directory = createStartedTask(original, judgeId, "trusted-local");
        original.finalizeTask(judgeId, new JudgeProgress("WA", "finished", 100));
        Path finalFile = directory.resolve("final.json");
        if (damage.equals("missing")) {
            Files.move(finalFile, directory.resolve("final.saved.json"));
        } else {
            ObjectNode finalJson = (ObjectNode) objectMapper.readTree(finalFile.toFile());
            switch (damage) {
                case "status" -> finalJson.put("status", "RUNNING");
                case "attemptId" -> finalJson.put("attemptId", "foreign-attempt");
                default -> finalJson.remove(damage);
            }
            objectMapper.writeValue(finalFile.toFile(), finalJson);
        }
        Map<String, byte[]> before = snapshot(directory);

        assertThatThrownBy(() -> store().markStaleRunningTasksOnStartup()).isInstanceOf(IOException.class);
        assertUnchanged(directory, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mode", "policy"})
    void missingLocalProfileIdentityDoesNotUseTheLocalRecoveryPath(String field) throws Exception {
        FileTaskStore original = store();
        String judgeId = "missing-profile";
        Path directory = createStartedTask(original, judgeId, "trusted-local");
        original.finalizeTask(judgeId, new JudgeProgress("WA", "finished", 100));
        Path metadataFile = directory.resolve("metadata.json");
        ObjectNode metadata = (ObjectNode) objectMapper.readTree(metadataFile.toFile());
        metadata.remove(field);
        objectMapper.writeValue(metadataFile.toFile(), metadata);
        Map<String, byte[]> before = snapshot(directory);

        assertThatThrownBy(() -> store().markStaleRunningTasksOnStartup())
                .isInstanceOf(IOException.class).hasMessageContaining("Execution stop is unconfirmed");
        assertUnchanged(directory, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"control/attempts/retained/evidence.json", "control/linux-supervisor.lock"})
    void aLocalFinalNeverOverridesManagedExecutionEvidence(String evidenceName) throws Exception {
        FileTaskStore original = store();
        String judgeId = "retained-evidence";
        Path directory = createStartedTask(original, judgeId, "trusted-local");
        original.finalizeTask(judgeId, new JudgeProgress("WA", "finished", 100));
        Path evidence = directory.resolve(evidenceName);
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, "must remain");
        Map<String, byte[]> before = snapshot(directory);

        assertThatThrownBy(() -> store().markStaleRunningTasksOnStartup())
                .isInstanceOf(IOException.class).hasMessageContaining("requires trusted reconciliation");
        assertUnchanged(directory, before);
    }

    @Test
    void localFinalRepairsAnInterruptedProjectionWithoutChangingTheCommittedResult() throws Exception {
        FileTaskStore original = store();
        String judgeId = "interrupted-projection";
        Path directory = createStartedTask(original, judgeId, "trusted-local");
        byte[] runningMetadata = Files.readAllBytes(directory.resolve("metadata.json"));
        JudgeProgress committed = original.finalizeTask(judgeId, new JudgeProgress("WA", "finished", 100));
        byte[] finalBytes = Files.readAllBytes(directory.resolve("final.json"));
        byte[] eventBytes = Files.readAllBytes(directory.resolve("events.jsonl"));
        Files.write(directory.resolve("metadata.json"), runningMetadata);

        FileTaskStore restarted = store();
        assertThat(restarted.markStaleRunningTasksOnStartup()).isEmpty();
        assertThat(objectMapper.readTree(directory.resolve("metadata.json").toFile()).path("status").asText()).isEqualTo("WA");
        assertThat(restarted.findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(committed);
        assertThat(Files.readAllBytes(directory.resolve("final.json"))).isEqualTo(finalBytes);
        byte[] repairedEvents = Files.readAllBytes(directory.resolve("events.jsonl"));
        assertThat(repairedEvents).startsWith(eventBytes);
        assertThat(Files.readAllLines(directory.resolve("events.jsonl"))).hasSize(3);
        assertThat(store().markStaleRunningTasksOnStartup()).isEmpty();
        assertThat(Files.readAllBytes(directory.resolve("events.jsonl"))).isEqualTo(repairedEvents);
    }

    private Path createStartedTask(FileTaskStore store, String judgeId, String profile) throws IOException {
        Path directory = store.taskDirectory(judgeId);
        store.create(task(judgeId, directory, profile, JudgeStatus.RUNNING));
        Files.writeString(directory.resolve("1.in"), "input");
        Files.writeString(directory.resolve("1.out"), "output");
        Files.writeString(directory.resolve("1.ans"), "answer");
        return directory;
    }

    private JudgeTask task(String judgeId, Path directory, String profile, JudgeStatus status) {
        return JudgeTask.builder().judgeId(judgeId).status(status).mode(profile).requestedCases(1)
                .policy(new ResolvedTaskPolicy(profile, false, 1, 1, 1, 1,
                        Duration.ofSeconds(1), Duration.ofMinutes(1), 64L * 1024 * 1024, 1024, false))
                .workDir(directory.toString()).createdAt(Instant.now().minusSeconds(2))
                .startedAt(status == JudgeStatus.CREATED ? null : Instant.now().minusSeconds(1)).build();
    }

    private FileTaskStore store() {
        return new FileTaskStore(objectMapper, storageBase);
    }

    private Map<String, byte[]> snapshot(Path directory) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var paths = Files.walk(directory)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                files.put(directory.relativize(file).toString(), Files.readAllBytes(file));
            }
        }
        return files;
    }

    private void assertUnchanged(Path directory, Map<String, byte[]> before) throws IOException {
        Map<String, byte[]> after = snapshot(directory);
        assertThat(after.keySet()).isEqualTo(before.keySet());
        for (String name : before.keySet()) {
            assertThat(after.get(name)).as(name).isEqualTo(before.get(name));
        }
    }
}
