package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 内存监控配置类
 * 用于管理内存限制、监控间隔等内存相关配置
 */
@Configuration
@ConfigurationProperties(prefix = "judge.memory")
@Data
public class MemoryConfiguration {
    
    /**
     * 默认内存限制 (字节)
     */
    private long defaultLimit = 256 * 1024 * 1024; // 256MB
    
    /**
     * 最大内存限制 (字节)
     */
    private long maxLimit = 1024 * 1024 * 1024; // 1GB
    
    /**
     * 内存检查间隔 (毫秒)
     */
    private int checkInterval = 100;
}