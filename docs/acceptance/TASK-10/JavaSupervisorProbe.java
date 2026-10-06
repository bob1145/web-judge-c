package com.example.demo.acceptance;

import com.example.demo.dto.SandboxComparison;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.sandbox.LinuxContainerRunner;
import com.example.demo.service.sandbox.LinuxAttemptDiscovery;
import com.example.demo.service.sandbox.LinuxRuntimeJournal;
import com.example.demo.service.sandbox.LinuxStageRecoveryPreparation;
import com.example.demo.service.sandbox.LinuxSupervisorCommandExecutor;
import com.example.demo.service.sandbox.LinuxSupervisorLauncher;
import com.example.demo.service.sandbox.LinuxSupervisorOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JavaSupervisorProbe {
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final String JUDGE = "task10-java-supervisor";
    private static final String ENDPOINT = "unix:///run/wjc/docker.sock";
    private static final long MEBIBYTE = 1024 * 1024;
    private static final Map<String, String> SOURCES = Map.of(
            "generator.cpp", "#include <iostream>\nint main() { std::cout << \"42\\n\"; }\n",
            "user.cpp", "#include <iostream>\nint main() { int value; if (!(std::cin >> value)) return 2; std::cout << value << \"\\n\"; }\n",
            "oracle.cpp", "#include <iostream>\nint main() { int value; if (!(std::cin >> value)) return 2; std::cout << value << \"\\n\"; }\n");

    private JavaSupervisorProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 5 && arguments[0].equals("--discover-only")) {
            inspectFromFreshJvm(arguments);
            return;
        }
        if (arguments.length == 6 && arguments[0].equals("--prepare-only")) {
            prepareFromFreshJvm(arguments);
            return;
        }
        if ((arguments.length == 7 && arguments[0].equals("--recover-confirmed-only"))
                || (arguments.length == 8 && arguments[0].equals("--recover-confirmed-fault"))) {
            recoverFromFreshJvm(arguments);
            return;
        }
        if (arguments.length == 5 && Set.of("--start-active-only", "--cancel-active-only").contains(arguments[0])) {
            startActiveFromFreshJvm(arguments);
            return;
        }
        require(arguments.length == 4, "expected task root, evidence root, supervisor image and executor image");
        Path root = Path.of(arguments[0]);
        Path evidence = Path.of(arguments[1]);
        require(root.equals(Path.of("/lab/java-storage/judge-task10-java-supervisor")) && evidence.equals(Path.of("/lab/evidence/java")),
                "diagnostic paths must remain inside the dedicated private root");
        Files.createDirectory(root.getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createDirectory(evidence);
        List<String> negativeCases = configurationNegatives(arguments[2], arguments[3]);
        require(!Files.exists(root.resolve("control")), "configuration-only negatives created task state");
        write(evidence.resolve("configuration-negatives.json"), negativeCases);
        Map<String, String> fixedHashes = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : SOURCES.entrySet()) {
            Files.writeString(root.resolve(source.getKey()), source.getValue());
        }
        StringBuilder audit = new StringBuilder();
        for (String role : List.of("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE")) {
            audit.append("{\"role\":\"").append(role).append("\",\"state\":\"CONTROL_SENTINEL_UNCHANGED\"}\n");
        }
        Files.writeString(root.resolve("events.jsonl"), audit);
        Files.writeString(root.resolve("metadata.json"), "{\"owner\":\"trusted-java-probe\",\"state\":\"fixed\"}\n");
        Files.writeString(root.resolve("final.json"), "{\"trustedFinal\":true,\"ac\":0}\n");
        for (String name : List.of("generator.cpp", "user.cpp", "oracle.cpp", "events.jsonl", "metadata.json", "final.json")) {
            fixedHashes.put(name, hash(root.resolve(name)));
        }
        write(evidence.resolve("hashes-before.json"), fixedHashes);
        LinuxSupervisorOptions options = options(ENDPOINT, 0, 65532, arguments[2], arguments[3]);
        LinuxContainerRunner runner = new LinuxContainerRunner(
                LinuxContainerRunner.Options.builder().supervisor(options).build(), null, MAPPER);
        SandboxTaskSpec spec = SandboxTaskSpec.builder().judgeId(JUDGE).userId("task10-probe")
                .profile("trusted-local").storageBase(root.getParent()).workDir(root)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, root.resolve("generator.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.USER, root.resolve("user.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, root.resolve("oracle.cpp"))
                .testCases(1).caseTimeLimit(Duration.ofSeconds(5)).maxTaskRuntime(Duration.ofSeconds(180))
                .memoryLimitBytes(256 * MEBIBYTE).maxOutputBytesPerCase(MEBIBYTE)
                .comparison(new SandboxComparison(SandboxComparison.Mode.LEGACY_TEXT, "0")).build();
        try {
            SandboxRunHandle handle = runner.start(spec);
            require(handle.judgeId().equals(JUDGE) && handle.provider().equals("linux-container")
                    && handle.runId().matches("[a-f0-9]{64}")
                    && UUID.fromString(handle.attemptId()).toString().equals(handle.attemptId()), "invalid real start handle");
            write(evidence.resolve("handle.json"), handle);
            Path attempt = root.resolve("control/attempts").resolve(handle.attemptId());
            String manifestHash = hash(attempt.resolve("task-spec.json"));
            String configHash = hash(attempt.resolve("supervisor-config.json"));
            List<SandboxTaskEvent> events = new ArrayList<>();
            int pollNumber = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(240).toNanos();
            while (events.stream().noneMatch(event -> event.type() == SandboxTaskEvent.Type.COMPLETED)) {
                require(System.nanoTime() < deadline, "Java event deadline exceeded; durable resources retained");
                List<SandboxTaskEvent> batch = runner.pollEvents(handle);
                pollNumber++;
                if (!batch.isEmpty()) {
                    retainPolledBatch(evidence, pollNumber, batch);
                }
                events.addAll(batch);
                if (!batch.isEmpty()) {
                    byte[] polled = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(events.stream().limit(17).toList());
                    require(polled.length <= 128 * 1024, "polled event evidence exceeds its byte bound");
                    Files.write(evidence.resolve("java-polled-events.json"), polled);
                }
                require(events.size() <= 16, "event count exceeded diagnostic bound");
                require(events.stream().noneMatch(event -> Set.of(SandboxTaskEvent.Type.SYSTEM_ERROR,
                        SandboxTaskEvent.Type.CANCELLED, SandboxTaskEvent.Type.BUDGET_EXCEEDED,
                        SandboxTaskEvent.Type.SECURITY_VIOLATION, SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE).contains(event.type())),
                        "Java polling observed a failure terminal event");
                Thread.sleep(50);
            }
            write(evidence.resolve("java-polled-events.json"), events);
            require(events.stream().map(event -> event.type().name()).toList().equals(List.of(
                    "COMPILE_STARTED", "COMPILE_FINISHED", "RUN_STARTED", "RUN_FINISHED", "SUMMARY", "COMPLETED")),
                    "unexpected protocol event sequence");
            require(events.stream().allMatch(event -> JUDGE.equals(event.judgeId())), "event judge identity differs");
            JsonNode summary = read(attempt.resolve("runner-summary.json"));
            require(summary.path("totalCases").asInt(-1) == 1 && summary.path("completedCases").asInt(-1) == 1
                    && summary.path("ac").asInt(-1) == 1, "expected exactly one AC case");
            for (String counter : List.of("wa", "tle", "mle", "re", "systemError", "outputLimitExceeded")) {
                require(summary.path(counter).asInt(-1) == 0, "summary contains failures");
            }
            require(MAPPER.valueToTree(events.get(4).summary()).equals(summary), "polled summary differs from durable summary");
            List<SandboxTaskEvent> persistedEvents = new ArrayList<>();
            for (String line : Files.readAllLines(bounded(attempt.resolve("runner-events.jsonl")))) {
                persistedEvents.add(MAPPER.readValue(line, SandboxTaskEvent.class));
            }
            require(events.equals(persistedEvents), "polled and persisted events differ");
            ObjectNode journal = LinuxRuntimeJournal.readRetained(attempt, JUDGE, handle.attemptId(), MAPPER, 0, 0);
            require(journal.path("state").asText().equals("STARTED")
                    && journal.path("supervisor").path("runtimeId").asText().equals(handle.runId()), "durable runtime identity differs");
            for (String resource : List.of("volume", "holder", "supervisor")) {
                require(journal.path(resource).path("creationState").asText().equals("CONFIRMED"), "resource creation is unresolved");
            }
            JsonNode manifest = read(attempt.resolve("task-spec.json"));
            JsonNode config = read(attempt.resolve("supervisor-config.json"));
            require(manifest.path("judgeId").asText().equals(JUDGE)
                    && manifest.path("attemptId").asText().equals(handle.attemptId())
                    && config.path("attemptId").asText().equals(handle.attemptId())
                    && config.path("stage_volume").asText().equals(journal.path("volume").path("name").asText())
                    && config.path("stage_root").asText().equals(journal.path("volume").path("mountpoint").asText())
                    && config.path("volume_holder_id").asText().equals(journal.path("holder").path("runtimeId").asText())
                    && config.path("executor_image").asText().equals(arguments[3]), "manifest/config/journal correlation differs");
            require(hash(attempt.resolve("task-spec.json")).equals(manifestHash)
                    && hash(attempt.resolve("supervisor-config.json")).equals(configHash), "trusted control file changed");
            List<JsonNode> stages = stages(attempt, evidence, handle.attemptId(), config.path("stage_volume").asText());
            write(evidence.resolve("verified-stage-journals.json"), stages);
            verifyPostCompletionStop(runner, options, handle, root, attempt, evidence);
            verifyFreshJvmDiscovery(options, handle, root, evidence);
            verifyFreshJvmPreparation(options, handle, root, attempt, evidence);
            require(hash(attempt.resolve("task-spec.json")).equals(manifestHash)
                    && hash(attempt.resolve("supervisor-config.json")).equals(configHash), "stop changed trusted control files");
            for (Map.Entry<String, String> fixed : fixedHashes.entrySet()) {
                require(hash(root.resolve(fixed.getKey())).equals(fixed.getValue()), "host audit, metadata or source changed");
            }
            require(Files.readAllLines(root.resolve("events.jsonl")).size() == 4, "host audit no longer has four entries");
            for (String artifact : List.of("1.in", "1.out", "1.ans")) {
                require(Files.readString(bounded(root.resolve(artifact))).equals("42\n"), "fixed case artifact differs");
            }
            write(evidence.resolve("hashes-after.json"), fixedHashes);
            ObjectNode observation = MAPPER.createObjectNode().put("realJavaStart", true).put("javaPollingVerified", true)
                    .put("processId", ProcessHandle.current().pid())
                    .put("realPostCompletionStop", true).put("repeatStopVerified", true)
                    .put("cancelRecoveryRemainsPending", true)
                    .put("freshJvmDiscoveryVerified", true)
                    .put("freshJvmPreparationVerified", true)
                    .put("productionAcceptance", false).put("productRecoveryComplete", false)
                    .put("resourceDisposition", "retained for independent harness inspection; domain teardown is not product recovery")
                    .put("attemptId", handle.attemptId()).put("supervisorId", handle.runId())
                    .put("holderId", journal.path("holder").path("runtimeId").asText())
                    .put("volumeName", config.path("stage_volume").asText()).put("stageCount", stages.size())
                    .put("taskRoot", root.toString()).put("manifestSha256", manifestHash).put("configSha256", configHash)
                    .put("auditUnchanged", true).put("metadataUnchanged", true).put("sourcesUnchanged", true)
                    .put("scope", "real start/poll, repeated post-completion stop and fresh-JVM discovery; no active-workload cancellation or UNKNOWN recovery");
            observation.set("summary", summary);
            write(evidence.resolve("java-observation.json"), observation);
            System.out.println(MAPPER.writeValueAsString(observation));
        } finally {
            retainEvidence(root, evidence);
        }
    }

    private static final String ACTIVE_JUDGE = "task10-java-active-recovery";

    private static void recoverFromFreshJvm(String[] arguments) throws Exception {
        Path storage = Path.of(arguments[1]);
        Path evidence = Path.of(arguments[2]);
        String judge = arguments[5];
        String attemptId = arguments[6];
        String faultMode = arguments.length == 8 ? arguments[7] : "none";
        require(Set.of("none", "stop-unreachable", "supervisor-rm-ack-loss", "stage-rm-ack-loss").contains(faultMode),
                "unsupported private recovery fault");
        require(storage.equals(Path.of("/lab/java-storage")) && Set.of(JUDGE, ACTIVE_JUDGE).contains(judge)
                && UUID.fromString(attemptId).toString().equals(attemptId), "recovery identity is outside the private experiment");
        require(evidence.equals(Path.of("/lab/evidence/java/completed-recovery"))
                || evidence.equals(Path.of("/lab/evidence/java/completed-retry"))
                || evidence.equals(Path.of("/lab/evidence/java/active-recovery"))
                || evidence.equals(Path.of("/lab/evidence/java/active-retry"))
                || evidence.equals(Path.of("/lab/evidence/java/completed-stop-unreachable"))
                || evidence.equals(Path.of("/lab/evidence/java/completed-supervisor-rm-ack-loss"))
                || evidence.equals(Path.of("/lab/evidence/java/active-stage-rm-ack-loss")),
                "recovery evidence path is outside the private experiment");
        Files.createDirectory(evidence);
        Path attempt = storage.resolve("judge-" + judge).resolve("control/attempts").resolve(attemptId);
        ObjectNode before = LinuxRuntimeJournal.readRetained(attempt, judge, attemptId, MAPPER, 0, 0);
        Map<String, JsonNode> stagesBefore = recoveryStageInventory(attempt);
        List<ObjectNode> trace = new ArrayList<>();
        LinuxSupervisorOptions options = options(ENDPOINT, 0, 65532, arguments[3], arguments[4]);
        LinuxSupervisorCommandExecutor real = new LinuxSupervisorCommandExecutor(options.controlOutputLimitBytes());
        ObjectNode injectedFault = MAPPER.createObjectNode();
        Set<String> stageIdentities = new java.util.HashSet<>();
        stagesBefore.values().forEach(stage -> stageIdentities.add(stage.path("containerId").asText()));
        LinuxContainerRunner.CommandExecutor recorder = (command, timeout) -> {
            require(trace.size() < 256, "recovery command trace exceeds its entry budget");
            String verb = command.size() > 4 ? command.get(4) : "";
            String identity = command.get(command.size() - 1);
            boolean inject = injectedFault.isEmpty() && switch (faultMode) {
                case "stop-unreachable" -> "stop".equals(verb)
                        && identity.equals(before.path("supervisor").path("runtimeId").textValue());
                case "supervisor-rm-ack-loss" -> "rm".equals(verb)
                        && identity.equals(before.path("supervisor").path("runtimeId").textValue());
                case "stage-rm-ack-loss" -> "rm".equals(verb) && stageIdentities.contains(identity);
                default -> false;
            };
            List<String> executed = new ArrayList<>(command);
            if (inject && "stop-unreachable".equals(faultMode)) {
                executed.set(2, "unix:///run/wjc/unavailable-recovery.sock");
            }
            LinuxContainerRunner.CommandResult result = real.run(executed, timeout);
            ObjectNode entry = MAPPER.createObjectNode().put("exitCode", result.exitCode())
                    .put("stdoutBytes", result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .put("stderrBytes", result.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            entry.set("arguments", MAPPER.valueToTree(executed));
            if (Set.of("stop", "rm").contains(verb)) {
                require(result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024
                        && result.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024,
                        "raw mutation response exceeds the private evidence budget");
                entry.put("stdout", result.stdout()).put("stderr", result.stderr());
            }
            if (inject) {
                entry.put("injectedFault", faultMode).set("requestedArguments", MAPPER.valueToTree(command));
                injectedFault.put("mode", faultMode).put("targetFullId", identity);
            }
            trace.add(entry);
            if (inject && !"stop-unreachable".equals(faultMode)) {
                require(result.exitCode() == 0 && result.stderr().isEmpty() && result.stdout().equals(identity + "\n"),
                        "response-loss injection requires a real successful full-ID rm");
                throw new IOException("private experiment lost successful container rm acknowledgement");
            }
            return result;
        };
        LinuxContainerRunner fresh = new LinuxContainerRunner(
                LinuxContainerRunner.Options.builder().supervisor(options).build(), recorder, MAPPER);
        write(evidence.resolve("runtime-before.json"), before);
        write(evidence.resolve("stages-before.json"), stagesBefore);
        try {
            LinuxSupervisorLauncher.ContainerReclamation result;
            try {
                result = fresh.recoverRetainedConfirmedContainers(storage, judge, attemptId);
            } catch (IOException failure) {
                require(!"none".equals(faultMode) && !injectedFault.isEmpty()
                        && faultMode.equals(trace.get(trace.size() - 1).path("injectedFault").textValue()),
                        "recovery failed before the intended private fault");
                if ("stop-unreachable".equals(faultMode)) {
                    require(trace.get(trace.size() - 1).path("exitCode").intValue() != 0,
                            "private unreachable CLI unexpectedly succeeded");
                } else {
                    require("private experiment lost successful container rm acknowledgement".equals(failure.getMessage()),
                            "recovery did not expose the intended post-rm acknowledgement loss");
                }
                ObjectNode observation = MAPPER.createObjectNode().put("freshJvm", true)
                        .put("processId", ProcessHandle.current().pid()).put("judgeId", judge).put("attemptId", attemptId)
                        .put("handleProvided", false).put("runnerStartedInThisProcess", false).put("realRuntimeCommands", true)
                        .put("confirmedStages", stagesBefore.size()).put("retainedVolumeName", before.path("volume").path("name").textValue())
                        .put("faultMode", faultMode).put("expectedRecoveryFailure", true)
                        .put("operationCompletedBeforeLoss", !"stop-unreachable".equals(faultMode))
                        .put("daemonFaultInjected", false).put("targetFullId", injectedFault.path("targetFullId").textValue())
                        .put("productRecoveryComplete", false).put("volumeContinuityProven", false)
                        .put("deletionCredentialIssued", false).put("productionAcceptance", false);
                write(evidence.resolve("failure-observation.json"), observation);
                return;
            }
            require("none".equals(faultMode), "expected private recovery failure did not occur");
            ObjectNode after = LinuxRuntimeJournal.readRetained(attempt, judge, attemptId, MAPPER, 0, 0);
            Map<String, JsonNode> stagesAfter = recoveryStageInventory(attempt);
            require("CLEANUP_PENDING".equals(after.path("state").textValue())
                    && "CREATED".equals(after.path("volume").path("state").textValue())
                    && after.path("volume").equals(before.path("volume")), "recovery changed the retained volume contract");
            for (String resource : List.of("supervisor", "holder")) {
                require("REMOVED".equals(after.path(resource).path("state").textValue())
                        && after.path(resource).path("runtimeId").equals(before.path(resource).path("runtimeId")),
                        "recovery did not preserve and remove the exact recorded container");
            }
            require(stagesBefore.keySet().equals(stagesAfter.keySet()), "recovery lost stage evidence");
            int confirmed = 0;
            for (Map.Entry<String, JsonNode> entry : stagesBefore.entrySet()) {
                ObjectNode expected = (ObjectNode) entry.getValue().deepCopy();
                if ("CONFIRMED".equals(expected.path("creationState").textValue())) {
                    confirmed++;
                    if (!"CLEANED".equals(expected.path("state").textValue())) {
                        expected.put("state", "REMOVED");
                    }
                }
                require(expected.equals(stagesAfter.get(entry.getKey())), "recovery invented outcome or artifact cleanup");
            }
            require(result.confirmedStages() == confirmed
                    && result.retainedVolumeName().equals(before.path("volume").path("name").textValue()),
                    "recovery result differs from persistent confirmed evidence");
            ObjectNode observation = MAPPER.createObjectNode().put("freshJvm", true)
                    .put("processId", ProcessHandle.current().pid()).put("judgeId", judge).put("attemptId", attemptId)
                    .put("handleProvided", false).put("runnerStartedInThisProcess", false)
                    .put("realRuntimeCommands", true).put("confirmedStages", confirmed)
                    .put("retainedVolumeName", result.retainedVolumeName()).put("productRecoveryComplete", false)
                    .put("volumeContinuityProven", false).put("deletionCredentialIssued", false)
                    .put("productionAcceptance", false);
            write(evidence.resolve("observation.json"), observation);
            System.out.println(MAPPER.writeValueAsString(observation));
        } finally {
            write(evidence.resolve("commands.json"), trace);
            write(evidence.resolve("runtime-after.json"), LinuxRuntimeJournal.readRetained(attempt, judge, attemptId, MAPPER, 0, 0));
            write(evidence.resolve("stages-after.json"), recoveryStageInventory(attempt));
        }
    }

    private static Map<String, JsonNode> recoveryStageInventory(Path attempt) throws Exception {
        Map<String, JsonNode> records = new LinkedHashMap<>();
        try (var entries = Files.newDirectoryStream(attempt.resolve("stage-journal"), "*.json")) {
            for (Path entry : entries) {
                require(records.size() < 32 && entry.getFileName().toString().matches("[a-f0-9-]{36}\\.json"),
                        "recovery stage inventory exceeds the private probe contract");
                records.put(entry.getFileName().toString(), read(entry));
            }
        }
        return records;
    }

    private static void startActiveFromFreshJvm(String[] arguments) throws Exception {
        Path task = Path.of(arguments[1]);
        Path evidence = Path.of(arguments[2]);
        require(task.equals(Path.of("/lab/java-storage/judge-" + ACTIVE_JUDGE))
                && evidence.equals(Path.of("/lab/evidence/java/active")), "active fixture paths are outside the private experiment");
        Files.createDirectory(task, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createDirectory(evidence);
        for (Map.Entry<String, String> source : SOURCES.entrySet()) {
            Files.writeString(task.resolve(source.getKey()), source.getKey().equals("user.cpp")
                    ? "#include <chrono>\n#include <thread>\nint main() { for (;;) std::this_thread::sleep_for(std::chrono::seconds(1)); }\n"
                    : source.getValue());
        }
        LinuxSupervisorOptions options = options(ENDPOINT, 0, 65532, arguments[3], arguments[4]);
        boolean cancelActive = arguments[0].equals("--cancel-active-only");
        LinuxSupervisorCommandExecutor real = new LinuxSupervisorCommandExecutor(options.controlOutputLimitBytes());
        List<ObjectNode> trace = new ArrayList<>();
        ObjectNode cancellationContext = MAPPER.createObjectNode();
        LinuxContainerRunner.CommandExecutor recorder = (command, timeout) -> {
            require(trace.size() < 256, "active cancellation command trace exceeds its entry budget");
            String verb = command.size() > 4 ? command.get(4) : "";
            String identity = command.get(command.size() - 1);
            if ("stop".equals(verb) && identity.equals(cancellationContext.path("supervisorId").textValue())) {
                write(evidence.resolve("supervisor-before-product-stop.json"), inspectPrivateContainer(real, identity));
            }
            LinuxContainerRunner.CommandResult result = real.run(command, timeout);
            ObjectNode entry = MAPPER.createObjectNode().put("exitCode", result.exitCode())
                    .put("stdoutBytes", result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .put("stderrBytes", result.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            entry.set("arguments", MAPPER.valueToTree(command));
            if (Set.of("stop", "rm").contains(verb)) {
                require(result.stdout().length() <= 1024 && result.stderr().length() <= 1024,
                        "active cancellation mutation response exceeds the evidence budget");
                entry.put("stdout", result.stdout()).put("stderr", result.stderr());
            }
            trace.add(entry);
            byte[] encoded = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(trace);
            require(encoded.length <= 128 * 1024, "active cancellation command trace exceeds its byte budget");
            Files.write(evidence.resolve("commands.json"), encoded);
            return result;
        };
        LinuxContainerRunner runner = new LinuxContainerRunner(
                LinuxContainerRunner.Options.builder().supervisor(options).build(), cancelActive ? recorder : null, MAPPER);
        SandboxTaskSpec spec = SandboxTaskSpec.builder().judgeId(ACTIVE_JUDGE).userId("task10-probe")
                .profile("trusted-local").storageBase(task.getParent()).workDir(task)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, task.resolve("generator.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.USER, task.resolve("user.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, task.resolve("oracle.cpp"))
                .testCases(1).caseTimeLimit(Duration.ofSeconds(120)).maxTaskRuntime(Duration.ofSeconds(180))
                .memoryLimitBytes(256 * MEBIBYTE).maxOutputBytesPerCase(MEBIBYTE)
                .comparison(new SandboxComparison(SandboxComparison.Mode.LEGACY_TEXT, "0")).build();
        SandboxRunHandle handle = runner.start(spec);
        Path attempt = task.resolve("control/attempts").resolve(handle.attemptId());
        if (cancelActive) {
            ObjectNode runtime = LinuxRuntimeJournal.readRetained(attempt, ACTIVE_JUDGE, handle.attemptId(), MAPPER, 0, 0);
            cancellationContext.put("supervisorId", runtime.path("supervisor").path("runtimeId").textValue());
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (!Files.isDirectory(attempt.resolve("stage-journal"), LinkOption.NOFOLLOW_LINKS)) {
                Thread.sleep(50);
                continue;
            }
            for (JsonNode stage : recoveryStageInventory(attempt).values()) {
                if ("CONFIRMED".equals(stage.path("creationState").textValue())
                        && "USER".equals(stage.path("role").textValue())
                        && "execute".equals(stage.path("phase").textValue())
                        && "STARTING".equals(stage.path("state").textValue())) {
                    String identity = stage.path("containerId").textValue();
                    require(identity != null && identity.matches("[a-f0-9]{64}"), "active stage identity is not complete");
                    LinuxContainerRunner.CommandResult inspection = real.run(List.of("/usr/bin/docker", "--host", ENDPOINT, "container", "inspect", identity), Duration.ofSeconds(15));
                    require(inspection.exitCode() == 0 && inspection.stderr().isEmpty(), "active stage inspect failed");
                    JsonNode objects = MAPPER.readTree(inspection.stdout());
                    require(objects.isArray() && objects.size() == 1 && identity.equals(objects.get(0).path("Id").textValue()),
                            "active stage inspection identity differs");
                    if (objects.get(0).path("State").path("Running").asBoolean(false)) {
                        if (cancelActive) {
                            verifyActiveCancellation(runner, handle, attempt, evidence, stage, real);
                            return;
                        }
                        ObjectNode observation = MAPPER.createObjectNode().put("producerProcessId", ProcessHandle.current().pid())
                                .put("judgeId", ACTIVE_JUDGE).put("attemptId", handle.attemptId())
                                .put("liveStageId", identity).put("liveStageVerified", true)
                                .put("resourceDisposition", "retained for a different JVM's product container recovery");
                        observation.set("stage", stage);
                        write(evidence.resolve("live-stage-inspect.json"), objects.get(0));
                        write(evidence.resolve("observation.json"), observation);
                        System.out.println(MAPPER.writeValueAsString(observation));
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new IOException("active confirmed stage was not observed before the private fixture deadline");
    }

    private static JsonNode inspectPrivateContainer(LinuxSupervisorCommandExecutor inspector, String identity)
            throws IOException, InterruptedException {
        require(identity != null && identity.matches("[a-f0-9]{64}"), "private container identity is incomplete");
        LinuxContainerRunner.CommandResult result = inspector.run(
                List.of("/usr/bin/docker", "--host", ENDPOINT, "container", "inspect", identity), Duration.ofSeconds(15));
        require(result.exitCode() == 0 && result.stderr().isEmpty(), "private container inspect failed");
        JsonNode objects = MAPPER.readTree(result.stdout());
        require(objects.isArray() && objects.size() == 1 && identity.equals(objects.get(0).path("Id").textValue()),
                "private container inspection identity differs");
        return objects.get(0);
    }

    private static void verifyActiveCancellation(LinuxContainerRunner runner, SandboxRunHandle handle, Path attempt,
                                                Path evidence, JsonNode stage, LinuxSupervisorCommandExecutor inspector)
            throws Exception {
        String identity = stage.path("containerId").textValue();
        String stageId = stage.path("stage").textValue();
        require(UUID.fromString(stageId).toString().equals(stageId), "live stage UUID is not canonical");
        JsonNode liveBefore = inspectPrivateContainer(inspector, identity);
        require(liveBefore.path("State").path("Running").asBoolean(false), "active stage exited before product cancel");
        write(evidence.resolve("live-stage-inspect.json"), liveBefore);
        ObjectNode before = LinuxRuntimeJournal.readRetained(attempt, ACTIVE_JUDGE, handle.attemptId(), MAPPER, 0, 0);
        write(evidence.resolve("runtime-before.json"), before);
        write(evidence.resolve("stages-before.json"), recoveryStageInventory(attempt));
        Path marker = attempt.resolve("cancel.requested");
        require(!Files.exists(marker, LinkOption.NOFOLLOW_LINKS), "active fixture already contains a cancel marker");
        String volume = before.path("volume").path("name").textValue();
        require(volume != null && volume.equals(stage.path("volume").textValue()), "active stage volume differs");
        LinuxContainerRunner.CommandResult volumeInspection = inspector.run(
                List.of("/usr/bin/docker", "--host", ENDPOINT, "volume", "inspect", volume), Duration.ofSeconds(15));
        require(volumeInspection.exitCode() == 0 && volumeInspection.stderr().isEmpty(), "active volume inspection failed");
        JsonNode volumes = MAPPER.readTree(volumeInspection.stdout());
        require(volumes.isArray() && volumes.size() == 1 && volume.equals(volumes.get(0).path("Name").textValue()),
                "active volume inspection identity differs");
        write(evidence.resolve("volume-before.json"), volumes.get(0));
        Path mountpoint = Path.of(volumes.get(0).path("Mountpoint").textValue());
        require(mountpoint.equals(Path.of("/var/lib/wjc/docker/volumes", volume, "_data")),
                "active stage volume is outside the dedicated private daemon");
        Path stageDirectory = mountpoint.resolve(stageId);
        require(Files.isDirectory(stageDirectory, LinkOption.NOFOLLOW_LINKS), "active stage directory is absent before cancel");
        String flockCode = "import fcntl,json,os,sys\n"
                + "descriptor=os.open(sys.argv[1],os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)\n"
                + "try:\n"
                + " try:\n  fcntl.flock(descriptor,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                + " except BlockingIOError:\n"
                + "  metadata=os.fstat(descriptor)\n"
                + "  print(json.dumps(dict(processId=os.getpid(),stageFlockHeld=True,device=metadata.st_dev,inode=metadata.st_ino)))\n"
                + " else:\n  raise SystemExit('stage journal flock unexpectedly available')\n"
                + "finally:\n os.close(descriptor)\n";
        LinuxContainerRunner.CommandResult flock = inspector.run(
                List.of("/usr/bin/python3", "-B", "-c", flockCode, attempt.resolve("stage-journal").toString()),
                Duration.ofSeconds(10));
        ObjectNode flockEvidence = MAPPER.createObjectNode().put("exitCode", flock.exitCode())
                .put("stdout", flock.stdout()).put("stderr", flock.stderr());
        write(evidence.resolve("flock-before-cancel.json"), flockEvidence);
        require(flock.exitCode() == 0 && flock.stderr().isEmpty(), "active stage flock is not held by its actual owner");
        JsonNode flockObservation = MAPPER.readTree(flock.stdout());
        require(flockObservation.path("stageFlockHeld").asBoolean(false)
                && flockObservation.path("processId").isIntegralNumber()
                && flockObservation.path("processId").longValue() > 0
                && flockObservation.path("processId").longValue() != ProcessHandle.current().pid(),
                "active stage flock proof did not come from a distinct process");
        IllegalStateException pending = null;
        try {
            runner.cancel(handle);
        } catch (IllegalStateException failure) {
            pending = failure;
        }
        boolean pendingReported = pending != null && pending.getCause() == null
                && "Linux supervisor stopped; stage and volume recovery remains pending".equals(pending.getMessage());
        ObjectNode after = LinuxRuntimeJournal.readRetained(attempt, ACTIVE_JUDGE, handle.attemptId(), MAPPER, 0, 0);
        Map<String, JsonNode> stagesAfter = recoveryStageInventory(attempt);
        write(evidence.resolve("runtime-after.json"), after);
        write(evidence.resolve("stages-after.json"), stagesAfter);
        LinuxContainerRunner.CommandResult listed = inspector.run(List.of("/usr/bin/docker", "--host", ENDPOINT, "container",
                "ls", "--all", "--quiet", "--no-trunc", "--filter", "id=" + identity), Duration.ofSeconds(15));
        write(evidence.resolve("live-stage-after-cancel-list.json"), MAPPER.createObjectNode()
                .put("exitCode", listed.exitCode()).put("stdout", listed.stdout()).put("stderr", listed.stderr()));
        require(listed.exitCode() == 0 && listed.stderr().isEmpty()
                && (listed.stdout().isEmpty() || listed.stdout().equals(identity + "\n")),
                "active stage absence query failed or returned an untrusted identity");
        boolean stageAbsent = listed.stdout().isEmpty();
        if (!stageAbsent) {
            write(evidence.resolve("live-stage-after-cancel-inspect.json"), inspectPrivateContainer(inspector, identity));
        }
        Map<String, Object> markerAttributes = Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
                ? Files.readAttributes(marker, "unix:uid,gid,mode,nlink,size", LinkOption.NOFOLLOW_LINKS) : Map.of();
        boolean trustedMarker = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker)
                && Integer.valueOf(0).equals(markerAttributes.get("uid")) && Integer.valueOf(0).equals(markerAttributes.get("gid"))
                && Integer.valueOf(1).equals(markerAttributes.get("nlink")) && Long.valueOf(0).equals(markerAttributes.get("size"))
                && markerAttributes.get("mode") instanceof Integer mode && (mode & 07777) == 0600;
        write(evidence.resolve("cancel-marker-after.json"), markerAttributes);
        JsonNode cancelled = stagesAfter.get(stageId + ".json");
        boolean stageCancelled = cancelled != null && "CLEANED".equals(cancelled.path("state").textValue())
                && "CANCELLED".equals(cancelled.path("outcome").path("status").textValue());
        boolean directoryRemoved = !Files.exists(stageDirectory, LinkOption.NOFOLLOW_LINKS);
        ObjectNode observation = MAPPER.createObjectNode().put("processId", ProcessHandle.current().pid())
                .put("judgeId", ACTIVE_JUDGE).put("attemptId", handle.attemptId()).put("liveStageId", identity)
                .put("sameJvmStartAndCancel", true).put("runnerCancelInvoked", true).put("liveStageVerifiedBeforeCancel", true)
                .put("stageFlockHeldBeforeCancel", true).put("trustedCancelMarkerObserved", trustedMarker)
                .put("stageOutcomeCancelled", stageCancelled).put("stageContainerAbsent", stageAbsent)
                .put("realRuntimeCommands", true).put("pendingRecoveryReported", pendingReported)
                .put("actualStageDirectoryRemoved", directoryRemoved).put("productRecoveryComplete", false)
                .put("productionAcceptance", false).put("activeCancellationComplete", false).put("deletionCredentialIssued", false);
        if (pending != null) {
            observation.put("pendingMessage", pending.getMessage());
        }
        write(evidence.resolve("observation.json"), observation);
        if (!stageAbsent || !trustedMarker || !stageCancelled || !directoryRemoved || !pendingReported) {
            write(evidence.resolve("failure-observation.json"), observation);
        }
        require(pendingReported, "active Runner.cancel did not retain the original pending recovery boundary");
        require(stageAbsent, "active Runner.cancel left the confirmed user stage container present");
        require(trustedMarker, "active Runner.cancel did not publish a trusted private cancellation marker");
        require(stageCancelled && directoryRemoved, "active Runner.cancel did not persist actual cancelled stage cleanup");
        JsonNode owner = read(evidence.resolve("supervisor-before-product-stop.json"));
        require(owner.path("Id").equals(before.path("supervisor").path("runtimeId"))
                && owner.path("State").path("Running").isBoolean() && !owner.path("State").path("Running").booleanValue()
                && owner.path("State").path("Pid").isIntegralNumber() && owner.path("State").path("Pid").longValue() == 0
                && owner.path("State").path("ExitCode").isIntegralNumber() && owner.path("State").path("ExitCode").intValue() == 20
                && owner.path("State").path("OOMKilled").isBoolean() && !owner.path("State").path("OOMKilled").booleanValue(),
                "active supervisor was not cooperatively cancelled before product stop");
        ObjectNode expected = before.deepCopy();
        expected.put("state", "CLEANUP_PENDING");
        ((ObjectNode) expected.path("supervisor")).put("state", "STOPPED");
        require(after.equals(expected), "active cancellation changed resource identity or cleanup boundary");
        System.out.println(MAPPER.writeValueAsString(observation));
    }

    private static void verifyPostCompletionStop(LinuxContainerRunner runner, LinuxSupervisorOptions options,
                                                 SandboxRunHandle handle, Path root,
                                                 Path attempt, Path evidence) throws Exception {
        awaitNormalSupervisorExit(options, handle, evidence);
        Path marker = root.resolve("control/linux-supervisor.lock");
        Object markerKey = Files.getAttribute(marker, "basic:fileKey", LinkOption.NOFOLLOW_LINKS);
        require(markerKey != null, "task marker identity is unavailable");
        byte[] eventsBefore = Files.readAllBytes(bounded(attempt.resolve("runner-events.jsonl")));
        byte[] summaryBefore = Files.readAllBytes(bounded(attempt.resolve("runner-summary.json")));
        ObjectNode before = LinuxRuntimeJournal.readRetained(attempt, JUDGE, handle.attemptId(), MAPPER, 0, 0);
        write(evidence.resolve("runtime-state-before-stop.json"), before);
        ObjectNode previous = null;
        for (int stopNumber = 1; stopNumber <= 2; stopNumber++) {
            try {
                runner.cancel(handle);
                throw new IllegalStateException("cancel claimed complete recovery");
            } catch (IllegalStateException pending) {
                require(pending.getCause() == null && "Linux supervisor stopped; stage and volume recovery remains pending"
                        .equals(pending.getMessage()), "cancel did not report the expected pending recovery boundary");
            }
            ObjectNode stopped = LinuxRuntimeJournal.readRetained(attempt, JUDGE, handle.attemptId(), MAPPER, 0, 0);
            ObjectNode expected = before.deepCopy();
            expected.put("state", "CLEANUP_PENDING");
            ((ObjectNode) expected.path("supervisor")).put("state", "STOPPED");
            require(stopped.equals(expected), "stop changed identity/configuration or did not retain cleanup pending");
            require(previous == null || stopped.equals(previous), "repeated stop changed retained resource state");
            require(Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && markerKey.equals(Files.getAttribute(marker, "basic:fileKey", LinkOption.NOFOLLOW_LINKS)),
                    "stop removed or replaced the permanent task marker");
            write(evidence.resolve("runtime-state-after-stop-" + stopNumber + ".json"), stopped);
            previous = stopped;
        }
        require(MessageDigest.isEqual(eventsBefore, Files.readAllBytes(bounded(attempt.resolve("runner-events.jsonl"))))
                && MessageDigest.isEqual(summaryBefore, Files.readAllBytes(bounded(attempt.resolve("runner-summary.json")))),
                "post-completion stop changed trusted events or summary");
    }

    private static void awaitNormalSupervisorExit(LinuxSupervisorOptions options, SandboxRunHandle handle,
                                                   Path evidence) throws Exception {
        LinuxSupervisorCommandExecutor inspector = new LinuxSupervisorCommandExecutor(options.controlOutputLimitBytes());
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            require(remaining > 0, "completed supervisor did not exit before post-completion stop");
            LinuxContainerRunner.CommandResult result = inspector.run(List.of(options.hostRuntimeCommand(),
                    "--host", options.hostRuntimeEndpoint(), "container", "inspect", handle.runId()),
                    Duration.ofNanos(Math.min(remaining, options.commandTimeout().toNanos())));
            require(result.exitCode() == 0, "pre-stop supervisor inspection failed");
            JsonNode inspected = MAPPER.readTree(result.stdout());
            require(inspected.isArray() && inspected.size() == 1
                    && inspected.get(0).path("Id").isTextual()
                    && handle.runId().equals(inspected.get(0).path("Id").textValue()), "pre-stop supervisor identity differs");
            JsonNode state = inspected.get(0).path("State");
            require(state.path("Running").isBoolean(), "pre-stop supervisor running state is missing");
            if (!state.path("Running").booleanValue()) {
                require(state.path("Pid").isIntegralNumber() && state.path("Pid").canConvertToLong()
                        && state.path("Pid").longValue() == 0
                        && state.path("ExitCode").isIntegralNumber() && state.path("ExitCode").canConvertToLong()
                        && state.path("ExitCode").longValue() == 0
                        && state.path("Status").isTextual() && "exited".equals(state.path("Status").textValue()),
                        "supervisor did not exit normally before post-completion stop");
                write(evidence.resolve("supervisor-before-stop-inspect.json"), inspected.get(0));
                return;
            }
            Thread.sleep(50);
        }
    }

    private static void inspectFromFreshJvm(String[] arguments) throws Exception {
        Path storage = Path.of(arguments[1]);
        Path evidence = Path.of(arguments[2]);
        require(storage.equals(Path.of("/lab/java-storage")) && evidence.equals(Path.of("/lab/evidence/java")),
                "fresh-JVM discovery paths must remain in the dedicated private root");
        LinuxSupervisorOptions options = options(ENDPOINT, 0, 65532, arguments[3], arguments[4]);
        LinuxContainerRunner freshRunner = new LinuxContainerRunner(
                LinuxContainerRunner.Options.builder().supervisor(options).build(), null, MAPPER);
        LinuxAttemptDiscovery.Budget budget = new LinuxAttemptDiscovery.Budget(128, 8,
                2 * LinuxAttemptDiscovery.JOURNAL_READ_RESERVATION_BYTES, 1024);
        LinuxAttemptDiscovery.Report report = freshRunner.inspectRetainedAttempts(storage, budget);
        require(report.scanComplete() && report.stopReason() == LinuxAttemptDiscovery.Reason.SCAN_FINISHED
                && report.findings().size() == 1, "fresh JVM did not discover exactly one classified attempt");
        LinuxAttemptDiscovery.Finding finding = report.findings().get(0);
        String prefix = "judge-" + JUDGE + "/control/attempts/";
        require(finding.relativePath().startsWith(prefix), "fresh-JVM discovery returned a foreign task");
        String attempt = finding.relativePath().substring(prefix.length());
        require(UUID.fromString(attempt).toString().equals(attempt), "fresh-JVM discovery attempt identity differs");
        require(finding.reason() == LinuxAttemptDiscovery.Reason.RECOVERY_REQUIRED
                && "SUPERVISOR_STOPPED".equals(finding.phase()) && finding.stageEvidenceRequiresFreeze(),
                "fresh-JVM discovery lost unresolved recovery or stage-freeze requirements");
        require(report.directoryEntriesVisited() <= budget.maxDirectoryEntries()
                && report.journalReadBytesReserved() == LinuxAttemptDiscovery.JOURNAL_READ_RESERVATION_BYTES
                && MAPPER.writeValueAsBytes(report).length <= budget.maximumReportJsonBytes(),
                "fresh-JVM discovery exceeded its explicit observation budget");
        ObjectNode observation = MAPPER.createObjectNode().put("freshJvm", true).put("processId", ProcessHandle.current().pid())
                .put("handleProvided", false).put("runnerStartedInThisProcess", false).put("productRecoveryComplete", false)
                .put("maximumReportJsonBytes", budget.maximumReportJsonBytes());
        observation.set("budget", MAPPER.valueToTree(budget));
        observation.set("report", MAPPER.valueToTree(report));
        write(evidence.resolve("fresh-jvm-discovery.json"), observation);
        System.out.println(MAPPER.writeValueAsString(observation));
    }

    private static void prepareFromFreshJvm(String[] arguments) throws Exception {
        Path storage = Path.of(arguments[1]);
        Path evidence = Path.of(arguments[2]);
        String attemptId = arguments[5];
        require(storage.equals(Path.of("/lab/java-storage")) && evidence.equals(Path.of("/lab/evidence/java")),
                "fresh-JVM preparation paths must remain in the dedicated private root");
        LinuxSupervisorOptions options = options(ENDPOINT, 0, 65532, arguments[3], arguments[4]);
        LinuxContainerRunner freshRunner = new LinuxContainerRunner(
                LinuxContainerRunner.Options.builder().supervisor(options).build(), null, MAPPER);
        LinuxStageRecoveryPreparation.Preparation first = freshRunner.prepareRetainedStageRecovery(storage, JUDGE, attemptId);
        ObjectNode firstMarker = markerIdentity(storage, attemptId);
        LinuxStageRecoveryPreparation.Preparation second = freshRunner.prepareRetainedStageRecovery(storage, JUDGE, attemptId);
        ObjectNode secondMarker = markerIdentity(storage, attemptId);
        require(first.admissionClosed() && second.admissionClosed(), "fresh-JVM preparation did not close admission");
        require(first.scanComplete() && first.stages().size() == 6
                && first.stages().stream().allMatch(stage -> stage.containerIdPresent() && stage.outcomePresent()
                        && "CONFIRMED".equals(stage.creationState()) && "CLEANED".equals(stage.state()))
                && first.unresolvedEntries().isEmpty() && !first.unresolvedUnknown(),
                "fresh-JVM preparation did not strictly consume the six completed real stages");
        require(secondMarker.equals(firstMarker), "repeated preparation changed the closed marker identity");
        ObjectNode observation = MAPPER.createObjectNode().put("freshJvm", true).put("processId", ProcessHandle.current().pid())
                .put("attemptId", attemptId).put("handleProvided", false)
                .put("admissionClosedFirst", first.admissionClosed()).put("admissionClosedSecond", second.admissionClosed())
                .put("runtimeCallsNeverIssued", true).put("productRecoveryComplete", false)
                .put("unknownCreateBarrierEstablished", false);
        observation.set("stages", MAPPER.valueToTree(first.stages()));
        observation.set("markerFirst", firstMarker);
        observation.set("markerSecond", secondMarker);
        write(evidence.resolve("fresh-jvm-preparation.json"), observation);
        System.out.println(MAPPER.writeValueAsString(observation));
    }

    private static ObjectNode markerIdentity(Path storage, String attemptId) throws Exception {
        Path marker = storage.resolve("judge-" + JUDGE).resolve("control/attempts").resolve(attemptId)
                .resolve("stage-journal").resolve("stage-admission.closed");
        Map<String, Object> identity = Files.readAttributes(marker, "unix:ino,uid,mode,nlink,size", LinkOption.NOFOLLOW_LINKS);
        byte[] payload = Files.readAllBytes(bounded(marker));
        return MAPPER.createObjectNode().put("inode", ((Number) identity.get("ino")).longValue())
                .put("uid", ((Number) identity.get("uid")).intValue())
                .put("mode", ((Number) identity.get("mode")).intValue() & 07777)
                .put("links", ((Number) identity.get("nlink")).intValue())
                .put("bytes", payload.length).put("sha256", HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(payload)));
    }

    private static void verifyFreshJvmPreparation(LinuxSupervisorOptions options, SandboxRunHandle handle, Path task,
                                                  Path attempt, Path evidence) throws Exception {
        Path storage = task.getParent();
        Map<String, String> before = storageInventory(storage);
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = "/lab/java-bundle/classes:/lab/java-bundle/lib/*";
        require(executable.equals("/usr/lib/jvm/java-21-openjdk-amd64/bin/java"),
                "fresh-JVM runtime assets differ from the frozen private bundle");
        List<String> command = List.of(executable, "-Xms16m", "-Xmx96m", "-XX:MaxMetaspaceSize=64m",
                "-XX:ReservedCodeCacheSize=32m", "-XX:MaxDirectMemorySize=16m", "-XX:ActiveProcessorCount=1",
                "-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData", "-Djava.awt.headless=true",
                "-Duser.home=/nonexistent", "-Djava.io.tmpdir=/lab/java-tmp", "-Djna.tmpdir=/lab/java-tmp",
                "-cp", classpath, JavaSupervisorProbe.class.getName(), "--prepare-only", storage.toString(),
                evidence.toString(), options.supervisorImageId(), options.executorImageId(), handle.attemptId());
        LinuxContainerRunner.CommandResult result = new LinuxSupervisorCommandExecutor(options.controlOutputLimitBytes())
                .run(command, Duration.ofSeconds(60));
        ObjectNode process = MAPPER.createObjectNode().put("exitCode", result.exitCode())
                .put("stdout", result.stdout().substring(0, Math.min(4096, result.stdout().length())))
                .put("stderr", result.stderr().substring(0, Math.min(4096, result.stderr().length())));
        write(evidence.resolve("fresh-jvm-preparation-process.json"), process);
        require(result.exitCode() == 0, "fresh-JVM preparation failed");
        JsonNode observation = read(evidence.resolve("fresh-jvm-preparation.json"));
        require(observation.path("freshJvm").asBoolean() && observation.path("processId").isIntegralNumber()
                && observation.path("processId").canConvertToLong()
                && observation.path("processId").longValue() != ProcessHandle.current().pid(),
                "preparation was not observed in a distinct live JVM");
        require(observation.path("attemptId").asText().equals(handle.attemptId())
                && observation.path("admissionClosedFirst").asBoolean()
                && observation.path("admissionClosedSecond").asBoolean()
                && observation.path("runtimeCallsNeverIssued").asBoolean()
                && !observation.path("productRecoveryComplete").asBoolean()
                && !observation.path("unknownCreateBarrierEstablished").asBoolean(),
                "fresh-JVM preparation claim or identity differs");
        JsonNode stages = observation.path("stages");
        require(stages.isArray() && stages.size() == 6, "fresh-JVM preparation stage count differs");
        for (JsonNode stage : stages) {
            require("CONFIRMED".equals(stage.path("creationState").asText())
                    && "CLEANED".equals(stage.path("state").asText())
                    && stage.path("containerIdPresent").asBoolean() && stage.path("outcomePresent").asBoolean(),
                    "fresh-JVM preparation stage classification differs");
        }
        String canonical = "{\"admissionVersion\":1,\"attemptId\":\"" + handle.attemptId() + "\",\"state\":\"CLOSED\"}";
        JsonNode marker = observation.path("markerFirst");
        require(marker.equals(observation.path("markerSecond"))
                && marker.path("uid").asInt() == 0 && marker.path("mode").asInt() == 0600
                && marker.path("links").asInt() == 1 && marker.path("bytes").asInt() == canonical.getBytes().length
                && marker.path("sha256").asText().equals(hashOf(canonical.getBytes())),
                "fresh-JVM preparation marker is not the canonical CLOSED bytes");
        Path closed = attempt.resolve("stage-journal/stage-admission.closed");
        require(Files.isRegularFile(closed, LinkOption.NOFOLLOW_LINKS), "closed marker missing after preparation");
        Map<String, String> after = storageInventory(storage);
        Map<String, String> added = new LinkedHashMap<>(after);
        before.keySet().forEach(added::remove);
        String relative = "judge-" + JUDGE + "/control/attempts/" + handle.attemptId() + "/stage-journal/stage-admission.closed";
        require(added.keySet().equals(Set.of(relative)), "preparation changed more than the closed marker");
        require(after.keySet().containsAll(before.keySet()), "preparation removed retained evidence");
    }

    private static String hashOf(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private static void verifyFreshJvmDiscovery(LinuxSupervisorOptions options, SandboxRunHandle handle, Path task,
                                                Path evidence) throws Exception {
        Path storage = task.getParent();
        Map<String, String> before = storageInventory(storage);
        write(evidence.resolve("storage-before-discovery.json"), before);
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = "/lab/java-bundle/classes:/lab/java-bundle/lib/*";
        require(executable.equals("/usr/lib/jvm/java-21-openjdk-amd64/bin/java"),
                "fresh-JVM runtime assets differ from the frozen private bundle");
        List<String> command = List.of(executable, "-Xms16m", "-Xmx96m", "-XX:MaxMetaspaceSize=64m",
                "-XX:ReservedCodeCacheSize=32m", "-XX:MaxDirectMemorySize=16m", "-XX:ActiveProcessorCount=1",
                "-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData", "-Djava.awt.headless=true",
                "-Duser.home=/nonexistent", "-Djava.io.tmpdir=/lab/java-tmp", "-Djna.tmpdir=/lab/java-tmp",
                "-cp", classpath, JavaSupervisorProbe.class.getName(), "--discover-only", storage.toString(),
                evidence.toString(), options.supervisorImageId(), options.executorImageId());
        LinuxContainerRunner.CommandResult result = new LinuxSupervisorCommandExecutor(options.controlOutputLimitBytes())
                .run(command, Duration.ofSeconds(20));
        ObjectNode process = MAPPER.createObjectNode().put("exitCode", result.exitCode())
                .put("stdout", result.stdout().substring(0, Math.min(4096, result.stdout().length())))
                .put("stderr", result.stderr().substring(0, Math.min(4096, result.stderr().length())));
        write(evidence.resolve("fresh-jvm-process.json"), process);
        require(result.exitCode() == 0, "fresh-JVM discovery failed");
        JsonNode observation = read(evidence.resolve("fresh-jvm-discovery.json"));
        require(observation.path("processId").isIntegralNumber() && observation.path("processId").canConvertToLong()
                && observation.path("processId").longValue() > 0
                && observation.path("processId").longValue() != ProcessHandle.current().pid(),
                "discovery was not observed in a distinct live JVM");
        JsonNode findings = observation.path("report").path("findings");
        require(findings.isArray() && findings.size() == 1
                && ("judge-" + JUDGE + "/control/attempts/" + handle.attemptId())
                .equals(findings.get(0).path("relativePath").textValue()), "fresh JVM discovered the wrong retained attempt");
        Map<String, String> after = storageInventory(storage);
        require(before.equals(after), "read-only discovery changed storage inventory, identities or contents");
        write(evidence.resolve("storage-after-discovery.json"), after);
    }

    private static Map<String, String> storageInventory(Path storage) throws Exception {
        require(storage.equals(Path.of("/lab/java-storage")), "discovery inventory is outside the private storage root");
        return boundedStorageInventory(storage);
    }

    static Map<String, String> boundedStorageInventory(Path storage) throws Exception {
        Map<String, String> inventory = new LinkedHashMap<>();
        try (var entries = Files.walk(storage)) {
            List<Path> paths = entries.limit(129).toList();
            require(paths.size() <= 128, "discovery inventory exceeds its entry budget");
            for (Path path : paths) {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(!attributes.isSymbolicLink() && (attributes.isDirectory() || attributes.isRegularFile())
                        && attributes.fileKey() != null, "discovery inventory has an unsafe entry");
                Map<String, Object> identity = Files.readAttributes(path, "unix:uid,gid,mode,nlink", LinkOption.NOFOLLOW_LINKS);
                String fingerprint = (attributes.isDirectory() ? "directory" : "file") + ":" + attributes.fileKey()
                        + ":" + identity.get("uid") + ":" + identity.get("gid") + ":" + identity.get("mode")
                        + ":" + identity.get("nlink");
                if (attributes.isRegularFile()) {
                    fingerprint += ":" + hash(bounded(path));
                }
                inventory.put(storage.relativize(path).toString(), fingerprint);
            }
        }
        return inventory;
    }

    private static LinuxSupervisorOptions options(String endpoint, int writer, int workload, String supervisorImage, String executorImage) {
        return new LinuxSupervisorOptions("/usr/bin/docker", endpoint, "/usr/bin/docker", ENDPOINT,
                supervisorImage, executorImage, "/work", writer, 0, 0, 0, 65532, 65532, workload, 65532,
                new LinuxSupervisorOptions.ResourceLimits(256 * MEBIBYTE, 64, 0.5),
                new LinuxSupervisorOptions.ResourceLimits(64 * MEBIBYTE, 16, 0.25),
                Duration.ofSeconds(300), 64, 1.0, MEBIBYTE, 16 * MEBIBYTE, 32 * MEBIBYTE, 128 * 1024,
                Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    private static List<String> configurationNegatives(String supervisorImage, String executorImage) {
        List<String> rejected = new ArrayList<>();
        Map<String, Runnable> cases = new LinkedHashMap<>();
        cases.put("remote-runtime-endpoint", () -> options("tcp://127.0.0.1:2375", 0, 65532, supervisorImage, executorImage));
        cases.put("non-root-experimental-writer", () -> options(ENDPOINT, 1, 65532, supervisorImage, executorImage));
        cases.put("root-workload", () -> options(ENDPOINT, 0, 0, supervisorImage, executorImage));
        cases.put("mutable-supervisor-image", () -> options(ENDPOINT, 0, 65532, "supervisor:latest", executorImage));
        for (Map.Entry<String, Runnable> invalid : cases.entrySet()) {
            try {
                invalid.getValue().run();
                throw new IllegalStateException("invalid configuration was accepted: " + invalid.getKey());
            } catch (IllegalArgumentException expected) {
                rejected.add(invalid.getKey());
            }
        }
        return rejected;
    }

    private static List<JsonNode> stages(Path attempt, Path evidence, String attemptId, String volume) throws Exception {
        Path journalRoot = attempt.resolve("stage-journal");
        require(Files.isDirectory(journalRoot, LinkOption.NOFOLLOW_LINKS) && journalRoot.toRealPath().equals(journalRoot),
                "stage journal root is not a canonical non-linked directory");
        List<Path> paths;
        try (var entries = Files.list(journalRoot)) {
            paths = entries.limit(17).sorted().toList();
        }
        ObjectNode inventory = MAPPER.createObjectNode().put("observedEntryCount", paths.size())
                .put("entryLimit", 16).put("entryLimitExceeded", paths.size() > 16)
                .put("inventoryMayBeTruncated", paths.size() == 17);
        var observed = inventory.putArray("entries");
        for (Path path : paths) {
            observed.addObject().put("name", path.getFileName().toString())
                    .put("symbolicLink", Files.isSymbolicLink(path))
                    .put("directoryNoFollow", Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .put("regularFileNoFollow", Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        }
        byte[] encodedInventory = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(inventory);
        require(encodedInventory.length <= 64 * 1024, "stage directory inventory exceeds its byte bound");
        Files.write(evidence.resolve("stage-journal-directory-inventory.json"), encodedInventory);
        require(paths.size() <= 16, "stage journal directory entry count exceeds bound");
        Path runtimeClient = journalRoot.resolve("runtime-client");
        require(paths.stream().filter(runtimeClient::equals).count() == 1
                && Files.isDirectory(runtimeClient, LinkOption.NOFOLLOW_LINKS)
                && runtimeClient.toRealPath().equals(runtimeClient), "runtime-client must be a unique non-linked directory");
        Map<String, Object> clientAttributes = Files.readAttributes(runtimeClient, "unix:uid,gid,mode", LinkOption.NOFOLLOW_LINKS);
        require(((Number) clientAttributes.get("uid")).intValue() == 0
                && ((Number) clientAttributes.get("gid")).intValue() == 0
                && (((Number) clientAttributes.get("mode")).intValue() & 07777) == 0700,
                "runtime-client ownership or permissions differ");
        try (var entries = Files.newDirectoryStream(runtimeClient)) {
            require(!entries.iterator().hasNext(), "runtime-client directory must remain empty");
        }
        paths = paths.stream().filter(path -> !path.equals(runtimeClient)).toList();
        require(paths.stream().allMatch(path -> path.getFileName().toString().matches(
                "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.json")),
                "unknown entry in stage journal directory");
        require(paths.size() == 6, "expected exactly six real stage journals");
        Set<String> identities = new HashSet<>();
        Set<String> phases = new HashSet<>();
        List<JsonNode> result = new ArrayList<>();
        for (Path path : paths) {
            JsonNode stage = read(path);
            String stageId = stage.path("stage").asText();
            require(UUID.fromString(stageId).toString().equals(stageId) && path.getFileName().toString().equals(stageId + ".json"),
                    "stage file identity differs");
            String identity = stage.path("containerId").asText();
            require(identity.matches("[a-f0-9]{64}") && identities.add(identity), "stage container identity missing or reused");
            require(stage.path("journalVersion").isIntegralNumber() && stage.path("journalVersion").asInt() == 1
                    && stage.path("creationState").asText().equals("CONFIRMED")
                    && stage.path("state").asText().equals("CLEANED") && stage.path("attempt").asText().equals(attemptId)
                    && stage.path("volume").asText().equals(volume)
                    && stage.path("name").asText().equals("wjc-stage-" + stageId), "stage ownership or cleanup differs");
            JsonNode outcome = stage.path("outcome");
            require(outcome.path("status").asText().equals("SUCCESS") && outcome.path("exitCode").asInt(-1) == 0
                    && outcome.path("oomKilled").isBoolean() && !outcome.path("oomKilled").asBoolean(), "stage outcome differs");
            Map<String, String> labels = Map.of("io.webjudge.attempt", attemptId, "io.webjudge.stage", stageId,
                    "io.webjudge.role", stage.path("role").asText(), "io.webjudge.managed", "stage");
            require(stage.path("labels").equals(MAPPER.valueToTree(labels)), "stage labels differ");
            phases.add(stage.path("role").asText() + ":" + stage.path("phase").asText());
            result.add(stage);
        }
        require(phases.equals(Set.of("GENERATOR:compile", "USER:compile", "ORACLE:compile",
                "GENERATOR:execute", "USER:execute", "ORACLE:execute")), "six role/phase pairs differ");
        return result;
    }

    private static void retainPolledBatch(Path evidence, int pollNumber, List<SandboxTaskEvent> batch) throws IOException {
        int capturedCount = Math.min(16, batch.size());
        int messageLimit = 4096 / Math.max(1, capturedCount);
        ObjectNode snapshot = MAPPER.createObjectNode().put("pollNumber", pollNumber)
                .put("batchEventCount", batch.size()).put("capturedEventCount", capturedCount)
                .put("eventsOmitted", batch.size() > capturedCount).put("diagnosticOnly", true);
        var captured = snapshot.putArray("events");
        for (SandboxTaskEvent event : batch.subList(0, capturedCount)) {
            String message = event.message();
            ObjectNode entry = captured.addObject().put("type", event.type().name())
                    .put("caseNumber", event.caseNumber()).put("messageCharacterCount", message == null ? 0 : message.length());
            entry.put("message", message == null ? null : message.substring(0, Math.min(messageLimit, message.length())));
            entry.put("messageTruncated", message != null && message.length() > messageLimit);
        }
        byte[] encoded = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
        require(encoded.length <= 64 * 1024, "polled-batch diagnostic exceeds its byte bound");
        Files.write(evidence.resolve("java-last-polled-batch.json"), encoded);
        Files.write(evidence.resolve(String.format("java-polled-batch-%05d.json", pollNumber)), encoded);
    }

    private static void retainEvidence(Path root, Path evidence) throws IOException {
        try (var entries = Files.walk(root, 6)) {
            List<Path> paths = entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).limit(65).toList();
            require(paths.size() <= 64, "task evidence file count exceeded bound");
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (!(name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".cpp")
                        || Set.of("1.in", "1.out", "1.ans").contains(name))) {
                    continue;
                }
                Path target = evidence.resolve("retained-task").resolve(root.relativize(path));
                Files.createDirectories(target.getParent());
                Files.copy(bounded(path), target);
            }
        }
    }

    private static Path bounded(Path path) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().equals(path)
                && Files.size(path) <= 128 * 1024, "unsafe or oversized evidence file");
        return path;
    }

    private static JsonNode read(Path path) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(bounded(path)));
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(bounded(path))));
    }

    private static void write(Path path, Object value) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
