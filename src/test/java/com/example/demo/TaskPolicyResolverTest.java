package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.MemoryConfiguration;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.TaskPolicyResolver;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskPolicyResolverTest {

    @Test
    void ordinaryProfileAcceptsConfiguredBoundaryAndRejectsOverLimit() {
        TaskPolicyResolver resolver = resolver(ordinaryProperties(12), memoryConfiguration());

        ResolvedTaskPolicy minimum = resolver.resolve(request(1));
        assertThat(minimum.requestedCases()).isEqualTo(1);
        assertThat(minimum.highVolume()).isFalse();

        ResolvedTaskPolicy boundary = resolver.resolve(request(12));
        assertThat(boundary.requestedCases()).isEqualTo(12);
        assertThat(boundary.maxCasesPerTask()).isEqualTo(12);

        assertRejectedWithPolicyContext(resolver, request(13), "13");
        assertRejectedWithPolicyContext(resolver, request(99_999), "99999");
    }

    @Test
    void localLargeProfileAcceptsHighVolume100000AndRejects100001() {
        ExecutionProperties properties = ordinaryProperties(100_000);
        properties.setProfile("local-large");
        properties.setLargeModeThreshold(5_000);
        TaskPolicyResolver resolver = resolver(properties, memoryConfiguration());

        ResolvedTaskPolicy almostLarge = resolver.resolve(request(99_999));
        assertThat(almostLarge.highVolume()).isTrue();
        assertThat(almostLarge.requestedCases()).isEqualTo(99_999);

        ResolvedTaskPolicy maxLarge = resolver.resolve(request(100_000));
        assertThat(maxLarge.highVolume()).isTrue();
        assertThat(maxLarge.maxCasesPerTask()).isEqualTo(100_000);

        assertRejectedWithPolicyContext(resolver, request(100_001), "100001");
    }

    @Test
    void rejectsZeroAndNegativeTestCasesWithUserReadablePolicyContext() {
        TaskPolicyResolver resolver = resolver(ordinaryProperties(12), memoryConfiguration());

        assertRejectedWithPolicyContext(resolver, request(0), "at least 1");
        assertRejectedWithPolicyContext(resolver, request(-1), "at least 1");
    }

    @Test
    void timeLimitUsesDefaultAndValidatesMinimumAndMaximum() {
        ExecutionProperties properties = ordinaryProperties(12);
        properties.setDefaultTimeLimit(Duration.ofMillis(2_000));
        properties.setMinTimeLimit(Duration.ofMillis(100));
        properties.setMaxTimeLimit(Duration.ofMillis(10_000));
        TaskPolicyResolver resolver = resolver(properties, memoryConfiguration());

        ResolvedTaskPolicy defaulted = resolver.resolve(request(3, 0, 0));
        assertThat(defaulted.caseTimeLimit()).isEqualTo(Duration.ofMillis(2_000));

        ResolvedTaskPolicy maxBoundary = resolver.resolve(request(3, 10_000, 0));
        assertThat(maxBoundary.caseTimeLimit()).isEqualTo(Duration.ofMillis(10_000));

        assertRejectedWithPolicyContext(resolver, request(3, -1, 0), "timeLimit");
        assertRejectedWithPolicyContext(resolver, request(3, 99, 0), "timeLimit");
        assertRejectedWithPolicyContext(resolver, request(3, 10_001, 0), "timeLimit");
    }

    @Test
    void memoryLimitUsesDefaultAndRejectsValuesOutsidePolicyAndMemoryMax() {
        ExecutionProperties properties = ordinaryProperties(12);
        properties.setMinMemoryLimitBytes(16L * 1024 * 1024);
        MemoryConfiguration memory = memoryConfiguration();
        TaskPolicyResolver resolver = resolver(properties, memory);

        ResolvedTaskPolicy defaulted = resolver.resolve(request(3, 0, 0));
        assertThat(defaulted.memoryLimitBytes()).isEqualTo(memory.getDefaultLimit());

        ResolvedTaskPolicy maxBoundary = resolver.resolve(request(3, 0, memory.getMaxLimit()));
        assertThat(maxBoundary.memoryLimitBytes()).isEqualTo(memory.getMaxLimit());

        assertRejectedWithPolicyContext(resolver, request(3, 0, -1), "memoryLimit");
        assertRejectedWithPolicyContext(resolver, request(3, 0, 1024), "memoryLimit");
        assertRejectedWithPolicyContext(resolver, request(3, 0, memory.getMaxLimit() + 1), "memoryLimit");
    }

    @Test
    void highVolumeFlagIsDerivedFromBackendThresholdOnly() {
        ExecutionProperties properties = ordinaryProperties(100);
        properties.setLargeModeThreshold(50);
        TaskPolicyResolver resolver = resolver(properties, memoryConfiguration());

        assertThat(resolver.resolve(request(49)).highVolume()).isFalse();
        assertThat(resolver.resolve(request(50)).highVolume()).isTrue();
    }

    private TaskPolicyResolver resolver(ExecutionProperties properties, MemoryConfiguration memoryConfiguration) {
        return new TaskPolicyResolver(properties, memoryConfiguration);
    }

    private ExecutionProperties ordinaryProperties(int maxCasesPerTask) {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setProfile("trusted-local");
        properties.setMaxCasesPerTask(maxCasesPerTask);
        properties.setLargeModeThreshold(50);
        properties.setBatchSize(8);
        properties.setMaxConcurrentCasesPerTask(4);
        properties.setDefaultTimeLimit(Duration.ofMillis(2_000));
        properties.setMinTimeLimit(Duration.ofMillis(100));
        properties.setMaxTimeLimit(Duration.ofMillis(10_000));
        properties.setMaxTaskRuntime(Duration.ofMinutes(5));
        properties.setMaxOutputBytesPerCase(1_048_576);
        properties.setRequireSandbox(false);
        return properties;
    }

    private MemoryConfiguration memoryConfiguration() {
        MemoryConfiguration memory = new MemoryConfiguration();
        memory.setDefaultLimit(256L * 1024 * 1024);
        memory.setMaxLimit(1024L * 1024 * 1024);
        return memory;
    }

    private JudgeRequest request(int testCases) {
        return request(testCases, 0, 0);
    }

    private JudgeRequest request(int testCases, long timeLimit, long memoryLimit) {
        JudgeRequest request = new JudgeRequest();
        request.setTestCases(testCases);
        request.setTimeLimit(timeLimit);
        request.setMemoryLimit(memoryLimit);
        return request;
    }

    private void assertRejectedWithPolicyContext(TaskPolicyResolver resolver, JudgeRequest request, String messageFragment) {
        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(TaskPolicyResolver.PolicyValidationException.class)
                .satisfies(error -> {
                    TaskPolicyResolver.PolicyValidationException validation =
                            (TaskPolicyResolver.PolicyValidationException) error;
                    assertThat(validation.getSubmittedCases()).isEqualTo(request.getTestCases());
                    assertThat(validation.getMaxCasesPerTask()).isGreaterThan(0);
                    assertThat(validation.getProfile()).isNotBlank();
                    assertThat(validation.getMessage()).contains(messageFragment);
                });
    }
}
