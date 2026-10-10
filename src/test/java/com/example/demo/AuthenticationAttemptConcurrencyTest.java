package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class AuthenticationAttemptConcurrencyTest {

    private static final String VALID_CREDENTIAL = "test-only-authentication-attempt-03";
    private static final String FIRST_ADDRESS = "127.0.0.1";
    private static final String OTHER_ADDRESS = "127.0.0.2";

    @ParameterizedTest
    @ValueSource(strings = {"account", "access-code"})
    void aPendingFailureSerializesOnlyItsOwnKeyBeforeTheNextLockoutCheck(String mode) throws Exception {
        AuthConfiguration configuration = spy(configuration(mode, 1));
        CountDownLatch firstCheckEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCheck = new CountDownLatch(1);
        CountDownLatch secondRequestStarted = new CountDownLatch(1);
        AtomicInteger credentialChecks = new AtomicInteger();
        Answer<Object> blockFirstCheck = invocation -> {
            if (credentialChecks.incrementAndGet() == 1) {
                firstCheckEntered.countDown();
                if (!releaseFirstCheck.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("First credential check was not released");
                }
            }
            return invocation.callRealMethod();
        };
        if (mode.equals("account")) {
            doAnswer(blockFirstCheck).when(configuration).getAccounts();
        } else {
            doAnswer(blockFirstCheck).when(configuration).isUsableAccessCode();
        }
        BiFunction<String, String, String> authenticate = authenticator(mode, configuration);
        ExecutorService requests = Executors.newFixedThreadPool(3);
        try {
            Future<String> first = requests.submit(() -> authenticate.apply("wrong", FIRST_ADDRESS));
            assertThat(firstCheckEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> unrelated = requests.submit(() -> authenticate.apply("wrong", OTHER_ADDRESS));
            assertThat(unrelated.get(5, TimeUnit.SECONDS)).isEqualTo(invalidStatus(mode));
            Future<String> second = requests.submit(() -> {
                secondRequestStarted.countDown();
                return authenticate.apply("wrong", FIRST_ADDRESS);
            });
            assertThat(secondRequestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirstCheck.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(invalidStatus(mode));
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("TOO_MANY_ATTEMPTS");
            assertThat(credentialChecks).hasValue(2);
            assertThat(authenticate.apply(VALID_CREDENTIAL, FIRST_ADDRESS)).isEqualTo("TOO_MANY_ATTEMPTS");
        } finally {
            releaseFirstCheck.countDown();
            requests.shutdownNow();
            assertThat(requests.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "access-code"})
    void aSuccessfulAuthenticationClearsOnlyItsOwnFailedAttempts(String mode) {
        BiFunction<String, String, String> authenticate = authenticator(mode, configuration(mode, 2));
        assertThat(authenticate.apply("wrong", FIRST_ADDRESS)).isEqualTo(invalidStatus(mode));
        assertThat(authenticate.apply("wrong", OTHER_ADDRESS)).isEqualTo(invalidStatus(mode));
        assertThat(authenticate.apply(VALID_CREDENTIAL, FIRST_ADDRESS)).isEqualTo("SUCCESS");
        assertThat(authenticate.apply("wrong", FIRST_ADDRESS)).isEqualTo(invalidStatus(mode));
        assertThat(authenticate.apply("wrong", FIRST_ADDRESS)).isEqualTo(invalidStatus(mode));
        assertThat(authenticate.apply(VALID_CREDENTIAL, FIRST_ADDRESS)).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(authenticate.apply("wrong", OTHER_ADDRESS)).isEqualTo(invalidStatus(mode));
        assertThat(authenticate.apply(VALID_CREDENTIAL, OTHER_ADDRESS)).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    private AuthConfiguration configuration(String mode, int maxAttempts) {
        AuthConfiguration configuration = new AuthConfiguration();
        configuration.setMaxAttempts(maxAttempts);
        configuration.setAttemptResetHours(1);
        configuration.setAccessCode(VALID_CREDENTIAL);
        if (mode.equals("account")) {
            AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
            account.setUserId("test-attempt-user");
            account.setUsername("test-attempt-login");
            account.setPasswordHash(new BCryptPasswordEncoder().encode(VALID_CREDENTIAL));
            AuthConfiguration.AccountProperties other = new AuthConfiguration.AccountProperties();
            other.setUserId("test-attempt-other-user");
            other.setUsername("test-attempt-other-login");
            other.setPasswordHash(account.getPasswordHash());
            configuration.setAccounts(List.of(account, other));
        }
        return configuration;
    }

    @Test
    void theSameAccountSerializesCredentialChecksAcrossDifferentSources() throws Exception {
        AuthConfiguration configuration = spy(configuration("account", 1));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        doAnswer(invocation -> {
            checks.incrementAndGet();
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Credential check was not released");
            }
            return invocation.callRealMethod();
        }).when(configuration).getAccounts();
        UserAccountService accounts = new UserAccountService(configuration, new ExecutionProperties());
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = requests.submit(() -> accounts.authenticate("test-attempt-login", "wrong", FIRST_ADDRESS).status().name());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> second = requests.submit(() -> {
                secondStarted.countDown();
                return accounts.authenticate("test-attempt-login", VALID_CREDENTIAL, OTHER_ADDRESS).status().name();
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("INVALID_CREDENTIALS");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("TOO_MANY_ATTEMPTS");
            assertThat(checks).hasValue(1);
        } finally {
            release.countDown();
            requests.shutdownNow();
            assertThat(requests.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void accessCodeGlobalBudgetIncludesPendingChecksFromOtherSources() throws Exception {
        AuthConfiguration configuration = spy(configuration("access-code", 10));
        configuration.setMaxGlobalAccessCodeAttempts(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        doAnswer(invocation -> {
            checks.incrementAndGet();
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Credential check was not released");
            }
            return invocation.callRealMethod();
        }).when(configuration).isUsableAccessCode();
        AccessCodeService codes = new AccessCodeService(configuration);
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = requests.submit(() -> codes.validateAccessCode("wrong", FIRST_ADDRESS).name());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<String> second = requests.submit(() -> codes.validateAccessCode(VALID_CREDENTIAL, OTHER_ADDRESS).name());
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("TOO_MANY_ATTEMPTS");
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("INVALID_CODE");
            assertThat(checks).hasValue(1);
            assertThat(codes.validateAccessCode(VALID_CREDENTIAL, "127.0.0.3").name()).isEqualTo("TOO_MANY_ATTEMPTS");
        } finally {
            release.countDown();
            requests.shutdownNow();
            assertThat(requests.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedCredentialCheckReleasesItsGlobalReservation() {
        AuthConfiguration configuration = spy(configuration("access-code", 10));
        configuration.setMaxGlobalAccessCodeAttempts(1);
        AtomicInteger checks = new AtomicInteger();
        doAnswer(invocation -> {
            if (checks.incrementAndGet() == 1) {
                throw new IllegalStateException("Test credential check unavailable");
            }
            return invocation.callRealMethod();
        }).when(configuration).isUsableAccessCode();
        AccessCodeService codes = new AccessCodeService(configuration);
        assertThatThrownBy(() -> codes.validateAccessCode("wrong", FIRST_ADDRESS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(codes.validateAccessCode(VALID_CREDENTIAL, OTHER_ADDRESS).name()).isEqualTo("SUCCESS");
        assertThat(checks).hasValue(2);
    }

    private BiFunction<String, String, String> authenticator(String mode, AuthConfiguration configuration) {
        if (mode.equals("account")) {
            UserAccountService service = new UserAccountService(configuration, new ExecutionProperties());
            return (credential, address) -> service.authenticate(
                    OTHER_ADDRESS.equals(address) ? "test-attempt-other-login" : "test-attempt-login",
                    credential, address).status().name();
        }
        AccessCodeService service = new AccessCodeService(configuration);
        return (credential, address) -> service.validateAccessCode(credential, address).name();
    }

    private String invalidStatus(String mode) {
        return mode.equals("account") ? "INVALID_CREDENTIALS" : "INVALID_CODE";
    }
}
