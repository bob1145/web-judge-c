package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.controller.JudgeController;
import com.example.demo.dto.CancelJudgeResponse;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.model.UserSession;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.JudgeFileService;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.JudgeService;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.ResolvedTaskPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JudgeSchedulerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private final List<ExecutorService> executors = new ArrayList<>();
    private final List<JudgeScheduler> schedulers = new ArrayList<>();

    @TempDir
    Path tempDir;

    @AfterEach
    void stopSchedulersAndExecutors() {
        for (JudgeScheduler scheduler : schedulers) {
            scheduler.shutdown();
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    @Test
    void maxConcurrentTasksOneKeepsSecondTaskQueuedUntilFirstFinishes() throws Exception {
        FileTaskStore store = store();
        createTask(store, "first", 12);
        createTask(store, "second", 12);
        JudgeScheduler scheduler = scheduler(properties(1, 2, Duration.ofSeconds(5)), store, executor(2));
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondRunning = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peakRunning = new AtomicInteger();

        scheduler.enqueue("first", context -> {
            int active = running.incrementAndGet();
            peakRunning.accumulateAndGet(active, Math::max);
            firstRunning.countDown();
            assertThat(releaseFirst.await(2, TimeUnit.SECONDS)).isTrue();
            running.decrementAndGet();
            store.updateStatus("first", JudgeStatus.COMPLETED, "first finished");
        });
        scheduler.enqueue("second", context -> {
            int active = running.incrementAndGet();
            peakRunning.accumulateAndGet(active, Math::max);
            secondRunning.countDown();
            running.decrementAndGet();
            store.updateStatus("second", JudgeStatus.COMPLETED, "second finished");
        });

        assertThat(firstRunning.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(store.find("second")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.QUEUED);
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
        assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);

        releaseFirst.countDown();

        assertThat(secondRunning.await(2, TimeUnit.SECONDS)).isTrue();
        awaitStatus(store, "second", JudgeStatus.COMPLETED);
        awaitIdle(scheduler);
        assertThat(peakRunning.get()).isEqualTo(1);
        assertThat(scheduler.snapshot().peakRunningCount()).isEqualTo(1);
        assertThat(scheduler.snapshot().peakQueueSize()).isEqualTo(1);
    }

    @Test
    void queueFullRejectsWithCapacityAndRetryHintWithoutRunningInCallerThread() throws Exception {
        FileTaskStore store = store();
        createTask(store, "running", 12);
        createTask(store, "queued", 12);
        createTask(store, "overflow", 12);
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofSeconds(5)), store, executor(1));
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch releaseRunning = new CountDownLatch(1);
        AtomicInteger overflowExecutions = new AtomicInteger();

        scheduler.enqueue("running", context -> {
            runningStarted.countDown();
            assertThat(releaseRunning.await(2, TimeUnit.SECONDS)).isTrue();
            store.updateStatus("running", JudgeStatus.COMPLETED, "running finished");
        });
        assertThat(runningStarted.await(2, TimeUnit.SECONDS)).isTrue();
        scheduler.enqueue("queued", context -> store.updateStatus("queued", JudgeStatus.COMPLETED, "queued finished"));

        assertThatThrownBy(() -> scheduler.enqueue("overflow", context -> overflowExecutions.incrementAndGet()))
                .isInstanceOf(JudgeScheduler.QueueFullException.class)
                .satisfies(error -> {
                    JudgeScheduler.QueueFullException queueFull = (JudgeScheduler.QueueFullException) error;
                    assertThat(queueFull.getQueueCapacity()).isEqualTo(1);
                    assertThat(queueFull.getCurrentQueueSize()).isEqualTo(1);
                    assertThat(queueFull.getMessage()).contains("queue is full").contains("retry later");
                });

        assertThat(overflowExecutions.get()).isZero();
        assertThat(store.find("overflow")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.CREATED);
        releaseRunning.countDown();
        awaitStatus(store, "queued", JudgeStatus.COMPLETED);
        awaitIdle(scheduler);
    }

    @Test
    void cancelQueuedTaskPreventsAnyCaseExecution() throws Exception {
        FileTaskStore store = store();
        createTask(store, "running", 12);
        createTask(store, "queued", 12);
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofSeconds(5)), store, executor(2));
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch releaseRunning = new CountDownLatch(1);
        AtomicInteger queuedExecutions = new AtomicInteger();

        scheduler.enqueue("running", context -> {
            runningStarted.countDown();
            assertThat(releaseRunning.await(2, TimeUnit.SECONDS)).isTrue();
            store.updateStatus("running", JudgeStatus.COMPLETED, "running finished");
        });
        assertThat(runningStarted.await(2, TimeUnit.SECONDS)).isTrue();
        scheduler.enqueue("queued", context -> queuedExecutions.incrementAndGet());

        CancelJudgeResponse response = scheduler.cancel("queued");

        assertThat(response.accepted()).isTrue();
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.queued()).isTrue();
        assertThat(store.find("queued")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.CANCELLED);

        releaseRunning.countDown();
        awaitStatus(store, "running", JudgeStatus.COMPLETED);
        awaitIdle(scheduler);

        assertThat(queuedExecutions.get()).isZero();
    }

    @Test
    void cancelRunningTaskSignalsTokenWithinTwoSecondsAndPersistsCancelledStatus() throws Exception {
        FileTaskStore store = store();
        createTask(store, "running", 12);
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofSeconds(5)), store, executor(1));
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);

        scheduler.enqueue("running", context -> {
            runningStarted.countDown();
            while (!context.cancellationToken().isCancellationRequested()) {
                Thread.sleep(10);
            }
            cancellationObserved.countDown();
        });

        assertThat(runningStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CancelJudgeResponse response = scheduler.cancel("running");

        assertThat(response.accepted()).isTrue();
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.running()).isTrue();
        assertThat(cancellationObserved.await(2, TimeUnit.SECONDS)).isTrue();
        awaitStatus(store, "running", JudgeStatus.CANCELLED);
        awaitIdle(scheduler);
    }

    @Test
    void budgetExceededCancelsRunningTaskAndPreservesCompletedCountInSummary() throws Exception {
        FileTaskStore store = store();
        createTask(store, "budget", 100, Duration.ofMillis(120));
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofMillis(120)), store, executor(1));
        CountDownLatch budgetObserved = new CountDownLatch(1);

        scheduler.enqueue("budget", context -> {
            while (!context.cancellationToken().isCancellationRequested()) {
                context.recordCompletedCase();
                Thread.sleep(20);
            }
            if (context.cancellationToken().isBudgetExceeded()) {
                budgetObserved.countDown();
            }
        });

        assertThat(budgetObserved.await(2, TimeUnit.SECONDS)).isTrue();
        awaitStatus(store, "budget", JudgeStatus.BUDGET_EXCEEDED);
        JudgeProgress summary = awaitSummary(store, "budget");
        assertThat(summary.getStatus()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(summary.getMessage()).contains("runtime budget");
        assertThat(summary.getSummary().getCompletedCases()).isGreaterThan(0);
        assertThat(summary.getSummary().getTotalCases()).isEqualTo(100);
        assertThat(summary.getSummary().getStoppedReason()).contains("runtime budget");
        awaitIdle(scheduler);
    }

    @Test
    void concurrentSubmissionOfTwentyTasksDoesNotLoseDuplicateOrDeadlockTasks() throws Exception {
        FileTaskStore store = store();
        JudgeScheduler scheduler = scheduler(properties(3, 20, Duration.ofSeconds(5)), store, executor(3));
        ExecutorService submitter = executor(8);
        CountDownLatch allDone = new CountDownLatch(20);
        Map<String, AtomicInteger> startsByTask = new ConcurrentHashMap<>();

        for (int i = 0; i < 20; i++) {
            String judgeId = "task-" + i;
            createTask(store, judgeId, 12);
            startsByTask.put(judgeId, new AtomicInteger());
            submitter.submit(() -> scheduler.enqueue(judgeId, context -> {
                startsByTask.get(judgeId).incrementAndGet();
                Thread.sleep(20);
                store.updateStatus(judgeId, JudgeStatus.COMPLETED, "done");
                allDone.countDown();
            }));
        }

        assertThat(allDone.await(5, TimeUnit.SECONDS)).isTrue();
        awaitIdle(scheduler);
        for (int i = 0; i < 20; i++) {
            String judgeId = "task-" + i;
            assertThat(startsByTask.get(judgeId).get()).isEqualTo(1);
            assertThat(store.find(judgeId)).isPresent().get()
                    .extracting(JudgeTask::getStatus)
                    .isEqualTo(JudgeStatus.COMPLETED);
        }
        assertThat(scheduler.snapshot().peakRunningCount()).isLessThanOrEqualTo(3);
    }

    @Test
    void cancelEndpointReturnsStructuredCancellationResponse() throws Exception {
        JudgeService judgeService = mock(JudgeService.class);
        UserSession session = controllerSession();
        when(judgeService.canAccessJudgeTask("abc", session)).thenReturn(true);
        when(judgeService.cancelJudgeTask("abc"))
                .thenReturn(new CancelJudgeResponse("abc", true, "CANCELLED", "Cancellation requested", true, false, 7));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new JudgeController(
                judgeService,
                mock(JudgeFileService.class),
                accessCodeService(session)
        )).build();

        mockMvc.perform(post("/judge/cancel/{judgeId}", "abc")
                        .header("X-Session-ID", session.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judgeId").value("abc"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.completedCases").value(7));
    }

    @Test
    void startEndpointReturnsTooManyRequestsWhenSchedulerQueueIsFull() throws Exception {
        JudgeService judgeService = mock(JudgeService.class);
        UserSession session = controllerSession();
        when(judgeService.canAccessJudgeTask("full", session)).thenReturn(true);
        doThrow(new JudgeScheduler.QueueFullException(3, 3))
                .when(judgeService).startJudgeTask("full");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new JudgeController(
                judgeService,
                mock(JudgeFileService.class),
                accessCodeService(session)
        )).build();

        mockMvc.perform(post("/judge/start/{judgeId}", "full")
                        .header("X-Session-ID", session.getSessionId()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("JUDGE_QUEUE_FULL"))
                .andExpect(jsonPath("$.queueCapacity").value(3))
                .andExpect(jsonPath("$.currentQueueSize").value(3))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("retry later")));
    }

    private JudgeScheduler scheduler(ExecutionProperties properties, FileTaskStore store, ExecutorService executor) {
        JudgeScheduler scheduler = new JudgeScheduler(properties, store, executor);
        schedulers.add(scheduler);
        return scheduler;
    }

    private UserSession controllerSession() {
        return UserSession.builder()
                .sessionId("controller-session")
                .userId("controller-user")
                .build();
    }

    private AccessCodeService accessCodeService(UserSession session) {
        AccessCodeService accessCodeService = mock(AccessCodeService.class);
        when(accessCodeService.getSession(session.getSessionId())).thenReturn(session);
        return accessCodeService;
    }

    private ExecutorService executor(int threads) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        executors.add(executor);
        return executor;
    }

    private FileTaskStore store() {
        return new FileTaskStore(objectMapper, tempDir);
    }

    private ExecutionProperties properties(int maxConcurrentTasks, int queueCapacity, Duration maxTaskRuntime) {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setMaxConcurrentTasks(maxConcurrentTasks);
        properties.setTaskQueueCapacity(queueCapacity);
        properties.setMaxTaskRuntime(maxTaskRuntime);
        return properties;
    }

    private void createTask(FileTaskStore store, String judgeId, int requestedCases) throws Exception {
        createTask(store, judgeId, requestedCases, Duration.ofMinutes(30));
    }

    private void createTask(FileTaskStore store, String judgeId, int requestedCases, Duration maxTaskRuntime) throws Exception {
        Path workDir = store.taskDirectory(judgeId);
        store.create(JudgeTask.builder()
                .judgeId(judgeId)
                .status(JudgeStatus.CREATED)
                .requestedCases(requestedCases)
                .mode("scheduler-test")
                .policy(policy(requestedCases, maxTaskRuntime))
                .workDir(workDir.toString())
                .createdAt(Instant.now())
                .build());
    }

    private ResolvedTaskPolicy policy(int requestedCases, Duration maxTaskRuntime) {
        return new ResolvedTaskPolicy(
                "scheduler-test",
                requestedCases >= 5_000,
                100_000,
                requestedCases,
                100,
                4,
                Duration.ofSeconds(2),
                maxTaskRuntime,
                268_435_456L,
                1_048_576L,
                false
        );
    }

    private void awaitStatus(FileTaskStore store, String judgeId, JudgeStatus expectedStatus) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (store.find(judgeId).orElseThrow().getStatus() == expectedStatus) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(store.find(judgeId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(expectedStatus);
    }

    private JudgeProgress awaitSummary(FileTaskStore store, String judgeId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var summary = store.findSummary(judgeId);
            if (summary.isPresent()) {
                return summary.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for summary for " + judgeId);
    }

    private void awaitIdle(JudgeScheduler scheduler) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            JudgeScheduler.QueueSnapshot snapshot = scheduler.snapshot();
            if (snapshot.runningCount() == 0 && snapshot.queuedCount() == 0) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
    }
}
