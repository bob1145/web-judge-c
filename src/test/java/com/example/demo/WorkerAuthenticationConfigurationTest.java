package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.ProductionSecurityStartupValidator;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SandboxProperties;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.config.WorkerProperties;
import com.example.demo.service.sandbox.RemoteWorkerRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerAuthenticationConfigurationTest {

    @Test
    void certificateStringDoesNotClaimAnAuthenticatedChannel() {
        WorkerProperties primary = new WorkerProperties();
        primary.setMtlsCertificate("test-only-certificate-path");
        assertThat(primary.hasAuthenticatedChannel()).isFalse();
        primary.setAuthToken("test-only-bearer");
        assertThat(primary.hasAuthenticatedChannel()).isFalse();
        SandboxProperties.Worker nested = new SandboxProperties.Worker();
        nested.setMtlsCertificate("test-only-certificate-path");
        assertThat(nested.hasAuthenticatedChannel()).isFalse();
        nested.setSigningSecret("test-only-signing-material");
        assertThat(nested.hasAuthenticatedChannel()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "test-only-bearer"})
    void unsupportedMtlsCannotBeEnabledByAddingOtherCredentials(String bearer) {
        assertThatThrownBy(() -> RemoteWorkerRunner.Options.builder()
                .endpoint("https://worker.example")
                .authToken(bearer)
                .mtlsCertificate("test-only-certificate-path")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mTLS")
                .hasMessageNotContaining("test-only-certificate-path");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://worker.example", "ftp://worker.example", "https://worker.example?credential=test-only-value",
            "https://worker.example/#test-only-value", "https://test-only-value@worker.example",
            "https://worker.example:70000", "https:///missing-host", "https://bad host/test-only-value"
    })
    void invalidOrPlaintextEndpointsFailWithoutEchoingCredentials(String endpoint) {
        assertThatThrownBy(() -> RemoteWorkerRunner.Options.builder()
                .endpoint(endpoint).authToken("test-only-bearer").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("test-only-value")
                .hasMessageNotContaining("test-only-bearer");
    }

    @Test
    void endpointAndAuthenticationConflictsAreNotSilentlyOverridden() {
        SandboxProperties sandbox = sandbox();
        sandbox.getWorker().setEndpoint("https://nested.example");
        sandbox.getWorker().setAuthToken("test-only-nested-bearer");
        WorkerProperties primary = new WorkerProperties();
        primary.setEndpoint("https://primary.example");
        primary.setAuthToken("test-only-nested-bearer");
        assertConflict(sandbox, primary);
        primary.setEndpoint(sandbox.getWorker().getEndpoint());
        primary.setAuthToken("test-only-primary-bearer");
        assertConflict(sandbox, primary);
    }

    @Test
    void matchingOrSingleSourceSettingsRemainUsable() {
        SandboxProperties sandbox = sandbox();
        sandbox.getWorker().setEndpoint("https://worker.example/base");
        sandbox.getWorker().setAuthToken("test-only-bearer");
        WorkerProperties primary = new WorkerProperties();
        assertThatCode(() -> new RemoteWorkerRunner(sandbox, primary, new ObjectMapper())).doesNotThrowAnyException();
        primary.setEndpoint(sandbox.getWorker().getEndpoint());
        primary.setAuthToken(sandbox.getWorker().getAuthToken());
        assertThatCode(() -> new RemoteWorkerRunner(sandbox, primary, new ObjectMapper())).doesNotThrowAnyException();
    }

    @Test
    void trustedLocalStartupUsesPrimaryWorkerSettingsRatherThanOnlyNestedSettings() {
        SandboxProperties sandbox = sandbox();
        WorkerProperties primary = new WorkerProperties();
        primary.setEndpoint("https://worker.example");
        primary.setSigningSecret("test-only-signing-material");
        contextRunner(sandbox, primary).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startupRejectsCertificateOnlyAndConflictingSources() {
        SandboxProperties sandbox = sandbox();
        sandbox.getWorker().setEndpoint("https://worker.example");
        sandbox.getWorker().setMtlsCertificate("test-only-certificate-path");
        WorkerProperties primary = new WorkerProperties();
        contextRunner(sandbox, primary).run(context -> assertThat(context).hasFailed());
        sandbox.getWorker().setMtlsCertificate("");
        sandbox.getWorker().setAuthToken("test-only-nested-bearer");
        primary.setEndpoint(sandbox.getWorker().getEndpoint());
        primary.setAuthToken("test-only-primary-bearer");
        contextRunner(sandbox, primary).run(context -> assertThat(context).hasFailed());
    }

    private void assertConflict(SandboxProperties sandbox, WorkerProperties primary) {
        assertThatThrownBy(() -> new RemoteWorkerRunner(sandbox, primary, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting worker")
                .hasMessageNotContaining("test-only-primary-bearer")
                .hasMessageNotContaining("test-only-nested-bearer");
    }

    @Test
    void trustedLocalNestedHmacAndActualRunnerBeanUseTheSameResolvedSettings() {
        SandboxProperties sandbox = sandbox();
        sandbox.getWorker().setEndpoint("https://worker.example");
        sandbox.getWorker().setSigningSecret("test-only-signing-material");
        assertThat(sandbox.getWorker().hasAuthenticatedChannel()).isTrue();
        contextRunner(sandbox, new WorkerProperties())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RemoteWorkerRunner.class);
                });
    }

    @Test
    void signingSecretConflictFailsWithoutRevealingEitherValue() {
        SandboxProperties sandbox = sandbox();
        sandbox.getWorker().setEndpoint("https://worker.example");
        sandbox.getWorker().setSigningSecret("test-only-nested-signing");
        WorkerProperties primary = new WorkerProperties();
        primary.setSigningSecret("test-only-primary-signing");
        assertThatThrownBy(() -> new RemoteWorkerRunner(sandbox, primary, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting worker signing")
                .hasMessageNotContaining("test-only-nested-signing")
                .hasMessageNotContaining("test-only-primary-signing");
    }

    private SandboxProperties sandbox() {
        SandboxProperties sandbox = new SandboxProperties();
        sandbox.setProvider(SandboxProperties.Provider.REMOTE_WORKER);
        sandbox.setIsolation(SandboxProperties.Isolation.REMOTE);
        sandbox.setCapabilityProbePassed(true);
        return sandbox;
    }

    private ApplicationContextRunner contextRunner(SandboxProperties sandbox, WorkerProperties worker) {
        return contextRunner(sandbox, worker, "trusted-local");
    }

    @Test
    void workerProdRemainsDisabledWithAValidActualRunnerAndCredentials() {
        WorkerProperties worker = new WorkerProperties();
        worker.setEndpoint("https://worker.example");
        worker.setAuthToken("test-only-disabled-worker-token");

        contextRunner(sandbox(), worker, "worker-prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DISABLED_UNVERIFIED")
                    .hasMessageNotContaining("test-only-disabled-worker-token");
        });
    }

    private ApplicationContextRunner contextRunner(SandboxProperties sandbox, WorkerProperties worker, String profile) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProductionSecurityStartupValidator.class, RemoteWorkerRunner.class)
                .withPropertyValues("judge.sandbox.production.provider=remote-worker")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(SandboxProperties.class, () -> sandbox)
                .withBean(WorkerProperties.class, () -> worker)
                .withBean(WebSocketConfig.class, () -> new WebSocketConfig("https://safe.example"))
                .withBean(SandboxConfiguration.class, () -> {
                    SandboxConfiguration configuration = new SandboxConfiguration();
                    configuration.setEnabled(true);
                    return configuration;
                })
                .withBean(ExecutionProperties.class, () -> {
                    ExecutionProperties execution = new ExecutionProperties();
                    execution.setProfile(profile);
                    execution.setRequireSandbox(true);
                    return execution;
                })
                .withBean(AuthConfiguration.class, () -> {
                    AuthConfiguration auth = new AuthConfiguration();
                    auth.setAccountAuthEnabled(true);
                    AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
                    account.setUserId("worker-validator-user");
                    account.setUsername("worker-validator-user");
                    account.setPasswordHash(new BCryptPasswordEncoder().encode("TestOnly-Worker-Account-48"));
                    auth.setAccounts(List.of(account));
                    return auth;
                });
    }
}
