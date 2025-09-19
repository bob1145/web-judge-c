package com.example.demo.exception;

/**
 * 编译相关异常
 * 用于处理代码编译过程中的异常情况
 */
public class CompilationException extends JudgeSystemException {
    
    public CompilationException(String message) {
        super(message);
    }
    
    public CompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}