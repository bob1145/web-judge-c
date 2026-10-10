package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.SessionManagementService;
import com.example.demo.service.UserAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.env.MapPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationBoundsTest {
    private static final String PASSWORD = "test-only-bounded-authentication-03";

    @Test
    void accountFailuresRemainLimitedWhenTheSourceChanges() {
        AuthConfiguration configuration = configuration();
        UserAccountService service = new UserAccountService(configuration, new ExecutionProperties());
        assertThat(service.authenticate("first", "wrong", "127.0.0.1").status())
                .isEqualTo(UserAccountService.Status.INVALID_CREDENTIALS);
        assertThat(service.authenticate("first", "wrong", "127.0.0.2").status())
                .isEqualTo(UserAccountService.Status.INVALID_CREDENTIALS);
        assertThat(service.authenticate(" FIRST ", PASSWORD, "127.0.0.3").status())
                .isEqualTo(UserAccountService.Status.TOO_MANY_ATTEMPTS);
    }

    @Test
    void sourceFailuresRemainLimitedWhenAccountsChange() {
        AuthConfiguration configuration = configuration();
        UserAccountService service = new UserAccountService(configuration, new ExecutionProperties());
        assertThat(service.authenticate("first", "wrong", "127.0.0.1").status())
                .isEqualTo(UserAccountService.Status.INVALID_CREDENTIALS);
        assertThat(service.authenticate("second", "wrong", "127.0.0.1").status())
                .isEqualTo(UserAccountService.Status.INVALID_CREDENTIALS);
        assertThat(service.authenticate("unknown", "wrong", "127.0.0.1").status())
                .isEqualTo(UserAccountService.Status.TOO_MANY_ATTEMPTS);
        assertThat(service.authenticate("second", PASSWORD, "127.0.0.2").status())
                .isEqualTo(UserAccountService.Status.SUCCESS);
    }

    @Test
    void accessCodeGlobalFailuresRemainLimitedWhenTheSourceChanges() {
        AuthConfiguration configuration = configuration();
        AccessCodeService service = new AccessCodeService(configuration);
        assertThat(service.validateAccessCode("wrong", "127.0.0.1").name()).isEqualTo("INVALID_CODE");
        assertThat(service.validateAccessCode("wrong", "127.0.0.2").name()).isEqualTo("INVALID_CODE");
        assertThat(service.validateAccessCode(PASSWORD, "127.0.0.3").name()).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "access-code"})
    void newKeysAreRejectedAtCapacityWithoutEvictingExistingLockouts(String mode) {
        AuthConfiguration configuration = configuration();
        configuration.setMaxAttempts(1);
        configuration.setMaxSourceAttempts(1);
        configuration.setMaxGlobalAccessCodeAttempts(100);
        configuration.setMaxAttemptStates(mode.equals("account") ? 2 : 1);
        UserAccountService accounts = new UserAccountService(configuration, new ExecutionProperties());
        AccessCodeService codes = new AccessCodeService(configuration);
        Object service = mode.equals("account") ? accounts : codes;
        String first = mode.equals("account")
                ? accounts.authenticate("first", "wrong", "127.0.0.1").status().name()
                : codes.validateAccessCode("wrong", "127.0.0.1").name();
        assertThat(first).isEqualTo(mode.equals("account") ? "INVALID_CREDENTIALS" : "INVALID_CODE");
        for (int index = 2; index <= 20; index++) {
            String status = mode.equals("account")
                    ? accounts.authenticate("unknown-" + index, "wrong", "127.0.0." + index).status().name()
                    : codes.validateAccessCode("wrong", "127.0.0." + index).name();
            assertThat(status).isEqualTo("TOO_MANY_ATTEMPTS");
        }
        Map<?, ?> attempts = (Map<?, ?>) ReflectionTestUtils.getField(service, "attempts");
        Map<?, ?> sources = mode.equals("account")
                ? (Map<?, ?>) ReflectionTestUtils.getField(service, "sourceAttempts") : null;
        assertThat(attempts.size() + (sources == null ? 0 : sources.size()))
                .isLessThanOrEqualTo(configuration.getMaxAttemptStates());
        String stillLocked = mode.equals("account")
                ? accounts.authenticate("first", PASSWORD, "127.0.0.1").status().name()
                : codes.validateAccessCode(PASSWORD, "127.0.0.1").name();
        assertThat(stillLocked).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "access-code"})
    void concurrentNewKeysCannotExceedTheConfiguredCapacity(String mode) throws Exception {
        AuthConfiguration configuration = configuration();
        configuration.setMaxAttempts(100);
        configuration.setMaxSourceAttempts(100);
        configuration.setMaxGlobalAccessCodeAttempts(100);
        configuration.setMaxAttemptStates(7);
        UserAccountService accounts = new UserAccountService(configuration, new ExecutionProperties());
        AccessCodeService codes = new AccessCodeService(configuration);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 1; index <= 20; index++) {
                String address = "127.0.0." + index;
                results.add(workers.submit(() -> mode.equals("account")
                        ? accounts.authenticate("first", "wrong", address).status().name()
                        : codes.validateAccessCode("wrong", address).name()));
            }
            int rejected = 0;
            for (Future<String> result : results) {
                String status = result.get(5, TimeUnit.SECONDS);
                if (status.equals("TOO_MANY_ATTEMPTS")) {
                    rejected++;
                } else {
                    assertThat(status).isEqualTo(mode.equals("account") ? "INVALID_CREDENTIALS" : "INVALID_CODE");
                }
            }
            assertThat(rejected).isEqualTo(mode.equals("account") ? 14 : 13);
            assertThat(stateCount(mode, mode.equals("account") ? accounts : codes)).isEqualTo(7);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "access-code"})
    void scheduledCleanupExpiresIdleStateWithoutEvictingFreshLockouts(String mode) {
        AuthConfiguration configuration = configuration();
        configuration.setMaxAttempts(1);
        configuration.setMaxSourceAttempts(100);
        configuration.setMaxGlobalAccessCodeAttempts(100);
        AtomicReference<Instant> time = new AtomicReference<>(Instant.now());
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        UserAccountService accounts = new UserAccountService(configuration, new ExecutionProperties(), clock);
        AccessCodeService codes = new AccessCodeService(configuration, accounts, clock);
        SessionManagementService sessions = new SessionManagementService(codes, configuration);
        Object service = mode.equals("account") ? accounts : codes;
        String first = mode.equals("account")
                ? accounts.authenticate("first", "wrong", "127.0.0.1").status().name()
                : codes.validateAccessCode("wrong", "127.0.0.1").name();
        assertThat(first).isEqualTo(mode.equals("account") ? "INVALID_CREDENTIALS" : "INVALID_CODE");
        time.set(time.get().plus(Duration.ofMinutes(110)));
        String fresh = mode.equals("account")
                ? accounts.authenticate("second", "wrong", "127.0.0.2").status().name()
                : codes.validateAccessCode("wrong", "127.0.0.2").name();
        assertThat(fresh).isEqualTo(first);
        time.set(time.get().plus(Duration.ofMinutes(11)));
        sessions.scheduledCleanup();
        assertThat(stateCount(mode, service)).isEqualTo(mode.equals("account") ? 2 : 1);
        String locked = mode.equals("account")
                ? accounts.authenticate("second", PASSWORD, "127.0.0.2").status().name()
                : codes.validateAccessCode(PASSWORD, "127.0.0.2").name();
        assertThat(locked).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @Test
    void sessionCapacityDoesNotEvictActiveSessionsAndReclaimsOnlyExpiredSessions() {
        AuthConfiguration configuration = configuration();
        configuration.setMaxActiveSessions(1);
        AccessCodeService service = new AccessCodeService(configuration);
        UserSession first = service.createSession(false, "127.0.0.1", "test-agent");
        assertThatThrownBy(() -> service.createSession(false, "127.0.0.2", "test-agent"))
                .isInstanceOf(AccessCodeService.SessionCapacityExceededException.class);
        assertThat(service.getActiveSessionCount()).isEqualTo(1);
        assertThat(service.isSessionValid(first.getSessionId())).isTrue();
        first.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
        UserSession second = service.createSession(false, "127.0.0.2", "test-agent");
        assertThat(service.getActiveSessionCount()).isEqualTo(1);
        assertThat(service.isSessionValid(first.getSessionId())).isFalse();
        assertThat(service.isSessionValid(second.getSessionId())).isTrue();
    }

    @Test
    void aSuccessfulAccountLoginCannotResetOtherAccountsSourceFailures() {
        AuthConfiguration configuration = configuration();
        configuration.setMaxAttempts(10);
        UserAccountService service = new UserAccountService(configuration, new ExecutionProperties());
        assertThat(service.authenticate("first", "wrong", "127.0.0.1").status())
                .isEqualTo(UserAccountService.Status.INVALID_CREDENTIALS);
        assertThat(service.authenticate("second", PASSWORD, "127.0.0.1").status())
                .isEqualTo(UserAccountService.Status.SUCCESS);
        assertThat(service.authenticate("first", "wrong", "127.0.0.1").status())
                .isEqualTo(UserAccountService.Status.INVALID_CREDENTIALS);
        assertThat(service.authenticate("second", PASSWORD, "127.0.0.1").status())
                .isEqualTo(UserAccountService.Status.TOO_MANY_ATTEMPTS);
    }

    @Test
    void emptyAccessCodesConsumeTheSameSourceAndGlobalFailureBudget() {
        AuthConfiguration configuration = configuration();
        AccessCodeService service = new AccessCodeService(configuration);
        assertThat(service.validateAccessCode("", "127.0.0.1").name()).isEqualTo("EMPTY_CODE");
        assertThat(service.validateAccessCode(null, "127.0.0.2").name()).isEqualTo("EMPTY_CODE");
        assertThat(service.validateAccessCode(PASSWORD, "127.0.0.3").name()).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    private int stateCount(String mode, Object service) {
        Map<?, ?> attempts = (Map<?, ?>) ReflectionTestUtils.getField(service, "attempts");
        Map<?, ?> sources = mode.equals("account")
                ? (Map<?, ?>) ReflectionTestUtils.getField(service, "sourceAttempts") : null;
        return attempts.size() + (sources == null ? 0 : sources.size());
    }

    @Test
    void configuredSchedulerActivelyExpiresBothModesAndSessionsWithoutAnotherRequest() throws Exception {
        AuthConfiguration configuration = configuration();
        configuration.setNormalSessionTimeout(Duration.ofSeconds(1));
        AtomicReference<Instant> time = new AtomicReference<>(Instant.now());
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        UserAccountService accounts = new UserAccountService(configuration, new ExecutionProperties(), clock);
        AccessCodeService codes = new AccessCodeService(configuration, accounts, clock);
        accounts.authenticate("first", "wrong", "127.0.0.1");
        codes.validateAccessCode("wrong", "127.0.0.1");
        codes.createSession(false, "127.0.0.1", "test-agent");
        assertThat(stateCount("account", accounts)).isEqualTo(2);
        assertThat(stateCount("access-code", codes)).isEqualTo(1);
        assertThat(codes.getActiveSessionCount()).isEqualTo(1);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("task03-scheduler-test",
                    Map.of("judge.auth.attempt-cleanup-interval-ms", "20")));
            context.registerBean(AuthConfiguration.class, () -> configuration);
            context.registerBean(UserAccountService.class, () -> accounts);
            context.registerBean(AccessCodeService.class, () -> codes);
            context.registerBean(SessionManagementService.class, () -> new SessionManagementService(codes, configuration));
            context.register(ExpiryScheduling.class);
            context.refresh();
            time.set(time.get().plus(Duration.ofHours(3)));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while ((stateCount("account", accounts) != 0 || stateCount("access-code", codes) != 0
                    || codes.getActiveSessionCount() != 0) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(stateCount("account", accounts)).isZero();
            assertThat(stateCount("access-code", codes)).isZero();
            assertThat(codes.getActiveSessionCount()).isZero();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableScheduling
    static class ExpiryScheduling {
    }

    private AuthConfiguration configuration() {
        AuthConfiguration configuration = new AuthConfiguration();
        configuration.setMaxAttempts(2);
        configuration.setMaxSourceAttempts(2);
        configuration.setMaxGlobalAccessCodeAttempts(2);
        configuration.setAccessCode(PASSWORD);
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        configuration.setAccounts(List.of(account("first", hash), account("second", hash)));
        return configuration;
    }

    private AuthConfiguration.AccountProperties account(String username, String hash) {
        AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
        account.setUserId("test-" + username);
        account.setUsername(username);
        account.setPasswordHash(hash);
        return account;
    }
}
