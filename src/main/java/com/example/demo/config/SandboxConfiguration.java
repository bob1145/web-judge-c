package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 沙箱配置类
 * 用于管理沙箱执行环境、安全策略等沙箱相关配置
 */
@Configuration
@ConfigurationProperties(prefix = "judge.sandbox")
@Data
public class SandboxConfiguration {
    
    /**
     * 是否启用沙箱
     */
    private boolean enabled = true;
    
    /**
     * 沙箱基础目录
     */
    private String baseDirectory = "/tmp/judge-sandbox";
    
    /**
     * 是否禁用网络访问
     */
    private boolean networkDisabled = true;
    
    /**
     * 最大进程数
     */
    private int maxProcesses = 10;
}