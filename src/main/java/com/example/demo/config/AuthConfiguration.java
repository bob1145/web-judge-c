package com.example.demo.config;

import lombok.Data;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 认证相关配置类
 * 用于管理访问控制、会话管理等认证相关配置
 */
@Configuration
@ConfigurationProperties(prefix = "judge.auth")
@Data
public class AuthConfiguration {

    private static final Set<String> LEGACY_ACCESS_CODES = Set.of("123", "secure-access-code-2024");
    
    /**
     * 访问校验码
     */
    private String accessCode;

    private String defaultAccessCode;

    private boolean accountAuthEnabled = false;

    private SessionEnvironmentPolicy sessionEnvironmentPolicy = SessionEnvironmentPolicy.AUDIT;

    private List<AccountProperties> accounts = new ArrayList<>();
    
    /**
     * 记住我会话超时时间（30天）
     */
    private Duration sessionTimeout = Duration.ofDays(30);
    
    /**
     * 普通会话超时时间（24小时）
     */
    private Duration normalSessionTimeout = Duration.ofHours(24);
    
    /**
     * 最大尝试次数
     */
    private int maxAttempts = 5;
    
    /**
     * 尝试次数重置时间（小时）
     */
    private int attemptResetHours = 1;

    private int maxSourceAttempts = 30;
    private Duration sourceAttemptWindow = Duration.ofMinutes(10);
    private int maxGlobalAccessCodeAttempts = 300;
    private Duration globalAccessCodeAttemptWindow = Duration.ofMinutes(10);
    private int maxAttemptStates = 10_000;
    private Duration attemptStateTtl = Duration.ofHours(2);
    private long attemptCleanupIntervalMs = 60_000;
    private int maxActiveSessions = 10_000;
    private List<String> trustedProxyCidrs = new ArrayList<>();
    private String forwardedClientHeader = "X-Forwarded-For";
    private int maxForwardedHeaderBytes = 4_096;
    private int maxForwardedHops = 16;

    @PostConstruct
    public void validateAuthenticationLimits() {
        if (maxAttempts < 1 || attemptResetHours < 1 || maxSourceAttempts < 1
                || maxGlobalAccessCodeAttempts < 1 || maxAttemptStates < 1
                || maxActiveSessions < 1 || attemptCleanupIntervalMs < 1
                || sourceAttemptWindow == null || sourceAttemptWindow.isZero() || sourceAttemptWindow.isNegative()
                || globalAccessCodeAttemptWindow == null || globalAccessCodeAttemptWindow.isZero()
                || globalAccessCodeAttemptWindow.isNegative() || attemptStateTtl == null
                || attemptStateTtl.compareTo(Duration.ofHours(attemptResetHours)) < 0
                || attemptStateTtl.compareTo(sourceAttemptWindow) < 0
                || attemptStateTtl.compareTo(globalAccessCodeAttemptWindow) < 0
                || maxForwardedHeaderBytes < 1 || maxForwardedHops < 1) {
            throw new IllegalArgumentException("Authentication limits must be positive and state TTL must cover every failure window");
        }
    }

    public boolean requiresAccountAuthentication(String profile) {
        return accountAuthEnabled || profile == null || !"trusted-local".equalsIgnoreCase(profile.trim());
    }

    public boolean isUsableAccessCode() {
        if (accessCode == null || accessCode.isBlank()) {
            return false;
        }
        String normalized = accessCode.trim();
        return !LEGACY_ACCESS_CODES.contains(normalized)
                && (defaultAccessCode == null || !normalized.equals(defaultAccessCode.trim()));
    }

    public enum SessionEnvironmentPolicy {
        AUDIT,
        STRICT
    }

    @Data
    public static class AccountProperties {
        private String userId;
        private String username;
        private String passwordHash;
        private boolean admin;
        private boolean enabled = true;
    }
}
