package com.example.demo.service;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.UserAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserAccountService {

    private static final String TRUSTED_LOCAL = "trusted-local";

    private final AuthConfiguration authConfiguration;
    private final ExecutionProperties executionProperties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Map<String, Integer> attemptCounts = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastAttemptTimes = new ConcurrentHashMap<>();

    public boolean requiresAccountAuthentication() {
        return authConfiguration.isAccountAuthEnabled() || !TRUSTED_LOCAL.equals(profile());
    }

    public LoginResult authenticate(String username, String password, String ipAddress) {
        String key = attemptKey(username, ipAddress);
        if (isExceeded(key)) {
            log.warn("Account authentication locked out for user={}, ip={}", safeUsername(username), ipAddress);
            return LoginResult.failure(Status.TOO_MANY_ATTEMPTS, "Too many authentication attempts; please retry later");
        }
        if (isBlank(username) || isBlank(password)) {
            recordFailedAttempt(key);
            return LoginResult.failure(Status.INVALID_CREDENTIALS, "Production account credentials are required");
        }

        Optional<UserAccount> account = findByUsername(username);
        if (account.isEmpty() || !account.get().isEnabled()
                || !passwordEncoder.matches(password, account.get().getPasswordHash())) {
            recordFailedAttempt(key);
            log.warn("Account authentication failed for user={}, ip={}", safeUsername(username), ipAddress);
            return LoginResult.failure(Status.INVALID_CREDENTIALS, "Invalid account credentials");
        }

        clearAttemptRecord(key);
        return LoginResult.success(account.get());
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
        attemptCounts.clear();
        lastAttemptTimes.clear();
    }

    private boolean isExceeded(String key) {
        Integer attempts = attemptCounts.get(key);
        if (attempts == null) {
            return false;
        }
        LocalDateTime lastAttempt = lastAttemptTimes.get(key);
        if (lastAttempt != null
                && lastAttempt.isBefore(LocalDateTime.now().minusHours(authConfiguration.getAttemptResetHours()))) {
            clearAttemptRecord(key);
            return false;
        }
        return attempts >= authConfiguration.getMaxAttempts();
    }

    private void recordFailedAttempt(String key) {
        attemptCounts.merge(key, 1, Integer::sum);
        lastAttemptTimes.put(key, LocalDateTime.now());
    }

    private void clearAttemptRecord(String key) {
        attemptCounts.remove(key);
        lastAttemptTimes.remove(key);
    }

    private String attemptKey(String username, String ipAddress) {
        String normalized = normalizeUsername(username);
        return (normalized == null ? "<blank>" : normalized)
                + "@"
                + (ipAddress == null ? "<unknown>" : ipAddress);
    }

    private String profile() {
        return executionProperties.getProfile() == null
                ? ""
                : executionProperties.getProfile().trim().toLowerCase(Locale.ROOT);
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
