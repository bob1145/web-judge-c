package com.example.demo.model;

/**
 * 认证状态枚举
 * 定义各种认证状态
 */
public enum AuthStatus {
    /**
     * 认证成功
     */
    SUCCESS("认证成功"),
    
    /**
     * 校验码错误
     */
    INVALID_CODE("校验码错误"),
    
    /**
     * 校验码为空
     */
    EMPTY_CODE("请输入校验码"),
    
    /**
     * 会话已过期
     */
    SESSION_EXPIRED("会话已过期，请重新认证"),
    
    /**
     * 会话无效
     */
    INVALID_SESSION("无效的会话"),
    
    /**
     * 尝试次数过多
     */
    TOO_MANY_ATTEMPTS("尝试次数过多，请稍后再试"),
    
    /**
     * 未认证
     */
    UNAUTHENTICATED("未认证，请先进行身份验证");
    
    private final String message;
    
    AuthStatus(String message) {
        this.message = message;
    }
    
    public String getMessage() {
        return message;
    }
}