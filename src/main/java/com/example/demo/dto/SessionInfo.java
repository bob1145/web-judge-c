package com.example.demo.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 会话信息DTO
 * 用于返回会话状态信息给客户端
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionInfo {
    /**
     * 会话是否有效
     */
    private boolean valid;
    
    /**
     * 会话ID
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
     * 是否为记住我会话
     */
    private boolean rememberMe;
    
    /**
     * 剩余有效时间（分钟）
     */
    private long remainingMinutes;
    
    /**
     * 创建无效会话信息
     */
    public static SessionInfo invalid() {
        return SessionInfo.builder()
                .valid(false)
                .build();
    }
}