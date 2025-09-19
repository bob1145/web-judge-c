package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 认证相关配置类
 * 用于管理访问控制、会话管理等认证相关配置
 */
@Configuration
@ConfigurationProperties(prefix = "judge.auth")
@Data
public class AuthConfiguration {
    
    /**
     * 访问校验码
     */
    private String accessCode = "secure-access-code-2024";
    
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
}