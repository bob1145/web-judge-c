package com.example.demo.service.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class LinuxConfirmedContainerRecovery {
    private static final int MAX_ENTRIES = 4096;
    private final LinuxSupervisorLauncher launcher;
    private final LinuxSupervisorOptions options;
    private final ObjectMapper mapper;

    LinuxConfirmedContainerRecovery(LinuxSupervisorLauncher launcher, LinuxSupervisorOptions options,
                                    ObjectMapper mapper) {
        this.launcher = launcher;
        this.options = options;
        this.mapper = LinuxStageRecord.strictMapper(mapper);
    }

    LinuxSupervisorLauncher.ContainerReclamation recoverLocked(Path task, String judgeId, String attemptId)
            throws IOException, InterruptedException {
        require(judgeId != null && !judgeId.isBlank() && judgeId.length() <= 256,
                "recovery requires an explicit judge identity");
        require(LinuxStageRecord.isCanonicalUuid(attemptId), "recovery requires a canonical attempt identity");
        Path control = task.resolve("control");
        Path attempts = control.resolve("attempts");
        Path attempt = attempts.resolve(attemptId);
        for (Path directory : List.of(control, attempts, attempt)) {
            launcher.requireTrustedDirectory(directory);
        }
        try (LinuxRuntimeJournal journal = LinuxRuntimeJournal.openExisting(attempt, judgeId, attemptId,
                mapper, options.configWriterUid(), options.configWriterGid())) {
            ObjectNode state = journal.read();
            LinuxRuntimeState.classifyUnresolvedSnapshot(state);
            JsonNode expectedConfiguration = mapper.readTree(mapper.writeValueAsBytes(launcher.configuration(task)));
            require(state.path("configuration").equals(expectedConfiguration),
                    "retained configuration differs from explicit recovery configuration");
            for (String resource : List.of("volume", "holder", "supervisor")) {
                require("CONFIRMED".equals(state.path(resource).path("creationState").textValue()),
                        "unconfirmed runtime resources require authoritative recovery");
            }
            String volume = state.path("volume").path("name").textValue();
            launcher.validateRecoveryVolume(state, task);
            Path stagePath = attempt.resolve("stage-journal");
            launcher.requireTrustedDirectory(stagePath);
            try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(stagePath,
                    options.configWriterUid())) {
                List<StageEntry> stages = readStages(lease, attemptId, volume);
                Set<String> recorded = new HashSet<>();
                require(recorded.add(state.path("holder").path("runtimeId").textValue()), "duplicate holder identity");
                require(recorded.add(state.path("supervisor").path("runtimeId").textValue()), "duplicate supervisor identity");
                for (StageEntry stage : stages) {
                    require(!"UNKNOWN".equals(stage.parsed().creationState()),
                            "UNKNOWN stage creation requires authoritative runtime termination");
                    if (stage.parsed().containerId() != null) {
                        require(recorded.add(stage.parsed().containerId()), "duplicate persisted container identity");
                    }
                }
                lease.publishClosedAdmission(attemptId);
                requireRecordedInventory(attemptId, recorded);
                verifyRuntimePresence(state, "supervisor");
                verifyRuntimePresence(state, "holder");
                for (StageEntry stage : stages) {
                    if (stage.parsed().containerId() != null) {
                        verifyStagePresence(state, stage);
                    }
                }
                stopRuntimeContainer(journal, state, "supervisor");
                int confirmedStages = 0;
                for (StageEntry stage : stages) {
                    reclaimStage(lease, state, stage, attemptId, volume);
                    if ("CONFIRMED".equals(stage.parsed().creationState())) {
                        confirmedStages++;
                    }
                }
                removeRuntimeContainer(journal, state, "supervisor");
                stopRuntimeContainer(journal, state, "holder");
                removeRuntimeContainer(journal, state, "holder");
                requireRecordedInventory(attemptId, recorded);
                for (String identity : recorded) {
                    require(observe(identity).isEmpty(), "retained container reappeared after reclamation");
                }
                lease.verifyIdentity();
                return new LinuxSupervisorLauncher.ContainerReclamation(confirmedStages, volume);
            }
        }
    }

    private List<StageEntry> readStages(LinuxStageJournalGuard.Lease lease, String attempt, String volume)
            throws IOException {
        List<String> names = new ArrayList<>();
        lease.verifyIdentity();
        try (var stream = Files.newDirectoryStream(lease.journalPath())) {
            for (Path entry : stream) {
                require(names.size() < MAX_ENTRIES, "stage journal inventory exceeded the recovery bound");
                names.add(entry.getFileName().toString());
            }
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        lease.verifyIdentity();
        names.sort(String::compareTo);
        List<StageEntry> stages = new ArrayList<>();
        for (String name : names) {
            if (name.equals("runtime-client")) {
                lease.requireTrustedEntryDirectory(name);
            } else if (!name.equals("stage-admission.closed") && !name.equals("stage-admission.pending")) {
                require(LinuxStageRecord.isJournalFileName(name), "unresolved stage journal evidence must be retained");
                byte[] payload = lease.readRegularEntry(name);
                LinuxStageRecord.Parsed parsed = LinuxStageRecord.parse(payload, payload.length, name,
                        attempt, volume, mapper);
                stages.add(new StageEntry(name, payload, parsed));
            }
        }
        return stages;
    }

    private void requireRecordedInventory(String attempt, Set<String> recorded) throws IOException, InterruptedException {
        Set<String> observed = listIds(List.of("label=io.webjudge.attempt=" + attempt));
        observed.addAll(listIds(List.of("label=io.webjudge.attempt=" + attempt, "label=io.webjudge.managed")));
        require(recorded.containsAll(observed), "unrecorded runtime containers require authoritative ownership recovery");
    }

    private Set<String> listIds(List<String> filters) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("container", "ls", "--all", "--quiet", "--no-trunc"));
        for (String filter : filters) {
            command.add("--filter");
            command.add(filter);
        }
        String output = launcher.recoveryCommand(command);
        Set<String> identities = new HashSet<>();
        if (output.isEmpty()) {
            return identities;
        }
        String[] lines = output.split("\n", -1);
        int count = lines.length;
        if (lines[count - 1].isEmpty()) {
            count--;
        }
        require(count <= MAX_ENTRIES, "runtime container inventory exceeded the recovery bound");
        for (int index = 0; index < count; index++) {
            String identity = lines[index];
            require(identity.matches("[0-9a-f]{64}"), "runtime inventory contains an incomplete container identity");
            require(identities.add(identity), "runtime inventory contains a duplicate container identity");
        }
        return identities;
    }

    private Optional<JsonNode> observe(String identity) throws IOException, InterruptedException {
        require(identity != null && identity.matches("[0-9a-f]{64}"), "recovery requires a complete container identity");
        Set<String> identities = listIds(List.of("id=" + identity));
        require(identities.isEmpty() || identities.equals(Set.of(identity)), "full-ID discovery returned a different container");
        if (identities.isEmpty()) {
            return Optional.empty();
        }
        JsonNode response = mapper.readTree(launcher.recoveryCommand(List.of("container", "inspect", identity)));
        require(response != null && response.isArray() && response.size() == 1 && response.get(0).isObject()
                && response.get(0).path("Id").isTextual()
                && identity.equals(response.get(0).path("Id").textValue()), "invalid full-ID container inspect response");
        return Optional.of(response.get(0));
    }

    private Optional<JsonNode> verifyRuntimePresence(ObjectNode state, String role) throws IOException, InterruptedException {
        JsonNode resource = state.path(role);
        Optional<JsonNode> container = observe(resource.path("runtimeId").textValue());
        if (container.isPresent()) {
            require(!"REMOVED".equals(resource.path("state").textValue()), "removed runtime container is still present");
            launcher.verifyRecoveryContainer(container.get(), state, role);
        }
        return container;
    }

    private Optional<JsonNode> verifyStagePresence(ObjectNode runtime, StageEntry stage) throws IOException, InterruptedException {
        Optional<JsonNode> container = observe(stage.parsed().containerId());
        if (container.isPresent()) {
            require(!Set.of("REMOVED", "CLEANED").contains(stage.parsed().state()),
                    "removed stage container is still present");
            LinuxStageContainerIdentity.verify(container.get(), runtime, stage.parsed(), options, mapper);
        }
        return container;
    }

    private void stopRuntimeContainer(LinuxRuntimeJournal journal, ObjectNode state, String role)
            throws IOException, InterruptedException {
        ObjectNode resource = (ObjectNode) state.get(role);
        if (Set.of("REMOVAL_REQUESTED", "REMOVED").contains(resource.path("state").textValue())) {
            Optional<JsonNode> retained = verifyRuntimePresence(state, role);
            if (retained.isPresent()) {
                requireStopped(retained.get());
            }
            return;
        }
        Optional<JsonNode> container = verifyRuntimePresence(state, role);
        resource.put("state", "STOP_REQUESTED");
        state.put("state", "CLEANUP_PENDING");
        writeRuntime(journal, state);
        if (container.isPresent()) {
            String identity = resource.path("runtimeId").textValue();
            require(identity.equals(launcher.recoveryCommand(List.of("container", "stop", "-t", "0", identity)).trim()),
                    "container stop returned a different identity");
            container = verifyRuntimePresence(state, role);
            if (container.isPresent()) {
                requireStopped(container.get());
            }
        }
        resource.put("state", "STOPPED");
        writeRuntime(journal, state);
    }

    private void removeRuntimeContainer(LinuxRuntimeJournal journal, ObjectNode state, String role)
            throws IOException, InterruptedException {
        ObjectNode resource = (ObjectNode) state.get(role);
        Optional<JsonNode> container = verifyRuntimePresence(state, role);
        if ("REMOVED".equals(resource.path("state").textValue())) {
            require(container.isEmpty(), "removed runtime container is still present");
            return;
        }
        require(Set.of("STOPPED", "REMOVAL_REQUESTED").contains(resource.path("state").textValue()),
                "runtime container removal requires a durable stop");
        if (container.isPresent()) {
            requireStopped(container.get());
        }
        resource.put("state", "REMOVAL_REQUESTED");
        writeRuntime(journal, state);
        String identity = resource.path("runtimeId").textValue();
        if (container.isPresent()) {
            require(identity.equals(launcher.recoveryCommand(List.of("container", "rm", identity)).trim()),
                    "container removal returned a different identity");
        }
        require(observe(identity).isEmpty(), "container removal was not confirmed");
        resource.put("state", "REMOVED");
        writeRuntime(journal, state);
    }

    private void reclaimStage(LinuxStageJournalGuard.Lease lease, ObjectNode runtime, StageEntry stage,
                              String attempt, String volume) throws IOException, InterruptedException {
        if ("NOT_REQUESTED".equals(stage.parsed().creationState())) {
            return;
        }
        Optional<JsonNode> container = verifyStagePresence(runtime, stage);
        if (Set.of("REMOVED", "CLEANED").contains(stage.parsed().state())) {
            require(container.isEmpty(), "removed stage container is still present");
            return;
        }
        StageEntry current = writeStage(lease, stage, stage.parsed().outcome() == null ? "STOPPING" : "REMOVING",
                attempt, volume);
        if (container.isPresent()) {
            String identity = current.parsed().containerId();
            require(identity.equals(launcher.recoveryCommand(List.of("container", "stop", "-t", "0", identity)).trim()),
                    "stage stop returned a different identity");
            container = verifyStagePresence(runtime, current);
            if (container.isPresent()) {
                requireStopped(container.get());
            }
        }
        current = writeStage(lease, current, "REMOVING", attempt, volume);
        if (container.isPresent()) {
            String identity = current.parsed().containerId();
            require(identity.equals(launcher.recoveryCommand(List.of("container", "rm", identity)).trim()),
                    "stage removal returned a different identity");
        }
        require(observe(current.parsed().containerId()).isEmpty(), "stage removal was not confirmed");
        writeStage(lease, current, "REMOVED", attempt, volume);
    }

    private StageEntry writeStage(LinuxStageJournalGuard.Lease lease, StageEntry current, String state,
                                  String attempt, String volume) throws IOException {
        ObjectNode updated = (ObjectNode) mapper.readTree(current.payload());
        updated.put("state", state);
        byte[] payload = mapper.writeValueAsBytes(updated);
        LinuxStageRecord.Parsed parsed = LinuxStageRecord.parse(payload, payload.length, current.name(), attempt, volume, mapper);
        lease.replaceStageRecord(current.name(), current.payload(), payload);
        return new StageEntry(current.name(), payload, parsed);
    }

    private void writeRuntime(LinuxRuntimeJournal journal, ObjectNode state) throws IOException {
        LinuxRuntimeState.classifyUnresolvedSnapshot(state);
        journal.write(state);
    }

    private static void requireStopped(JsonNode container) throws IOException {
        JsonNode state = container.path("State");
        require(state.path("Running").isBoolean() && !state.path("Running").booleanValue()
                && state.path("Pid").isIntegralNumber() && state.path("Pid").canConvertToLong()
                && state.path("Pid").longValue() == 0, "container stop was not confirmed");
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    private record StageEntry(String name, byte[] payload, LinuxStageRecord.Parsed parsed) { }
}
