package com.example.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.context.annotation.Bean;
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
    public ThreadPoolTaskExecutor testCaseExecutor(ExecutionProperties executionProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int caseConcurrency = Math.max(1, executionProperties.getMaxConcurrentCasesPerTask());
        int queueCapacity = Math.max(1, executionProperties.getBatchSize());
        executor.setCorePoolSize(caseConcurrency);
        executor.setMaxPoolSize(caseConcurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("TestCase-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
