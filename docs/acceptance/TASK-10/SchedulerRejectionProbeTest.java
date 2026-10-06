package com.example.demo;

import com.example.demo.config.AsyncConfig;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.JudgeScheduler;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerRejectionProbeTest {

    @TempDir
    Path tempDir;

    @Test
    void observesKnownQueuedHandoffDefectNotProductAcceptance() throws Exception {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setMaxConcurrentTasks(1);
        properties.setTaskQueueCapacity(2);
        properties.setMaxTaskRuntime(Duration.ofSeconds(30));
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        FileTaskStore store = new FileTaskStore(mapper, tempDir);
        for (String judgeId : new String[]{"probe-first", "probe-second"}) {
            store.create(JudgeTask.builder()
                    .judgeId(judgeId)
                    .status(JudgeStatus.CREATED)
                    .requestedCases(1)
                    .mode("scheduler-rejection-diagnostic")
                    .workDir(store.taskDirectory(judgeId).toString())
                    .build());
        }
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new AsyncConfig().judgeRequestExecutor(properties);
        JudgeScheduler scheduler = new JudgeScheduler(properties, store, executor);
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger secondExecutions = new AtomicInteger();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
            scheduler.enqueue("probe-first", context -> {
                firstRunning.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Diagnostic release timed out");
                }
            });
            assertThat(firstRunning.await(5, TimeUnit.SECONDS)).isTrue();
            scheduler.enqueue("probe-second", context -> secondExecutions.incrementAndGet());
            assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
            assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);
            assertThat(store.find("probe-second").orElseThrow().getStatus()).isEqualTo(JudgeStatus.QUEUED);
            releaseFirst.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            JudgeTask observed;
            do {
                observed = store.find("probe-second").orElseThrow();
                if (observed.getStatus().isTerminal()) {
                    break;
                }
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertThat(observed.getStatus()).isEqualTo(JudgeStatus.SYSTEM_ERROR);
            assertThat(observed.getMessage()).isEqualTo("Judge executor rejected the task");
            long workerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executor.getActiveCount() != 0 && System.nanoTime() < workerDeadline) {
                Thread.sleep(10);
            }
            assertThat(executor.getActiveCount()).isZero();
            assertThat(secondExecutions.get()).isZero();
            assertThat(store.find("probe-first").orElseThrow().getStatus()).isEqualTo(JudgeStatus.COMPLETED);
            Files.writeString(Path.of("target/scheduler-rejection-observation.json"), mapper.writeValueAsString(Map.of(
                    "knownDefectObserved", true,
                    "productAcceptance", false,
                    "r07CausalityProven", false,
                    "secondStatus", observed.getStatus().name(),
                    "secondMessage", observed.getMessage(),
                    "secondExecutions", secondExecutions.get()
            )));
        } finally {
            releaseFirst.countDown();
            scheduler.shutdown();
            executor.shutdown();
            assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
