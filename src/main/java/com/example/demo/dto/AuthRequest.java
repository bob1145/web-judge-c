package com.example.demo.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 认证请求DTO
 * 用于接收用户的校验码认证请求
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthRequest {
    /**
     * 用户输入的校验码
     */
    private String accessCode;
    
    /**
     * 是否记住用户30天
     */
    private boolean rememberMe;
}