package com.example.demo.service.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxStageContainerIdentityTest {
    private static final ObjectMapper MAPPER = LinuxStageRecord.strictMapper(new ObjectMapper());
    private static final String ATTEMPT = "67b76a6a-8268-45f3-88b4-ae50f7455083";
    private static final String STAGE = "3c1a13c1-adc1-4313-a793-34fa9c518d43";
    private static final String CONTAINER_ID = "e".repeat(64);
    private static final String IMAGE = "sha256:" + "a".repeat(64);
    private static final String VOLUME = LinuxRuntimeState.resourceName("volume", ATTEMPT);
    private static final String MOUNTPOINT = "/var/lib/wjc/volumes/identity-test/_data";
    private static final List<String> TEXT_FIELDS = List.of(
            "/Id", "/Name", "/Image", "/Config/User", "/Config/WorkingDir", "/HostConfig/NetworkMode",
            "/HostConfig/PidMode", "/HostConfig/UTSMode", "/HostConfig/UsernsMode", "/HostConfig/IpcMode",
            "/HostConfig/CgroupnsMode", "/HostConfig/LogConfig/Type",
            "/Mounts/0/Type", "/Mounts/0/Name", "/Mounts/0/Source", "/Mounts/0/Destination", "/Mounts/0/Driver",
            "/Mounts/1/Type", "/Mounts/1/Name", "/Mounts/1/Source", "/Mounts/1/Destination", "/Mounts/1/Driver",
            "/HostConfig/Mounts/0/Type", "/HostConfig/Mounts/0/Source", "/HostConfig/Mounts/0/Target",
            "/HostConfig/Mounts/0/VolumeOptions/Subpath", "/HostConfig/Mounts/1/Type",
            "/HostConfig/Mounts/1/Source", "/HostConfig/Mounts/1/Target",
            "/HostConfig/Mounts/1/VolumeOptions/Subpath");
    private static final Map<String, Boolean> FIXED_BOOLEANS = Map.of(
            "/HostConfig/Privileged", false, "/HostConfig/ReadonlyRootfs", true,
            "/Mounts/0/RW", false, "/Mounts/1/RW", true,
            "/HostConfig/Mounts/0/ReadOnly", true, "/HostConfig/Mounts/1/ReadOnly", false,
            "/HostConfig/Mounts/0/VolumeOptions/NoCopy", true,
            "/HostConfig/Mounts/1/VolumeOptions/NoCopy", true);
    private static final List<String> NUMERIC_FIELDS = List.of("/HostConfig/Memory", "/HostConfig/MemorySwap",
            "/HostConfig/PidsLimit", "/HostConfig/NanoCpus", "/State/Pid");
    private static final List<String> ARRAY_EXTRAS = List.of("/HostConfig/CapAdd", "/HostConfig/Devices",
            "/HostConfig/DeviceRequests", "/HostConfig/Binds", "/HostConfig/VolumesFrom", "/HostConfig/Links");
    private static final List<String> MAP_EXTRAS = List.of("/HostConfig/Tmpfs", "/HostConfig/PortBindings",
            "/HostConfig/Mounts/0/VolumeOptions/DriverConfig", "/HostConfig/Mounts/1/VolumeOptions/DriverConfig");
    private ObjectNode container;
    private ObjectNode runtime;
    private LinuxStageRecord.Parsed stage;
    private LinuxSupervisorOptions options;

    @BeforeEach
    void prepare() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/sandbox/linux-stage-inspect-real.json")) {
            assertNotNull(input);
            container = (ObjectNode) MAPPER.readTree(input);
        }
        options = options(IMAGE, 65531, 65531, 32, 0.5);
        container.put("Id", CONTAINER_ID).put("Name", "/" + LinuxStageRecord.expectedName(STAGE)).put("Image", IMAGE);
        ObjectNode config = (ObjectNode) container.get("Config");
        config.put("User", options.workloadUid() + ":" + options.workloadGid()).put("Image", IMAGE);
        config.set("Labels", MAPPER.valueToTree(LinuxStageRecord.expectedLabels(ATTEMPT, STAGE, "USER")));
        for (JsonNode mount : container.get("Mounts")) {
            ((ObjectNode) mount).put("Name", VOLUME).put("Source", MOUNTPOINT);
        }
        for (JsonNode declaration : container.path("HostConfig").path("Mounts")) {
            ((ObjectNode) declaration).put("Source", VOLUME);
            ((ObjectNode) declaration.get("VolumeOptions")).put("Subpath", STAGE + declaration.get("Target").textValue());
        }
        runtime = MAPPER.createObjectNode().put("attemptId", ATTEMPT);
        runtime.putObject("volume").put("name", VOLUME).put("creationState", "CONFIRMED")
                .put("state", "CREATED").put("runtimeId", VOLUME).put("mountpoint", MOUNTPOINT);
        stage = parsed("USER", "compile", "CREATED");
    }

    @Test
    void realInspectShapeRetainsVolumeRootAndSubpathRepresentation() throws Exception {
        ObjectNode before = container.deepCopy();
        ObjectNode retained = runtime.deepCopy();
        verify();
        assertEquals(MOUNTPOINT, container.at("/Mounts/0/Source").textValue());
        assertEquals(STAGE + "/input", container.at("/HostConfig/Mounts/0/VolumeOptions/Subpath").textValue());
        assertTrue(container.at("/Mounts/0/RW").isBoolean());
        assertEquals(before, container);
        assertEquals(retained, runtime);
    }

    @ParameterizedTest
    @MethodSource("rolesAndPhases")
    void everyRoleAndPhaseUsesItsPersistedBoundLabels(String role, String phase) throws Exception {
        stage = parsed(role, phase, "CREATED");
        ((ObjectNode) container.get("Config")).set("Labels",
                MAPPER.valueToTree(LinuxStageRecord.expectedLabels(ATTEMPT, STAGE, role)));
        verify();
    }

    static Stream<Arguments> rolesAndPhases() {
        return Stream.of("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE")
                .flatMap(role -> Stream.of("compile", "execute").map(phase -> Arguments.of(role, phase)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "STARTING", "STOPPING", "REMOVING", "REMOVED", "CLEANED", "CLEANUP_FAILED"})
    void identityVerificationDoesNotGrantRecoveryBasedOnStageState(String state) throws Exception {
        stage = parsed("USER", "compile", state);
        verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legitimateOomStateDoesNotChangeOwnedContainerIdentity(boolean oomKilled) throws Exception {
        ((ObjectNode) container.get("State")).put("OOMKilled", oomKilled);
        verify();
    }

    @Test
    void dynamicMemoryAndCommandAreNotGuessedFromSupervisorOrHolderLimits() throws Exception {
        ((ObjectNode) container.get("HostConfig")).put("Memory", 123456789L).put("MemorySwap", 123456789L);
        ((ObjectNode) container.get("Config")).putArray("Cmd").add("/input/program").add("--seed").add("73").add("");
        verify();
    }

    @Test
    void ownedUnhealthyStageIsNotMistakenForForeignOwnership() throws Exception {
        ((ObjectNode) container.get("State")).put("Dead", true).put("Error", "owned runtime error").put("OOMKilled", true);
        verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]"})
    void emptyEntrypointRepresentationsRemainAccepted(String json) throws Exception {
        set(container, "/Config/Entrypoint", MAPPER.readTree(json));
        verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "false"})
    void actualOomDisableRepresentationsRemainAccepted(String json) throws Exception {
        set(container, "/HostConfig/OomKillDisable", MAPPER.readTree(json));
        verify();
    }

    @Test
    void explicitWritableScratchAndReorderedMountsRemainAccepted() throws Exception {
        set(container, "/HostConfig/Mounts/1/ReadOnly", MAPPER.getNodeFactory().booleanNode(false));
        reverse((ArrayNode) container.get("Mounts"));
        reverse((ArrayNode) container.path("HostConfig").get("Mounts"));
        verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void optionalEmptyCollectionsKeepTheirActualContainerTypes(boolean explicitNull) throws Exception {
        for (String path : ARRAY_EXTRAS) {
            set(container, path, explicitNull ? MAPPER.getNodeFactory().nullNode() : MAPPER.createArrayNode());
        }
        for (String path : MAP_EXTRAS) {
            set(container, path, explicitNull ? MAPPER.getNodeFactory().nullNode() : MAPPER.createObjectNode());
        }
        verify();
    }

    @ParameterizedTest
    @MethodSource("invalidTextFields")
    void mismatchedOrCoercedTextNeverEstablishesOwnership(String path, String json) throws Exception {
        set(container, path, MAPPER.readTree(json));
        assertRejected();
    }

    static Stream<Arguments> invalidTextFields() {
        return TEXT_FIELDS.stream().flatMap(path -> Stream.of("\"unexpected\"", "null", "false", "1", "{}", "[]")
                .map(json -> Arguments.of(path, json)));
    }

    @ParameterizedTest
    @MethodSource("invalidBooleans")
    void privilegeAndMountBooleansAreStrictAndNotCoerced(String path, String json) throws Exception {
        set(container, path, MAPPER.readTree(json));
        assertRejected();
    }

    static Stream<Arguments> invalidBooleans() {
        return Stream.concat(FIXED_BOOLEANS.entrySet().stream().flatMap(entry ->
                        Stream.of(Boolean.toString(!entry.getValue()), "null", "0", "\"false\"")
                                .map(json -> Arguments.of(entry.getKey(), json))),
                Stream.of("/State/Running", "/State/OOMKilled", "/State/Dead")
                        .flatMap(path -> Stream.of("null", "0", "\"false\"").map(json -> Arguments.of(path, json))));
    }

    @ParameterizedTest
    @MethodSource("invalidNumbers")
    void numericResourcesRejectCoercionOverflowAndInvalidValues(String path, String json) throws Exception {
        set(container, path, MAPPER.readTree(json));
        assertRejected();
    }

    static Stream<Arguments> invalidNumbers() {
        return NUMERIC_FIELDS.stream().flatMap(path -> Stream.of("-1", "1.5", "\"32\"", "18446744073709551616",
                        "null", "true", "[]", "{}").map(json -> Arguments.of(path, json)));
    }

    @ParameterizedTest
    @MethodSource("mismatchedResources")
    void explicitResourceContractAndSwapEqualityAreChecked(String path, long value) throws Exception {
        set(container, path, MAPPER.getNodeFactory().numberNode(value));
        assertRejected();
    }

    static Stream<Arguments> mismatchedResources() {
        return Stream.of(Arguments.of("/HostConfig/Memory", 0L), Arguments.of("/HostConfig/MemorySwap", 1L),
                Arguments.of("/HostConfig/PidsLimit", 33L), Arguments.of("/HostConfig/NanoCpus", 1L));
    }

    @ParameterizedTest
    @MethodSource("malformedStructures")
    void malformedContainerSectionsAreRejected(String path, String json) throws Exception {
        set(container, path, MAPPER.readTree(json));
        assertRejected();
    }

    static Stream<Arguments> malformedStructures() {
        return Stream.of("/Config", "/HostConfig", "/State", "/Config/Labels", "/Mounts", "/HostConfig/Mounts",
                        "/HostConfig/LogConfig", "/HostConfig/Mounts/0/VolumeOptions", "/HostConfig/Mounts/1/VolumeOptions")
                .flatMap(path -> Stream.of("null", "false", "\"object\"", "[]").map(json -> Arguments.of(path, json)));
    }

    @ParameterizedTest
    @MethodSource("missingFields")
    void missingRequiredFieldsNeverEstablishOwnership(String path) throws Exception {
        remove(container, path);
        assertRejected();
    }

    static Stream<String> missingFields() {
        return Stream.concat(TEXT_FIELDS.stream(), Stream.concat(NUMERIC_FIELDS.stream(),
                Stream.concat(FIXED_BOOLEANS.keySet().stream().filter(path -> !path.equals("/HostConfig/Mounts/1/ReadOnly")),
                        Stream.of("/Config", "/HostConfig", "/State", "/Config/Labels", "/Config/Cmd", "/Config/Env",
                                "/Config/Entrypoint", "/HostConfig/SecurityOpt", "/HostConfig/CapDrop",
                                "/HostConfig/OomKillDisable", "/Mounts", "/HostConfig/Mounts",
                                "/State/Running", "/State/OOMKilled", "/State/Dead", "/State/Error"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"extra", "missing", "attempt", "stage", "role", "managed", "type"})
    void exactFourBoundLabelsCannotBeExpandedOrSubstituted(String mutation) throws Exception {
        ObjectNode labels = (ObjectNode) container.path("Config").get("Labels");
        switch (mutation) {
            case "extra" -> labels.put("foreign", "value");
            case "missing" -> labels.remove("io.webjudge.role");
            case "type" -> labels.put("io.webjudge.stage", 3);
            default -> labels.put("io.webjudge." + mutation, "different");
        }
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "different", "non-text", "null", "not-array"})
    void environmentTmpdirCannotBeMissingDuplicatedOrCoerced(String mutation) throws Exception {
        ObjectNode config = (ObjectNode) container.get("Config");
        ArrayNode environment = config.putArray("Env").add("PATH=/usr/bin");
        switch (mutation) {
            case "duplicate" -> environment.add("TMPDIR=/scratch").add("TMPDIR=/scratch");
            case "different" -> environment.add("TMPDIR=/control");
            case "non-text" -> environment.add(123).add("TMPDIR=/scratch");
            case "null" -> config.putNull("Env");
            case "not-array" -> config.put("Env", "TMPDIR=/scratch");
            default -> { }
        }
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[1]", "[\"\"]", "[\"/input/program\",1]", "{}", "\"program\""})
    void dynamicCommandsMustStillBeAStringArgumentVector(String json) throws Exception {
        set(container, "/Config/Cmd", MAPPER.readTree(json));
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "\"program\"", "[\"program\"]", "false"})
    void nonemptyOrCoercedEntrypointsAreRejected(String json) throws Exception {
        set(container, "/Config/Entrypoint", MAPPER.readTree(json));
        assertRejected();
    }

    @ParameterizedTest
    @MethodSource("nonemptyOrMalformedExtras")
    void extraMountsDevicesAndPrivilegesCannotHideInMalformedCollections(String path, String json) throws Exception {
        set(container, path, MAPPER.readTree(json));
        assertRejected();
    }

    static Stream<Arguments> nonemptyOrMalformedExtras() {
        return Stream.concat(ARRAY_EXTRAS.stream().flatMap(path -> Stream.of("[\"foreign\"]", "{}", "false", "\"value\"")
                        .map(json -> Arguments.of(path, json))),
                MAP_EXTRAS.stream().flatMap(path -> Stream.of("{\"foreign\":\"value\"}", "[]", "false", "\"value\"")
                        .map(json -> Arguments.of(path, json))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mount-extra", "declaration-extra", "mount-duplicate", "declaration-duplicate"})
    void bothMountLayersRejectAdditionalOrDuplicateTargets(String mutation) throws Exception {
        ArrayNode mounts = (ArrayNode) (mutation.startsWith("mount-") ? container.get("Mounts")
                : container.path("HostConfig").get("Mounts"));
        if (mutation.endsWith("extra")) {
            mounts.add(mounts.get(0).deepCopy());
        } else {
            mounts.set(1, mounts.get(0).deepCopy());
        }
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt", "name", "runtime-id", "creation", "mountpoint"})
    void trustedRuntimeBindingMustMatchTheObservedStage(String mutation) throws Exception {
        ObjectNode volume = (ObjectNode) runtime.get("volume");
        switch (mutation) {
            case "attempt" -> runtime.put("attemptId", "a0560082-0f2c-44cf-ad95-f7d2f58ed315");
            case "name" -> volume.put("name", "foreign-volume");
            case "runtime-id" -> volume.put("runtimeId", "foreign-volume");
            case "creation" -> volume.put("creationState", "UNKNOWN");
            default -> volume.put("mountpoint", MOUNTPOINT + "/other");
        }
        assertRejected();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "uid", "gid", "pids", "cpu"})
    void differentExplicitOptionsCannotVerifyTheObservedStage(String mutation) throws Exception {
        options = options(mutation.equals("image") ? "sha256:" + "b".repeat(64) : IMAGE,
                mutation.equals("uid") ? 65530 : 65531, mutation.equals("gid") ? 65530 : 65531,
                mutation.equals("pids") ? 31 : 32, mutation.equals("cpu") ? 0.6 : 0.5);
        assertRejected();
    }

    @Test
    void missingInputsAndNonObjectContainersAreRejected() throws Exception {
        assertThrows(IOException.class, () -> LinuxStageContainerIdentity.verify(null, runtime, stage, options, MAPPER));
        assertThrows(IOException.class, () -> LinuxStageContainerIdentity.verify(MAPPER.createArrayNode(), runtime, stage, options, MAPPER));
        assertThrows(IOException.class, () -> LinuxStageContainerIdentity.verify(container, null, stage, options, MAPPER));
        assertThrows(IOException.class, () -> LinuxStageContainerIdentity.verify(container, runtime, null, options, MAPPER));
        assertThrows(IOException.class, () -> LinuxStageContainerIdentity.verify(container, runtime, stage, null, MAPPER));
        assertThrows(IOException.class, () -> LinuxStageContainerIdentity.verify(container, runtime, stage, options, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stage", "role", "null-role", "phase", "null-phase", "creation", "id"})
    void malformedParsedBindingsCannotBypassTheIdentityContract(String mutation) throws Exception {
        stage = new LinuxStageRecord.Parsed(mutation.equals("stage") ? "not-a-uuid" : STAGE,
                mutation.equals("null-role") ? null : mutation.equals("role") ? "OTHER" : "USER",
                mutation.equals("null-phase") ? null : mutation.equals("phase") ? "OTHER" : "compile",
                mutation.equals("creation") ? "UNKNOWN" : "CONFIRMED", "CREATED",
                mutation.equals("id") ? "short-id" : CONTAINER_ID, null);
        assertRejected();
    }

    private LinuxStageRecord.Parsed parsed(String role, String phase, String state) throws IOException {
        ObjectNode record = MAPPER.createObjectNode().put("journalVersion", 1).put("creationState", "CONFIRMED")
                .put("stage", STAGE).put("attempt", ATTEMPT).put("role", role).put("phase", phase)
                .put("name", LinuxStageRecord.expectedName(STAGE)).put("volume", VOLUME)
                .put("state", state).put("containerId", CONTAINER_ID);
        record.set("labels", MAPPER.valueToTree(LinuxStageRecord.expectedLabels(ATTEMPT, STAGE, role)));
        byte[] payload = MAPPER.writeValueAsBytes(record);
        return LinuxStageRecord.parse(payload, payload.length, STAGE + ".json", ATTEMPT, VOLUME, MAPPER);
    }

    private static LinuxSupervisorOptions options(String image, int workloadUid, int workloadGid, int pids, double cpus) {
        return new LinuxSupervisorOptions("/usr/bin/docker", "unix:///run/identity-host/docker.sock", "/usr/bin/docker",
                "unix:///run/identity-supervisor/docker.sock", "sha256:" + "b".repeat(64), image, "/task-control",
                0, 0, 0, 0, 65532, 65532, workloadUid, workloadGid,
                new LinuxSupervisorOptions.ResourceLimits(67108864, 16, 0.5),
                new LinuxSupervisorOptions.ResourceLimits(16777216, 4, 0.1), Duration.ofSeconds(60),
                pids, cpus, 1024, 2048, 33554432, 65536, Duration.ofSeconds(5), Duration.ofSeconds(3));
    }

    private void verify() throws IOException {
        LinuxStageContainerIdentity.verify(container, runtime, stage, options, MAPPER);
    }

    private void assertRejected() throws IOException {
        ObjectNode original = container.deepCopy();
        ObjectNode retained = runtime.deepCopy();
        assertThrows(IOException.class, this::verify);
        assertEquals(original, container);
        assertEquals(retained, runtime);
    }

    private static void set(JsonNode root, String path, JsonNode value) {
        int separator = path.lastIndexOf('/');
        JsonNode parent = root.at(path.substring(0, separator));
        String field = path.substring(separator + 1);
        if (parent.isArray()) {
            ((ArrayNode) parent).set(Integer.parseInt(field), value);
        } else {
            ((ObjectNode) parent).set(field, value);
        }
    }

    private static void remove(JsonNode root, String path) {
        int separator = path.lastIndexOf('/');
        ((ObjectNode) root.at(path.substring(0, separator))).remove(path.substring(separator + 1));
    }

    private static void reverse(ArrayNode array) {
        JsonNode first = array.get(0);
        JsonNode second = array.get(1);
        array.removeAll().add(second).add(first);
    }
}
