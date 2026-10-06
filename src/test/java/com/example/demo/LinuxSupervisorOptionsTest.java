package com.example.demo;

import com.example.demo.config.SandboxProperties;
import com.example.demo.service.sandbox.LinuxSupervisorOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinuxSupervisorOptionsTest {

    @Test
    void supervisorModeAndItsPropertiesHaveNoDefaults() {
        assertThat(new SandboxProperties().getLinuxContainer().getSupervisor()).isNull();
        SandboxProperties.LinuxSupervisor properties = new SandboxProperties.LinuxSupervisor();
        for (Field field : SandboxProperties.LinuxSupervisor.class.getDeclaredFields()) {
            assertThat(field.getType().isPrimitive()).as(field.getName()).isFalse();
            assertThat(ReflectionTestUtils.getField(properties, field.getName())).as(field.getName()).isNull();
        }
        SandboxProperties.LinuxSupervisor.ResourceLimits limits = new SandboxProperties.LinuxSupervisor.ResourceLimits();
        for (Field field : SandboxProperties.LinuxSupervisor.ResourceLimits.class.getDeclaredFields()) {
            assertThat(field.getType().isPrimitive()).as(field.getName()).isFalse();
            assertThat(ReflectionTestUtils.getField(limits, field.getName())).as(field.getName()).isNull();
        }
        assertThat(new SandboxProperties().getLinuxContainer().getRuntimeCommand()).isEqualTo("docker");
    }

    @Test
    void absentSupervisorConfigurationIsRejected() {
        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supervisor");
    }

    @ParameterizedTest
    @MethodSource("requiredFields")
    void everySupervisorFieldMustBeExplicit(String field) {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        ReflectionTestUtils.setField(properties, field, null);

        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    static Stream<String> requiredFields() {
        return Arrays.stream(SandboxProperties.LinuxSupervisor.class.getDeclaredFields()).map(Field::getName);
    }

    @ParameterizedTest
    @MethodSource("missingResourceFields")
    void everyResourceFieldMustBeExplicit(String resource, String field) {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(properties, resource), field, null);

        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(resource + "." + field);
    }

    static Stream<Arguments> missingResourceFields() {
        return Stream.of("supervisorLimits", "holderLimits").flatMap(resource ->
                Stream.of("memoryBytes", "pidsLimit", "cpus").map(field -> Arguments.of(resource, field)));
    }

    @ParameterizedTest
    @MethodSource("invalidProperties")
    void malformedSupervisorPropertiesAreRejected(String field, Object value) {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        ReflectionTestUtils.setField(properties, field, value);

        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    static Stream<Arguments> invalidProperties() {
        Stream<Arguments> paths = Stream.of("hostRuntimeCommand", "supervisorRuntimeCommand", "workMount")
                .flatMap(field -> Stream.of("", "docker", "/", "//bin/docker", "/bin/../docker",
                        "/bin/./docker", "/bin/docker/", "/bin/docker\\bad", "/bin/doc ker",
                        "/bin/doc\nker", "/bin/doc" + (char) 0 + "ker", "/bin/docker,bad")
                        .map(value -> Arguments.of(field, value)));
        Stream<Arguments> endpoints = Stream.of("hostRuntimeEndpoint", "supervisorRuntimeEndpoint")
                .flatMap(field -> Stream.of("", "tcp://127.0.0.1:2375", "unix://run/docker.sock",
                        "unix:///", "unix:////run/docker.sock", "unix:///run/../docker.sock",
                        "unix:///run/./docker.sock", "unix:///run/docker.sock/", "unix:///run/doc ker.sock",
                        "unix:///run/docker.sock,bad", "unix:///run\\docker.sock")
                        .map(value -> Arguments.of(field, value)));
        Stream<Arguments> images = Stream.of("supervisorImageId", "executorImageId")
                .flatMap(field -> Stream.of("", "runner:latest", "sha256:" + "a".repeat(63),
                        "sha256:" + "a".repeat(65), "sha256:" + "g".repeat(64), "sha256:" + "A".repeat(64),
                        " sha256:" + "a".repeat(64), "sha256:" + "a".repeat(64) + "\n")
                        .map(value -> Arguments.of(field, value)));
        Stream<Arguments> identities = Stream.of("configWriterUid", "configWriterGid", "supervisorUid",
                        "supervisorGid", "holderUid", "holderGid", "workloadUid", "workloadGid")
                .map(field -> Arguments.of(field, -1));
        Stream<Arguments> unsupported = Stream.of("configWriterUid", "configWriterGid", "supervisorUid", "supervisorGid")
                .map(field -> Arguments.of(field, 1000));
        Stream<Arguments> resources = Stream.of(
                Arguments.of("workloadUid", 0), Arguments.of("workloadGid", 0),
                Arguments.of("stagePidsLimit", 0), Arguments.of("stagePidsLimit", -1),
                Arguments.of("stageCpus", 0.0), Arguments.of("stageCpus", -1.0),
                Arguments.of("stageCpus", Double.NaN), Arguments.of("stageCpus", Double.POSITIVE_INFINITY),
                Arguments.of("stageCpus", Double.NEGATIVE_INFINITY));
        Stream<Arguments> bytes = Stream.of("inputLimitBytes", "artifactLimitBytes", "volumeLimitBytes", "controlOutputLimitBytes")
                .flatMap(field -> Stream.of(0L, -1L).map(value -> Arguments.of(field, value)));
        Stream<Arguments> durations = Stream.of("holderLifetime", "commandTimeout", "stageCommandTimeout")
                .flatMap(field -> Stream.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1),
                        Duration.ofNanos(1000001), Duration.ofSeconds(Long.MAX_VALUE))
                        .map(value -> Arguments.of(field, value)));
        return Stream.of(paths, endpoints, images, identities, unsupported, resources, bytes, durations).flatMap(stream -> stream);
    }

    @ParameterizedTest
    @MethodSource("invalidResourceLimits")
    void invalidNestedResourceLimitsAreRejected(String resource, String field, Object value) {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        ReflectionTestUtils.setField(ReflectionTestUtils.getField(properties, resource), field, value);

        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    static Stream<Arguments> invalidResourceLimits() {
        return Stream.of("supervisorLimits", "holderLimits").flatMap(resource -> Stream.of(
                Arguments.of(resource, "memoryBytes", 0L), Arguments.of(resource, "memoryBytes", -1L),
                Arguments.of(resource, "pidsLimit", 0), Arguments.of(resource, "pidsLimit", -1),
                Arguments.of(resource, "cpus", 0.0), Arguments.of(resource, "cpus", -1.0),
                Arguments.of(resource, "cpus", Double.NaN), Arguments.of(resource, "cpus", Double.POSITIVE_INFINITY),
                Arguments.of(resource, "cpus", Double.NEGATIVE_INFINITY)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/run", "/run/supervisor", "/run/supervisor/work"})
    void workMountCannotMaskSupervisorSocketParent(String mount) {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        properties.setWorkMount(mount);

        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workMount");
    }

    @Test
    void rootSocketParentCannotBeUsedWithAnyWorkMount() {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        properties.setSupervisorRuntimeEndpoint("unix:///docker.sock");

        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workMount");
    }

    @Test
    void sharedPathPrefixWithoutComponentOverlapIsAccepted() {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        properties.setWorkMount("/run/supervisor-work");

        assertThat(LinuxSupervisorOptions.fromProperties(properties).workMount()).isEqualTo("/run/supervisor-work");
    }

    @Test
    void holderLifetimeMustUseWholeSeconds() {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        properties.setHolderLifetime(Duration.ofMillis(1500));

        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("holderLifetime");
    }

    @ParameterizedTest
    @ValueSource(longs = {2147483648L, Long.MAX_VALUE})
    void controlOutputLimitMustFitInBoundedCliBuffer(long limit) {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        properties.setControlOutputLimitBytes(limit);

        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("controlOutputLimitBytes");
    }

    @Test
    void commandTimeoutMustFitInNanosecondsForBoundedCliDeadline() {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        properties.setCommandTimeout(Duration.ofMillis(Long.MAX_VALUE / 1_000_000 + 1));

        assertThatThrownBy(() -> LinuxSupervisorOptions.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("commandTimeout");
    }

    @Test
    void cliTechnicalBoundariesDoNotRestrictUnrelatedLifetimeAndStageTimeout() {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        properties.setControlOutputLimitBytes((long) Integer.MAX_VALUE);
        properties.setCommandTimeout(Duration.ofMillis(Long.MAX_VALUE / 1_000_000));
        properties.setHolderLifetime(Duration.ofSeconds(Long.MAX_VALUE / 1000));
        properties.setStageCommandTimeout(Duration.ofMillis(Long.MAX_VALUE));

        LinuxSupervisorOptions options = LinuxSupervisorOptions.fromProperties(properties);

        assertThat(options.controlOutputLimitBytes()).isEqualTo(Integer.MAX_VALUE);
        assertThat(options.commandTimeout()).isEqualTo(Duration.ofMillis(Long.MAX_VALUE / 1_000_000));
        assertThat(options.holderLifetime()).isEqualTo(Duration.ofSeconds(Long.MAX_VALUE / 1000));
        assertThat(options.stageCommandTimeout()).isEqualTo(Duration.ofMillis(Long.MAX_VALUE));
    }

    @Test
    void absoluteRuntimePathsAreValidatedWithoutAssumingLocalFilesystemPresence() {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        properties.setHostRuntimeCommand("/explicit-host-runtime/docker");
        properties.setSupervisorRuntimeCommand("/explicit-container-runtime/docker");

        LinuxSupervisorOptions options = LinuxSupervisorOptions.fromProperties(properties);

        assertThat(options.hostRuntimeCommand()).isEqualTo("/explicit-host-runtime/docker");
        assertThat(options.supervisorRuntimeCommand()).isEqualTo("/explicit-container-runtime/docker");
    }

    @Test
    void resourceRecordCannotBypassPropertyValidation() {
        assertThatThrownBy(() -> new LinuxSupervisorOptions.ResourceLimits(0, 1, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("memoryBytes");
        assertThatThrownBy(() -> new LinuxSupervisorOptions.ResourceLimits(1, 0, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pidsLimit");
        assertThatThrownBy(() -> new LinuxSupervisorOptions.ResourceLimits(1, 1, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cpus");
    }

    @Test
    void immutableOptionsDoNotRetainMutablePropertyReferences() {
        SandboxProperties.LinuxSupervisor properties = validProperties();
        LinuxSupervisorOptions options = LinuxSupervisorOptions.fromProperties(properties);
        properties.setWorkMount("/changed");
        properties.getSupervisorLimits().setMemoryBytes(1L);
        properties.getHolderLimits().setCpus(9.0);

        assertThat(options.workMount()).isEqualTo("/work");
        assertThat(options.supervisorLimits().memoryBytes()).isEqualTo(268435456L);
        assertThat(options.holderLimits().cpus()).isEqualTo(0.1);
    }

    @Test
    void explicitlyBoundConfigurationKeepsDistinctRuntimeNamespacesAndLimits() {
        String prefix = "judge.sandbox.production.linux-container.supervisor.";
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(prefix + "host-runtime-command", "/usr/bin/docker");
        values.put(prefix + "host-runtime-endpoint", "unix:///run/host/docker.sock");
        values.put(prefix + "supervisor-runtime-command", "/usr/local/bin/docker");
        values.put(prefix + "supervisor-runtime-endpoint", "unix:///run/supervisor/docker.sock");
        values.put(prefix + "supervisor-image-id", "sha256:" + "a".repeat(64));
        values.put(prefix + "executor-image-id", "sha256:" + "b".repeat(64));
        values.put(prefix + "work-mount", "/work");
        values.put(prefix + "config-writer-uid", "0");
        values.put(prefix + "config-writer-gid", "0");
        values.put(prefix + "supervisor-uid", "0");
        values.put(prefix + "supervisor-gid", "0");
        values.put(prefix + "holder-uid", "0");
        values.put(prefix + "holder-gid", "0");
        values.put(prefix + "workload-uid", "65532");
        values.put(prefix + "workload-gid", "65532");
        values.put(prefix + "supervisor-limits.memory-bytes", "268435456");
        values.put(prefix + "supervisor-limits.pids-limit", "32");
        values.put(prefix + "supervisor-limits.cpus", "0.5");
        values.put(prefix + "holder-limits.memory-bytes", "16777216");
        values.put(prefix + "holder-limits.pids-limit", "4");
        values.put(prefix + "holder-limits.cpus", "0.1");
        values.put(prefix + "holder-lifetime", "600s");
        values.put(prefix + "stage-pids-limit", "64");
        values.put(prefix + "stage-cpus", "1.0");
        values.put(prefix + "input-limit-bytes", "1048576");
        values.put(prefix + "artifact-limit-bytes", "2097152");
        values.put(prefix + "volume-limit-bytes", "33554432");
        values.put(prefix + "control-output-limit-bytes", "65536");
        values.put(prefix + "command-timeout", "1501ms");
        values.put(prefix + "stage-command-timeout", "2001ms");
        SandboxProperties properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("judge.sandbox.production", Bindable.of(SandboxProperties.class)).get();

        LinuxSupervisorOptions options = LinuxSupervisorOptions.fromProperties(properties.getLinuxContainer().getSupervisor());

        assertThat(options.hostRuntimeCommand()).isEqualTo("/usr/bin/docker");
        assertThat(options.supervisorRuntimeCommand()).isEqualTo("/usr/local/bin/docker");
        assertThat(options.hostRuntimeEndpoint()).isEqualTo("unix:///run/host/docker.sock");
        assertThat(options.supervisorRuntimeEndpoint()).isEqualTo("unix:///run/supervisor/docker.sock");
        assertThat(options.supervisorImageId()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(options.executorImageId()).isEqualTo("sha256:" + "b".repeat(64));
        assertThat(options.configWriterUid()).isZero();
        assertThat(options.configWriterGid()).isZero();
        assertThat(options.supervisorUid()).isZero();
        assertThat(options.supervisorGid()).isZero();
        assertThat(options.holderUid()).isZero();
        assertThat(options.holderGid()).isZero();
        assertThat(options.workloadUid()).isEqualTo(65532);
        assertThat(options.workloadGid()).isEqualTo(65532);
        assertThat(options.supervisorLimits()).isEqualTo(new LinuxSupervisorOptions.ResourceLimits(268435456L, 32, 0.5));
        assertThat(options.holderLimits()).isEqualTo(new LinuxSupervisorOptions.ResourceLimits(16777216L, 4, 0.1));
        assertThat(options.holderLifetime()).isEqualTo(Duration.ofSeconds(600));
        assertThat(options.stagePidsLimit()).isEqualTo(64);
        assertThat(options.stageCpus()).isEqualTo(1.0);
        assertThat(options.inputLimitBytes()).isEqualTo(1048576L);
        assertThat(options.artifactLimitBytes()).isEqualTo(2097152L);
        assertThat(options.volumeLimitBytes()).isEqualTo(33554432L);
        assertThat(options.controlOutputLimitBytes()).isEqualTo(65536L);
        assertThat(options.commandTimeout()).isEqualTo(Duration.ofMillis(1501));
        assertThat(options.stageCommandTimeout()).isEqualTo(Duration.ofMillis(2001));
    }

    private static SandboxProperties.LinuxSupervisor validProperties() {
        SandboxProperties.LinuxSupervisor properties = new SandboxProperties.LinuxSupervisor();
        properties.setHostRuntimeCommand("/usr/bin/docker");
        properties.setHostRuntimeEndpoint("unix:///run/host/docker.sock");
        properties.setSupervisorRuntimeCommand("/usr/bin/docker");
        properties.setSupervisorRuntimeEndpoint("unix:///run/supervisor/docker.sock");
        properties.setSupervisorImageId("sha256:" + "a".repeat(64));
        properties.setExecutorImageId("sha256:" + "b".repeat(64));
        properties.setWorkMount("/work");
        properties.setConfigWriterUid(0);
        properties.setConfigWriterGid(0);
        properties.setSupervisorUid(0);
        properties.setSupervisorGid(0);
        properties.setHolderUid(0);
        properties.setHolderGid(0);
        properties.setWorkloadUid(65532);
        properties.setWorkloadGid(65532);
        SandboxProperties.LinuxSupervisor.ResourceLimits supervisor = new SandboxProperties.LinuxSupervisor.ResourceLimits();
        supervisor.setMemoryBytes(268435456L);
        supervisor.setPidsLimit(32);
        supervisor.setCpus(0.5);
        properties.setSupervisorLimits(supervisor);
        SandboxProperties.LinuxSupervisor.ResourceLimits holder = new SandboxProperties.LinuxSupervisor.ResourceLimits();
        holder.setMemoryBytes(16777216L);
        holder.setPidsLimit(4);
        holder.setCpus(0.1);
        properties.setHolderLimits(holder);
        properties.setHolderLifetime(Duration.ofSeconds(600));
        properties.setStagePidsLimit(64);
        properties.setStageCpus(1.0);
        properties.setInputLimitBytes(1048576L);
        properties.setArtifactLimitBytes(2097152L);
        properties.setVolumeLimitBytes(33554432L);
        properties.setControlOutputLimitBytes(65536L);
        properties.setCommandTimeout(Duration.ofSeconds(30));
        properties.setStageCommandTimeout(Duration.ofSeconds(20));
        return properties;
    }
}
