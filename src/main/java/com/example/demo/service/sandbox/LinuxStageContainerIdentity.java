package com.example.demo.service.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Set;

final class LinuxStageContainerIdentity {
    private static final String REJECTION = "stage container differs from the trusted identity contract";

    private LinuxStageContainerIdentity() {
    }

    static void verify(JsonNode container, ObjectNode runtime, LinuxStageRecord.Parsed stage,
                       LinuxSupervisorOptions options, ObjectMapper mapper) throws IOException {
        require(container != null && container.isObject() && runtime != null && stage != null
                && options != null && mapper != null);
        require(LinuxStageRecord.isCanonicalUuid(stage.stage())
                && stage.role() != null && Set.of("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE").contains(stage.role())
                && stage.phase() != null && Set.of("compile", "execute").contains(stage.phase())
                && "CONFIRMED".equals(stage.creationState())
                && stage.containerId() != null && stage.containerId().matches("[0-9a-f]{64}"));
        String attempt = text(runtime, "attemptId");
        require(LinuxStageRecord.isCanonicalUuid(attempt));
        JsonNode volume = object(runtime, "volume");
        String volumeName = text(volume, "name");
        require(LinuxRuntimeState.resourceName("volume", attempt).equals(volumeName)
                && "CONFIRMED".equals(text(volume, "creationState"))
                && volumeName.equals(text(volume, "runtimeId")));
        String mountpoint = text(volume, "mountpoint");
        try {
            LinuxSupervisorOptions.requireAbsolutePath(mountpoint, "retainedStageMountpoint");
        } catch (IllegalArgumentException invalid) {
            throw new IOException(REJECTION, invalid);
        }
        require(stage.containerId().equals(text(container, "Id"))
                && ("/" + LinuxStageRecord.expectedName(stage.stage())).equals(text(container, "Name"))
                && options.executorImageId().equals(text(container, "Image")));
        JsonNode config = object(container, "Config");
        JsonNode labels = object(config, "Labels");
        require(labels.equals(mapper.valueToTree(LinuxStageRecord.expectedLabels(attempt, stage.stage(), stage.role())))
                && (options.workloadUid() + ":" + options.workloadGid()).equals(text(config, "User"))
                && "/scratch".equals(text(config, "WorkingDir")));
        JsonNode entrypoint = config.get("Entrypoint");
        require(entrypoint != null && (entrypoint.isNull() || entrypoint.isArray() && entrypoint.isEmpty()));
        JsonNode arguments = config.get("Cmd");
        require(arguments != null && arguments.isArray() && !arguments.isEmpty());
        for (JsonNode argument : arguments) {
            require(argument.isTextual() && argument.textValue().indexOf(0) < 0);
        }
        require(!arguments.get(0).textValue().isEmpty());
        JsonNode environment = config.get("Env");
        require(environment != null && environment.isArray());
        int temporaryDirectoryEntries = 0;
        for (JsonNode variable : environment) {
            require(variable.isTextual() && variable.textValue().indexOf(0) < 0);
            if (variable.textValue().startsWith("TMPDIR=")) {
                require(variable.textValue().equals("TMPDIR=/scratch"));
                temporaryDirectoryEntries++;
            }
        }
        require(temporaryDirectoryEntries == 1);

        JsonNode host = object(container, "HostConfig");
        exactBoolean(host, "Privileged", false);
        exactBoolean(host, "ReadonlyRootfs", true);
        require("none".equals(text(host, "NetworkMode")) && "".equals(text(host, "PidMode"))
                && "".equals(text(host, "UTSMode")) && "".equals(text(host, "UsernsMode"))
                && "private".equals(text(host, "IpcMode")) && "private".equals(text(host, "CgroupnsMode")));
        require(host.path("CapDrop").equals(mapper.valueToTree(List.of("ALL")))
                && host.path("SecurityOpt").equals(mapper.valueToTree(List.of("no-new-privileges")))
                && "none".equals(text(object(host, "LogConfig"), "Type")));
        emptyCollection(host.get("CapAdd"), true);
        JsonNode oomKillDisable = host.get("OomKillDisable");
        require(oomKillDisable != null && (oomKillDisable.isNull()
                || oomKillDisable.isBoolean() && !oomKillDisable.booleanValue()));
        long memory = integral(host, "Memory");
        require(memory > 0 && integral(host, "MemorySwap") == memory
                && integral(host, "PidsLimit") == options.stagePidsLimit()
                && integral(host, "NanoCpus") == Math.round(options.stageCpus() * 1_000_000_000));
        for (String field : List.of("Devices", "DeviceRequests", "Binds", "VolumesFrom", "Links")) {
            emptyCollection(host.get(field), true);
        }
        for (String field : List.of("Tmpfs", "PortBindings")) {
            emptyCollection(host.get(field), false);
        }

        JsonNode mounts = container.get("Mounts");
        JsonNode declarations = host.get("Mounts");
        require(mounts != null && mounts.isArray() && mounts.size() == 2
                && declarations != null && declarations.isArray() && declarations.size() == 2);
        for (String directory : List.of("input", "scratch")) {
            String target = "/" + directory;
            JsonNode mount = uniqueMount(mounts, "Destination", target);
            require("volume".equals(text(mount, "Type")) && volumeName.equals(text(mount, "Name"))
                    && mountpoint.equals(text(mount, "Source")) && "local".equals(text(mount, "Driver")));
            exactBoolean(mount, "RW", directory.equals("scratch"));
            JsonNode declaration = uniqueMount(declarations, "Target", target);
            require("volume".equals(text(declaration, "Type")) && volumeName.equals(text(declaration, "Source")));
            if (directory.equals("input")) {
                exactBoolean(declaration, "ReadOnly", true);
            } else if (declaration.has("ReadOnly")) {
                exactBoolean(declaration, "ReadOnly", false);
            }
            JsonNode volumeOptions = object(declaration, "VolumeOptions");
            exactBoolean(volumeOptions, "NoCopy", true);
            require((stage.stage() + "/" + directory).equals(text(volumeOptions, "Subpath")));
            emptyCollection(volumeOptions.get("DriverConfig"), false);
        }
        JsonNode state = object(container, "State");
        requireBoolean(state, "Running");
        requireBoolean(state, "OOMKilled");
        requireBoolean(state, "Dead");
        require(integral(state, "Pid") >= 0);
        text(state, "Error");
    }

