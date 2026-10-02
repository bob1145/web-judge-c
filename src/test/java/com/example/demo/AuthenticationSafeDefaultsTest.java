package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.ProductionSecurityStartupValidator;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SandboxProperties;
import com.example.demo.config.SecurityModeStartupValidator;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.model.AuthStatus;
import com.example.demo.service.AccessCodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationSafeDefaultsTest {

    @Test
    void javaAndYamlHaveNoUsablePasswordAndBindLoopbackByDefault() throws Exception {
        AuthConfiguration auth = new AuthConfiguration();
        assertThat(auth.getAccessCode()).isNullOrEmpty();
        assertThat(auth.getDefaultAccessCode()).isNullOrEmpty();
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("address: 127.0.0.1")
                .doesNotContain("admin_ed", "admin_wx13", "secure-access-code-2024", "default-access-code:", "access-code: \"123\"");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "123", "secure-access-code-2024", " 123 "})
    void accessCodeModeFailsClosedAtBothStartupGuards(String material) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode(material);
        ExecutionProperties execution = execution("trusted-local");
        assertThatThrownBy(() -> securityValidator(auth, execution).validateNow())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("access code");
        assertThatThrownBy(() -> productionValidator(auth, execution).validateNow())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("access code");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "123", "secure-access-code-2024"})
    void invalidOrLegacyAccessMaterialCannotAuthenticateEvenWithoutStartup(String material) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode(material);
        AccessCodeService service = new AccessCodeService(auth);
        assertThat(service.validateAccessCode("123", "127.0.0.1")).isEqualTo(AuthStatus.INVALID_CODE);
        assertThat(service.validateAccessCode("secure-access-code-2024", "127.0.0.2")).isEqualTo(AuthStatus.INVALID_CODE);
    }

    @Test
    void explicitlyConfiguredLegacySentinelCannotBeReenabled() {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode("test-only-retired-code");
        auth.setDefaultAccessCode("test-only-retired-code");
        assertThatThrownBy(() -> productionValidator(auth, execution("trusted-local")).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("test-only-retired-code");
        assertThat(new AccessCodeService(auth).validateAccessCode("test-only-retired-code", "127.0.0.1"))
                .isEqualTo(AuthStatus.INVALID_CODE);
    }

    @Test
    void explicitAccessCodeCanStartAndAuthenticateInLocalMode() {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode("test-only-explicit-material");
        ExecutionProperties execution = execution("trusted-local");
        assertThatCode(() -> securityValidator(auth, execution).validateNow()).doesNotThrowAnyException();
        assertThatCode(() -> productionValidator(auth, execution).validateNow()).doesNotThrowAnyException();
        assertThat(new AccessCodeService(auth).validateAccessCode(" test-only-explicit-material ", "127.0.0.1"))
                .isEqualTo(AuthStatus.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"trusted-local", "linux-prod", "intranet-large"})
    void accountModeDoesNotRequireOrValidateUnusedAccessMaterial(String profile) {
        AuthConfiguration auth = accountAuth();
        for (String unused : new String[]{null, "", "123", "secure-access-code-2024"}) {
            auth.setAccessCode(unused);
            ExecutionProperties execution = execution(profile);
            assertThatCode(() -> securityValidator(auth, execution).validateNow()).doesNotThrowAnyException();
            assertThatCode(() -> productionValidator(auth, execution).validateNow()).doesNotThrowAnyException();
        }
    }

    @Test
    void enabledLocalAccountModeRequiresUsableAccountsRatherThanUnusedAccessCode() {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccountAuthEnabled(true);
        auth.setAccessCode("test-only-valid-but-unused");
        assertInvalidAccount(auth);
        auth.setAccounts(accountAuth().getAccounts());
        auth.getAccounts().get(0).setEnabled(false);
        assertInvalidAccount(auth);
        auth.getAccounts().get(0).setEnabled(true);
        auth.getAccounts().get(0).setPasswordHash("$2-not-a-valid-bcrypt-hash");
        assertInvalidAccount(auth);
    }

    @Test
    void springStartupWithoutAccessMaterialFailsButExplicitPropertySucceeds() {
        contextRunner().run(context -> assertThat(context).hasFailed());
        contextRunner().withPropertyValues("judge.auth.access-code=test-only-context-material")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private void assertInvalidAccount(AuthConfiguration auth) {
        assertThatThrownBy(() -> productionValidator(auth, execution("trusted-local")).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account")
                .hasMessageNotContaining("test-only-valid-but-unused")
                .hasMessageNotContaining("$2-not-a-valid-bcrypt-hash");
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(AuthConfiguration.class, StartupConfiguration.class);
    }

    private AuthConfiguration accountAuth() {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccountAuthEnabled(true);
        AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
        account.setUserId("safe-default-user");
        account.setUsername("safe-default-user");
        account.setPasswordHash(new BCryptPasswordEncoder().encode("TestOnly-Account-Password-02"));
        auth.setAccounts(List.of(account));
        return auth;
    }

    private static ExecutionProperties execution(String profile) {
        ExecutionProperties execution = new ExecutionProperties();
        execution.setProfile(profile);
        execution.setRequireSandbox(true);
        return execution;
    }

    private SecurityModeStartupValidator securityValidator(AuthConfiguration auth, ExecutionProperties execution) {
        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(true);
        return new SecurityModeStartupValidator(auth, execution, sandbox, new WebSocketConfig("https://safe.example"));
    }

    private static ProductionSecurityStartupValidator productionValidator(AuthConfiguration auth, ExecutionProperties execution) {
        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(true);
        SandboxProperties production = new SandboxProperties();
        production.setProvider(SandboxProperties.Provider.LINUX_CONTAINER);
        production.setIsolation(SandboxProperties.Isolation.CONTAINER);
        production.setSecurityProfile("test-only-policy");
        production.setCapabilityProbePassed(true);
        return new ProductionSecurityStartupValidator(auth, execution, sandbox, production, new WebSocketConfig("https://safe.example"));
    }

    @Configuration(proxyBeanMethods = false)
    static class StartupConfiguration {
        @Bean
        ProductionSecurityStartupValidator validator(AuthConfiguration auth) {
            return productionValidator(auth, execution("trusted-local"));
        }
    }
}
