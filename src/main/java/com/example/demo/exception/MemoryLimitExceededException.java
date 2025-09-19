package com.example.demo.exception;

/**
 * 内存限制超出异常
 * 用于处理代码执行时内存使用超出限制的情况
 */
public class MemoryLimitExceededException extends ExecutionException {
    
    private final long currentUsage;
    private final long limit;
    
    public MemoryLimitExceededException(long currentUsage, long limit) {
        super(String.format("Memory limit exceeded: current usage %d bytes, limit %d bytes", 
              currentUsage, limit));
        this.currentUsage = currentUsage;
        this.limit = limit;
    }
    
    public MemoryLimitExceededException(long currentUsage, long limit, Throwable cause) {
        super(String.format("Memory limit exceeded: current usage %d bytes, limit %d bytes", 
              currentUsage, limit), cause);
        this.currentUsage = currentUsage;
        this.limit = limit;
    }
    
    public long getCurrentUsage() {
        return currentUsage;
    }
    
    public long getLimit() {
        return limit;
    }
}