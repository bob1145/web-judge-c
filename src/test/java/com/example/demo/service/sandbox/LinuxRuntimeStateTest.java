package com.example.demo.service.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinuxRuntimeStateTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ATTEMPT = "bf072e8b-3418-4e37-84f8-ed034a7d275c";
    private static final String HOLDER_ID = "c".repeat(64);
    private static final String SUPERVISOR_ID = "d".repeat(64);
    private static final List<SnapshotCase> VALID = List.of(
            new SnapshotCase("PREPARED", "NOT_REQUESTED", "NOT_REQUESTED", "NOT_REQUESTED", LinuxRuntimeState.SnapshotPhase.PREPARED),
            new SnapshotCase("CREATE_UNKNOWN", "CREATE_REQUESTED", "NOT_REQUESTED", "NOT_REQUESTED", LinuxRuntimeState.SnapshotPhase.VOLUME_CREATE_UNKNOWN),
            new SnapshotCase("PREPARING", "CREATED", "NOT_REQUESTED", "NOT_REQUESTED", LinuxRuntimeState.SnapshotPhase.VOLUME_CONFIRMED),
            new SnapshotCase("CREATE_UNKNOWN", "CREATED", "CREATE_REQUESTED", "NOT_REQUESTED", LinuxRuntimeState.SnapshotPhase.HOLDER_CREATE_UNKNOWN),
            new SnapshotCase("PREPARING", "CREATED", "CREATED", "NOT_REQUESTED", LinuxRuntimeState.SnapshotPhase.HOLDER_CONFIRMED),
            new SnapshotCase("PREPARING", "CREATED", "START_REQUESTED", "NOT_REQUESTED", LinuxRuntimeState.SnapshotPhase.HOLDER_START_REQUESTED),
            new SnapshotCase("PREPARING", "CREATED", "STARTED", "NOT_REQUESTED", LinuxRuntimeState.SnapshotPhase.HOLDER_STARTED),
            new SnapshotCase("CREATE_UNKNOWN", "CREATED", "STARTED", "CREATE_REQUESTED", LinuxRuntimeState.SnapshotPhase.SUPERVISOR_CREATE_UNKNOWN),
            new SnapshotCase("PREPARING", "CREATED", "STARTED", "CREATED", LinuxRuntimeState.SnapshotPhase.SUPERVISOR_CONFIRMED),
            new SnapshotCase("PREPARING", "CREATED", "STARTED", "START_REQUESTED", LinuxRuntimeState.SnapshotPhase.SUPERVISOR_START_REQUESTED),
            new SnapshotCase("STARTED", "CREATED", "STARTED", "STARTED", LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STARTED),
            new SnapshotCase("CLEANUP_PENDING", "CREATED", "STARTED", "STOP_REQUESTED", LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOP_REQUESTED),
            new SnapshotCase("CLEANUP_PENDING", "CREATED", "STARTED", "STOPPED", LinuxRuntimeState.SnapshotPhase.SUPERVISOR_STOPPED));
    private static final List<SnapshotCase> CONFIRMED_CLEANUP = List.of(
            new SnapshotCase("CLEANUP_PENDING", "CREATED", "STARTED", "REMOVAL_REQUESTED", LinuxRuntimeState.SnapshotPhase.SUPERVISOR_REMOVAL_REQUESTED),
            new SnapshotCase("CLEANUP_PENDING", "CREATED", "STARTED", "REMOVED", LinuxRuntimeState.SnapshotPhase.SUPERVISOR_REMOVED),
            new SnapshotCase("CLEANUP_PENDING", "CREATED", "STOP_REQUESTED", "REMOVED", LinuxRuntimeState.SnapshotPhase.HOLDER_STOP_REQUESTED),
            new SnapshotCase("CLEANUP_PENDING", "CREATED", "STOPPED", "REMOVED", LinuxRuntimeState.SnapshotPhase.HOLDER_STOPPED),
            new SnapshotCase("CLEANUP_PENDING", "CREATED", "REMOVAL_REQUESTED", "REMOVED", LinuxRuntimeState.SnapshotPhase.HOLDER_REMOVAL_REQUESTED),
            new SnapshotCase("CLEANUP_PENDING", "CREATED", "REMOVED", "REMOVED", LinuxRuntimeState.SnapshotPhase.HOLDER_REMOVED));

    @ParameterizedTest
    @MethodSource("validPersistedSnapshots")
    void classifiesEveryPersistedSnapshotWithoutMutation(SnapshotCase expected) throws Exception {
        ObjectNode state = snapshot(expected.global(), expected.volume(), expected.holder(), expected.supervisor());
        JsonNode original = MAPPER.readTree(MAPPER.writeValueAsBytes(state));
        assertEquals(expected.phase(), LinuxRuntimeState.classifyUnresolvedSnapshot(original));
        assertEquals(state, original);
    }

    private static Stream<SnapshotCase> validPersistedSnapshots() {
        return VALID.stream();
    }

    @ParameterizedTest
    @MethodSource("confirmedCleanupSnapshots")
    void classifiesOnlyConfirmedCleanupSnapshotsWhileRetainingVolumeAndContainerIdentities(SnapshotCase expected)
            throws Exception {
        ObjectNode state = snapshot(expected.global(), expected.volume(), expected.holder(), expected.supervisor());
        JsonNode retained = MAPPER.readTree(MAPPER.writeValueAsBytes(state));
        assertEquals(expected.phase(), LinuxRuntimeState.classifyUnresolvedSnapshot(retained));
        assertEquals("CLEANUP_PENDING", retained.path("state").textValue());
        assertEquals("CREATED", retained.path("volume").path("state").textValue());
        assertEquals("CONFIRMED", retained.path("volume").path("creationState").textValue());
        for (String role : List.of("volume", "holder", "supervisor")) {
            assertEquals(LinuxRuntimeState.resourceName(role, ATTEMPT), retained.path(role).path("name").textValue());
        }
        assertEquals("CONFIRMED", retained.path("holder").path("creationState").textValue());
        assertEquals("CONFIRMED", retained.path("supervisor").path("creationState").textValue());
        assertEquals(HOLDER_ID, retained.path("holder").path("runtimeId").textValue());
        assertEquals(SUPERVISOR_ID, retained.path("supervisor").path("runtimeId").textValue());
        assertEquals(state, retained);
    }

    private static Stream<SnapshotCase> confirmedCleanupSnapshots() {
        return CONFIRMED_CLEANUP.stream();
    }

    @Test
    void retainsOriginalThirteenPhasesAndAppendsExactlySixConfirmedCleanupPhases() {
        List<LinuxRuntimeState.SnapshotPhase> phases = List.of(LinuxRuntimeState.SnapshotPhase.values());
        assertEquals(13, VALID.size());
        assertEquals(6, CONFIRMED_CLEANUP.size());
        assertEquals(19, phases.size());
        assertEquals(VALID.stream().map(SnapshotCase::phase).toList(), phases.subList(0, 13));
        assertEquals(CONFIRMED_CLEANUP.stream().map(SnapshotCase::phase).toList(), phases.subList(13, 19));
    }

    @Test
    void rejectsEveryOtherGlobalResourceCombination() throws Exception {
        Set<List<String>> valid = new HashSet<>();
        VALID.forEach(state -> valid.add(List.of(state.global(), state.volume(), state.holder(), state.supervisor())));
        List<String> resources = List.of("NOT_REQUESTED", "CREATE_REQUESTED", "CREATED", "START_REQUESTED",
                "STARTED", "STOP_REQUESTED", "STOPPED");
        int rejected = 0;
        for (String global : List.of("PREPARED", "CREATE_UNKNOWN", "PREPARING", "STARTED", "CLEANUP_PENDING")) {
            for (String volume : resources) {
                for (String holder : resources) {
                    for (String supervisor : resources) {
                        List<String> combination = List.of(global, volume, holder, supervisor);
                        if (!valid.contains(combination)) {
                            ObjectNode state = snapshot(global, volume, holder, supervisor);
                            assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(state),
                                    combination.toString());
                            rejected++;
                        }
                    }
                }
            }
        }
        assertEquals(1702, rejected);
    }

    @Test
    void rejectsEveryAdditionalRemovalStateCombinationOutsideTheSixConfirmedCleanupSnapshots() throws Exception {
        Set<List<String>> valid = new HashSet<>();
        CONFIRMED_CLEANUP.forEach(state -> valid.add(List.of(state.global(), state.volume(), state.holder(), state.supervisor())));
        List<String> resources = List.of("NOT_REQUESTED", "CREATE_REQUESTED", "CREATED", "START_REQUESTED",
                "STARTED", "STOP_REQUESTED", "STOPPED", "REMOVAL_REQUESTED", "REMOVED");
        Set<String> removalStates = Set.of("REMOVAL_REQUESTED", "REMOVED");
        int rejected = 0;
        for (String global : List.of("PREPARED", "CREATE_UNKNOWN", "PREPARING", "STARTED", "CLEANUP_PENDING")) {
            for (String volume : resources) {
                for (String holder : resources) {
                    for (String supervisor : resources) {
                        if (!removalStates.contains(volume) && !removalStates.contains(holder)
                                && !removalStates.contains(supervisor)) {
                            continue;
                        }
                        List<String> combination = List.of(global, volume, holder, supervisor);
                        if (!valid.contains(combination)) {
                            ObjectNode state = snapshot(global, volume, holder, supervisor);
                            assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(state),
                                    combination.toString());
                            rejected++;
                        }
                    }
                }
            }
        }
        assertEquals(1924, rejected);
    }

    @ParameterizedTest
    @MethodSource("confirmedCleanupSnapshots")
    void confirmedCleanupRequiresCompleteV1FieldsAndOriginalIdentities(SnapshotCase expected) throws Exception {
        ObjectNode state = snapshot(expected.global(), expected.volume(), expected.holder(), expected.supervisor());
        for (String field : List.of("state", "volume", "volume.name", "volume.creationState", "volume.state",
                "volume.runtimeId", "volume.mountpoint", "holder.name", "holder.creationState", "holder.state",
                "holder.runtimeId", "supervisor.name", "supervisor.creationState", "supervisor.state", "supervisor.runtimeId")) {
            for (String shape : List.of("MISSING", "null")) {
                ObjectNode changed = state.deepCopy();
                setField(changed, field, shape);
                assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(changed),
                        expected.phase() + ":" + field + ":" + shape);
            }
        }
        for (String role : List.of("holder", "supervisor")) {
            for (String creation : List.of("UNKNOWN", "NOT_REQUESTED", "CLEANED", "BOGUS")) {
                ObjectNode changed = state.deepCopy();
                ((ObjectNode) changed.get(role)).put("creationState", creation);
                assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(changed));
            }
            for (String identity : List.of("c".repeat(63), "c".repeat(65), "C".repeat(64), "g".repeat(64),
                    role.equals("holder") ? SUPERVISOR_ID : HOLDER_ID)) {
                ObjectNode changed = state.deepCopy();
                ((ObjectNode) changed.get(role)).put("runtimeId", identity);
                assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(changed));
            }
            ObjectNode changedName = state.deepCopy();
            ((ObjectNode) changedName.get(role)).put("name", LinuxRuntimeState.resourceName(role,
                    "0f45f4a6-1a0f-4796-9ed0-ab8f1469d9c8"));
            assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(changedName));
            for (String field : List.of("extra", "mountpoint")) {
                ObjectNode changed = state.deepCopy();
                ((ObjectNode) changed.get(role)).putNull(field);
                assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(changed));
            }
        }
        ObjectNode extraVolume = state.deepCopy();
        ((ObjectNode) extraVolume.get("volume")).putNull("extra");
        assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(extraVolume));
    }

    @ParameterizedTest
    @MethodSource("confirmedCleanupSnapshots")
    void confirmedCleanupNeverClassifiesAsCleanedOrUnknownTerminalState(SnapshotCase expected) throws Exception {
        ObjectNode state = snapshot(expected.global(), expected.volume(), expected.holder(), expected.supervisor());
        for (String global : List.of("CLEANED", "REMOVED", "ABSENT", "PLANNED", "RECOVERED", "CREATE_UNKNOWN")) {
            ObjectNode changed = state.deepCopy().put("state", global);
            assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(changed));
        }
        for (String role : List.of("volume", "holder", "supervisor")) {
            ObjectNode changed = state.deepCopy();
            ((ObjectNode) changed.get(role)).put("state", "CLEANED");
            assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(changed));
        }
    }

    @ParameterizedTest
    @MethodSource("malformedSnapshots")
    void rejectsMalformedFieldsWithoutMutation(String path, String json) throws Exception {
        ObjectNode state = startedSnapshot();
        setField(state, path, json);
        ObjectNode original = state.deepCopy();
        assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(state));
        assertEquals(original, state);
    }

    private static Stream<Arguments> malformedSnapshots() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("state", "volume.state", "holder.state", "supervisor.state", "judgeId", "attemptId",
                "provider", "volume.name", "holder.name", "supervisor.name", "volume.creationState", "holder.creationState",
                "supervisor.creationState", "volume.runtimeId", "holder.runtimeId", "supervisor.runtimeId", "volume.mountpoint")) {
            for (String shape : List.of("false", "1", "{}", "[]", "\"\"")) {
                cases.add(Arguments.of(field, shape));
            }
        }
        for (String field : List.of("version", "configuration.configWriterUid", "configuration.configWriterGid",
                "configuration.supervisorUid", "configuration.supervisorGid", "configuration.holderUid", "configuration.holderGid",
                "configuration.workloadUid", "configuration.workloadGid", "configuration.holderLifetimeSeconds",
                "configuration.stagePidsLimit", "configuration.inputLimitBytes", "configuration.artifactLimitBytes",
                "configuration.volumeLimitBytes", "configuration.controlOutputLimitBytes", "configuration.commandTimeoutMillis",
                "configuration.stageCommandTimeoutMillis", "configuration.supervisorLimits.memoryBytes",
                "configuration.supervisorLimits.pidsLimit", "configuration.holderLimits.memoryBytes", "configuration.holderLimits.pidsLimit")) {
            for (String shape : List.of("false", "1.0", "\"1\"", "[]", "{}", "9223372036854775808")) {
                cases.add(Arguments.of(field, shape));
            }
        }
        for (String field : List.of("configuration.stageCpus", "configuration.supervisorLimits.cpus", "configuration.holderLimits.cpus")) {
            for (String shape : List.of("false", "1", "\"1.0\"", "[]", "{}", "0.0", "-1.0", "1e400")) {
                cases.add(Arguments.of(field, shape));
            }
        }
        for (String field : List.of("configuration.hostRuntimeCommand", "configuration.hostRuntimeEndpoint",
                "configuration.supervisorRuntimeCommand", "configuration.supervisorRuntimeEndpoint", "configuration.supervisorImageId",
                "configuration.executorImageId", "configuration.workDir", "configuration.workMount")) {
            for (String shape : List.of("false", "1", "[]", "{}", "\"\"", "\"relative\"")) {
                cases.add(Arguments.of(field, shape));
            }
        }
        for (String field : List.of("configuration", "configuration.holderLimits", "configuration.supervisorLimits", "volume", "holder", "supervisor")) {
            for (String shape : List.of("false", "1", "[]", "\"object\"")) {
                cases.add(Arguments.of(field, shape));
            }
        }
        for (String field : List.of("volume.creationState", "holder.creationState", "supervisor.creationState")) {
            for (String shape : List.of("\"NOT_REQUESTED\"", "\"UNKNOWN\"", "\"PLANNED\"", "\"BOGUS\"")) {
                cases.add(Arguments.of(field, shape));
            }
        }
        for (String field : List.of("extra", "configuration.extra", "volume.extra", "holder.extra", "supervisor.extra",
                "configuration.holderLimits.extra", "configuration.supervisorLimits.extra", "holder.mountpoint", "supervisor.mountpoint")) {
            cases.add(Arguments.of(field, "null"));
        }
        cases.add(Arguments.of("version", "2"));
        cases.add(Arguments.of("judgeId", "\" \""));
        cases.add(Arguments.of("judgeId", "\"" + "j".repeat(257) + "\""));
        cases.add(Arguments.of("attemptId", "\"" + ATTEMPT.toUpperCase() + "\""));
        cases.add(Arguments.of("attemptId", "\"1-1-1-1-1\""));
        cases.add(Arguments.of("provider", "\"legacy\""));
        cases.add(Arguments.of("holder.runtimeId", "\"" + SUPERVISOR_ID + "\""));
        cases.add(Arguments.of("volume.runtimeId", "\"other-volume\""));
        for (String field : List.of("holder.runtimeId", "supervisor.runtimeId")) {
            for (String identity : List.of("d".repeat(63), "d".repeat(65), "D".repeat(64), "g".repeat(64), " " + "d".repeat(64))) {
                cases.add(Arguments.of(field, "\"" + identity + "\""));
            }
        }
        for (String field : List.of("volume.name", "holder.name", "supervisor.name")) {
            cases.add(Arguments.of(field, "\"wjc-supervisor-other\""));
        }
        for (String field : List.of("configuration.workDir", "volume.mountpoint")) {
            for (String path : List.of("/", "/root/", "/root//task", "/root/./task", "/root/../task", "/root task", "/root,task", "/root\\task", "/root task")) {
                try {
                    cases.add(Arguments.of(field, MAPPER.writeValueAsString(path)));
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        }
        for (String field : List.of("configuration.configWriterUid", "configuration.configWriterGid",
                "configuration.supervisorUid", "configuration.supervisorGid")) {
            cases.add(Arguments.of(field, "1"));
        }
        for (String field : List.of("configuration.holderUid", "configuration.holderGid", "configuration.workloadUid",
                "configuration.workloadGid", "configuration.stagePidsLimit", "configuration.supervisorLimits.pidsLimit",
                "configuration.holderLimits.pidsLimit")) {
            cases.add(Arguments.of(field, "2147483648"));
            cases.add(Arguments.of(field, "-1"));
        }
        for (String field : List.of("configuration.workloadUid", "configuration.workloadGid", "configuration.holderLifetimeSeconds",
                "configuration.stagePidsLimit", "configuration.inputLimitBytes", "configuration.artifactLimitBytes",
                "configuration.volumeLimitBytes", "configuration.controlOutputLimitBytes", "configuration.commandTimeoutMillis",
                "configuration.stageCommandTimeoutMillis", "configuration.supervisorLimits.memoryBytes", "configuration.holderLimits.memoryBytes")) {
            cases.add(Arguments.of(field, "0"));
        }
        cases.add(Arguments.of("configuration.controlOutputLimitBytes", "2147483648"));
        cases.add(Arguments.of("configuration.commandTimeoutMillis", "9223372036854775807"));
        cases.add(Arguments.of("configuration.holderLifetimeSeconds", "9223372036854775807"));
        cases.add(Arguments.of("configuration.workMount", "\"/run/runtime/nested\""));
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("missingOrNullFields")
    void rejectsEveryMissingOrNullRequiredField(String path, String json) throws Exception {
        ObjectNode state = startedSnapshot();
        setField(state, path, json);
        assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(state));
    }

    private static Stream<Arguments> missingOrNullFields() {
        List<String> fields = new ArrayList<>();
        addFieldPaths(startedSnapshot(), "", fields);
        return fields.stream().flatMap(field -> Stream.of(Arguments.of(field, "MISSING"), Arguments.of(field, "null")));
    }

    private static void addFieldPaths(ObjectNode object, String prefix, List<String> paths) {
        object.fields().forEachRemaining(field -> {
            String path = prefix + field.getKey();
            paths.add(path);
            if (field.getValue().isObject()) {
                addFieldPaths((ObjectNode) field.getValue(), path + ".", paths);
            }
        });
    }

    @Test
    void unconfirmedResourcesCannotRetainRuntimeIdentityOrMountpoint() throws Exception {
        for (SnapshotCase expected : VALID) {
            ObjectNode state = snapshot(expected.global(), expected.volume(), expected.holder(), expected.supervisor());
            for (String role : List.of("volume", "holder", "supervisor")) {
                if (!state.path(role).path("creationState").textValue().equals("CONFIRMED")) {
                    for (String field : List.of("runtimeId", "mountpoint")) {
                        ObjectNode changed = state.deepCopy();
                        ((ObjectNode) changed.get(role)).put(field, "unconfirmed-value");
                        assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(changed));
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLEANED", "REMOVED", "ABSENT", "PLANNED", "RECOVERED"})
    void neverClassifiesUnsupportedStatesAsRecovered(String unsupported) {
        ObjectNode state = startedSnapshot().put("state", unsupported);
        assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(state));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "false", "1", "\"object\""})
    void rejectsNonObjectSnapshots(String json) throws Exception {
        assertThrows(IOException.class, () -> LinuxRuntimeState.classifyUnresolvedSnapshot(MAPPER.readTree(json)));
    }

    private static void setField(ObjectNode root, String field, String json) throws IOException {
        String[] parts = field.split("\\.");
        ObjectNode parent = root;
        for (int index = 0; index < parts.length - 1; index++) {
            parent = (ObjectNode) parent.get(parts[index]);
        }
        if (json.equals("MISSING")) {
            parent.remove(parts[parts.length - 1]);
        } else {
            parent.set(parts[parts.length - 1], MAPPER.readTree(json));
        }
    }

    private static ObjectNode startedSnapshot() {
        return snapshot("STARTED", "CREATED", "STARTED", "STARTED");
    }

    private static ObjectNode snapshot(String global, String volume, String holder, String supervisor) {
        ObjectNode state = MAPPER.createObjectNode().put("version", 1).put("judgeId", "judge-supervisor")
                .put("attemptId", ATTEMPT).put("provider", "linux-container").put("state", global);
        state.set("configuration", configuration());
        state.set("volume", resource("volume", volume));
        state.set("holder", resource("holder", holder));
        state.set("supervisor", resource("supervisor", supervisor));
        return state;
    }

    private static ObjectNode resource(String role, String state) {
        String name = "wjc-" + switch (role) {
            case "volume" -> "stage-volume-";
            case "holder" -> "stage-holder-";
            default -> "supervisor-";
        } + ATTEMPT;
        String creation = state.equals("NOT_REQUESTED") ? "NOT_REQUESTED"
                : state.equals("CREATE_REQUESTED") ? "UNKNOWN" : "CONFIRMED";
        ObjectNode resource = MAPPER.createObjectNode().put("name", name).put("creationState", creation).put("state", state);
        if (creation.equals("CONFIRMED")) {
            resource.put("runtimeId", role.equals("volume") ? name : role.equals("holder") ? HOLDER_ID : SUPERVISOR_ID);
            if (role.equals("volume")) {
                resource.put("mountpoint", "/var/lib/wjc/volumes/stage/_data");
            }
        }
        return resource;
    }

    private static ObjectNode configuration() {
        ObjectNode config = MAPPER.createObjectNode().put("hostRuntimeCommand", "/usr/bin/docker")
                .put("hostRuntimeEndpoint", "unix:///run/docker.sock").put("supervisorRuntimeCommand", "/usr/bin/docker")
                .put("supervisorRuntimeEndpoint", "unix:///run/runtime/docker.sock")
                .put("supervisorImageId", "sha256:" + "a".repeat(64)).put("executorImageId", "sha256:" + "b".repeat(64))
                .put("workDir", "/srv/tasks/task").put("workMount", "/work")
                .put("configWriterUid", 0).put("configWriterGid", 0).put("supervisorUid", 0).put("supervisorGid", 0)
                .put("holderUid", 0).put("holderGid", 0).put("workloadUid", 65534).put("workloadGid", 65534)
                .put("holderLifetimeSeconds", 60).put("stagePidsLimit", 32).put("stageCpus", 1.0)
                .put("inputLimitBytes", 1024).put("artifactLimitBytes", 2048).put("volumeLimitBytes", 4096)
                .put("controlOutputLimitBytes", 8192).put("commandTimeoutMillis", 1000).put("stageCommandTimeoutMillis", 2000);
        config.putObject("supervisorLimits").put("memoryBytes", 67108864).put("pidsLimit", 64).put("cpus", 1.0);
        config.putObject("holderLimits").put("memoryBytes", 8388608).put("pidsLimit", 8).put("cpus", 0.5);
        return config;
    }

    private record SnapshotCase(String global, String volume, String holder, String supervisor,
                                LinuxRuntimeState.SnapshotPhase phase) { }
}
