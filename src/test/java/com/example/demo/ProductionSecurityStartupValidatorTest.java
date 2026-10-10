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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Path;
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
                .hasMessageContaining("DISABLED_UNVERIFIED");
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
    @CsvSource({
            "WINDOWS_CONTAINER, windows-prod, true, true", "WINDOWS_CONTAINER, windows-prod, true, false",
            "WINDOWS_CONTAINER, windows-prod, false, true", "WINDOWS_CONTAINER, windows-prod, false, false",
            "REMOTE_WORKER, worker-prod, true, true", "REMOTE_WORKER, worker-prod, true, false",
            "REMOTE_WORKER, worker-prod, false, true", "REMOTE_WORKER, worker-prod, false, false",
            "WINDOWS_CONTAINER, linux-prod, false, true", "REMOTE_WORKER, linux-prod, false, true",
            "WINDOWS_CONTAINER, custom-prod, false, true", "REMOTE_WORKER, custom-prod, false, true",
            "WINDOWS_CONTAINER, , false, true", "REMOTE_WORKER, , false, true"
    })
    void unverifiedProvidersCannotBeEnabledByProfileFlagsOrCapabilityClaims(
            SandboxProperties.Provider provider, String profile, boolean required, boolean passed
    ) {
        SandboxProperties properties = unverifiedProperties(provider);
        properties.setCapabilityProbeRequired(required);
        properties.setCapabilityProbePassed(passed);
        SandboxRunner runner = mock(SandboxRunner.class);
        when(runner.probe()).thenReturn(isolatedStartupContract()
                .provider(provider == SandboxProperties.Provider.WINDOWS_CONTAINER ? "windows-container" : "remote-worker")
                .isolation(provider == SandboxProperties.Provider.WINDOWS_CONTAINER ? "hyperv" : "remote")
                .build());

        assertThatThrownBy(() -> validator(profile, "changed-secret", "https://safe.example.com", true, true,
                properties, 1_048_576L, 1_048_576L, runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISABLED_UNVERIFIED")
                .hasMessageNotContaining("test-only-disabled-worker-token");
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows-prod", "worker-prod"})
    void disabledProductionProfilesCannotSelectAnApprovedProviderInstead(String profile) {
        SandboxRunner runner = mock(SandboxRunner.class);

        assertThatThrownBy(() -> validator(profile, "changed-secret", "https://safe.example.com", true, true,
                validLinuxProperties(), 1_048_576L, 1_048_576L, runner).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISABLED_UNVERIFIED");
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @CsvSource({"windows-prod, WINDOWS_CONTAINER", "worker-prod, REMOTE_WORKER"})
    void springStartupRejectsUnverifiedProviderWithoutProbing(String profile, SandboxProperties.Provider provider) {
        SandboxRunner runner = mock(SandboxRunner.class);

        containerContextRunner(profile, unverifiedProperties(provider))
                .withBean(SandboxRunner.class, () -> runner).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("DISABLED_UNVERIFIED");
                    verifyNoInteractions(runner);
                });
    }

    @ParameterizedTest
    @CsvSource({"windows-prod, WINDOWS_CONTAINER", "worker-prod, REMOTE_WORKER"})
    void springStartupRejectsUnverifiedProviderWithoutRunner(String profile, SandboxProperties.Provider provider) {
        containerContextRunner(profile, unverifiedProperties(provider)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .hasMessageContaining("DISABLED_UNVERIFIED");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"WINDOWS_CONTAINER", "REMOTE_WORKER"})
    void trustedLocalRetainsDevelopmentRunnerAccessWithoutProductionApproval(String providerName) {
        SandboxRunner runner = mock(SandboxRunner.class);

        assertThatCode(() -> validator("trusted-local", "explicit-local-test-material", "*", false, false,
                unverifiedProperties(SandboxProperties.Provider.valueOf(providerName)),
                1_048_576L, 1_048_576L, runner).validateNow()).doesNotThrowAnyException();
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows-prod", "worker-prod"})
    void actualApplicationRejectsUnverifiedProviderAtStartup(String profile, @TempDir Path directory) {
        SpringApplication application = new SpringApplication(Demo18Application.class);
        application.setRegisterShutdownHook(false);

        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext context = application.run(
                    "--spring.profiles.active=" + profile,
                    "--server.address=127.0.0.1", "--server.port=0",
                    "--logging.file.name=" + directory.resolve("startup.log"),
                    "--judge.sandbox.base-directory=" + directory.resolve("sandbox"),
                    "--judge.auth.account-auth-enabled=true",
                    "--judge.auth.accounts[0].user-id=disabled-provider-test-user",
                    "--judge.auth.accounts[0].username=disabled-provider-test-user",
                    "--judge.auth.accounts[0].password-hash=" + passwordEncoder.encode("TestOnly-Provider-Password-57"),
                    "--judge.sandbox.production.capability-probe-required=false",
                    "--judge.sandbox.production.capability-probe-passed=true",
                    "--judge.worker.endpoint=https://127.0.0.1:1",
                    "--judge.worker.auth-token=test-only-disabled-worker-token")) {
                assertThat(context).isNotNull();
            }
        }).satisfies(failure -> assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISABLED_UNVERIFIED")
                .hasMessageNotContaining("test-only-disabled-worker-token"));
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

    private SandboxProperties validWindowsProperties() {
        return sandboxProperties(SandboxProperties.Provider.WINDOWS_CONTAINER,
                SandboxProperties.Isolation.HYPER_V, "hyper-v;job-object", true);
    }

    private SandboxProperties unverifiedProperties(SandboxProperties.Provider provider) {
        if (provider == SandboxProperties.Provider.WINDOWS_CONTAINER) {
            return validWindowsProperties();
        }
        SandboxProperties properties = sandboxProperties(provider, SandboxProperties.Isolation.REMOTE,
                "worker-policy", true);
        properties.getWorker().setEndpoint("https://worker.example.com");
        properties.getWorker().setAuthToken("test-only-disabled-worker-token");
        return properties;
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
