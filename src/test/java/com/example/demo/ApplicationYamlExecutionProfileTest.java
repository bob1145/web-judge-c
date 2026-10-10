package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlExecutionProfileTest {

    private static final long DEFAULT_OUTPUT_BYTES_PER_CASE = 10_485_760L;
    private static final long DEFAULT_DETAIL_PREVIEW_BYTES = 65_536L;
    private static final long PRODUCTION_OUTPUT_BYTES_PER_CASE = 16_777_216L;

    @Test
    void defaultProfileKeepsSmallLocalOutputCap() throws Exception {
        ExecutionProperties properties = bindExecutionProperties(null);

        assertThat(properties.getProfile()).isEqualTo("trusted-local");
        assertThat(properties.getMaxCasesPerTask()).isEqualTo(10_000);
        assertThat(properties.getMaxOutputBytesPerCase()).isEqualTo(DEFAULT_OUTPUT_BYTES_PER_CASE);
        assertThat(properties.getMaxDetailPreviewBytes()).isEqualTo(DEFAULT_DETAIL_PREVIEW_BYTES);
        assertThat(properties.isRequireSandbox()).isFalse();
        assertThat(properties.getCreatedTaskScanInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getCreatedTaskScanMaxTasks()).isEqualTo(128);
        assertThat(properties.getCreatedTaskScanBudget()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getProgressCacheMaxEntries()).isEqualTo(1_024);
        assertThat(properties.getProgressCacheTtl()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void largeAndProductionProfilesBindHighVolumeOutputCaps() throws Exception {
        assertLocalLargeProfile();
        assertHighVolumeProfile("intranet-large", true, PRODUCTION_OUTPUT_BYTES_PER_CASE);
        assertHighVolumeProfile("windows-prod", true, PRODUCTION_OUTPUT_BYTES_PER_CASE);
        assertHighVolumeProfile("linux-prod", true, PRODUCTION_OUTPUT_BYTES_PER_CASE);
        assertHighVolumeProfile("worker-prod", true, PRODUCTION_OUTPUT_BYTES_PER_CASE);
    }

    @ParameterizedTest
    @CsvSource({"created-task-scan-interval,0ms", "created-task-scan-interval,-1ms",
            "created-task-scan-max-tasks,0", "created-task-scan-max-tasks,-1",
            "created-task-scan-budget,0s", "created-task-scan-budget,-1s",
            "created-task-scan-budget,1000000000000s",
            "created-task-scan-interval,9223372036854775807s"})
    void invalidCreatedScanPolicyFailsStartup(String property, String value) {
        new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class)
                .withPropertyValues("judge.execution." + property + "=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void positiveCreatedScanPolicyStartsWithBoundProperties() {
        new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class)
                .withPropertyValues("judge.execution.created-task-scan-interval=2s",
                        "judge.execution.created-task-scan-max-tasks=7",
                        "judge.execution.created-task-scan-budget=50ms")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ExecutionProperties properties = context.getBean(ExecutionProperties.class);
                    assertThat(properties.getCreatedTaskScanInterval()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.getCreatedTaskScanMaxTasks()).isEqualTo(7);
                    assertThat(properties.getCreatedTaskScanBudget()).isEqualTo(Duration.ofMillis(50));
                });
    }

    @EnableConfigurationProperties(ExecutionProperties.class)
    static class ExecutionConfiguration {}

    @ParameterizedTest
    @CsvSource({"progress-cache-max-entries,0", "progress-cache-max-entries,-1",
            "progress-cache-max-entries,2147483648", "progress-cache-ttl,0s",
            "progress-cache-ttl,-1s", "progress-cache-ttl,invalid", "progress-cache-ttl,''",
            "progress-cache-max-entries,''"})
    void invalidProgressCachePolicyFailsStartup(String property, String value) {
        new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class)
                .withPropertyValues("judge.execution." + property + "=" + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void positiveProgressCachePolicyStartsWithBoundProperties() {
        new ApplicationContextRunner().withUserConfiguration(ExecutionConfiguration.class)
                .withPropertyValues("judge.execution.progress-cache-max-entries=3",
                        "judge.execution.progress-cache-ttl=2m")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ExecutionProperties properties = context.getBean(ExecutionProperties.class);
                    assertThat(properties.getProgressCacheMaxEntries()).isEqualTo(3);
                    assertThat(properties.getProgressCacheTtl()).isEqualTo(Duration.ofMinutes(2));
                });
    }

    private void assertLocalLargeProfile() throws Exception {
        ExecutionProperties properties = bindExecutionProperties("local-large");

        assertThat(properties.getProfile()).isEqualTo("local-large");
        assertThat(properties.getMaxCasesPerTask()).isEqualTo(100_000);
        assertThat(properties.getMaxOutputBytesPerCase()).isGreaterThan(DEFAULT_OUTPUT_BYTES_PER_CASE);
        assertThat(properties.getMaxDetailPreviewBytes()).isEqualTo(DEFAULT_DETAIL_PREVIEW_BYTES);
        assertThat(properties.getMaxTaskRuntime()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.isRequireSandbox()).isFalse();
    }

    private void assertHighVolumeProfile(String profile, boolean requireSandbox, long maxOutputBytesPerCase) throws Exception {
        ExecutionProperties properties = bindExecutionProperties(profile);

        assertThat(properties.getProfile()).isEqualTo(profile);
        assertThat(properties.getMaxCasesPerTask()).isEqualTo(100_000);
        assertThat(properties.getMaxOutputBytesPerCase()).isEqualTo(maxOutputBytesPerCase);
        assertThat(properties.getMaxDetailPreviewBytes()).isEqualTo(DEFAULT_DETAIL_PREVIEW_BYTES);
        assertThat(properties.getMaxTaskRuntime()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.isRequireSandbox()).isEqualTo(requireSandbox);
    }

    private ExecutionProperties bindExecutionProperties(String activeProfile) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        MockEnvironment environment = new MockEnvironment();

        for (PropertySource<?> source : sources) {
            if (appliesToProfile(source, activeProfile)) {
                environment.getPropertySources().addFirst(source);
            }
        }

        return Binder.get(environment)
                .bind("judge.execution", Bindable.of(ExecutionProperties.class))
                .orElseThrow(() -> new AssertionError("judge.execution did not bind from application.yml"));
    }

    private boolean appliesToProfile(PropertySource<?> source, String activeProfile) {
        Object profile = source.getProperty("spring.config.activate.on-profile");
        if (profile == null) {
            return true;
        }
        return activeProfile != null && profile.toString().equals(activeProfile);
    }
}
