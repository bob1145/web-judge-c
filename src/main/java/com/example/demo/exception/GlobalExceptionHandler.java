package com.example.demo.exception;

import com.example.demo.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

/**
 * 全局异常处理器
 * 统一处理系统中的各种异常，提供一致的错误响应格式
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    /**
     * 处理认证相关异常
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(
            AuthenticationException e, WebRequest request) {
        log.warn("Authentication exception: {}", e.getMessage());
        
        ErrorResponse errorResponse = new ErrorResponse(
            "AUTH_ERROR",
            "认证失败",
            e.getMessage(),
            request.getDescription(false).replace("uri=", "")
        );
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }
    
    /**
     * 处理内存限制超出异常
     */
    @ExceptionHandler(MemoryLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleMemoryLimitExceeded(
            MemoryLimitExceededException e, WebRequest request) {
        log.warn("Memory limit exceeded: current={}, limit={}", 
                e.getCurrentUsage(), e.getLimit());
        
        ErrorResponse errorResponse = new ErrorResponse(
            "MEMORY_LIMIT_EXCEEDED",
            "内存使用超出限制",
            String.format("当前使用: %d bytes, 限制: %d bytes", 
                         e.getCurrentUsage(), e.getLimit()),
            request.getDescription(false).replace("uri=", "")
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    /**
     * 处理安全违规异常
     */
    @ExceptionHandler(SecurityViolationException.class)
    public ResponseEntity<ErrorResponse> handleSecurityViolation(
            SecurityViolationException e, WebRequest request) {
        log.error("Security violation detected: type={}, details={}", 
                 e.getViolationType(), e.getDetails());
        
        ErrorResponse errorResponse = new ErrorResponse(
            "SECURITY_VIOLATION",
            "检测到安全违规行为",
            String.format("违规类型: %s, 详情: %s", 
                         e.getViolationType(), e.getDetails()),
            request.getDescription(false).replace("uri=", "")
        );
        
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }
    
    /**
     * 处理编译异常
     */
    @ExceptionHandler(CompilationException.class)
    public ResponseEntity<ErrorResponse> handleCompilationException(
            CompilationException e, WebRequest request) {
        log.warn("Compilation exception: {}", e.getMessage());
        
        ErrorResponse errorResponse = new ErrorResponse(
            "COMPILATION_ERROR",
            "编译失败",
            e.getMessage(),
            request.getDescription(false).replace("uri=", "")
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    /**
     * 处理执行异常
     */
    @ExceptionHandler(ExecutionException.class)
    public ResponseEntity<ErrorResponse> handleExecutionException(
            ExecutionException e, WebRequest request) {
        log.warn("Execution exception: {}", e.getMessage());
        
        ErrorResponse errorResponse = new ErrorResponse(
            "EXECUTION_ERROR",
            "执行失败",
            e.getMessage(),
            request.getDescription(false).replace("uri=", "")
        );
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }
    
    /**
     * 处理判题系统基础异常
     */
    @ExceptionHandler(JudgeSystemException.class)
    public ResponseEntity<ErrorResponse> handleJudgeSystemException(
            JudgeSystemException e, WebRequest request) {
        log.error("Judge system exception: {}", e.getMessage(), e);
        
        ErrorResponse errorResponse = new ErrorResponse(
            "SYSTEM_ERROR",
            "系统错误",
            e.getMessage(),
            request.getDescription(false).replace("uri=", "")
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
    
    /**
     * 处理其他未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception e, WebRequest request) {
        log.error("Unexpected exception: {}", e.getMessage(), e);
        
        ErrorResponse errorResponse = new ErrorResponse(
            "INTERNAL_ERROR",
            "内部服务器错误",
            "系统发生未知错误，请联系管理员",
            request.getDescription(false).replace("uri=", "")
        );
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}