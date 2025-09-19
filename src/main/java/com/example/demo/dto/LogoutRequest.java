package com.example.demo.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 登出请求DTO
 * 用于处理用户登出请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogoutRequest {
    /**
     * 会话ID
     */
    private String sessionId;
}