package com.example.demo.service;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.UserAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class UserAccountService {

    private final AuthConfiguration authConfiguration;
    private final ExecutionProperties executionProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();
    private final Map<String, AttemptRecord> sourceAttempts = new ConcurrentHashMap<>();
    private final Object attemptCapacityLock = new Object();
    private final Clock clock;

    @Autowired
    public UserAccountService(AuthConfiguration authConfiguration, ExecutionProperties executionProperties) {
        this(authConfiguration, executionProperties, Clock.systemDefaultZone());
    }

    public UserAccountService(AuthConfiguration authConfiguration, ExecutionProperties executionProperties, Clock clock) {
        this.authConfiguration = authConfiguration;
        this.executionProperties = executionProperties;
        this.clock = clock;
    }

    public boolean requiresAccountAuthentication() {
        return authConfiguration.requiresAccountAuthentication(executionProperties.getProfile());
    }

    public LoginResult authenticate(String username, String password, String ipAddress) {
        String normalized = normalizeUsername(username);
        String key = fingerprint(normalized == null ? "<blank>" : normalized);
        String sourceKey = fingerprint(ipAddress == null ? "<unknown>" : ipAddress);
        if (!reserveAttemptStates(key, sourceKey)) {
            return rateLimited();
        }
        AtomicReference<LoginResult> result = new AtomicReference<>();
        sourceAttempts.computeIfPresent(sourceKey, (storedSource, sourceRecord) -> {
            Instant now = clock.instant();
            AttemptRecord source = resetExpired(sourceRecord, authConfiguration.getSourceAttemptWindow(), now);
            if (source.failures() >= authConfiguration.getMaxSourceAttempts()) {
                result.set(rateLimited());
                return source;
            }
            attempts.computeIfPresent(key, (storedKey, accountRecord) -> {
                AttemptRecord record = resetExpired(accountRecord,
                        Duration.ofHours(authConfiguration.getAttemptResetHours()), now);
                if (record.failures() >= authConfiguration.getMaxAttempts()) {
                    result.set(rateLimited());
                    return record;
                }
                if (isBlank(username) || isBlank(password)) {
                    result.set(LoginResult.failure(Status.INVALID_CREDENTIALS, "Invalid account credentials"));
                    return recordFailedAttempt(record);
                }
                Optional<UserAccount> account = findByUsername(username);
                if (account.isEmpty() || !account.get().isEnabled()
                        || !passwordEncoder.matches(password, account.get().getPasswordHash())) {
                    log.warn("Account authentication failed for user={}, ip={}", safeUsername(username), ipAddress);
                    result.set(LoginResult.failure(Status.INVALID_CREDENTIALS, "Invalid account credentials"));
                    return recordFailedAttempt(record);
                }
                result.set(LoginResult.success(account.get()));
                return new AttemptRecord(0, clock.instant());
            });
            if (result.get() == null) {
                result.set(rateLimited());
                return source;
            }
            if (!result.get().success()) {
                return recordFailedAttempt(source);
            }
            return source.failures() == 0 ? new AttemptRecord(0, clock.instant()) : source;
        });
        return result.get() == null ? rateLimited() : result.get();
    }

    public Optional<UserAccount> findByUsername(String username) {
        String normalized = normalizeUsername(username);
        if (normalized == null) {
            return Optional.empty();
        }
        return authConfiguration.getAccounts().stream()
                .map(UserAccount::from)
                .filter(account -> normalized.equals(normalizeUsername(account.getUsername())))
                .findFirst();
    }

    public void clearRateLimits() {
        synchronized (attemptCapacityLock) {
            attempts.clear();
            sourceAttempts.clear();
        }
    }

    public void cleanupExpiredAttempts() {
        Instant cutoff = clock.instant().minus(authConfiguration.getAttemptStateTtl());
        attempts.forEach((key, record) -> attempts.computeIfPresent(key,
                (storedKey, current) -> current.lastAttemptTime().isAfter(cutoff) ? current : null));
        sourceAttempts.forEach((key, record) -> sourceAttempts.computeIfPresent(key,
                (storedKey, current) -> current.lastAttemptTime().isAfter(cutoff) ? current : null));
    }

    private boolean reserveAttemptStates(String key, String sourceKey) {
        synchronized (attemptCapacityLock) {
            int required = (attempts.containsKey(key) ? 0 : 1) + (sourceAttempts.containsKey(sourceKey) ? 0 : 1);
            if ((long) attempts.size() + sourceAttempts.size() + required > authConfiguration.getMaxAttemptStates()) {
                return false;
            }
            Instant now = clock.instant();
            attempts.putIfAbsent(key, new AttemptRecord(0, now));
            sourceAttempts.putIfAbsent(sourceKey, new AttemptRecord(0, now));
            return true;
        }
    }

    private AttemptRecord resetExpired(AttemptRecord record, Duration window, Instant now) {
        return record.lastAttemptTime().isAfter(now.minus(window)) ? record : new AttemptRecord(0, now);
    }

    private LoginResult rateLimited() {
        return LoginResult.failure(Status.TOO_MANY_ATTEMPTS, "Too many authentication attempts; please retry later");
    }

    private AttemptRecord recordFailedAttempt(AttemptRecord previous) {
        return new AttemptRecord(previous.failures() + 1, clock.instant());
    }

    private record AttemptRecord(int failures, Instant lastAttemptTime) {
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeUsername(String username) {
        if (isBlank(username)) {
            return null;
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String safeUsername(String username) {
        String normalized = normalizeUsername(username);
        return normalized == null ? "<blank>" : normalized;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record LoginResult(Status status, UserAccount account, String message) {

        public static LoginResult success(UserAccount account) {
            return new LoginResult(Status.SUCCESS, account, "Authentication successful");
        }

        public static LoginResult failure(Status status, String message) {
            return new LoginResult(status, null, message);
        }

        public boolean success() {
            return status == Status.SUCCESS;
        }
    }

    public enum Status {
        SUCCESS,
        INVALID_CREDENTIALS,
        TOO_MANY_ATTEMPTS
    }
}
