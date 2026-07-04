package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationYamlExecutionProfileTest {

    private static final long DEFAULT_OUTPUT_BYTES_PER_CASE = 1_048_576L;
    private static final long PRODUCTION_OUTPUT_BYTES_PER_CASE = 16_777_216L;

    @Test
    void defaultProfileKeepsSmallLocalOutputCap() throws Exception {
        ExecutionProperties properties = bindExecutionProperties(null);

        assertThat(properties.getProfile()).isEqualTo("trusted-local");
        assertThat(properties.getMaxCasesPerTask()).isEqualTo(10_000);
        assertThat(properties.getMaxOutputBytesPerCase()).isEqualTo(DEFAULT_OUTPUT_BYTES_PER_CASE);
        assertThat(properties.isRequireSandbox()).isFalse();
    }

    @Test
    void largeAndProductionProfilesBindHighVolumeOutputCaps() throws Exception {
        assertLocalLargeProfile();
        assertHighVolumeProfile("intranet-large", true, PRODUCTION_OUTPUT_BYTES_PER_CASE);
        assertHighVolumeProfile("windows-prod", true, PRODUCTION_OUTPUT_BYTES_PER_CASE);
        assertHighVolumeProfile("linux-prod", true, PRODUCTION_OUTPUT_BYTES_PER_CASE);
        assertHighVolumeProfile("worker-prod", true, PRODUCTION_OUTPUT_BYTES_PER_CASE);
    }

    private void assertLocalLargeProfile() throws Exception {
        ExecutionProperties properties = bindExecutionProperties("local-large");

        assertThat(properties.getProfile()).isEqualTo("local-large");
        assertThat(properties.getMaxCasesPerTask()).isEqualTo(100_000);
        assertThat(properties.getMaxOutputBytesPerCase()).isGreaterThan(DEFAULT_OUTPUT_BYTES_PER_CASE);
        assertThat(properties.getMaxTaskRuntime()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.isRequireSandbox()).isFalse();
    }

    private void assertHighVolumeProfile(String profile, boolean requireSandbox, long maxOutputBytesPerCase) throws Exception {
        ExecutionProperties properties = bindExecutionProperties(profile);

        assertThat(properties.getProfile()).isEqualTo(profile);
        assertThat(properties.getMaxCasesPerTask()).isEqualTo(100_000);
        assertThat(properties.getMaxOutputBytesPerCase()).isEqualTo(maxOutputBytesPerCase);
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
