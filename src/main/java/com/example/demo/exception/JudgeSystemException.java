package com.example.demo.exception;

/**
 * 判题系统基础异常类
 * 所有判题系统相关异常的基类
 */
public class JudgeSystemException extends RuntimeException {
    
    public JudgeSystemException(String message) {
        super(message);
    }
    
    public JudgeSystemException(String message, Throwable cause) {
        super(message, cause);
    }
}