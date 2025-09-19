package com.example.demo.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 用户会话模型
 * 用于管理用户认证会话信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession {
    /**
     * 会话ID，唯一标识一个用户会话
     */
    private String sessionId;
    
    /**
     * 会话创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 会话过期时间
     */
    private LocalDateTime expiresAt;
    
    /**
     * 是否为"记住我"会话（30天有效期）
     */
    private boolean rememberMe;
    
    /**
     * 用户IP地址（用于安全验证）
     */
    private String ipAddress;
    
    /**
     * 用户代理信息（用于安全验证）
     */
    private String userAgent;
    
    /**
     * 检查会话是否已过期
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    /**
     * 检查会话是否有效（未过期）
     */
    public boolean isValid() {
        return !isExpired();
    }
}