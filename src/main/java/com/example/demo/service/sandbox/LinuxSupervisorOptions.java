package com.example.demo.service.sandbox;

import com.example.demo.config.SandboxProperties;

import java.time.Duration;

public record LinuxSupervisorOptions(
        String hostRuntimeCommand,
        String hostRuntimeEndpoint,
        String supervisorRuntimeCommand,
        String supervisorRuntimeEndpoint,
        String supervisorImageId,
        String executorImageId,
        String workMount,
        int configWriterUid,
        int configWriterGid,
        int supervisorUid,
        int supervisorGid,
        int holderUid,
        int holderGid,
        int workloadUid,
        int workloadGid,
        ResourceLimits supervisorLimits,
        ResourceLimits holderLimits,
        Duration holderLifetime,
        int stagePidsLimit,
        double stageCpus,
        long inputLimitBytes,
        long artifactLimitBytes,
        long volumeLimitBytes,
        long controlOutputLimitBytes,
        Duration commandTimeout,
        Duration stageCommandTimeout) {

    public LinuxSupervisorOptions {
        requireAbsolutePath(hostRuntimeCommand, "hostRuntimeCommand");
        requireEndpoint(hostRuntimeEndpoint, "hostRuntimeEndpoint");
        requireAbsolutePath(supervisorRuntimeCommand, "supervisorRuntimeCommand");
        String supervisorSocket = requireEndpoint(supervisorRuntimeEndpoint, "supervisorRuntimeEndpoint");
        requireImageId(supervisorImageId, "supervisorImageId");
        requireImageId(executorImageId, "executorImageId");
        requireAbsolutePath(workMount, "workMount");
        String socketParent = supervisorSocket.substring(0, supervisorSocket.lastIndexOf('/'));
        if (socketParent.isEmpty() || isWithin(workMount, socketParent) || isWithin(socketParent, workMount)) {
            throw invalid("workMount", "must not overlap the supervisor socket parent");
        }
        requireExperimentalRoot(configWriterUid, "configWriterUid");
        requireExperimentalRoot(configWriterGid, "configWriterGid");
        requireExperimentalRoot(supervisorUid, "supervisorUid");
        requireExperimentalRoot(supervisorGid, "supervisorGid");
        requireNonNegative(holderUid, "holderUid");
        requireNonNegative(holderGid, "holderGid");
        requirePositive(workloadUid, "workloadUid");
        requirePositive(workloadGid, "workloadGid");
        requireExplicit(supervisorLimits, "supervisorLimits");
        requireExplicit(holderLimits, "holderLimits");
        requireDuration(holderLifetime, "holderLifetime");
        if (holderLifetime.getNano() != 0) {
            throw invalid("holderLifetime", "must use whole seconds");
        }
        requirePositive(stagePidsLimit, "stagePidsLimit");
        requirePositiveFinite(stageCpus, "stageCpus");
        requirePositive(inputLimitBytes, "inputLimitBytes");
        requirePositive(artifactLimitBytes, "artifactLimitBytes");
        requirePositive(volumeLimitBytes, "volumeLimitBytes");
        requirePositive(controlOutputLimitBytes, "controlOutputLimitBytes");
        if (controlOutputLimitBytes > Integer.MAX_VALUE) {
            throw invalid("controlOutputLimitBytes", "must fit in the signed 32-bit CLI buffer limit");
        }
        requireDuration(commandTimeout, "commandTimeout");
        try {
            commandTimeout.toNanos();
        } catch (ArithmeticException error) {
            throw invalid("commandTimeout", "must fit in signed 64-bit nanoseconds");
        }
        requireDuration(stageCommandTimeout, "stageCommandTimeout");
    }

    public static LinuxSupervisorOptions fromProperties(SandboxProperties.LinuxSupervisor properties) {
        requireExplicit(properties, "supervisor");
        return new LinuxSupervisorOptions(
                properties.getHostRuntimeCommand(),
                properties.getHostRuntimeEndpoint(),
                properties.getSupervisorRuntimeCommand(),
                properties.getSupervisorRuntimeEndpoint(),
                properties.getSupervisorImageId(),
                properties.getExecutorImageId(),
                properties.getWorkMount(),
                requireExplicit(properties.getConfigWriterUid(), "configWriterUid"),
                requireExplicit(properties.getConfigWriterGid(), "configWriterGid"),
                requireExplicit(properties.getSupervisorUid(), "supervisorUid"),
                requireExplicit(properties.getSupervisorGid(), "supervisorGid"),
                requireExplicit(properties.getHolderUid(), "holderUid"),
                requireExplicit(properties.getHolderGid(), "holderGid"),
                requireExplicit(properties.getWorkloadUid(), "workloadUid"),
                requireExplicit(properties.getWorkloadGid(), "workloadGid"),
                resourceLimits(properties.getSupervisorLimits(), "supervisorLimits"),
                resourceLimits(properties.getHolderLimits(), "holderLimits"),
                properties.getHolderLifetime(),
                requireExplicit(properties.getStagePidsLimit(), "stagePidsLimit"),
                requireExplicit(properties.getStageCpus(), "stageCpus"),
                requireExplicit(properties.getInputLimitBytes(), "inputLimitBytes"),
                requireExplicit(properties.getArtifactLimitBytes(), "artifactLimitBytes"),
                requireExplicit(properties.getVolumeLimitBytes(), "volumeLimitBytes"),
                requireExplicit(properties.getControlOutputLimitBytes(), "controlOutputLimitBytes"),
                properties.getCommandTimeout(),
                properties.getStageCommandTimeout());
    }

    public record ResourceLimits(long memoryBytes, int pidsLimit, double cpus) {
        public ResourceLimits {
            requirePositive(memoryBytes, "memoryBytes");
            requirePositive(pidsLimit, "pidsLimit");
            requirePositiveFinite(cpus, "cpus");
        }
    }

    private static ResourceLimits resourceLimits(SandboxProperties.LinuxSupervisor.ResourceLimits properties,
                                                 String name) {
        requireExplicit(properties, name);
        return new ResourceLimits(
                requireExplicit(properties.getMemoryBytes(), name + ".memoryBytes"),
                requireExplicit(properties.getPidsLimit(), name + ".pidsLimit"),
                requireExplicit(properties.getCpus(), name + ".cpus"));
    }

    static void requireAbsolutePath(String value, String name) {
        requireExplicit(value, name);
        if (!value.startsWith("/") || value.equals("/") || value.endsWith("/")
                || value.contains("//") || value.contains("\\") || value.contains(",")
                || value.codePoints().anyMatch(character -> Character.isWhitespace(character)
                        || Character.isSpaceChar(character) || Character.isISOControl(character))) {
            throw invalid(name, "must be a non-root canonical absolute Linux path");
        }
        for (String component : value.split("/")) {
            if (component.equals(".") || component.equals("..")) {
                throw invalid(name, "must be a canonical absolute Linux path without dot segments");
            }
        }
    }

    private static String requireEndpoint(String value, String name) {
        requireExplicit(value, name);
        if (!value.startsWith("unix:///")) {
            throw invalid(name, "must be an explicit local unix:/// socket endpoint");
        }
        String socket = value.substring("unix://".length());
        requireAbsolutePath(socket, name);
        return socket;
    }

    private static void requireImageId(String value, String name) {
        requireExplicit(value, name);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw invalid(name, "must be a complete lowercase sha256 image ID");
        }
    }

    private static boolean isWithin(String path, String parent) {
        return path.equals(parent) || path.startsWith(parent + "/");
    }

    private static void requireExperimentalRoot(int value, String name) {
        if (value != 0) {
            throw invalid(name, "must explicitly be 0 for the current experimental trusted-volume implementation");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw invalid(name, "must not be negative");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw invalid(name, "must be positive");
        }
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw invalid(name, "must be finite and positive");
        }
    }

    private static void requireDuration(Duration value, String name) {
        requireExplicit(value, name);
        if (value.isNegative() || value.isZero() || value.getNano() % 1_000_000 != 0) {
            throw invalid(name, "must be positive whole milliseconds");
        }
        try {
            value.toMillis();
        } catch (ArithmeticException error) {
            throw invalid(name, "must fit in signed 64-bit milliseconds");
        }
    }

    private static <Value> Value requireExplicit(Value value, String name) {
        if (value == null) {
            throw invalid(name, "must be explicitly configured");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String name, String reason) {
        return new IllegalArgumentException("linux supervisor " + name + " " + reason);
    }
}
