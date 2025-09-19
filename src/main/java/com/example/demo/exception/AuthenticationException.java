package com.example.demo.exception;

/**
 * 认证相关异常
 * 用于处理访问控制、会话管理等认证相关的异常情况
 */
public class AuthenticationException extends JudgeSystemException {
    
    public AuthenticationException(String message) {
        super(message);
    }
    
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}