package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeProgressEvent;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.service.ResultAggregator;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.TaskCleanupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ProgressPublisherTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private FileTaskStore taskStore;
    private SimpMessagingTemplate messagingTemplate;
    private MutableClock clock;
    private ProgressPublisher publisher;

    @BeforeEach
    void setUp() {
        taskStore = new FileTaskStore(objectMapper, tempDir.resolve("storage"));
        messagingTemplate = mock(SimpMessagingTemplate.class);
        clock = new MutableClock(Instant.parse("2026-07-02T00:00:00Z"));
        publisher = new ProgressPublisher(messagingTemplate, taskStore, objectMapper, clock, Duration.ofSeconds(1));
    }

    @Test
    void throttlesRunningProgressButAlwaysPersistsLatestForPolling() throws Exception {
        createTask("progress-task", 100, false);

        JudgeProgress pending = publisher.publish("progress-task", new JudgeProgress("PENDING", "queued", 0));
        JudgeProgress firstRunning = publisher.publish("progress-task", new JudgeProgress("RUNNING", "10 done", 10));
        clock.advance(Duration.ofMillis(100));
        JudgeProgress throttled = publisher.publish("progress-task", new JudgeProgress("RUNNING", "20 done", 20));
        assertThat(pending.getRevision()).isEqualTo(1L);
        assertThat(firstRunning.getRevision()).isEqualTo(2L);
        assertThat(throttled.getRevision()).isEqualTo(3L);
        assertThat(new FileTaskStore(objectMapper, tempDir.resolve("storage"))
                .findSummary("progress-task").orElseThrow()).usingRecursiveComparison().isEqualTo(throttled);

        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(Object.class), anyMap());
        assertThat(taskStore.find("progress-task")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.RUNNING);
        assertThat(taskStore.findSummary("progress-task")).isPresent().get()
                .extracting(JudgeProgress::getProgress)
                .isEqualTo(20);

        clock.advance(Duration.ofSeconds(1));
        publisher.publish("progress-task", new JudgeProgress("RUNNING", "30 done", 30));

        verify(messagingTemplate, times(3)).convertAndSend(anyString(), any(Object.class), anyMap());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 40})
    void firstCompletedRunningSummaryBypassesTheEmptyRunningThrottleWindow(int firstCompleted) throws Exception {
        String judgeId = "first-running-result-" + firstCompleted;
        createTask(judgeId, 40, true);
        JudgeSummary empty = new JudgeSummary(40, 0, 0, 0, 0, 0, 0, 0, 0,
                null, List.of(), List.of(), null);
        publisher.publish(judgeId, new JudgeProgress("RUNNING", "execution started", 0, null, empty));

        clock.advance(Duration.ofMillis(100));
        JudgeSummary first = new JudgeSummary(40, firstCompleted, firstCompleted, 0, 0, 0, 0, 0, 0,
                null, List.of(), List.of(), null);
        publisher.publish(judgeId, new JudgeProgress("RUNNING", "first result", firstCompleted, null, first));
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(Object.class), anyMap());

        int nextCompleted = Math.min(40, firstCompleted + 1);
        JudgeSummary next = new JudgeSummary(40, nextCompleted, nextCompleted, 0, 0, 0, 0, 0, 0,
                null, List.of(), List.of(), null);
        publisher.publish(judgeId, new JudgeProgress("RUNNING", "next result", nextCompleted, null, next));
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(Object.class), anyMap());
        JudgeProgress persisted = taskStore.findSummary(judgeId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo("RUNNING");
        assertThat(persisted.getSummary().getCompletedCases()).isEqualTo(nextCompleted);
        assertThat(persisted.getSummary().getAc()).isEqualTo(nextCompleted);
        assertThat(persisted.getResults()).isNull();

        clock.advance(Duration.ofSeconds(1));
        publisher.publish(judgeId, new JudgeProgress("RUNNING", "interval elapsed", nextCompleted, null, next));
        JudgeSummary terminal = new JudgeSummary(40, 40, 40, 0, 0, 0, 0, 0, 0,
                null, List.of(), List.of(), null);
        publisher.publish(judgeId, new JudgeProgress("AC", "finished", 100, null, terminal));
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(4)).convertAndSend(eq("/topic/progress/" + judgeId), payloads.capture(), anyMap());
        List<JudgeProgress> sent = payloads.getAllValues().stream().map(JudgeProgress.class::cast).toList();
        assertThat(sent).extracting(JudgeProgress::getStatus).containsExactly("RUNNING", "RUNNING", "RUNNING", "AC");
        assertThat(sent).extracting(progress -> progress.getSummary().getCompletedCases())
                .containsExactly(0, firstCompleted, nextCompleted, 40);
        assertThat(taskStore.findSummary(judgeId).orElseThrow().getStatus()).isEqualTo("AC");
    }

    @Test
    void publishesStatusTransitionsImmediatelyInsideThrottleWindow() throws Exception {
        createTask("transition-task", 10, false);

        publisher.publish("transition-task", new JudgeProgress("PENDING", "pending", 0));
        publisher.publish("transition-task", new JudgeProgress("COMPILING", "compiling", 5));
        publisher.publish("transition-task", new JudgeProgress("RUNNING", "running", 10));
        publisher.publish("transition-task", new JudgeProgress("CANCELLED", "cancelled", 100));

        verify(messagingTemplate, times(4)).convertAndSend(anyString(), any(Object.class), anyMap());
        assertThat(taskStore.find("transition-task")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.CANCELLED);
    }

    @Test
    void websocketFailuresDoNotFailTasksAndStillPersistState() throws Exception {
        createTask("ws-failure-task", 10, false);
        doThrow(new IllegalStateException("session closed"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class), anyMap());

        assertThatCode(() -> publisher.publish("ws-failure-task", new JudgeProgress("PENDING", "pending", 0)))
                .doesNotThrowAnyException();

        assertThat(taskStore.find("ws-failure-task")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.PENDING);
        assertThat(taskStore.findSummary("ws-failure-task")).isPresent().get()
                .extracting(JudgeProgress::getStatus)
                .isEqualTo("PENDING");

        publisher.publish("ws-failure-task", new JudgeProgress("CANCELLED", "cancelled", 100));
        publisher.publish("ws-failure-task", new JudgeProgress("RUNNING", "late progress", 20));

        assertThat(taskStore.find("ws-failure-task").orElseThrow().getStatus()).isEqualTo(JudgeStatus.CANCELLED);
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(publisher, "lastSentByJudgeId")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void retriesOnlyThePersistedSnapshotAtMostTwiceWithinOneNoticeBudget(int failures) throws Exception {
        String judgeId = "notice-retry-" + failures;
        createTask(judgeId, 1, false);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() < failures) {
                throw new IllegalStateException("notification unavailable");
            }
            return null;
        }).when(messagingTemplate).convertAndSend(eq("/topic/progress/" + judgeId), any(Object.class), anyMap());

        JudgeProgress committed = publisher.publish(judgeId, new JudgeProgress("CANCELLED", "cancelled", 100));

        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Map> headers = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, times(Math.min(failures + 1, 3)))
                .convertAndSend(eq("/topic/progress/" + judgeId), payloads.capture(), headers.capture());
        assertThat(payloads.getAllValues()).allSatisfy(payload ->
                assertThat(payload).usingRecursiveComparison().isEqualTo(committed));
        Object notice = headers.getAllValues().get(0).get("progressNoticeId");
        Object deadline = headers.getAllValues().get(0).get("progressDeadlineNanos");
        assertThat(notice).isInstanceOf(String.class);
        assertThat(deadline).isInstanceOf(Long.class);
        assertThat(headers.getAllValues()).allSatisfy(header -> {
            assertThat(header.get("progressNoticeId")).isEqualTo(notice);
            assertThat(header.get("progressDeadlineNanos")).isEqualTo(deadline);
        });
        assertThat(new FileTaskStore(objectMapper, tempDir.resolve("storage"))
                .findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(committed);
        assertThat(taskStore.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.CANCELLED);
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(publisher, "lastSentByJudgeId")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AC", "CANCELLED", "BUDGET_EXCEEDED", "CE", "System Error", "OLE"})
    void terminalProgressReleasesThrottleEntriesWithoutRemovingActiveTask(String terminalStatus) throws Exception {
        createTask("active-task", 100, false);
        publisher.publish("active-task", new JudgeProgress("RUNNING", "first running", 10));
        Map<?, ?> sentStates = (Map<?, ?>) ReflectionTestUtils.getField(publisher, "lastSentByJudgeId");
        assertThat(sentStates).hasSize(1);

        for (int taskIndex = 0; taskIndex < 32; taskIndex++) {
            String judgeId = "finished-task-" + taskIndex;
            createTask(judgeId, 1, false);
            publisher.publish(judgeId, new JudgeProgress("RUNNING", "running", 10));
            assertThat(sentStates).hasSize(2);

            publisher.publish(judgeId, new JudgeProgress(terminalStatus, "finished", 100));
            publisher.publish(judgeId, new JudgeProgress("RUNNING", "late running", 50));
            publisher.publish(judgeId, new JudgeProgress(terminalStatus, "duplicate terminal", 100));

            assertThat(sentStates).hasSize(1);
            assertThat(sentStates.containsKey(judgeId)).isFalse();
            assertThat(sentStates.containsKey("active-task")).isTrue();
            assertThat(taskStore.find(judgeId).orElseThrow().getStatus())
                    .isEqualTo(JudgeStatus.fromProgressStatus(terminalStatus).orElseThrow());
            verify(messagingTemplate, times(4)).convertAndSend(eq("/topic/progress/" + judgeId), any(Object.class), anyMap());
        }

        publisher.publish("active-task", new JudgeProgress("RUNNING", "throttled running", 20));
        verify(messagingTemplate).convertAndSend(eq("/topic/progress/active-task"), any(Object.class), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "partial", "missing"})
    void delayedRunningSnapshotCannotDiscardAlreadyPersistedResults(String earlier) throws Exception {
        String judgeId = "delayed-running-" + earlier;
        createTask(judgeId, 4, false);
        ResultAggregator aggregator = new ResultAggregator(false, 4, 3, 2);
        if (earlier.equals("partial")) {
            aggregator.accept(new TestCaseResult(1, "AC", 10, 12));
        }
        JudgeProgress old = new JudgeProgress("RUNNING", "older callback", 15, null,
                earlier.equals("missing") ? null : aggregator.toSummary());
        if (!earlier.equals("partial")) {
            aggregator.accept(new TestCaseResult(1, "AC", 10, 12));
        }
        aggregator.accept(new TestCaseResult(2, "WA", 20, 14));
        CountDownLatch oldReady = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CompletableFuture<JudgeProgress> delayed = CompletableFuture.supplyAsync(() -> {
            oldReady.countDown();
            try {
                assertThat(releaseOld.await(5, TimeUnit.SECONDS)).isTrue();
                return publisher.publish(judgeId, old);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        try {
            assertThat(oldReady.await(5, TimeUnit.SECONDS)).isTrue();
            JudgeProgress current = publisher.publish(judgeId,
                    new JudgeProgress("RUNNING", "two completed results", 50, null, aggregator.toSummary()));
            assertThat(current.getSummary().getCompletedCases()).isEqualTo(2);
            assertThat(current.getSummary().getAc()).isEqualTo(1);
            assertThat(current.getSummary().getWa()).isEqualTo(1);
            assertThat(current.getSummary().getFirstFailedCase()).isEqualTo(2);
            assertThat(current.getRevision()).isEqualTo(1L);
            byte[] summaryBytes = Files.readAllBytes(taskStore.taskDirectory(judgeId).resolve("summary.json"));
            clock.advance(Duration.ofSeconds(1));
            releaseOld.countDown();
            assertThat(delayed.get(5, TimeUnit.SECONDS)).usingRecursiveComparison().isEqualTo(current);
            assertThat(Files.readAllBytes(taskStore.taskDirectory(judgeId).resolve("summary.json")))
                    .isEqualTo(summaryBytes);
            assertThat(new FileTaskStore(objectMapper, tempDir.resolve("storage"))
                    .findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(current);
            ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/progress/" + judgeId), sent.capture(), anyMap());
            assertThat(sent.getAllValues()).containsExactly(current, current);
            assertThat(taskStore.taskDirectory(judgeId).resolve("final.json")).doesNotExist();
        } finally {
            releaseOld.countDown();
        }
    }

    @Test
    void concurrentRunningAndTerminalCallbacksLeaveNoThrottleEntry() throws Exception {
        String judgeId = "concurrent-progress-task";
        createTask(judgeId, 100, false);
        CompletableFuture<Void> start = new CompletableFuture<>();
        List<CompletableFuture<JudgeProgress>> callbacks = new ArrayList<>();
        for (int callbackIndex = 0; callbackIndex < 8; callbackIndex++) {
            int progress = callbackIndex + 1;
            callbacks.add(start.thenApplyAsync(ignored ->
                    publisher.publish(judgeId, new JudgeProgress("RUNNING", "running", progress))));
        }
        callbacks.add(start.thenApplyAsync(ignored ->
                publisher.publish(judgeId, new JudgeProgress("CANCELLED", "cancelled", 100))));

        start.complete(null);
        CompletableFuture.allOf(callbacks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

        assertThat(taskStore.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.CANCELLED);
        assertThat(taskStore.findSummary(judgeId).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(publisher, "lastSentByJudgeId")).isEmpty();
        assertThat(publisher.publish(judgeId, new JudgeProgress("RUNNING", "late running", 50)).getStatus())
                .isEqualTo("CANCELLED");
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(publisher, "lastSentByJudgeId")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AC", "CANCELLED", "SYSTEM_ERROR"})
    void sendsInCommitOrderWhenEarlierPublicationIsStillSending(String terminalStatus) throws Exception {
        String judgeId = "send-order-task";
        createTask(judgeId, 40, false);
        CountDownLatch runningSendStarted = new CountDownLatch(1);
        CountDownLatch releaseRunningSend = new CountDownLatch(1);
        CountDownLatch terminalPublishStarted = new CountDownLatch(1);
        List<String> sentStatuses = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            JudgeProgress progress = invocation.getArgument(1);
            if (progress.getStatus().equals("RUNNING")) {
                runningSendStarted.countDown();
                assertThat(releaseRunningSend.await(5, TimeUnit.SECONDS)).isTrue();
            }
            sentStatuses.add(progress.getStatus());
            return null;
        }).when(messagingTemplate).convertAndSend(eq("/topic/progress/" + judgeId), any(Object.class), anyMap());
        List<CompletableFuture<JudgeProgress>> callbacks = new ArrayList<>();
        callbacks.add(CompletableFuture.supplyAsync(() ->
                publisher.publish(judgeId, new JudgeProgress("RUNNING", "running", 50))));
        try {
            assertThat(runningSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<JudgeProgress> terminal = CompletableFuture.supplyAsync(() -> {
                terminalPublishStarted.countDown();
                return publisher.publish(judgeId, new JudgeProgress(terminalStatus, "finished", 100));
            });
            callbacks.add(terminal);
            assertThat(terminalPublishStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> terminal.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(taskStore.findSummary(judgeId).orElseThrow().getStatus()).isEqualTo("RUNNING");
        } finally {
            releaseRunningSend.countDown();
            CompletableFuture.allOf(callbacks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        }
        assertThat(sentStatuses).containsExactly("RUNNING", terminalStatus);
        assertThat(taskStore.findSummary(judgeId).orElseThrow().getStatus()).isEqualTo(terminalStatus);
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(publisher, "lastSentByJudgeId")).isEmpty();
    }

    @Test
    void compileErrorAliasPersistsAsCompilationErrorAndHidesHostPaths() throws Exception {
        createTask("compile-error-task", 1, false);
        String compilerMessage = "Compilation failed for user.cpp with status RUNTIME_ERROR and exit code 1:\n"
                + "C:\\Users\\ywy\\AppData\\Local\\Temp\\online-judge\\judge-123\\user.cpp:1:1: "
                + "error: 'aa' does not name a type\n"
                + "/tmp/online-judge/judge-123/generator.cpp:2:1: error: expected declaration";

        publisher.publish("compile-error-task", new JudgeProgress("CE", compilerMessage, 100));

        assertThat(taskStore.find("compile-error-task")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.COMPILATION_ERROR);
        JudgeProgress persisted = taskStore.findSummary("compile-error-task").orElseThrow();
        assertThat(persisted.getMessage())
                .contains("user.cpp:1:1")
                .contains("generator.cpp:2:1")
                .doesNotContain("C:\\Users")
                .doesNotContain("/tmp/online-judge")
                .doesNotContain("AppData");

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(anyString(), payloadCaptor.capture(), anyMap());
        JudgeProgress sent = (JudgeProgress) payloadCaptor.getValue();
        assertThat(sent.getMessage()).isEqualTo(persisted.getMessage());
    }

    @Test
    void highVolumeFinalPayloadDoesNotContainFullResults() throws Exception {
        createTask("large-final-task", 100_000, true);
        List<TestCaseResult> fullResults = new ArrayList<>(100_000);
        for (int i = 1; i <= 100_000; i++) {
            fullResults.add(new TestCaseResult(i, "AC", 1, 1));
        }
        JudgeSummary summary = new JudgeSummary(
                100_000,
                100_000,
                100_000,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                List.of(),
                List.of(),
                null
        );

        publisher.publish("large-final-task", new JudgeProgress("AC", "done", 100, fullResults, summary));

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(anyString(), payloadCaptor.capture(), anyMap());
        JudgeProgress sent = (JudgeProgress) payloadCaptor.getValue();
        assertThat(sent.getResults()).isNull();
        assertThat(objectMapper.writeValueAsBytes(sent).length).isLessThan(65_536);
        assertThat(taskStore.findSummary("large-final-task")).isPresent().get()
                .extracting(JudgeProgress::getResults)
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 40, 1_000})
    void runningPayloadOmitsAllInlineResultsWhileSmallTerminalCompatibilityRemains(int resultCount) throws Exception {
        String judgeId = "running-inline-" + resultCount;
        int totalCases = Math.max(1, resultCount);
        createTask(judgeId, totalCases, false);
        List<TestCaseResult> results = new ArrayList<>();
        for (int caseId = 1; caseId <= resultCount; caseId++) {
            results.add(new TestCaseResult(caseId, "AC", 1, 64));
        }
        List<JudgeProgressEvent> samples = results.isEmpty() ? List.of() : List.of(JudgeProgressEvent.from(results.get(0)));
        JudgeSummary summary = new JudgeSummary(totalCases, resultCount, resultCount, 0, 0, 0, 0, 0, 0,
                null, List.of(), samples, null);

        JudgeProgress running = publisher.publish(judgeId,
                new JudgeProgress("RUNNING", "running", 50, results, summary));

        assertThat(running.getResults()).isNull();
        assertThat(running.getSummary()).isEqualTo(summary);
        assertThat(taskStore.findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(running);
        assertThat(taskStore.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.RUNNING);
        assertThat(taskStore.taskDirectory(judgeId).resolve("final.json")).doesNotExist();

        String terminalStatus = resultCount == 0 ? "CANCELLED" : "AC";
        JudgeProgress terminal = publisher.publish(judgeId,
                new JudgeProgress(terminalStatus, "finished", 100, results, summary));
        assertThat(terminal.getResults()).containsExactlyElementsOf(results);
        assertThat(terminal.getSummary()).isEqualTo(summary);
        assertThat(taskStore.findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(terminal);
        assertThat(taskStore.taskDirectory(judgeId).resolve("final.json")).exists();
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/progress/" + judgeId), payloads.capture(), anyMap());
        assertThat(payloads.getAllValues()).containsExactly(running, terminal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AC", "WA", "RUNNING"})
    void lateProgressReturnsStoredTerminalSnapshotWithoutReplacingStatistics(String lateStatus) throws Exception {
        String judgeId = "terminal-snapshot";
        createTask(judgeId, 4, false);
        taskStore.updateStatus(judgeId, JudgeStatus.RUNNING, "running");
        JudgeSummary summary = new JudgeSummary(4, 3, 2, 1, 0, 0, 0, 0, 0,
                2, List.of(), List.of(), "Cancellation requested");
        JudgeProgress first = publisher.publish(judgeId,
                new JudgeProgress("CANCELLED", "first terminal message", 75, null, summary));
        Path workDir = taskStore.taskDirectory(judgeId);
        byte[] metadataBefore = Files.readAllBytes(workDir.resolve("metadata.json"));
        byte[] eventsBefore = Files.readAllBytes(workDir.resolve("events.jsonl"));
        byte[] summaryBefore = Files.readAllBytes(workDir.resolve("summary.json"));
        JudgeProgress late = new JudgeProgress(lateStatus, "late replacement", 100);

        taskStore.saveSummary(judgeId, late);
        assertThat(Files.readAllBytes(workDir.resolve("summary.json"))).isEqualTo(summaryBefore);
        JudgeProgress published = publisher.publish(judgeId, late);

        assertThat(published).usingRecursiveComparison().isEqualTo(first);
        assertThat(new FileTaskStore(objectMapper, tempDir.resolve("storage")).findSummary(judgeId).orElseThrow())
                .usingRecursiveComparison().isEqualTo(first);
        assertThat(Files.readAllBytes(workDir.resolve("metadata.json"))).isEqualTo(metadataBefore);
        assertThat(Files.readAllBytes(workDir.resolve("events.jsonl"))).isEqualTo(eventsBefore);
        assertThat(Files.readAllBytes(workDir.resolve("summary.json"))).isEqualTo(summaryBefore);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), payloadCaptor.capture(), anyMap());
        for (Object payload : payloadCaptor.getAllValues()) {
            assertThat(payload).usingRecursiveComparison().isEqualTo(first);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"final.json"})
    void persistenceFailureDoesNotPublishSuccessAndCanRetry(String failingFile) throws Exception {
        AtomicBoolean failWrites = new AtomicBoolean();
        taskStore = new FileTaskStore(objectMapper, tempDir.resolve("storage")) {
            @Override
            protected void moveAtomically(Path tempFile, Path targetFile) throws IOException {
                if (failWrites.get() && targetFile.getFileName().toString().equals(failingFile)) {
                    throw new IOException("Injected replacement failure for " + failingFile);
                }
                super.moveAtomically(tempFile, targetFile);
            }
        };
        publisher = new ProgressPublisher(messagingTemplate, taskStore, objectMapper, clock, Duration.ofSeconds(1));
        String judgeId = "persistence-failure";
        createTask(judgeId, 4, false);
        taskStore.updateStatus(judgeId, JudgeStatus.RUNNING, "running");
        JudgeSummary summary = new JudgeSummary(4, 3, 2, 1, 0, 0, 0, 0, 0,
                2, List.of(), List.of(), "Cancellation requested");
        taskStore.saveSummary(judgeId, new JudgeProgress("RUNNING", "partial progress", 75, null, summary));
        Path summaryFile = taskStore.taskDirectory(judgeId).resolve("summary.json");
        byte[] summaryBefore = Files.readAllBytes(summaryFile);
        JudgeProgress terminal = new JudgeProgress("CANCELLED", "cancelled", 75, null, summary);
        failWrites.set(true);

        assertThatThrownBy(() -> publisher.publish(judgeId, terminal)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to persist judge progress").hasCauseInstanceOf(IOException.class);
        verifyNoInteractions(messagingTemplate);
        assertThat(Files.readAllBytes(summaryFile)).isEqualTo(summaryBefore);

        failWrites.set(false);
        JudgeProgress retried = publisher.publish(judgeId, terminal);
        assertThat(retried).usingRecursiveComparison().ignoringFields("attemptId", "revision", "finalizedAt")
                .isEqualTo(terminal);
        assertThat(retried.getAttemptId()).isNull();
        assertThat(retried.getRevision()).isEqualTo(2L);
        assertThat(retried.getFinalizedAt()).isNotNull();
        assertThat(taskStore.findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(retried);
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"metadata.json", "summary.json"})
    void projectionFailureStillPublishesTheCommittedFinalAndRepairsOnRetry(String failingFile) throws Exception {
        AtomicBoolean failWrites = new AtomicBoolean();
        taskStore = new FileTaskStore(objectMapper, tempDir.resolve("storage")) {
            @Override
            protected void moveAtomically(Path temporary, Path target) throws IOException {
                if (failWrites.get() && target.getFileName().toString().equals(failingFile)) {
                    throw new IOException("injected projection failure");
                }
                super.moveAtomically(temporary, target);
            }
        };
        publisher = new ProgressPublisher(messagingTemplate, taskStore, objectMapper, clock, Duration.ofSeconds(1));
        createTask("projection-failure", 4, false);
        failWrites.set(true);
        JudgeProgress committed = publisher.publish("projection-failure", new JudgeProgress("CANCELLED", "cancelled", 0));
        assertThat(committed.getRevision()).isEqualTo(1L);
        assertThat(committed.getFinalizedAt()).isNotNull();
        assertThat(new FileTaskStore(objectMapper, tempDir.resolve("storage")).findSummary("projection-failure")
                .orElseThrow()).usingRecursiveComparison().isEqualTo(committed);
        verify(messagingTemplate).convertAndSend(anyString(), eq(committed), anyMap());
        failWrites.set(false);
        assertThat(publisher.publish("projection-failure", new JudgeProgress("RUNNING", "late", 10)))
                .usingRecursiveComparison().isEqualTo(committed);
        JsonNode finalJson = objectMapper.readTree(taskStore.taskDirectory("projection-failure").resolve("final.json").toFile());
        assertThat(objectMapper.readTree(taskStore.taskDirectory("projection-failure").resolve("summary.json").toFile()))
                .isEqualTo(finalJson);
    }

    private void createTask(String judgeId, int requestedCases, boolean highVolume) throws IOException {
        Path workDir = taskStore.taskDirectory(judgeId);
        taskStore.create(JudgeTask.builder()
                .judgeId(judgeId)
                .status(JudgeStatus.CREATED)
                .requestedCases(requestedCases)
                .mode("trusted-local")
                .policy(new ResolvedTaskPolicy(
                        "trusted-local",
                        highVolume,
                        requestedCases,
                        requestedCases,
                        100,
                        4,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        64L * 1024 * 1024,
                        1024L * 1024,
                        false
                ))
                .workDir(workDir.toString())
                .createdAt(Instant.now(clock))
                .build());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void concurrentCacheOverflowPreservesEveryDurableSnapshotAndReleasesCapacity(int capacity) throws Exception {
        configureCache(capacity, Duration.ofMinutes(30), Duration.ofSeconds(1));
        List<String> judgeIds = new ArrayList<>();
        CompletableFuture<Void> start = new CompletableFuture<>();
        List<CompletableFuture<JudgeProgress>> callbacks = new ArrayList<>();
        for (int taskIndex = 0; taskIndex < 16; taskIndex++) {
            String judgeId = "cache-limit-" + taskIndex;
            createTask(judgeId, 1, false);
            judgeIds.add(judgeId);
            callbacks.add(start.thenApplyAsync(ignored -> {
                JudgeProgress persisted = publisher.publish(judgeId, new JudgeProgress("RUNNING", "running", 20));
                assertThat(cacheStates().size()).isLessThanOrEqualTo(capacity);
                return persisted;
            }));
        }
        start.complete(null);
        CompletableFuture.allOf(callbacks.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        assertThat(cacheStates()).hasSize(capacity);
        assertThat(availableCacheSlots()).isZero();
        verify(messagingTemplate, times(judgeIds.size())).convertAndSend(anyString(), any(Object.class), anyMap());
        FileTaskStore coldStore = new FileTaskStore(objectMapper, tempDir.resolve("storage"));
        for (int taskIndex = 0; taskIndex < judgeIds.size(); taskIndex++) {
            String judgeId = judgeIds.get(taskIndex);
            assertThat(coldStore.findSummary(judgeId).orElseThrow())
                    .usingRecursiveComparison().isEqualTo(callbacks.get(taskIndex).get());
            JudgeProgress terminal = publisher.publish(judgeId, new JudgeProgress("AC", "accepted", 100));
            assertThat(coldStore.findSummary(judgeId).orElseThrow())
                    .usingRecursiveComparison().isEqualTo(terminal);
        }
        assertThat(cacheStates()).isEmpty();
        assertThat(availableCacheSlots()).isEqualTo(capacity);
        createTask("cache-after-terminal", 1, false);
        publisher.publish("cache-after-terminal", new JudgeProgress("RUNNING", "running", 10));
        assertThat(cacheStates()).hasSize(1);
        assertThat(availableCacheSlots()).isEqualTo(capacity - 1);
    }

    @ParameterizedTest
    @ValueSource(longs = {1_799_999, 1_800_000, 1_800_001})
    void idleTtlBoundaryReleasesOnlyRebuildableCacheWithoutChangingFiles(long idleMillis) throws Exception {
        configureCache(1, Duration.ofMinutes(30), Duration.ofSeconds(1));
        String judgeId = "idle-cache-task";
        createTask(judgeId, 1, false);
        JudgeProgress persisted = publisher.publish(judgeId, new JudgeProgress("RUNNING", "running", 20));
        Path taskDirectory = taskStore.taskDirectory(judgeId);
        byte[] metadata = Files.readAllBytes(taskDirectory.resolve("metadata.json"));
        byte[] summary = Files.readAllBytes(taskDirectory.resolve("summary.json"));
        clock.advance(Duration.ofMillis(idleMillis));
        publisher.expireThrottleState();
        assertThat(cacheStates().size()).isEqualTo(idleMillis < 1_800_000 ? 1 : 0);
        assertThat(availableCacheSlots()).isEqualTo(idleMillis < 1_800_000 ? 0 : 1);
        assertThat(Files.readAllBytes(taskDirectory.resolve("metadata.json"))).isEqualTo(metadata);
        assertThat(Files.readAllBytes(taskDirectory.resolve("summary.json"))).isEqualTo(summary);
        assertThat(taskDirectory.resolve("final.json")).doesNotExist();
        assertThat(new FileTaskStore(objectMapper, tempDir.resolve("storage")).findSummary(judgeId).orElseThrow())
                .usingRecursiveComparison().isEqualTo(persisted);
        publisher.publish(judgeId, new JudgeProgress("RUNNING", "updated running", 30));
        assertThat(cacheStates()).hasSize(1);
        assertThat(availableCacheSlots()).isZero();
        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(Object.class), anyMap());
    }

    @Test
    void throttledActivityRenewsIdleTtlWithoutResettingTheSendInterval() throws Exception {
        configureCache(1, Duration.ofMinutes(30), Duration.ofHours(1));
        createTask("touched-cache-task", 1, false);
        publisher.publish("touched-cache-task", new JudgeProgress("RUNNING", "running", 10));
        clock.advance(Duration.ofMinutes(29));
        JudgeProgress persisted = publisher.publish("touched-cache-task", new JudgeProgress("RUNNING", "updated", 20));
        clock.advance(Duration.ofMinutes(29));
        publisher.expireThrottleState();
        assertThat(cacheStates()).hasSize(1);
        assertThat(availableCacheSlots()).isZero();
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class), anyMap());
        clock.advance(Duration.ofMinutes(1));
        publisher.expireThrottleState();
        assertThat(cacheStates()).isEmpty();
        assertThat(availableCacheSlots()).isEqualTo(1);
        assertThat(new FileTaskStore(objectMapper, tempDir.resolve("storage"))
                .findSummary("touched-cache-task").orElseThrow()).usingRecursiveComparison().isEqualTo(persisted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "list-failure"})
    void existingMaintenanceExpiresIdleCacheEvenWhenTaskListingFails(String outcome) throws Exception {
        taskStore = new FileTaskStore(objectMapper, tempDir.resolve("storage")) {
            @Override
            public List<JudgeTask> findAll() throws IOException {
                if (outcome.equals("list-failure")) {
                    throw new IOException("Injected task listing failure");
                }
                return super.findAll();
            }
        };
        configureCache(1, Duration.ofMinutes(30), Duration.ofSeconds(1));
        String judgeId = "maintenance-cache-task";
        createTask(judgeId, 1, false);
        publisher.publish(judgeId, new JudgeProgress("RUNNING", "running", 20));
        Path taskDirectory = taskStore.taskDirectory(judgeId);
        byte[] metadata = Files.readAllBytes(taskDirectory.resolve("metadata.json"));
        byte[] summary = Files.readAllBytes(taskDirectory.resolve("summary.json"));
        clock.advance(Duration.ofMinutes(30));
        TaskCleanupService cleanup = new TaskCleanupService(taskStore, new ExecutionProperties(), Optional.empty(),
                Optional.of(publisher));
        TaskCleanupService.CleanupReport report = cleanup.cleanupExpiredTasks(clock.instant());
        assertThat(report.deletedJudgeIds()).isEmpty();
        assertThat(report.inspectedTasks()).isEqualTo(outcome.equals("normal") ? 1 : 0);
        assertThat(cacheStates()).isEmpty();
        assertThat(availableCacheSlots()).isEqualTo(1);
        assertThat(Files.readAllBytes(taskDirectory.resolve("metadata.json"))).isEqualTo(metadata);
        assertThat(Files.readAllBytes(taskDirectory.resolve("summary.json"))).isEqualTo(summary);
        assertThat(taskDirectory.resolve("final.json")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expiry", "clear", "full"})
    void cacheExpiryAndOverflowKeepInFlightRunningBeforeTerminal(String scenario) throws Exception {
        configureCache(1, Duration.ofMinutes(30), Duration.ofSeconds(1));
        String judgeId = "cache-order-task";
        createTask(judgeId, 1, false);
        if (scenario.equals("full")) {
            createTask("cache-owner", 1, false);
            publisher.publish("cache-owner", new JudgeProgress("RUNNING", "running", 10));
        } else {
            publisher.publish(judgeId, new JudgeProgress("RUNNING", "running", 10));
        }
        clock.advance(scenario.equals("expiry") ? Duration.ofMinutes(30) : Duration.ofSeconds(2));
        CountDownLatch runningSendStarted = new CountDownLatch(1);
        CountDownLatch releaseRunningSend = new CountDownLatch(1);
        CountDownLatch terminalPublishStarted = new CountDownLatch(1);
        List<String> statuses = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            JudgeProgress progress = invocation.getArgument(1);
            if (progress.getStatus().equals("RUNNING")) {
                runningSendStarted.countDown();
                assertThat(releaseRunningSend.await(5, TimeUnit.SECONDS)).isTrue();
            }
            statuses.add(progress.getStatus());
            return null;
        }).when(messagingTemplate).convertAndSend(eq("/topic/progress/" + judgeId), any(Object.class), anyMap());
        List<CompletableFuture<?>> callbacks = new ArrayList<>();
        callbacks.add(CompletableFuture.supplyAsync(() ->
                publisher.publish(judgeId, new JudgeProgress("RUNNING", "updated running", 20))));
        try {
            assertThat(runningSendStarted.await(5, TimeUnit.SECONDS)).isTrue();
            if (!scenario.equals("full")) {
                callbacks.add(CompletableFuture.runAsync(() -> {
                    if (scenario.equals("expiry")) {
                        publisher.expireThrottleState();
                    } else {
                        publisher.clearThrottleState(judgeId);
                    }
                }));
            }
            CompletableFuture<JudgeProgress> terminal = CompletableFuture.supplyAsync(() -> {
                terminalPublishStarted.countDown();
                return publisher.publish(judgeId, new JudgeProgress("AC", "accepted", 100));
            });
            callbacks.add(terminal);
            assertThat(terminalPublishStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> terminal.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(taskStore.findSummary(judgeId).orElseThrow().getStatus()).isEqualTo("RUNNING");
        } finally {
            releaseRunningSend.countDown();
            CompletableFuture.allOf(callbacks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        }
        assertThat(statuses).containsExactly("RUNNING", "AC");
        assertThat(taskStore.findSummary(judgeId).orElseThrow().getStatus()).isEqualTo("AC");
        assertThat(cacheStates().size()).isEqualTo(scenario.equals("full") ? 1 : 0);
        assertThat(availableCacheSlots()).isEqualTo(scenario.equals("full") ? 0 : 1);
        publisher.clearThrottleState("cache-owner");
        publisher.clearThrottleState("cache-owner");
        assertThat(availableCacheSlots()).isEqualTo(1);
        createTask("cache-after-race", 1, false);
        publisher.publish("cache-after-race", new JudgeProgress("RUNNING", "running", 10));
        JudgeProgress late = publisher.publish(judgeId, new JudgeProgress("RUNNING", "late running", 50));
        assertThat(late.getStatus()).isEqualTo("AC");
        assertThat(cacheStates().keySet()).isEqualTo(Collections.singleton("cache-after-race"));
        assertThat(availableCacheSlots()).isZero();
    }

    private void configureCache(int capacity, Duration ttl, Duration interval) {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setProgressCacheMaxEntries(capacity);
        properties.setProgressCacheTtl(ttl);
        publisher = new ProgressPublisher(messagingTemplate, taskStore, objectMapper, clock, interval, properties);
    }

    private Map<?, ?> cacheStates() {
        return (Map<?, ?>) ReflectionTestUtils.getField(publisher, "lastSentByJudgeId");
    }

    private int availableCacheSlots() {
        return ((Semaphore) ReflectionTestUtils.getField(publisher, "cacheSlots")).availablePermits();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
