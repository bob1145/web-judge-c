package com.example.demo.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 认证响应DTO
 * 用于返回认证结果给客户端
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    /**
     * 认证是否成功
     */
    private boolean success;
    
    /**
     * 响应消息
     */
    private String message;
    
    /**
     * 会话ID（认证成功时返回）
     */
    private String sessionId;
    
    /**
     * 重定向URL（认证成功时返回）
     */
    private String redirectUrl;
    
    /**
     * 创建成功响应
     */
    public static AuthResponse success(String sessionId, String redirectUrl) {
        return new AuthResponse(true, "认证成功", sessionId, redirectUrl);
    }
    
    /**
     * 创建失败响应
     */
    public static AuthResponse failure(String message) {
        return new AuthResponse(false, message, null, null);
    }
}