package com.example.demo.exception;

/**
 * 执行相关异常
 * 用于处理代码执行过程中的异常情况
 */
public class ExecutionException extends JudgeSystemException {
    
    public ExecutionException(String message) {
        super(message);
    }
    
    public ExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}