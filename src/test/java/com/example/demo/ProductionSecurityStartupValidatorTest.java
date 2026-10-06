package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.ProductionSecurityStartupValidator;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SandboxProperties;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.config.WorkerProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.service.sandbox.SandboxRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ProductionSecurityStartupValidatorTest {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    void trustedLocalAllowsDirectRunnerAndWildcardOriginButLogsWarnings(CapturedOutput output) {
        SandboxProperties sandbox = sandboxProperties(
                SandboxProperties.Provider.DIRECT,
                SandboxProperties.Isolation.PROCESS,
                "",
                false
        );

        ProductionSecurityStartupValidator validator = validator(
                "trusted-local",
                "test-only-explicit-material",
                "*",
                false,
                false,
                sandbox
        );

        assertThatCode(validator::validateNow).doesNotThrowAnyException();
        assertThat(output.getOut() + output.getErr())
                .contains("trusted-local")
                .contains("wildcard WebSocket origin")
                .contains("sandbox is disabled")
                .contains("direct sandbox provider");
    }

    @Test
    void windowsProdRejectsDirectProviderProcessIsolationDisabledSandboxAndWildcardOrigin() {
        SandboxProperties sandbox = sandboxProperties(
                SandboxProperties.Provider.DIRECT,
                SandboxProperties.Isolation.PROCESS,
                "",
                false
        );

        assertThatThrownBy(() -> validator("windows-prod", "123", "*", false, false, sandbox).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insecure windows-prod configuration")
                .hasMessageContaining("wildcard WebSocket origin")
                .hasMessageContaining("sandbox.required must be true")
                .hasMessageContaining("sandbox is disabled")
                .hasMessageContaining("provider must be windows-container")
                .hasMessageContaining("isolation must be hyper-v")
                .hasMessageContaining("control-plane isolation");
    }

    @Test
    void linuxProdRejectsDirectProviderMissingSecurityProfileDisabledSandboxAndWildcardOrigin() {
        SandboxProperties sandbox = sandboxProperties(
                SandboxProperties.Provider.DIRECT,
                SandboxProperties.Isolation.CONTAINER,
                "",
                false
        );

        assertThatThrownBy(() -> validator("linux-prod", "123", "*", false, false, sandbox).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insecure linux-prod configuration")
                .hasMessageContaining("wildcard WebSocket origin")
                .hasMessageContaining("sandbox.required must be true")
                .hasMessageContaining("sandbox is disabled")
                .hasMessageContaining("provider must be linux-container")
                .hasMessageContaining("security profile is required")
                .hasMessageContaining("control-plane isolation");
    }

    @Test
    void workerProdRequiresEndpointAndAuthenticatedWorkerChannelWithoutLeakingSecrets() {
        SandboxProperties missingWorker = sandboxProperties(
                SandboxProperties.Provider.REMOTE_WORKER,
                SandboxProperties.Isolation.REMOTE,
                "worker-policy",
                true
        );

        assertThatThrownBy(() -> validator("worker-prod", "changed-secret", "https://safe.example.com", true, true, missingWorker).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worker endpoint is required")
                .hasMessageContaining("worker authentication is required");

        SandboxProperties secretWorker = sandboxProperties(
                SandboxProperties.Provider.REMOTE_WORKER,
                SandboxProperties.Isolation.REMOTE,
                "worker-policy",
                false
        );
        secretWorker.getWorker().setEndpoint("https://worker.example.com");
        secretWorker.getWorker().setAuthToken("super-secret-token");

        assertThatThrownBy(() -> validator("worker-prod", "changed-secret", "https://safe.example.com", true, true, secretWorker).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox capability probe has not passed")
                .hasMessageNotContaining("super-secret-token");
    }

    @Test
    void changingOnlySpringActiveProfileNameDoesNotBypassExecutionProfileValidation() {
        String originalActiveProfile = System.getProperty("spring.profiles.active");
        System.setProperty("spring.profiles.active", "trusted-local");
        try {
            SandboxProperties sandbox = sandboxProperties(
                    SandboxProperties.Provider.DIRECT,
                    SandboxProperties.Isolation.PROCESS,
                    "",
                    false
            );

            assertThatThrownBy(() -> validator("windows-prod", "123", "*", false, false, sandbox).validateNow())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Insecure windows-prod configuration");
        } finally {
            if (originalActiveProfile == null) {
                System.clearProperty("spring.profiles.active");
            } else {
                System.setProperty("spring.profiles.active", originalActiveProfile);
            }
        }
    }

    @Test
    void linuxProdRejectsConfigurationOnlyCapabilityApproval() {
        SandboxProperties sandbox = sandboxProperties(
                SandboxProperties.Provider.LINUX_CONTAINER,
                SandboxProperties.Isolation.CONTAINER,
                "seccomp:judge-linux",
                true
        );

        assertThatThrownBy(() -> validator("linux-prod", "changed-secret", "https://safe.example.com", true, true, sandbox).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control-plane isolation");
    }

    @Test
    void productionProfilesRejectNonPositiveOutputLimitAtStartup() {
        SandboxProperties sandbox = sandboxProperties(
                SandboxProperties.Provider.LINUX_CONTAINER,
                SandboxProperties.Isolation.CONTAINER,
                "seccomp:judge-linux",
                true
        );

        assertThatThrownBy(() -> validator(
                "linux-prod",
                "changed-secret",
                "https://safe.example.com",
                true,
                true,
                sandbox,
                0
        ).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxOutputBytesPerCase");
    }

    @Test
    void productionProfilesRejectNonPositiveDetailPreviewLimitAtStartup() {
        SandboxProperties sandbox = sandboxProperties(
                SandboxProperties.Provider.LINUX_CONTAINER,
                SandboxProperties.Isolation.CONTAINER,
                "seccomp:judge-linux",
                true
        );

        assertThatThrownBy(() -> validator(
                "linux-prod",
                "changed-secret",
                "https://safe.example.com",
                true,
                true,
                sandbox,
                1_048_576L,
                0
        ).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxDetailPreviewBytes");
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void linuxProdRequiresActualIsolationRegardlessOfConfigurationFlags(boolean required, boolean passed) {
        SandboxProperties properties = validLinuxProperties();
        properties.setCapabilityProbeRequired(required);
        properties.setCapabilityProbePassed(passed);
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(isolatedStartupContract().controlPlaneIsolated(false).build());

        assertThatThrownBy(() -> linuxValidator(properties, runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control-plane isolation");
        verify(runner).probe();
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "isolation", "productionSafe", "controlPlaneIsolated",
            "networkDisabled", "nonRoot", "resourceLimits", "securityProfile"})
    void linuxProdRejectsContradictoryCapabilityResults(String field) {
        SandboxCapabilities.Builder capabilities = isolatedStartupContract();
        switch (field) {
            case "provider" -> capabilities.provider("remote-worker");
            case "isolation" -> capabilities.isolation("process");
            case "productionSafe" -> capabilities.productionSafe(false);
            case "controlPlaneIsolated" -> capabilities.controlPlaneIsolated(false);
            case "networkDisabled" -> capabilities.networkDisabled(false);
            case "nonRoot" -> capabilities.nonRoot(false);
            case "resourceLimits" -> capabilities.resourceLimits(false);
            case "securityProfile" -> capabilities.securityProfile(" ");
            default -> throw new IllegalArgumentException(field);
        }
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(capabilities.build());

        assertThatThrownBy(() -> linuxValidator(validLinuxProperties(), runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control-plane isolation");
        verify(runner).probe();
    }

    @Test
    void linuxProdRejectsNullCapabilityResult() {
        SandboxRunner runner = mock(SandboxRunner.class);

        assertThatThrownBy(() -> linuxValidator(validLinuxProperties(), runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control-plane isolation");
        verify(runner).probe();
    }

    @Test
    void linuxProdSanitizesCapabilityProbeFailure(CapturedOutput output) {
        SandboxRunner runner = mock(SandboxRunner.class);
        String secret = "/private/runner/token=do-not-leak";
        when(runner.probe()).thenThrow(new IllegalStateException(secret));

        assertThatThrownBy(() -> linuxValidator(validLinuxProperties(), runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("control-plane isolation")
                .hasMessageNotContaining(secret)
                .hasNoCause();
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void linuxProdAcceptsConsistentActualCapabilitiesNotManualFlags(boolean required, boolean passed) {
        SandboxProperties properties = validLinuxProperties();
        properties.setCapabilityProbeRequired(required);
        properties.setCapabilityProbePassed(passed);
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(isolatedStartupContract().build());

        assertThatCode(() -> linuxValidator(properties, runner).validateNow()).doesNotThrowAnyException();
        verify(runner).probe();
    }

    @Test
    void trustedLocalDoesNotProbeRunner() {
        SandboxRunner runner = mock(SandboxRunner.class);

        assertThatCode(() -> validator("trusted-local", "explicit-local-test-material", "*", false, false,
                validLinuxProperties(), 1_048_576L, 1_048_576L, runner).validateNow())
                .doesNotThrowAnyException();
        verifyNoInteractions(runner);
    }

    @Test
    void springStartupUsesInjectedRunnerCapabilities() {
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(isolatedStartupContract().controlPlaneIsolated(false).build());

        linuxContextRunner().withBean(SandboxRunner.class, () -> runner).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure())).hasMessage(
                    "Insecure linux-prod configuration: linux-container control-plane isolation and production safety must be verified by the runner");
            verify(runner).probe();
        });
    }

    @Test
    void springStartupRejectsMissingRunner() {
        linuxContextRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure())).hasMessage(
                    "Insecure linux-prod configuration: linux-container control-plane isolation capability probe is unavailable");
        });
    }

    @Test
    void springStartupAcceptsIsolatedCapabilityContractFixture() {
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(isolatedStartupContract().build());

        linuxContextRunner().withBean(SandboxRunner.class, () -> runner).run(context -> {
            assertThat(context).hasNotFailed();
            verify(runner).probe();
        });
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void windowsProdRequiresActualIsolationRegardlessOfConfigurationFlags(boolean required, boolean passed) {
        SandboxProperties properties = validWindowsProperties();
        properties.setCapabilityProbeRequired(required);
        properties.setCapabilityProbePassed(passed);
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(isolatedWindowsStartupContract().controlPlaneIsolated(false).build());

        assertThatThrownBy(() -> windowsValidator(properties, runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("windows-container control-plane isolation");
        verify(runner).probe();
    }

    @ParameterizedTest
    @ValueSource(strings = {"provider", "isolation", "productionSafe", "controlPlaneIsolated",
            "networkDisabled", "nonRoot", "resourceLimits", "securityProfile"})
    void windowsProdRejectsContradictoryCapabilityResults(String field) {
        SandboxCapabilities.Builder capabilities = isolatedWindowsStartupContract();
        switch (field) {
            case "provider" -> capabilities.provider("linux-container");
            case "isolation" -> capabilities.isolation("process");
            case "productionSafe" -> capabilities.productionSafe(false);
            case "controlPlaneIsolated" -> capabilities.controlPlaneIsolated(false);
            case "networkDisabled" -> capabilities.networkDisabled(false);
            case "nonRoot" -> capabilities.nonRoot(false);
            case "resourceLimits" -> capabilities.resourceLimits(false);
            case "securityProfile" -> capabilities.securityProfile(" ");
            default -> throw new IllegalArgumentException(field);
        }
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(capabilities.build());

        assertThatThrownBy(() -> windowsValidator(validWindowsProperties(), runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("windows-container control-plane isolation");
        verify(runner).probe();
    }

    @Test
    void windowsProdRejectsNullCapabilityResult() {
        SandboxRunner runner = mock(SandboxRunner.class);

        assertThatThrownBy(() -> windowsValidator(validWindowsProperties(), runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("windows-container control-plane isolation capability probe is unavailable");
        verify(runner).probe();
    }

    @Test
    void windowsProdSanitizesCapabilityProbeFailure(CapturedOutput output) {
        SandboxRunner runner = mock(SandboxRunner.class);
        String secret = "/private/runner/token=do-not-leak";
        when(runner.probe()).thenThrow(new IllegalStateException(secret));

        assertThatThrownBy(() -> windowsValidator(validWindowsProperties(), runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("windows-container control-plane isolation capability probe failed")
                .hasMessageNotContaining(secret)
                .hasNoCause();
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void windowsProdAcceptsConsistentActualCapabilityContractNotManualFlags(boolean required, boolean passed) {
        SandboxProperties properties = validWindowsProperties();
        properties.setCapabilityProbeRequired(required);
        properties.setCapabilityProbePassed(passed);
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(isolatedWindowsStartupContract().build());

        assertThatCode(() -> windowsValidator(properties, runner).validateNow()).doesNotThrowAnyException();
        verify(runner).probe();
    }

    @Test
    void windowsSpringStartupRejectsMissingRunnerDespiteManualApproval() {
        containerContextRunner("windows-prod", validWindowsProperties()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure())).hasMessage(
                    "Insecure windows-prod configuration: windows-container control-plane isolation capability probe is unavailable");
        });
    }

    @Test
    void windowsSpringStartupUsesInjectedRunnerCapabilities() {
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(isolatedWindowsStartupContract().controlPlaneIsolated(false).build());

        containerContextRunner("windows-prod", validWindowsProperties())
                .withBean(SandboxRunner.class, () -> runner).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure())).hasMessage(
                            "Insecure windows-prod configuration: windows-container control-plane isolation and production safety must be verified by the runner");
                    verify(runner).probe();
                });
    }

    @Test
    void windowsSpringStartupAcceptsIsolatedCapabilityContractFixture() {
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(isolatedWindowsStartupContract().build());

        containerContextRunner("windows-prod", validWindowsProperties())
                .withBean(SandboxRunner.class, () -> runner).run(context -> {
                    assertThat(context).hasNotFailed();
                    verify(runner).probe();
                });
    }

    private ApplicationContextRunner linuxContextRunner() {
        return containerContextRunner("linux-prod", validLinuxProperties());
    }

    private ApplicationContextRunner containerContextRunner(String profile, SandboxProperties properties) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccountAuthEnabled(true);
        auth.setAccounts(List.of(validAccount()));
        ExecutionProperties execution = new ExecutionProperties();
        execution.setProfile(profile);
        execution.setRequireSandbox(true);
        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(true);
        return new ApplicationContextRunner()
                .withUserConfiguration(ProductionSecurityStartupValidator.class)
                .withBean(AuthConfiguration.class, () -> auth)
                .withBean(ExecutionProperties.class, () -> execution)
                .withBean(SandboxConfiguration.class, () -> sandbox)
                .withBean(SandboxProperties.class, () -> properties)
                .withBean(WebSocketConfig.class, () -> new WebSocketConfig("https://safe.example.com"))
                .withBean(WorkerProperties.class, WorkerProperties::new);
    }

    private ProductionSecurityStartupValidator linuxValidator(SandboxProperties properties, SandboxRunner runner) {
        return validator("linux-prod", "changed-secret", "https://safe.example.com", true, true,
                properties, 1_048_576L, 1_048_576L, runner);
    }

    private ProductionSecurityStartupValidator windowsValidator(SandboxProperties properties, SandboxRunner runner) {
        return validator("windows-prod", "changed-secret", "https://safe.example.com", true, true,
                properties, 1_048_576L, 1_048_576L, runner);
    }

    private SandboxProperties validWindowsProperties() {
        return sandboxProperties(SandboxProperties.Provider.WINDOWS_CONTAINER,
                SandboxProperties.Isolation.HYPER_V, "hyper-v;job-object", true);
    }

    private SandboxCapabilities.Builder isolatedWindowsStartupContract() {
        return isolatedStartupContract().provider("windows-container").isolation("hyperv");
    }

    private SandboxProperties validLinuxProperties() {
        return sandboxProperties(SandboxProperties.Provider.LINUX_CONTAINER,
                SandboxProperties.Isolation.CONTAINER, "seccomp:judge-linux", true);
    }

    private SandboxCapabilities.Builder isolatedStartupContract() {
        return SandboxCapabilities.builder().provider("linux-container").isolation("container")
                .productionSafe(true).controlPlaneIsolated(true).networkDisabled(true).nonRoot(true)
                .resourceLimits(true).securityProfile("test-only-startup-contract");
    }

    private ProductionSecurityStartupValidator validator(
            String profile,
            String accessCode,
            String allowedOrigins,
            boolean sandboxEnabled,
            boolean requireSandbox,
            SandboxProperties sandboxProperties
    ) {
        return validator(
                profile,
                accessCode,
                allowedOrigins,
                sandboxEnabled,
                requireSandbox,
                sandboxProperties,
                1_048_576L,
                1_048_576L
        );
    }

    private ProductionSecurityStartupValidator validator(
            String profile,
            String accessCode,
            String allowedOrigins,
            boolean sandboxEnabled,
            boolean requireSandbox,
            SandboxProperties sandboxProperties,
            long maxOutputBytesPerCase
    ) {
        return validator(
                profile,
                accessCode,
                allowedOrigins,
                sandboxEnabled,
                requireSandbox,
                sandboxProperties,
                maxOutputBytesPerCase,
                1_048_576L
        );
    }

    private ProductionSecurityStartupValidator validator(
            String profile,
            String accessCode,
            String allowedOrigins,
            boolean sandboxEnabled,
            boolean requireSandbox,
            SandboxProperties sandboxProperties,
            long maxOutputBytesPerCase,
            long maxDetailPreviewBytes
    ) {
        return validator(profile, accessCode, allowedOrigins, sandboxEnabled, requireSandbox,
                sandboxProperties, maxOutputBytesPerCase, maxDetailPreviewBytes, null);
    }

    private ProductionSecurityStartupValidator validator(
            String profile,
            String accessCode,
            String allowedOrigins,
            boolean sandboxEnabled,
            boolean requireSandbox,
            SandboxProperties sandboxProperties,
            long maxOutputBytesPerCase,
            long maxDetailPreviewBytes,
            SandboxRunner runner
    ) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode(accessCode);
        auth.setDefaultAccessCode("123");
        if (!"trusted-local".equals(profile)) {
            auth.setAccountAuthEnabled(true);
            auth.setAccounts(List.of(validAccount()));
        }

        ExecutionProperties execution = new ExecutionProperties();
        execution.setProfile(profile);
        execution.setRequireSandbox(requireSandbox);
        execution.setMaxOutputBytesPerCase(maxOutputBytesPerCase);
        execution.setMaxDetailPreviewBytes(maxDetailPreviewBytes);

        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(sandboxEnabled);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (runner != null) {
            beanFactory.addBean("startupContractRunner", runner);
        }

        return new ProductionSecurityStartupValidator(
                auth,
                execution,
                sandbox,
                sandboxProperties,
                new WebSocketConfig(allowedOrigins),
                new WorkerProperties(),
                beanFactory.getBeanProvider(SandboxRunner.class)
        );
    }

    private SandboxProperties sandboxProperties(
            SandboxProperties.Provider provider,
            SandboxProperties.Isolation isolation,
            String securityProfile,
            boolean capabilityProbePassed
    ) {
        SandboxProperties sandbox = new SandboxProperties();
        sandbox.setProvider(provider);
        sandbox.setIsolation(isolation);
        sandbox.setSecurityProfile(securityProfile);
        sandbox.setCapabilityProbeRequired(true);
        sandbox.setCapabilityProbePassed(capabilityProbePassed);
        return sandbox;
    }

    private AuthConfiguration.AccountProperties validAccount() {
        AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
        account.setUserId("validator-admin");
        account.setUsername("validator-admin");
        account.setPasswordHash(passwordEncoder.encode("CorrectPassword-51"));
        account.setAdmin(true);
        return account;
    }
}
