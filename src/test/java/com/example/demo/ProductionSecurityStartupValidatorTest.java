package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.ProductionSecurityStartupValidator;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SandboxProperties;
import com.example.demo.config.WebSocketConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "123",
                "*",
                false,
                false,
                sandbox
        );

        assertThatCode(validator::validateNow).doesNotThrowAnyException();
        assertThat(output.getOut() + output.getErr())
                .contains("trusted-local")
                .contains("default access code")
                .contains("wildcard WebSocket origin")
                .contains("sandbox is disabled")
                .contains("direct sandbox provider");
    }

    @Test
    void windowsProdRejectsDirectProviderProcessIsolationDisabledSandboxDefaultCodeAndWildcardOrigin() {
        SandboxProperties sandbox = sandboxProperties(
                SandboxProperties.Provider.DIRECT,
                SandboxProperties.Isolation.PROCESS,
                "",
                false
        );

        assertThatThrownBy(() -> validator("windows-prod", "123", "*", false, false, sandbox).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insecure windows-prod configuration")
                .hasMessageContaining("default access code")
                .hasMessageContaining("wildcard WebSocket origin")
                .hasMessageContaining("sandbox.required must be true")
                .hasMessageContaining("sandbox is disabled")
                .hasMessageContaining("provider must be windows-container")
                .hasMessageContaining("isolation must be hyper-v")
                .hasMessageContaining("sandbox capability probe has not passed");
    }

    @Test
    void linuxProdRejectsDirectProviderMissingSecurityProfileDisabledSandboxDefaultCodeAndWildcardOrigin() {
        SandboxProperties sandbox = sandboxProperties(
                SandboxProperties.Provider.DIRECT,
                SandboxProperties.Isolation.CONTAINER,
                "",
                false
        );

        assertThatThrownBy(() -> validator("linux-prod", "123", "*", false, false, sandbox).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insecure linux-prod configuration")
                .hasMessageContaining("default access code")
                .hasMessageContaining("wildcard WebSocket origin")
                .hasMessageContaining("sandbox.required must be true")
                .hasMessageContaining("sandbox is disabled")
                .hasMessageContaining("provider must be linux-container")
                .hasMessageContaining("security profile is required")
                .hasMessageContaining("sandbox capability probe has not passed");
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
    void linuxProdStartsWhenContainerSecurityProfileSandboxAndCapabilityProbeAreConfigured() {
        SandboxProperties sandbox = sandboxProperties(
                SandboxProperties.Provider.LINUX_CONTAINER,
                SandboxProperties.Isolation.CONTAINER,
                "seccomp:judge-linux",
                true
        );

        assertThatCode(() -> validator("linux-prod", "changed-secret", "https://safe.example.com", true, true, sandbox).validateNow())
                .doesNotThrowAnyException();
    }

    private ProductionSecurityStartupValidator validator(
            String profile,
            String accessCode,
            String allowedOrigins,
            boolean sandboxEnabled,
            boolean requireSandbox,
            SandboxProperties sandboxProperties
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

        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(sandboxEnabled);

        return new ProductionSecurityStartupValidator(
                auth,
                execution,
                sandbox,
                sandboxProperties,
                new WebSocketConfig(allowedOrigins)
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
