package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.MemoryConfiguration;
import com.example.demo.dto.JudgeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Test
    void rejectsNonPositiveConfiguredOutputLimitBeforeTaskStarts() {
        ExecutionProperties properties = ordinaryProperties(12);
        properties.setMaxOutputBytesPerCase(0);
        TaskPolicyResolver resolver = resolver(properties, memoryConfiguration());

        assertRejectedWithPolicyContext(resolver, request(1), "maxOutputBytesPerCase");
    }

    private TaskPolicyResolver resolver(ExecutionProperties properties, MemoryConfiguration memoryConfiguration) {
        return new TaskPolicyResolver(properties, memoryConfiguration);
    }

    @Test
    void comparisonSnapshotPreservesExistingDefaultZeroAndExplicitPagePrecision() {
        TaskPolicyResolver resolver = resolver(ordinaryProperties(12), memoryConfiguration());
        JudgeRequest request = request(1);
        assertThat(resolver.resolve(request).comparison().precision()).isEqualTo("0");
        request.setPrecision(0);
        assertThat(resolver.resolve(request).comparison().precision()).isEqualTo("0");
        request.setPrecision(1e-9);
        var serializedPrecision = new ObjectMapper().valueToTree(request).path("precision");
        assertThat(serializedPrecision.isNumber()).isTrue();
        assertThat(serializedPrecision.doubleValue()).isEqualTo(1e-9);
        ResolvedTaskPolicy policy = resolver.resolve(request);
        assertThat(policy.comparison().precision()).isEqualTo("1.0E-9");
        assertThat(policy.comparison().mode()).isEqualTo(com.example.demo.dto.SandboxComparison.Mode.DECIMAL_ABSOLUTE);
        request.setPrecision(0);
        assertThatThrownBy(() -> resolver.validate(request, policy))
                .isInstanceOf(TaskPolicyResolver.PolicyValidationException.class);
        request.setUseSpecialJudge(true);
        request.setSpecialJudgeCode("int main(){return 0;}");
        assertThat(resolver.resolve(request).comparison().mode())
                .isEqualTo(com.example.demo.dto.SandboxComparison.Mode.SPECIAL_JUDGE);
    }

    @Test
    void comparisonSnapshotRejectsNegativeAndNonFinitePrecision() {
        TaskPolicyResolver resolver = resolver(ordinaryProperties(12), memoryConfiguration());
        for (double value : new double[]{-1e-9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            JudgeRequest request = request(1);
            request.setPrecision(value);
            assertRejectedWithPolicyContext(resolver, request, "precision");
        }
    }

    @Test
    void jsonNumbersAndStringsKeepExactPrecisionThroughSnapshotAndSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TaskPolicyResolver resolver = resolver(ordinaryProperties(12), memoryConfiguration());
        String exact = "1.00000000000000000000000000000000000001";
        for (String jsonPrecision : new String[]{exact, mapper.writeValueAsString(exact)}) {
            JudgeRequest request = mapper.readValue("{\"testCases\":1,\"precision\":" + jsonPrecision + "}", JudgeRequest.class);
            assertThat(request.precisionText()).isEqualTo(exact);
            assertThat(resolver.resolve(request).comparison().precision()).isEqualTo(exact);
            JudgeRequest roundTrip = mapper.readValue(mapper.writeValueAsString(request), JudgeRequest.class);
            assertThat(roundTrip.getDecimalPrecision()).isEqualByComparingTo(exact);
        }
    }

    @Test
    void precisionBudgetAcceptsInclusiveLimitsAndRejectsOutOfRangeBeforeAssignment() {
        TaskPolicyResolver resolver = resolver(ordinaryProperties(12), memoryConfiguration());
        JudgeRequest request = request(1);
        for (String valid : new String[]{"1e1000", "1e-1000", "0".repeat(4096)}) {
            request.setPrecision(valid);
            assertThat(resolver.resolve(request).comparison().precision())
                    .isEqualTo(valid.startsWith("1") ? valid : "0");
        }
        for (String invalid : new String[]{"1e1001", "1e-1001", "0".repeat(4097), "NaN", "0x1p0", "1f"}) {
            assertThatThrownBy(() -> request.setPrecision(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThat(request.precisionText()).isEqualTo("0".repeat(4096));
        }
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
