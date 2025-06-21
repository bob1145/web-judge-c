package com.example.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String JUDGE_REQUEST_EXECUTOR = "judgeRequestExecutor";
    public static final String TEST_CASE_EXECUTOR = "testCaseExecutor";

    @Bean(name = JUDGE_REQUEST_EXECUTOR)
    public Executor judgeRequestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("JudgeReq-");
        executor.initialize();
        return executor;
    }

    @Bean(name = TEST_CASE_EXECUTOR)
    public ThreadPoolTaskExecutor testCaseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int coreCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        executor.setCorePoolSize(coreCount);
        executor.setMaxPoolSize(coreCount);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("TestCase-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
} 