    private static JsonNode uniqueMount(JsonNode mounts, String field, String target) throws IOException {
        JsonNode selected = null;
        for (JsonNode mount : mounts) {
            require(mount.isObject());
            if (target.equals(text(mount, field))) {
                require(selected == null);
                selected = mount;
            }
        }
        require(selected != null);
        return selected;
    }

    private static void emptyCollection(JsonNode value, boolean array) throws IOException {
        require(value == null || value.isNull() || (array ? value.isArray() : value.isObject()) && value.isEmpty());
    }

    private static JsonNode object(JsonNode parent, String field) throws IOException {
        JsonNode value = parent.get(field);
        require(value != null && value.isObject());
        return value;
    }

    private static String text(JsonNode parent, String field) throws IOException {
        JsonNode value = parent.get(field);
        require(value != null && value.isTextual());
        return value.textValue();
    }

    private static long integral(JsonNode parent, String field) throws IOException {
        JsonNode value = parent.get(field);
        require(value != null && value.isIntegralNumber() && value.canConvertToLong());
        return value.longValue();
    }

    private static void exactBoolean(JsonNode parent, String field, boolean expected) throws IOException {
        require(requireBoolean(parent, field) == expected);
    }

    private static boolean requireBoolean(JsonNode parent, String field) throws IOException {
        JsonNode value = parent.get(field);
        require(value != null && value.isBoolean());
        return value.booleanValue();
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) {
            throw new IOException(REJECTION);
        }
    }
}
