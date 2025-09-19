package com.example.demo.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 错误响应DTO
 * 用于统一错误信息的返回格式
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ErrorResponse {
    
    /**
     * 错误代码
     */
    private String errorCode;
    
    /**
     * 错误消息
     */
    private String message;
    
    /**
     * 错误详情
     */
    private String details;
    
    /**
     * 时间戳
     */
    private LocalDateTime timestamp;
    
    /**
     * 请求路径
     */
    private String path;
    
    public ErrorResponse(String errorCode, String message, String path) {
        this.errorCode = errorCode;
        this.message = message;
        this.path = path;
        this.timestamp = LocalDateTime.now();
    }
    
    public ErrorResponse(String errorCode, String message, String details, String path) {
        this.errorCode = errorCode;
        this.message = message;
        this.details = details;
        this.path = path;
        this.timestamp = LocalDateTime.now();
    }
}