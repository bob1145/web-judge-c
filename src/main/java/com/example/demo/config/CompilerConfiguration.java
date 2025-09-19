package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 编译器配置类
 * 用于管理编译器选项、标准、优化级别等编译相关配置
 */
@Configuration
@ConfigurationProperties(prefix = "judge.compiler")
@Data
public class CompilerConfiguration {
    
    /**
     * 默认C++标准
     */
    private String defaultStandard = "cpp17";
    
    /**
     * 默认优化级别
     */
    private String defaultOptimization = "O2";
    
    /**
     * 编译超时时间
     */
    private Duration timeout = Duration.ofSeconds(60);
}