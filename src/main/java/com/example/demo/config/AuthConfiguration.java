package com.example.demo.config;

import lombok.Data;
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

    @Data
    public static class AccountProperties {
        private String userId;
        private String username;
        private String passwordHash;
        private boolean admin;
        private boolean enabled = true;
    }
}
