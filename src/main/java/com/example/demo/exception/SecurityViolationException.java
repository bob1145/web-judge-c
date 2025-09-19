package com.example.demo.exception;

/**
 * 安全违规异常
 * 用于处理沙箱执行中的安全违规情况
 */
public class SecurityViolationException extends ExecutionException {
    
    private final String violationType;
    private final String details;
    
    public SecurityViolationException(String violationType, String details) {
        super(String.format("Security violation detected: %s - %s", violationType, details));
        this.violationType = violationType;
        this.details = details;
    }
    
    public SecurityViolationException(String violationType, String details, Throwable cause) {
        super(String.format("Security violation detected: %s - %s", violationType, details), cause);
        this.violationType = violationType;
        this.details = details;
    }
    
    public String getViolationType() {
        return violationType;
    }
    
    public String getDetails() {
        return details;
    }
}