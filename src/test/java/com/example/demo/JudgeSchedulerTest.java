package com.example.demo;

import com.example.demo.config.AsyncConfig;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.controller.JudgeController;
import com.example.demo.dto.CancelJudgeResponse;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.UserSession;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.AuditService;
import com.example.demo.service.JudgeFileService;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.JudgeService;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.ResultAggregator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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

    @Test
    void dispatchedJobStartsPendingInsteadOfClaimingProgramExecution() throws Exception {
        FileTaskStore store = store();
        createTask(store, "dispatch-phase", 3, Duration.ofSeconds(5));
        JudgeScheduler scheduler = scheduler(properties(1, 2, Duration.ofSeconds(5)), store, Runnable::run);
        AtomicInteger jobStarts = new AtomicInteger();

        scheduler.enqueue("dispatch-phase", context -> {
            jobStarts.incrementAndGet();
            JudgeTask dispatched = store.find("dispatch-phase").orElseThrow();
            assertThat(dispatched.getStatus()).isEqualTo(JudgeStatus.PENDING);
            assertThat(dispatched.getStartedAt()).isNotNull();
            assertThat(store().find("dispatch-phase").orElseThrow()).usingRecursiveComparison().isEqualTo(dispatched);
            context.cancellationToken().cancel();
        });

        assertThat(jobStarts).hasValue(1);
        assertThat(store.find("dispatch-phase").orElseThrow().getStatus()).isEqualTo(JudgeStatus.CANCELLED);
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
    }

    @AfterEach
    void stopSchedulersAndExecutors() {
        for (JudgeScheduler scheduler : schedulers) {
            scheduler.shutdown();
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void rejectedExecutionReturnsPermitsEvenWhenTerminalPersistenceFails(boolean failTerminalWrite) throws Exception {
        FileTaskStore store = spy(store());
        ExecutionProperties properties = properties(1, 1, Duration.ofSeconds(5));
        properties.setMaxRunningTasksPerUser(1);
        AtomicInteger executorAttempts = new AtomicInteger();
        AtomicInteger startedJobs = new AtomicInteger();
        JudgeScheduler scheduler = scheduler(properties, store, runnable -> {
            executorAttempts.incrementAndGet();
            throw new RejectedExecutionException("test executor rejected task");
        });
        for (String judgeId : List.of("reject-first", "reject-next")) {
            createTask(store, judgeId, 1, Duration.ofSeconds(5),
                    JudgeOwnership.owner("reject-owner", "session-" + judgeId));
            if (failTerminalWrite) {
                doThrow(new IOException("forced terminal write failure")).when(store)
                        .finalizeTask(eq(judgeId), any(JudgeProgress.class));
            }
            assertThatThrownBy(() -> scheduler.enqueue(judgeId, context -> startedJobs.incrementAndGet()))
                    .isInstanceOf(failTerminalWrite ? IllegalStateException.class : RejectedExecutionException.class)
                    .hasMessageContaining(failTerminalWrite
                            ? "Failed to save stopped judge summary" : "test executor rejected task");
            assertThat(store.find(judgeId).orElseThrow().getStatus())
                    .isEqualTo(failTerminalWrite ? JudgeStatus.QUEUED : JudgeStatus.SYSTEM_ERROR);
            if (!failTerminalWrite) {
                JudgeProgress stopped = store.findSummary(judgeId).orElseThrow();
                assertThat(stopped.getStatus()).isEqualTo("SYSTEM_ERROR");
                assertThat(stopped.getSummary().getTotalCases()).isEqualTo(1);
                assertThat(stopped.getSummary().getCompletedCases()).isZero();
            }
            assertThat(scheduler.snapshot().runningCount()).isZero();
            assertThat(scheduler.snapshot().queuedCount()).isZero();
        }
        assertThat(executorAttempts).hasValue(2);
        assertThat(startedJobs).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void cancellationBeforeJobInvocationSkipsWorkAndHandsOffThePermit(
            boolean promotedFromQueue, boolean cancelDuringStart) throws Exception {
        FileTaskStore store = spy(store());
        ExecutionProperties properties = properties(1, 2, Duration.ofSeconds(5));
        properties.setMaxRunningTasksPerUser(1);
        List<Runnable> dispatchedJobs = new ArrayList<>();
        JudgeScheduler scheduler = scheduler(properties, store, dispatchedJobs::add);
        AtomicInteger cancelledJobStarts = new AtomicInteger();
        AtomicInteger nextJobStarts = new AtomicInteger();
        for (String judgeId : List.of("handoff-blocker", "handoff-cancel", "handoff-next")) {
            createTask(store, judgeId, 3, Duration.ofSeconds(5),
                    JudgeOwnership.owner("handoff-owner", "session-" + judgeId));
        }
        if (promotedFromQueue) {
            scheduler.enqueue("handoff-blocker", context -> { });
        }
        scheduler.enqueue("handoff-cancel", context -> cancelledJobStarts.incrementAndGet());
        scheduler.enqueue("handoff-next", context -> nextJobStarts.incrementAndGet());
        if (promotedFromQueue) {
            dispatchedJobs.remove(0).run();
        }
        assertThat(dispatchedJobs).hasSize(1);
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
        assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);
        if (cancelDuringStart) {
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                assertThat(scheduler.cancel("handoff-cancel").accepted()).isTrue();
                return result;
            }).when(store).updateStatus("handoff-cancel", JudgeStatus.PENDING, "Judge task preparation started");
        } else {
            assertThat(scheduler.cancel("handoff-cancel").accepted()).isTrue();
        }

        dispatchedJobs.remove(0).run();

        assertThat(cancelledJobStarts).hasValue(0);
        assertThat(store.find("handoff-cancel").orElseThrow().getStatus()).isEqualTo(JudgeStatus.CANCELLED);
        JudgeProgress stopped = store.findSummary("handoff-cancel").orElseThrow();
        assertThat(stopped.getStatus()).isEqualTo("CANCELLED");
        assertThat(stopped.getSummary().getTotalCases()).isEqualTo(3);
        assertThat(stopped.getSummary().getCompletedCases()).isZero();
        assertThat(stopped.getSummary().getAc()).isZero();
        assertThat(scheduler.cancel("handoff-cancel").accepted()).isFalse();
        assertThat(store.findSummary("handoff-cancel").orElseThrow())
                .usingRecursiveComparison().isEqualTo(stopped);
        assertThat(dispatchedJobs).hasSize(1);
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
        assertThat(scheduler.snapshot().queuedCount()).isZero();
        dispatchedJobs.remove(0).run();
        assertThat(nextJobStarts).hasValue(1);
        assertThat(store.find("handoff-next").orElseThrow().getStatus()).isEqualTo(JudgeStatus.COMPLETED);
        assertThat(dispatchedJobs).isEmpty();
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
    }

    @Test
    void rejectedBudgetSchedulingEndsUnstartedJobsAndReturnsUserAndGlobalPermits() throws Exception {
        FileTaskStore store = store();
        ExecutionProperties properties = properties(1, 1, Duration.ofSeconds(5));
        properties.setMaxRunningTasksPerUser(1);
        List<Runnable> dispatchedJobs = new ArrayList<>();
        JudgeScheduler scheduler = scheduler(properties, store, dispatchedJobs::add);
        AtomicInteger startedJobs = new AtomicInteger();
        JudgeScheduler.ScheduledJudgeJob job = context -> startedJobs.incrementAndGet();
        createTask(store, "budget-first", 1, Duration.ofSeconds(5),
                JudgeOwnership.owner("budget-owner", "budget-session-a"));
        createTask(store, "budget-next", 1, Duration.ofSeconds(5),
                JudgeOwnership.owner("budget-owner", "budget-session-b"));
        scheduler.enqueue("budget-first", job);
        scheduler.enqueue("budget-next", job);
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
        assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);
        assertThat(dispatchedJobs).hasSize(1);

        scheduler.shutdown();

        assertThatCode(() -> dispatchedJobs.remove(0).run()).doesNotThrowAnyException();
        assertThat(store.find("budget-first").orElseThrow().getStatus()).isEqualTo(JudgeStatus.SYSTEM_ERROR);
        assertThat(store.find("budget-next").orElseThrow().getStatus()).isEqualTo(JudgeStatus.QUEUED);
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
        assertThat(scheduler.snapshot().queuedCount()).isZero();
        assertThat(dispatchedJobs).hasSize(1);
        assertThatCode(() -> dispatchedJobs.remove(0).run()).doesNotThrowAnyException();
        assertThat(store.find("budget-next").orElseThrow().getStatus()).isEqualTo(JudgeStatus.SYSTEM_ERROR);
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
        assertThat(dispatchedJobs).isEmpty();
        assertThat(startedJobs).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"empty", "partial", "finalized", "metadata-error"})
    void unexpectedJobFailurePersistsTerminalSummaryWithoutErasingAcceptedResults(String checkpoint) throws Exception {
        FileTaskStore store = store();
        createTask(store, "host-failure", 12, Duration.ofSeconds(5));
        List<Runnable> dispatchedJobs = new ArrayList<>();
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofSeconds(5)), store, dispatchedJobs::add);
        ResultAggregator aggregator = new ResultAggregator(true, 12, 3, 2);
        if (!"empty".equals(checkpoint)) {
            aggregator.accept(new TestCaseResult(1, "AC", 10, 12));
            aggregator.accept(new TestCaseResult(2, "WA", 20, 14));
        }
        boolean finalized = "finalized".equals(checkpoint);
        boolean metadataFailed = "metadata-error".equals(checkpoint);
        String message = finalized ? "Execution service finished"
                : metadataFailed ? "Execution service failed" : "Judge task failed unexpectedly";
        JudgeStatus expectedStatus = finalized ? JudgeStatus.WA : JudgeStatus.SYSTEM_ERROR;
        JudgeSummary expectedSummary = aggregator.toSummary();
        expectedSummary.setStoppedReason(message);
        int expectedProgress = "empty".equals(checkpoint) ? 0 : 29;
        JudgeProgress expected = new JudgeProgress(expectedStatus.name(), message, expectedProgress, null, expectedSummary);
        scheduler.enqueue("host-failure", context -> {
            if (!"empty".equals(checkpoint)) {
                context.recordCompletedCase();
                context.recordCompletedCase();
                store.saveSummary("host-failure", new JudgeProgress("RUNNING", "Accepted partial results",
                        expectedProgress, null, aggregator.toSummary()));
            }
            if (finalized) {
                store.finalizeTask("host-failure", expected);
            } else if (metadataFailed) {
                store.updateStatus("host-failure", expectedStatus, message);
            }
            throw new IllegalStateException("Injected unhandled host failure");
        });

        dispatchedJobs.remove(0).run();

        JudgeTask finished = store.find("host-failure").orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(expectedStatus);
        assertThat(finished.getFinishedAt()).isNotNull();
        expected.setRevision("empty".equals(checkpoint) ? 1L : 2L);
        expected.setFinalizedAt(finished.getFinishedAt());
        assertThat(store.findSummary("host-failure").orElseThrow()).usingRecursiveComparison().isEqualTo(expected);
        FileTaskStore coldStore = store();
        assertThat(coldStore.findSummary("host-failure").orElseThrow()).usingRecursiveComparison().isEqualTo(expected);
        assertThat(expectedSummary.getAc() + expectedSummary.getWa()).isEqualTo(expectedSummary.getCompletedCases());
        if (!"empty".equals(checkpoint)) {
            assertThat(expectedSummary.getFirstFailedCase()).isEqualTo(2);
            assertThat(expectedSummary.getFailureSamples()).hasSize(1);
            assertThat(expectedSummary.getSlowSamples()).hasSize(2);
        }
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"14,start", "15,start", "16,start", "14,cancel", "15,cancel", "16,cancel",
            "14,scan", "15,scan", "16,scan"})
    void createdTasksHonorApprovedTtlBeforeStartingOrCancelling(int ageMinutes, String operation) throws Exception {
        FileTaskStore store = store();
        createTask(store, "created-ttl", 3, Duration.ofSeconds(5));
        JudgeTask created = store.find("created-ttl").orElseThrow();
        created.setCreatedAt(Instant.now().minus(Duration.ofMinutes(ageMinutes)));
        objectMapper.writeValue(store.taskDirectory("created-ttl").resolve("metadata.json").toFile(), created);
        List<Runnable> dispatchedJobs = new ArrayList<>();
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofSeconds(5)), store, dispatchedJobs::add);
        AtomicInteger jobStarts = new AtomicInteger();
        boolean expired = ageMinutes >= 15;
        JudgeStatus expectedStatus;
        if ("start".equals(operation)) {
            if (expired) {
                assertThatThrownBy(() -> scheduler.enqueue("created-ttl", context -> jobStarts.incrementAndGet()))
                        .isInstanceOf(JudgeScheduler.TaskAlreadyFinishedException.class);
                expectedStatus = JudgeStatus.STALE;
            } else {
                scheduler.enqueue("created-ttl", context -> jobStarts.incrementAndGet());
                assertThat(dispatchedJobs).hasSize(1);
                dispatchedJobs.remove(0).run();
                assertThat(jobStarts).hasValue(1);
                expectedStatus = JudgeStatus.COMPLETED;
            }
        } else if ("scan".equals(operation)) {
            assertThat(scheduler.expireCreatedTask("created-ttl", Instant.now()))
                    .isEqualTo(expired ? Optional.of(JudgeStatus.STALE) : Optional.empty());
            expectedStatus = expired ? JudgeStatus.STALE : JudgeStatus.CREATED;
        } else {
            CancelJudgeResponse cancellation = scheduler.cancel("created-ttl");
            assertThat(cancellation.accepted()).isEqualTo(!expired);
            assertThat(cancellation.running()).isFalse();
            assertThat(cancellation.queued()).isFalse();
            expectedStatus = expired ? JudgeStatus.STALE : JudgeStatus.CANCELLED;
            assertThat(cancellation.status()).isEqualTo(expectedStatus.name());
        }
        assertThat(store.find("created-ttl").orElseThrow().getStatus()).isEqualTo(expectedStatus);
        if (expired || "cancel".equals(operation)) {
            assertThat(jobStarts).hasValue(0);
            assertThat(store.find("created-ttl").orElseThrow().getStartedAt()).isNull();
            JudgeProgress stopped = store.findSummary("created-ttl").orElseThrow();
            assertThat(stopped.getStatus()).isEqualTo(expectedStatus.name());
            assertThat(stopped.getSummary().getTotalCases()).isEqualTo(3);
            assertThat(stopped.getSummary().getCompletedCases()).isZero();
            assertThat(scheduler.cancel("created-ttl").accepted()).isFalse();
            assertThat(store.findSummary("created-ttl").orElseThrow())
                    .usingRecursiveComparison().isEqualTo(stopped);
        }
        assertThat(dispatchedJobs).isEmpty();
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"1,false", "1,true", "2,false", "2,true"})
    void productionExecutorHandsOffQueuedAndRetriedRequestsWithinExistingLimits(
            int concurrency, boolean cancelMiddle) throws Exception {
        FileTaskStore store = store();
        ExecutionProperties properties = properties(concurrency, 3, Duration.ofSeconds(5));
        ThreadPoolTaskExecutor productionExecutor = (ThreadPoolTaskExecutor) new AsyncConfig()
                .judgeRequestExecutor(properties);
        executors.add(productionExecutor.getThreadPoolExecutor());
        JudgeScheduler scheduler = scheduler(properties, store, productionExecutor);
        assertThat(productionExecutor.getCorePoolSize()).isEqualTo(concurrency);
        assertThat(productionExecutor.getMaxPoolSize()).isEqualTo(concurrency);
        assertThat(productionExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(concurrency);
        assertThat(productionExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
        CountDownLatch initialStarted = new CountDownLatch(concurrency);
        CountDownLatch releaseInitial = new CountDownLatch(1);
        CountDownLatch firstQueuedStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstQueued = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peakActive = new AtomicInteger();
        List<String> executed = new CopyOnWriteArrayList<>();
        List<String> workerNames = new CopyOnWriteArrayList<>();
        JudgeScheduler.ScheduledJudgeJob job = context -> {
            peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            executed.add(context.judgeId());
            workerNames.add(Thread.currentThread().getName());
            try {
                if (context.judgeId().startsWith("initial-")) {
                    initialStarted.countDown();
                    assertThat(releaseInitial.await(3, TimeUnit.SECONDS)).isTrue();
                } else if (context.judgeId().equals("queued-first")) {
                    firstQueuedStarted.countDown();
                    assertThat(releaseFirstQueued.await(3, TimeUnit.SECONDS)).isTrue();
                }
            } finally {
                active.decrementAndGet();
            }
        };
        try {
            for (int index = 0; index < concurrency; index++) {
                String judgeId = "initial-" + index;
                createTask(store, judgeId, 1, Duration.ofSeconds(5));
                scheduler.enqueue(judgeId, job);
            }
            assertThat(initialStarted.await(2, TimeUnit.SECONDS)).isTrue();
            for (String judgeId : List.of("queued-first", "queued-middle", "queued-last", "overflow")) {
                createTask(store, judgeId, 1, Duration.ofSeconds(5));
                if (!judgeId.equals("overflow")) {
                    scheduler.enqueue(judgeId, job);
                }
            }
            assertThat(scheduler.snapshot().runningCount()).isEqualTo(concurrency);
            assertThat(scheduler.snapshot().queuedCount()).isEqualTo(3);
            scheduler.requireScheduled("initial-0");
            scheduler.requireScheduled("queued-first");
            assertThat(scheduler.snapshot().queuedCount()).isEqualTo(3);
            assertThatThrownBy(() -> scheduler.enqueue("overflow", job))
                    .isInstanceOf(JudgeScheduler.QueueFullException.class);
            assertThat(store.find("overflow").orElseThrow().getStatus()).isEqualTo(JudgeStatus.CREATED);
            if (cancelMiddle) {
                assertThat(scheduler.cancel("queued-middle").accepted()).isTrue();
            }
            releaseInitial.countDown();
            assertThat(firstQueuedStarted.await(2, TimeUnit.SECONDS)).isTrue();
            scheduler.enqueue("overflow", job);
            releaseFirstQueued.countDown();
            awaitStatus(store, "overflow", JudgeStatus.COMPLETED);
            awaitStatus(store, "queued-first", JudgeStatus.COMPLETED);
            awaitStatus(store, "queued-last", JudgeStatus.COMPLETED);
            awaitStatus(store, "queued-middle", cancelMiddle ? JudgeStatus.CANCELLED : JudgeStatus.COMPLETED);
            awaitIdle(scheduler);
            assertThat(executed).hasSize(concurrency + (cancelMiddle ? 3 : 4)).doesNotHaveDuplicates();
            assertThat(peakActive.get()).isLessThanOrEqualTo(concurrency);
            assertThat(scheduler.snapshot().peakRunningCount()).isLessThanOrEqualTo(concurrency);
            assertThat(scheduler.snapshot().peakQueueSize()).isEqualTo(3);
            assertThat(workerNames).allMatch(name -> name.startsWith("JudgeReq-"));
            if (concurrency == 1) {
                assertThat(executed).containsExactlyElementsOf(cancelMiddle
                        ? List.of("initial-0", "queued-first", "queued-last", "overflow")
                        : List.of("initial-0", "queued-first", "queued-middle", "queued-last", "overflow"));
            }
        } finally {
            releaseInitial.countDown();
            releaseFirstQueued.countDown();
        }
    }

    @ParameterizedTest
    @CsvSource({"1,account", "2,account", "1,anonymous", "1,legacy"})
    void userRunningLimitIsSharedAcrossSessionsAndDoesNotBlockOtherOwners(
            int perUserLimit, String ownershipKind) throws Exception {
        FileTaskStore store = store();
        ExecutionProperties properties = properties(perUserLimit + 1, 4, Duration.ofSeconds(5));
        properties.setMaxRunningTasksPerUser(perUserLimit);
        ThreadPoolTaskExecutor productionExecutor = (ThreadPoolTaskExecutor) new AsyncConfig()
                .judgeRequestExecutor(properties);
        executors.add(productionExecutor.getThreadPoolExecutor());
        JudgeScheduler scheduler = scheduler(properties, store, productionExecutor);
        CountDownLatch initialStarted = new CountDownLatch(perUserLimit);
        CountDownLatch ownerNextStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch otherFirstStarted = new CountDownLatch(1);
        CountDownLatch releaseOtherFirst = new CountDownLatch(1);
        CountDownLatch otherNextStarted = new CountDownLatch(1);
        CountDownLatch releaseOtherNext = new CountDownLatch(1);
        AtomicInteger ownerActive = new AtomicInteger();
        AtomicInteger peakOwnerActive = new AtomicInteger();
        List<String> executed = new CopyOnWriteArrayList<>();
        JudgeScheduler.ScheduledJudgeJob ownerJob = context -> {
            peakOwnerActive.accumulateAndGet(ownerActive.incrementAndGet(), Math::max);
            executed.add(context.judgeId());
            if (context.judgeId().equals("owner-next")) {
                ownerNextStarted.countDown();
            } else {
                initialStarted.countDown();
            }
            try {
                assertThat(releaseOwner.await(3, TimeUnit.SECONDS)).isTrue();
            } finally {
                ownerActive.decrementAndGet();
            }
        };
        try {
            for (int index = 0; index <= perUserLimit; index++) {
                String judgeId = index == perUserLimit ? "owner-next" : "owner-" + index;
                JudgeOwnership ownership = switch (ownershipKind) {
                    case "account" -> JudgeOwnership.owner("same-account", "session-" + index);
                    case "anonymous" -> JudgeOwnership.anonymous();
                    case "legacy" -> null;
                    default -> throw new IllegalArgumentException(ownershipKind);
                };
                createTask(store, judgeId, 1, Duration.ofSeconds(5), ownership);
                scheduler.enqueue(judgeId, ownerJob);
            }
            assertThat(initialStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.snapshot().runningCount()).isEqualTo(perUserLimit);
            assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);
            assertThat(store.find("owner-next").orElseThrow().getStatus()).isEqualTo(JudgeStatus.QUEUED);
            assertThat(ownerNextStarted.getCount()).isEqualTo(1);
            for (String judgeId : List.of("other-first", "other-next")) {
                createTask(store, judgeId, 1, Duration.ofSeconds(5),
                        JudgeOwnership.owner("other-account", "other-session"));
                scheduler.enqueue(judgeId, context -> {
                    executed.add(context.judgeId());
                    if (context.judgeId().equals("other-first")) {
                        otherFirstStarted.countDown();
                        assertThat(releaseOtherFirst.await(3, TimeUnit.SECONDS)).isTrue();
                    } else {
                        otherNextStarted.countDown();
                        assertThat(releaseOtherNext.await(3, TimeUnit.SECONDS)).isTrue();
                    }
                });
            }
            assertThat(otherFirstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.snapshot().runningCount()).isEqualTo(perUserLimit + 1);
            assertThat(scheduler.snapshot().queuedCount()).isEqualTo(2);
            releaseOtherFirst.countDown();
            assertThat(otherNextStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(ownerNextStarted.getCount()).isEqualTo(1);
            assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);
            releaseOwner.countDown();
            releaseOtherNext.countDown();
            assertThat(ownerNextStarted.await(2, TimeUnit.SECONDS)).isTrue();
            awaitIdle(scheduler);
            assertThat(executed).hasSize(perUserLimit + 3).doesNotHaveDuplicates();
            assertThat(peakOwnerActive.get()).isLessThanOrEqualTo(perUserLimit);
            assertThat(scheduler.snapshot().peakRunningCount()).isLessThanOrEqualTo(perUserLimit + 1);
        } finally {
            releaseOwner.countDown();
            releaseOtherFirst.countDown();
            releaseOtherNext.countDown();
            awaitIdle(scheduler);
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
    void failedQueuedCancellationKeepsTheQueueEntryForRetry() throws Exception {
        FileTaskStore store = spy(store());
        createTask(store, "running", 12);
        createTask(store, "queued", 12);
        List<Runnable> acceptedJobs = new ArrayList<>();
        AtomicInteger queuedExecutions = new AtomicInteger();
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofSeconds(5)), store, acceptedJobs::add);
        scheduler.enqueue("running", context -> { });
        scheduler.enqueue("queued", context -> queuedExecutions.incrementAndGet());
        doThrow(new IOException("test-only final write failure"))
                .when(store).finalizeTask(eq("queued"), any(JudgeProgress.class));

        assertThatThrownBy(() -> scheduler.cancel("queued"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to save stopped judge summary");
        assertThat(store.find("queued").orElseThrow().getStatus()).isEqualTo(JudgeStatus.QUEUED);
        assertThat(scheduler.snapshot().queuedCount()).isEqualTo(1);
        assertThat(acceptedJobs).hasSize(1);

        doCallRealMethod().when(store).finalizeTask(eq("queued"), any(JudgeProgress.class));
        CancelJudgeResponse retried = scheduler.cancel("queued");
        assertThat(retried.accepted()).isTrue();
        assertThat(retried.status()).isEqualTo("CANCELLED");
        assertThat(scheduler.snapshot().queuedCount()).isZero();
        assertThat(store.findSummary("queued").orElseThrow().getStatus()).isEqualTo("CANCELLED");
        acceptedJobs.get(0).run();
        awaitIdle(scheduler);
        assertThat(queuedExecutions).hasValue(0);
    }

    @ParameterizedTest
    @CsvSource({"start", "cancel"})
    void queuedCancellationSerializesRetriesUntilTerminalPersistence(String retryAction) throws Exception {
        FileTaskStore store = spy(store());
        createTask(store, "running", 12);
        createTask(store, "queued", 12);
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofSeconds(5)), store, executor(1));
        ExecutorService callers = executor(2);
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch releaseRunning = new CountDownLatch(1);
        CountDownLatch cancellationWriting = new CountDownLatch(1);
        CountDownLatch releaseCancellationWrite = new CountDownLatch(1);
        CountDownLatch retryRequested = new CountDownLatch(1);
        AtomicInteger queuedExecutions = new AtomicInteger();

        scheduler.enqueue("running", context -> {
            runningStarted.countDown();
            assertThat(releaseRunning.await(2, TimeUnit.SECONDS)).isTrue();
        });
        assertThat(runningStarted.await(2, TimeUnit.SECONDS)).isTrue();
        scheduler.enqueue("queued", context -> queuedExecutions.incrementAndGet());
        doAnswer(invocation -> {
            cancellationWriting.countDown();
            assertThat(releaseCancellationWrite.await(2, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(store).finalizeTask(eq("queued"), any(JudgeProgress.class));

        try {
            var cancellation = callers.submit(() -> scheduler.cancel("queued"));
            assertThat(cancellationWriting.await(2, TimeUnit.SECONDS)).isTrue();
            var retry = callers.submit(() -> {
                retryRequested.countDown();
                if ("start".equals(retryAction)) {
                    assertThatThrownBy(() -> scheduler.enqueue("queued", context -> queuedExecutions.incrementAndGet()))
                            .isInstanceOf(JudgeScheduler.TaskAlreadyFinishedException.class)
                            .hasMessageContaining("CANCELLED");
                } else {
                    CancelJudgeResponse repeated = scheduler.cancel("queued");
                    assertThat(repeated.accepted()).isFalse();
                    assertThat(repeated.status()).isEqualTo("CANCELLED");
                    assertThat(repeated.running()).isFalse();
                    assertThat(repeated.queued()).isFalse();
                }
            });
            assertThat(retryRequested.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> retry.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseCancellationWrite.countDown();
            CancelJudgeResponse cancelled = cancellation.get(2, TimeUnit.SECONDS);
            assertThat(cancelled.accepted()).isTrue();
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            retry.get(2, TimeUnit.SECONDS);
            JudgeProgress summary = store.findSummary("queued").orElseThrow();
            assertThat(summary.getStatus()).isEqualTo("CANCELLED");
            assertThat(summary.getSummary().getCompletedCases()).isZero();
            assertThat(scheduler.snapshot().queuedCount()).isZero();
        } finally {
            releaseCancellationWrite.countDown();
            releaseRunning.countDown();
            awaitIdle(scheduler);
        }
        assertThat(queuedExecutions).hasValue(0);
    }

    @Test
    void cancelCreatedTaskPersistsEmptySummaryRejectsLateStartAndIsIdempotent() throws Exception {
        FileTaskStore store = store();
        createTask(store, "created", 12);
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofSeconds(5)), store, executor(1));
        AtomicInteger executions = new AtomicInteger();

        CancelJudgeResponse response = scheduler.cancel("created");

        assertThat(response.accepted()).isTrue();
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.running()).isFalse();
        assertThat(response.queued()).isFalse();
        assertThat(store.find("created")).isPresent().get().extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.CANCELLED);
        JudgeProgress summary = store.findSummary("created").orElseThrow();
        assertThat(summary.getStatus()).isEqualTo("CANCELLED");
        assertThat(summary.getSummary().getTotalCases()).isEqualTo(12);
        assertThat(summary.getSummary().getCompletedCases()).isZero();
        assertThat(summary.getSummary().getAc()).isZero();
        assertThatThrownBy(() -> scheduler.enqueue("created", context -> executions.incrementAndGet()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CANCELLED");
        CancelJudgeResponse repeated = scheduler.cancel("created");
        assertThat(repeated.accepted()).isFalse();
        assertThat(repeated.status()).isEqualTo("CANCELLED");
        assertThat(repeated.running()).isFalse();
        assertThat(repeated.queued()).isFalse();
        assertThat(store.findSummary("created").orElseThrow()).usingRecursiveComparison().isEqualTo(summary);
        assertThat(executions).hasValue(0);
        assertThatThrownBy(() -> scheduler.requireScheduled("created"))
                .isInstanceOf(JudgeScheduler.TaskAlreadyFinishedException.class);
        assertThat(scheduler.snapshot().runningCount()).isZero();
        assertThat(scheduler.snapshot().queuedCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "false,false,CANCELLED", "true,false,BUDGET_EXCEEDED",
            "false,true,CANCELLED", "true,true,BUDGET_EXCEEDED",
            "false,true,WA", "true,true,WA"
    })
    void stoppingSignalsBeforeExitAndPreservesRealOrFallbackSummary(
            boolean budgetExceeded, boolean jobFinalizes, JudgeStatus finalStatus) throws Exception {
        FileTaskStore store = store();
        createTask(store, "running", 12, budgetExceeded ? Duration.ofMillis(250) : Duration.ofSeconds(5));
        JudgeScheduler scheduler = scheduler(properties(1, 1, Duration.ofSeconds(5)), store, executor(1));
        CountDownLatch runningStarted = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        CountDownLatch releaseJob = new CountDownLatch(1);
        ResultAggregator aggregator = new ResultAggregator(true, 12, 3, 2);
        aggregator.accept(new TestCaseResult(1, "AC", 10, 12));
        aggregator.accept(new TestCaseResult(2, "WA", 20, 14));
        JudgeProgress running = new JudgeProgress("RUNNING", "Two real results", 29, null, aggregator.toSummary());
        running.setRevision(1L);
        String stoppedReason = budgetExceeded ? "Task runtime budget exceeded" : "Cancellation requested";
        JudgeSummary finalSummary = aggregator.toSummary();
        finalSummary.setStoppedReason(jobFinalizes ? "Execution service retained reason" : stoppedReason);
        JudgeProgress expected = new JudgeProgress(finalStatus.name(),
                jobFinalizes ? "Execution service finished" : stoppedReason, 29, null, finalSummary);

        scheduler.enqueue("running", context -> {
            context.recordCompletedCase();
            context.recordCompletedCase();
            store.updateStatus("running", JudgeStatus.RUNNING, running.getMessage());
            store.saveSummary("running", running);
            runningStarted.countDown();
            while (!context.cancellationToken().isCancellationRequested()) {
                Thread.sleep(10);
            }
            cancellationObserved.countDown();
            assertThat(releaseJob.await(2, TimeUnit.SECONDS)).isTrue();
            if (jobFinalizes) {
                store.finalizeTask("running", expected);
            }
        });

        assertThat(runningStarted.await(2, TimeUnit.SECONDS)).isTrue();
        if (budgetExceeded) {
            assertThat(cancellationObserved.await(2, TimeUnit.SECONDS)).isTrue();
        }
        CancelJudgeResponse response = scheduler.cancel("running");

        assertThat(response.accepted()).isTrue();
        assertThat(response.status()).isEqualTo("RUNNING");
        assertThat(response.running()).isTrue();
        assertThat(response.completedCases()).isEqualTo(2);
        assertThat(cancellationObserved.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(store.find("running").orElseThrow().getStatus()).isEqualTo(JudgeStatus.RUNNING);
        assertThat(store.findSummary("running").orElseThrow()).usingRecursiveComparison().isEqualTo(running);
        assertThat(scheduler.snapshot().runningCount()).isEqualTo(1);
        releaseJob.countDown();
        awaitIdle(scheduler);
        JudgeTask finished = store.find("running").orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(finalStatus);
        assertThat(finished.getFinishedAt()).isNotNull();
        expected.setRevision(2L);
        expected.setFinalizedAt(finished.getFinishedAt());
        assertThat(store.findSummary("running").orElseThrow()).usingRecursiveComparison().isEqualTo(expected);
        assertThat(finalSummary.getAc() + finalSummary.getWa()).isEqualTo(finalSummary.getCompletedCases());
        assertThat(finalSummary.getFirstFailedCase()).isEqualTo(2);
        assertThat(finalSummary.getFailureSamples()).hasSize(1);
        assertThat(finalSummary.getSlowSamples()).hasSize(2);
        assertThat(scheduler.cancel("running").accepted()).isFalse();
        assertThat(store.find("running").orElseThrow().getFinishedAt()).isEqualTo(finished.getFinishedAt());
        assertThat(store.findSummary("running").orElseThrow()).usingRecursiveComparison().isEqualTo(expected);
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
                .thenReturn(new CancelJudgeResponse("abc", true, "RUNNING", "Cancellation requested", true, false, 7));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new JudgeController(
                judgeService,
                mock(JudgeFileService.class),
                accessCodeService(session),
                new AuditService(),
                new ExecutionProperties()
        )).build();

        mockMvc.perform(post("/judge/cancel/{judgeId}", "abc")
                        .header("X-Session-ID", session.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judgeId").value("abc"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.status").value("RUNNING"))
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
                accessCodeService(session),
                new AuditService(),
                new ExecutionProperties()
        )).build();

        mockMvc.perform(post("/judge/start/{judgeId}", "full")
                        .header("X-Session-ID", session.getSessionId()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("JUDGE_QUEUE_FULL"))
                .andExpect(jsonPath("$.queueCapacity").value(3))
                .andExpect(jsonPath("$.currentQueueSize").value(3))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("retry later")));
    }

    private JudgeScheduler scheduler(ExecutionProperties properties, FileTaskStore store, Executor executor) {
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
        createTask(store, judgeId, requestedCases, maxTaskRuntime,
                JudgeOwnership.owner("user-" + judgeId, "session-" + judgeId));
    }

    private void createTask(FileTaskStore store, String judgeId, int requestedCases, Duration maxTaskRuntime,
                            JudgeOwnership ownership) throws Exception {
        Path workDir = store.taskDirectory(judgeId);
        store.create(JudgeTask.builder()
                .judgeId(judgeId)
                .ownership(ownership)
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
