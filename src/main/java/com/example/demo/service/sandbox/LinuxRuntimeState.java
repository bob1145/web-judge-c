package com.example.demo.service.sandbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class LinuxRuntimeState {
    private static final Set<String> ROOT_FIELDS = Set.of("version", "judgeId", "attemptId", "provider", "state",
            "configuration", "volume", "holder", "supervisor");
    private static final Set<String> CONFIGURATION_FIELDS = Set.of("hostRuntimeCommand", "hostRuntimeEndpoint",
            "supervisorRuntimeCommand", "supervisorRuntimeEndpoint", "supervisorImageId", "executorImageId", "workDir",
            "workMount", "configWriterUid", "configWriterGid", "supervisorUid", "supervisorGid", "holderUid", "holderGid",
            "workloadUid", "workloadGid", "holderLifetimeSeconds", "stagePidsLimit", "stageCpus", "inputLimitBytes",
            "artifactLimitBytes", "volumeLimitBytes", "controlOutputLimitBytes", "commandTimeoutMillis",
            "stageCommandTimeoutMillis", "supervisorLimits", "holderLimits");

    private LinuxRuntimeState() { }

    static SnapshotPhase classifyUnresolvedSnapshot(JsonNode snapshot) throws IOException {
        requireFields(snapshot, ROOT_FIELDS);
        require(integer(snapshot, "version") == 1, "unsupported runtime journal version");
        String judge = text(snapshot, "judgeId");
        require(!judge.isBlank() && judge.length() <= 256, "invalid runtime journal judge identity");
        String attempt = text(snapshot, "attemptId");
        try {
            require(UUID.fromString(attempt).toString().equals(attempt), "noncanonical runtime attempt identity");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid runtime attempt identity", invalid);
        }
        require(text(snapshot, "provider").equals("linux-container"), "unsupported runtime journal provider");
        validateConfiguration(snapshot.get("configuration"));
        String volume = resourceState(snapshot.get("volume"), "volume", attempt);
        String holder = resourceState(snapshot.get("holder"), "holder", attempt);
        String supervisor = resourceState(snapshot.get("supervisor"), "supervisor", attempt);
        if (snapshot.get("holder").has("runtimeId") && snapshot.get("supervisor").has("runtimeId")) {
            require(!snapshot.get("holder").get("runtimeId").equals(snapshot.get("supervisor").get("runtimeId")),
                    "runtime container identities must be distinct");
        }
        String global = text(snapshot, "state");
        for (SnapshotPhase phase : SnapshotPhase.values()) {
            if (phase.global.equals(global) && phase.volume.equals(volume)
                    && phase.holder.equals(holder) && phase.supervisor.equals(supervisor)) {
                return phase;
            }
        }
        throw new IOException("runtime journal contains an impossible persisted state combination");
    }

    static String resourceName(String resource, String attempt) {
        return "wjc-" + switch (resource) {
            case "volume" -> "stage-volume-";
            case "holder" -> "stage-holder-";
            case "supervisor" -> "supervisor-";
            default -> throw new IllegalArgumentException("unknown runtime resource role");
        } + attempt;
    }

    private static String resourceState(JsonNode resource, String role, String attempt) throws IOException {
        require(resource != null && resource.isObject(), "runtime resource must be an object: " + role);
        String state = text(resource, "state");
        String creation = switch (state) {
            case "NOT_REQUESTED" -> "NOT_REQUESTED";
            case "CREATE_REQUESTED" -> "UNKNOWN";
            case "CREATED", "START_REQUESTED", "STARTED", "STOP_REQUESTED", "STOPPED" -> "CONFIRMED";
            case "REMOVAL_REQUESTED", "REMOVED" -> {
                require(!role.equals("volume"), "runtime volume removal is outside the v1 writer contract");
                yield "CONFIRMED";
            }
            default -> throw new IOException("unknown runtime resource state: " + role);
        };
        require(text(resource, "creationState").equals(creation), "runtime creation and resource states disagree");
        boolean confirmed = creation.equals("CONFIRMED");
        Set<String> fields = new HashSet<>(Set.of("name", "creationState", "state"));
        if (confirmed) {
            fields.add("runtimeId");
            if (role.equals("volume")) {
                fields.add("mountpoint");
            }
        }
        requireFields(resource, fields);
        String name = text(resource, "name");
        require(name.equals(resourceName(role, attempt)), "runtime resource name differs from attempt identity");
        if (confirmed) {
            String identity = text(resource, "runtimeId");
            if (role.equals("volume")) {
                require(identity.equals(name), "runtime volume identity differs from its name");
                requireCanonicalPath(text(resource, "mountpoint"));
            } else {
                require(identity.matches("[0-9a-f]{64}"), "runtime container identity is not a complete ID");
            }
        }
        return state;
    }

    private static void validateConfiguration(JsonNode config) throws IOException {
        requireFields(config, CONFIGURATION_FIELDS);
        requireCanonicalPath(text(config, "workDir"));
        try {
            new LinuxSupervisorOptions(text(config, "hostRuntimeCommand"), text(config, "hostRuntimeEndpoint"),
                    text(config, "supervisorRuntimeCommand"), text(config, "supervisorRuntimeEndpoint"),
                    text(config, "supervisorImageId"), text(config, "executorImageId"), text(config, "workMount"),
                    integer(config, "configWriterUid"), integer(config, "configWriterGid"),
                    integer(config, "supervisorUid"), integer(config, "supervisorGid"),
                    integer(config, "holderUid"), integer(config, "holderGid"),
                    integer(config, "workloadUid"), integer(config, "workloadGid"),
                    limits(config.get("supervisorLimits")), limits(config.get("holderLimits")),
                    Duration.ofSeconds(integral(config, "holderLifetimeSeconds")), integer(config, "stagePidsLimit"),
                    floating(config, "stageCpus"), integral(config, "inputLimitBytes"), integral(config, "artifactLimitBytes"),
                    integral(config, "volumeLimitBytes"), integral(config, "controlOutputLimitBytes"),
                    Duration.ofMillis(integral(config, "commandTimeoutMillis")),
                    Duration.ofMillis(integral(config, "stageCommandTimeoutMillis")));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("invalid retained runtime configuration", invalid);
        }
    }

    private static LinuxSupervisorOptions.ResourceLimits limits(JsonNode limits) throws IOException {
        requireFields(limits, Set.of("memoryBytes", "pidsLimit", "cpus"));
        return new LinuxSupervisorOptions.ResourceLimits(integral(limits, "memoryBytes"), integer(limits, "pidsLimit"),
                floating(limits, "cpus"));
    }

    private static void requireCanonicalPath(String value) throws IOException {
        try {
            LinuxSupervisorOptions.requireAbsolutePath(value, "retainedRuntimePath");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("noncanonical runtime path", invalid);
        }
    }

    private static void requireFields(JsonNode object, Set<String> expected) throws IOException {
        require(object != null && object.isObject(), "runtime journal value must be an object");
        Set<String> actual = new HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected), "runtime journal fields differ from the v1 writer contract");
    }

    private static String text(JsonNode parent, String field) throws IOException {
        JsonNode value = parent.path(field);
        require(value.isTextual() && !value.textValue().isEmpty(), "runtime journal text is missing: " + field);
        return value.textValue();
    }

    private static long integral(JsonNode parent, String field) throws IOException {
        JsonNode value = parent.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong(), "runtime journal integer is invalid: " + field);
        return value.longValue();
    }

    private static int integer(JsonNode parent, String field) throws IOException {
        long value = integral(parent, field);
        require(value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE, "runtime journal integer is out of range: " + field);
        return (int) value;
    }

    private static double floating(JsonNode parent, String field) throws IOException {
        JsonNode value = parent.path(field);
        require(value.isFloatingPointNumber() && Double.isFinite(value.doubleValue()),
                "runtime journal floating point value is invalid: " + field);
        return value.doubleValue();
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    enum SnapshotPhase {
        PREPARED("PREPARED", "NOT_REQUESTED", "NOT_REQUESTED", "NOT_REQUESTED"),
        VOLUME_CREATE_UNKNOWN("CREATE_UNKNOWN", "CREATE_REQUESTED", "NOT_REQUESTED", "NOT_REQUESTED"),
        VOLUME_CONFIRMED("PREPARING", "CREATED", "NOT_REQUESTED", "NOT_REQUESTED"),
        HOLDER_CREATE_UNKNOWN("CREATE_UNKNOWN", "CREATED", "CREATE_REQUESTED", "NOT_REQUESTED"),
        HOLDER_CONFIRMED("PREPARING", "CREATED", "CREATED", "NOT_REQUESTED"),
        HOLDER_START_REQUESTED("PREPARING", "CREATED", "START_REQUESTED", "NOT_REQUESTED"),
        HOLDER_STARTED("PREPARING", "CREATED", "STARTED", "NOT_REQUESTED"),
        SUPERVISOR_CREATE_UNKNOWN("CREATE_UNKNOWN", "CREATED", "STARTED", "CREATE_REQUESTED"),
        SUPERVISOR_CONFIRMED("PREPARING", "CREATED", "STARTED", "CREATED"),
        SUPERVISOR_START_REQUESTED("PREPARING", "CREATED", "STARTED", "START_REQUESTED"),
        SUPERVISOR_STARTED("STARTED", "CREATED", "STARTED", "STARTED"),
        SUPERVISOR_STOP_REQUESTED("CLEANUP_PENDING", "CREATED", "STARTED", "STOP_REQUESTED"),
        SUPERVISOR_STOPPED("CLEANUP_PENDING", "CREATED", "STARTED", "STOPPED"),
        SUPERVISOR_REMOVAL_REQUESTED("CLEANUP_PENDING", "CREATED", "STARTED", "REMOVAL_REQUESTED"),
        SUPERVISOR_REMOVED("CLEANUP_PENDING", "CREATED", "STARTED", "REMOVED"),
        HOLDER_STOP_REQUESTED("CLEANUP_PENDING", "CREATED", "STOP_REQUESTED", "REMOVED"),
        HOLDER_STOPPED("CLEANUP_PENDING", "CREATED", "STOPPED", "REMOVED"),
        HOLDER_REMOVAL_REQUESTED("CLEANUP_PENDING", "CREATED", "REMOVAL_REQUESTED", "REMOVED"),
        HOLDER_REMOVED("CLEANUP_PENDING", "CREATED", "REMOVED", "REMOVED");

        private final String global;
        private final String volume;
        private final String holder;
        private final String supervisor;

        SnapshotPhase(String global, String volume, String holder, String supervisor) {
            this.global = global;
            this.volume = volume;
            this.holder = holder;
            this.supervisor = supervisor;
        }
    }
}
