package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.SandboxEventIngestor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SandboxEventIngestorTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private FileTaskStore taskStore;
    private SimpMessagingTemplate messagingTemplate;
    private SandboxEventIngestor ingestor;

    @BeforeEach
    void setUp() {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setProgressPublishInterval(Duration.ofSeconds(1));
        properties.setMaxFailureSamples(5);
        properties.setMaxSlowSamples(5);
        taskStore = new FileTaskStore(objectMapper, tempDir.resolve("storage"));
        messagingTemplate = mock(SimpMessagingTemplate.class);
        ProgressPublisher publisher = new ProgressPublisher(
                messagingTemplate,
                taskStore,
                objectMapper,
                new FixedClock(Instant.parse("2026-07-03T00:00:00Z")),
                properties.getProgressPublishInterval()
        );
        ingestor = new SandboxEventIngestor(publisher, properties);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"foreign-task", "missing-id", "missing-type"})
    void invalidEventCannotMutateOwnedTaskOrPublishProgress(String shape) throws Exception {
        String judgeId = "ingest-owned";
        ResolvedTaskPolicy policy = policy(3, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        session.accept(runFinished(judgeId, 1, "AC"));
        byte[] before = objectMapper.writeValueAsBytes(List.of(taskStore.find(judgeId).orElseThrow(),
                taskStore.findSummary(judgeId).orElseThrow()));
        clearInvocations(messagingTemplate);
        SandboxTaskEvent invalid = shape == null ? null : switch (shape) {
            case "foreign-task" -> SandboxTaskEvent.of("another-task", SandboxTaskEvent.Type.COMPLETED, "invalid");
            case "missing-id" -> SandboxTaskEvent.of(null, SandboxTaskEvent.Type.COMPLETED, "invalid");
            case "missing-type" -> SandboxTaskEvent.of(judgeId, null, "invalid");
            default -> throw new IllegalArgumentException("unknown test shape");
        };

        assertThatThrownBy(() -> session.accept(invalid)).isInstanceOf(IllegalArgumentException.class);

        assertThat(objectMapper.writeValueAsBytes(List.of(taskStore.find(judgeId).orElseThrow(),
                taskStore.findSummary(judgeId).orElseThrow()))).isEqualTo(before);
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void hundredThousandRunnerEventsProduceBoundedSummarySamplesAndWebsocketMessages() throws Exception {
        String judgeId = "ingest-large";
        ResolvedTaskPolicy policy = policy(100_000, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        Map<Integer, String> failures = Map.of(
                3, "WA",
                10, "TLE",
                20, "MLE",
                30, "RE",
                40, "System Error",
                99_999, "WA"
        );

        for (int caseNumber = 100_000; caseNumber >= 1; caseNumber--) {
            session.accept(runFinished(judgeId, caseNumber, failures.getOrDefault(caseNumber, "AC")));
        }
        JudgeProgress finalProgress = session.accept(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED, "runner completed"));

        JudgeProgress persisted = taskStore.findSummary(judgeId).orElseThrow();
        JudgeSummary summary = persisted.getSummary();
        assertThat(finalProgress.getResults()).isNull();
        assertThat(persisted.getResults()).isNull();
        assertThat(summary.getTotalCases()).isEqualTo(100_000);
        assertThat(summary.getCompletedCases()).isEqualTo(100_000);
        assertThat(summary.getAc()).isEqualTo(99_994);
        assertThat(summary.getWa()).isEqualTo(2);
        assertThat(summary.getTle()).isEqualTo(1);
        assertThat(summary.getMle()).isEqualTo(1);
        assertThat(summary.getRe()).isEqualTo(1);
        assertThat(summary.getSystemError()).isEqualTo(1);
        assertThat(summary.getFirstFailedCase()).isEqualTo(3);
        assertThat(summary.getFailureSamples()).hasSize(5);
        assertThat(summary.getFailureSamples()).extracting(event -> event.getCaseNumber())
                .containsExactly(3, 10, 20, 30, 40);
        assertThat(objectMapper.writeValueAsBytes(persisted).length).isLessThan(65_536);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atMost(3)).convertAndSend(anyString(), payloadCaptor.capture());
        for (Object payload : payloadCaptor.getAllValues()) {
            assertThat(objectMapper.writeValueAsBytes(payload).length).isLessThan(65_536);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-result", "missing-case", "zero-case", "out-of-range", "mismatched-case",
            "missing-status", "mismatched-status", "unknown-status", "negative-time", "negative-memory", "duplicate"})
    void invalidCaseResultCannotChangeStatisticsOrPublish(String shape) throws Exception {
        String judgeId = "invalid-case";
        ResolvedTaskPolicy policy = policy(3, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        session.accept(runFinished(judgeId, 1, "AC"));
        Integer caseNumber = 2;
        String status = "AC";
        TestCaseResult result = new TestCaseResult(2, "AC", 2, 512);
        switch (shape) {
            case "missing-result" -> result = null;
            case "missing-case" -> caseNumber = null;
            case "zero-case" -> { caseNumber = 0; result.setCaseNumber(0); }
            case "out-of-range" -> { caseNumber = 4; result.setCaseNumber(4); }
            case "mismatched-case" -> result.setCaseNumber(3);
            case "missing-status" -> { status = null; result.setStatus(null); }
            case "mismatched-status" -> result.setStatus("WA");
            case "unknown-status" -> { status = "UNKNOWN"; result.setStatus(status); }
            case "negative-time" -> result.setTimeUsed(-1);
            case "negative-memory" -> result.setMemoryUsed(-1);
            case "duplicate" -> { caseNumber = 1; result.setCaseNumber(1); }
            default -> throw new IllegalArgumentException("unknown test shape");
        }
        SandboxTaskEvent invalid = new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.RUN_FINISHED,
                Instant.now(), caseNumber, status, "invalid", result, null);
        assertRejectedWithoutPublication(judgeId, session, invalid);

        session.accept(runFinished(judgeId, 2, "AC"));
        session.accept(runFinished(judgeId, 3, "AC"));
        JudgeSummary summary = session.accept(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED,
                "completed")).getSummary();
        assertThat(summary.getCompletedCases()).isEqualTo(3);
        assertThat(summary.getAc()).isEqualTo(3);
        assertThat(summary.getFirstFailedCase()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-summary", "total", "completed", "classification", "first-failure",
            "failure-sample", "slow-sample", "sample-limit", "duplicate-samples", "status", "stopped-reason"})
    void contradictorySummaryCannotReplaceTrustedStatistics(String shape) throws Exception {
        String judgeId = "invalid-summary";
        ResolvedTaskPolicy policy = policy(2, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        session.accept(runFinished(judgeId, 1, "AC"));
        JudgeSummary trusted = session.accept(runFinished(judgeId, 2, "WA")).getSummary();
        JudgeSummary reported = objectMapper.readValue(objectMapper.writeValueAsBytes(trusted), JudgeSummary.class);
        String status = "DONE";
        switch (shape) {
            case "missing-summary" -> reported = null;
            case "total" -> reported.setTotalCases(3);
            case "completed" -> reported.setCompletedCases(3);
            case "classification" -> { reported.setAc(2); reported.setWa(0); }
            case "first-failure" -> reported.setFirstFailedCase(1);
            case "failure-sample" -> reported.getFailureSamples().get(0).setCaseNumber(1);
            case "slow-sample" -> reported.getSlowSamples().get(0).setTimeUsed(999);
            case "sample-limit" -> reported.setFailureSamples(java.util.Collections.nCopies(6,
                    reported.getFailureSamples().get(0)));
            case "duplicate-samples" -> reported.setFailureSamples(java.util.Collections.nCopies(2,
                    reported.getFailureSamples().get(0)));
            case "status" -> status = "AC";
            case "stopped-reason" -> reported.setStoppedReason("untrusted stop");
            default -> throw new IllegalArgumentException("unknown test shape");
        }
        assertRejectedWithoutPublication(judgeId, session, new SandboxTaskEvent(judgeId,
                SandboxTaskEvent.Type.SUMMARY, Instant.now(), null, status, "invalid", null, reported));

        JudgeProgress completed = session.accept(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED,
                "completed"));
        assertThat(completed.getStatus()).isEqualTo("WA");
        assertThat(completed.getSummary()).isEqualTo(trusted);
    }

    @Test
    void validSummaryIsNonTerminalAndOnlyPublishesHostAggregatedSamples() throws Exception {
        String judgeId = "valid-summary";
        ResolvedTaskPolicy policy = policy(2, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        session.accept(runFinished(judgeId, 1, "AC"));
        JudgeSummary trusted = session.accept(runFinished(judgeId, 2, "WA")).getSummary();
        JudgeSummary reported = objectMapper.readValue(objectMapper.writeValueAsBytes(trusted), JudgeSummary.class);
        reported.setFailureSamples(List.of());
        reported.setSlowSamples(List.of());

        JudgeProgress summaryProgress = session.accept(new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.SUMMARY,
                Instant.now(), null, "DONE", "summary", null, reported));

        assertThat(session.isTerminal(summaryProgress)).isFalse();
        assertThat(summaryProgress.getProgress()).isLessThan(100);
        assertThat(summaryProgress.getSummary()).isEqualTo(trusted).isNotSameAs(reported);
        assertThat(taskStore.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.RUNNING);
        JudgeProgress completed = session.accept(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED,
                "completed"));
        assertThat(completed.getStatus()).isEqualTo("WA");
        assertThat(completed.getSummary().getFailureSamples()).hasSize(1);
        assertThat(completed.getSummary().getSlowSamples()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = SandboxTaskEvent.Type.class, names = {"SUMMARY", "COMPLETED"})
    void prematureCompletionCannotClaimMissingAcceptedCases(SandboxTaskEvent.Type type) throws Exception {
        String judgeId = "premature-completion";
        ResolvedTaskPolicy policy = policy(2, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        session.accept(runFinished(judgeId, 1, "AC"));
        JudgeSummary fabricated = new JudgeSummary(2, 2, 2, 0, 0, 0, 0, 0, 0,
                null, List.of(), List.of(), null);

        assertRejectedWithoutPublication(judgeId, session, new SandboxTaskEvent(judgeId, type,
                Instant.now(), null, "AC", "premature", null, fabricated));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void earlyCompletionRequiresTheOriginalStopOnFirstFailurePolicy(boolean stopOnFirstNonAc) throws Exception {
        String judgeId = "early-completion";
        ResolvedTaskPolicy policy = policy(3, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy, stopOnFirstNonAc);
        session.accept(runFinished(judgeId, 1, "AC"));
        JudgeSummary reported = session.accept(runFinished(judgeId, 2, "WA")).getSummary();
        reported.setStoppedReason("Stopped after first non-AC test case");
        SandboxTaskEvent summaryEvent = new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.SUMMARY,
                Instant.now(), null, "DONE", "summary", null, reported);

        if (stopOnFirstNonAc) {
            assertThat(session.isTerminal(session.accept(summaryEvent))).isFalse();
            JudgeProgress completed = session.accept(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED,
                    "completed"));
            assertThat(completed.getStatus()).isEqualTo("WA");
            assertThat(completed.getSummary().getCompletedCases()).isEqualTo(2);
            assertThat(completed.getSummary().getStoppedReason()).isEqualTo(reported.getStoppedReason());
        } else {
            assertRejectedWithoutPublication(judgeId, session, summaryEvent);
            assertRejectedWithoutPublication(judgeId, session,
                    SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED, "completed"));
        }
    }

    @Test
    void earlyCompletionCannotSkipAnEarlierCase() throws Exception {
        String judgeId = "missing-earlier-case";
        ResolvedTaskPolicy policy = policy(3, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy, true);
        session.accept(runFinished(judgeId, 2, "WA"));

        assertRejectedWithoutPublication(judgeId, session,
                SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED, "completed"));
    }

    @ParameterizedTest
    @EnumSource(value = SandboxTaskEvent.Type.class, names = {"CANCELLED", "BUDGET_EXCEEDED", "SYSTEM_ERROR",
            "SECURITY_VIOLATION", "SANDBOX_UNAVAILABLE"})
    void stoppingPreservesAggregateAndIgnoresLateResultsOrTerminalCallbacks(SandboxTaskEvent.Type type) throws Exception {
        String judgeId = "ingest-stop";
        ResolvedTaskPolicy policy = policy(3, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        SandboxRunHandle handle = v2Handle(judgeId);
        session.bindRunHandle(handle);
        session.accept(withEnvelope(runFinished(judgeId, 1, "AC"), handle, 1));
        JudgeProgress running = session.accept(withEnvelope(runFinished(judgeId, 2, "WA"), handle, 2));
        String message = type == SandboxTaskEvent.Type.CANCELLED ? "Task cancelled" : "runner stopped";
        String stoppedReason = switch (type) {
            case CANCELLED -> "Cancellation requested";
            case BUDGET_EXCEEDED -> "Task runtime budget exceeded";
            default -> message;
        };
        JudgeSummary expected = objectMapper.readValue(objectMapper.writeValueAsBytes(running.getSummary()), JudgeSummary.class);
        expected.setStoppedReason(stoppedReason);
        clearInvocations(messagingTemplate);

        JudgeProgress terminal = session.accept(withEnvelope(SandboxTaskEvent.of(judgeId, type, message), handle, 3));

        assertThat(terminal.getStatus()).isEqualTo(type.name());
        assertThat(terminal.getSummary()).usingRecursiveComparison().isEqualTo(expected);
        assertThat(terminal.getSummary().getCompletedCases()).isEqualTo(2);
        assertThat(terminal.getSummary().getAc()).isEqualTo(1);
        assertThat(terminal.getSummary().getWa()).isEqualTo(1);
        assertThat(terminal.getSummary().getFirstFailedCase()).isEqualTo(2);
        assertThat(terminal.getSummary().getFailureSamples()).hasSize(1);
        assertThat(terminal.getSummary().getSlowSamples()).hasSize(2);
        assertThat(running.getSummary().getStoppedReason()).isNull();
        JudgeTask finished = taskStore.find(judgeId).orElseThrow();
        assertThat(session.accept(withEnvelope(runFinished(judgeId, 3, "AC"), handle, 4))).isSameAs(terminal);
        assertThat(session.accept(withEnvelope(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.RUN_STARTED,
                "late running"), handle, 4))).isSameAs(terminal);
        assertThat(session.accept(withEnvelope(SandboxTaskEvent.of(judgeId, type, "duplicate stop"), handle, 3)))
                .isSameAs(terminal);
        assertThat(session.terminal("SYSTEM_ERROR", "late callback")).isSameAs(terminal);
        assertThat(taskStore.findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(terminal);
        assertThat(taskStore.find(judgeId).orElseThrow().getFinishedAt()).isEqualTo(finished.getFinishedAt());
        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void concurrentStopCallbacksCommitAndPublishOneRealSummary() throws Exception {
        String judgeId = "ingest-stop-race";
        ResolvedTaskPolicy policy = policy(3, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        session.accept(runFinished(judgeId, 1, "AC"));
        session.accept(runFinished(judgeId, 2, "WA"));
        clearInvocations(messagingTemplate);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var cancelled = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
                return session.terminal("CANCELLED", "Task cancelled");
            });
            var budgetExceeded = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
                return session.terminal("BUDGET_EXCEEDED", "Task runtime budget exceeded");
            });
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            JudgeProgress terminal = cancelled.get(2, TimeUnit.SECONDS);
            assertThat(budgetExceeded.get(2, TimeUnit.SECONDS)).isSameAs(terminal);
            assertThat(terminal.getStatus()).isIn("CANCELLED", "BUDGET_EXCEEDED");
            assertThat(terminal.getSummary().getCompletedCases()).isEqualTo(2);
            assertThat(terminal.getSummary().getAc()).isEqualTo(1);
            assertThat(terminal.getSummary().getWa()).isEqualTo(1);
            assertThat(taskStore.findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(terminal);
            verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OUTPUT_LIMIT_EXCEEDED", "OLE"})
    void outputLimitCompletionPersistsTerminalStatusAndRoundTrips(String status) throws Exception {
        String judgeId = "ingest-output-limit";
        ResolvedTaskPolicy policy = policy(1, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        session.accept(runFinished(judgeId, 1, status));

        JudgeProgress completed = session.accept(SandboxTaskEvent.of(judgeId,
                SandboxTaskEvent.Type.COMPLETED, "runner completed"));

        assertThat(completed.getStatus()).isEqualTo(status);
        assertThat(session.isTerminal(completed)).isTrue();
        assertThat(completed.getSummary().getOutputLimitExceeded()).isEqualTo(1);
        assertThat(completed.getSummary().getAc()).isZero();
        assertThat(completed.getSummary().getSystemError()).isZero();
        FileTaskStore reloaded = new FileTaskStore(objectMapper, tempDir.resolve("storage"));
        assertThat(reloaded.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.OUTPUT_LIMIT_EXCEEDED);
        assertThat(reloaded.findSummary(judgeId).orElseThrow()).isEqualTo(completed);
    }

    @Test
    void terminalSystemErrorPublishesImmediatelyAndPersistsTerminalStatus() throws Exception {
        String judgeId = "ingest-terminal";
        ResolvedTaskPolicy policy = policy(10, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);

        session.accept(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.RUN_STARTED, "running"));
        JudgeProgress progress = session.accept(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.SYSTEM_ERROR, "worker crashed"));

        assertThat(progress.getStatus()).isEqualTo("SYSTEM_ERROR");
        assertThat(taskStore.find(judgeId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.SYSTEM_ERROR);
        verify(messagingTemplate, atMost(2)).convertAndSend(anyString(), any(Object.class));
    }

    @ParameterizedTest
    @EnumSource(value = SandboxTaskEvent.Type.class, names = {"COMPILE_STARTED", "COMPILE_FINISHED", "RUN_STARTED"})
    void laterRoleStagesCannotRegressRunningProgressOrLoseAcceptedCases(SandboxTaskEvent.Type type) throws Exception {
        String judgeId = "v2-stage-order";
        ResolvedTaskPolicy policy = policy(2, true);
        createTask(judgeId, policy);
        SandboxRunHandle handle = v2Handle(judgeId);
        taskStore.saveRunHandle(judgeId, handle);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        session.bindRunHandle(handle);
        session.accept(withEnvelope(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.RUN_STARTED, "first role"), handle, 1));
        JudgeProgress running = session.accept(withEnvelope(runFinished(judgeId, 1, "AC"), handle, 2));
        SandboxTaskEvent laterStage = new SandboxTaskEvent(judgeId, type, Instant.now(), null,
                type == SandboxTaskEvent.Type.COMPILE_FINISHED ? "OK" : null, "next role", null, null);

        JudgeProgress progress = session.accept(withEnvelope(laterStage, handle, 3));

        assertThat(progress.getStatus()).isEqualTo("RUNNING");
        assertThat(progress.getProgress()).isEqualTo(running.getProgress());
        assertThat(progress.getSummary()).usingRecursiveComparison().isEqualTo(running.getSummary());
        assertThat(progress.getResults()).isNull();
        assertThat(taskStore.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.RUNNING);
        FileTaskStore cold = new FileTaskStore(objectMapper, tempDir.resolve("storage"));
        assertThat(cold.findSummary(judgeId).orElseThrow()).usingRecursiveComparison().isEqualTo(progress);
        session.accept(withEnvelope(runFinished(judgeId, 2, "AC"), handle, 4));
        JudgeProgress terminal = session.accept(withEnvelope(SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.COMPLETED,
                "completed"), handle, 5));
        assertThat(terminal.getStatus()).isEqualTo("AC");
        assertThat(terminal.getSummary().getCompletedCases()).isEqualTo(2);
        assertThat(terminal.getSummary().getAc()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy-event", "missing-version", "wrong-version", "missing-attempt", "foreign-attempt",
            "missing-sequence", "zero-sequence", "negative-sequence", "sequence-gap", "huge-sequence", "duplicate-case",
            "run-started-zero-case", "run-started-out-of-range"})
    void invalidV2EnvelopeCannotAdvanceSequenceOrMutateTheTask(String shape) throws Exception {
        String judgeId = "v2-owned";
        ResolvedTaskPolicy policy = policy(2, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        SandboxRunHandle handle = v2Handle(judgeId);
        session.bindRunHandle(handle);
        session.accept(withEnvelope(runFinished(judgeId, 1, "AC"), handle, 1));
        Integer version = 2;
        String attemptId = handle.attemptId();
        Long sequence = 2L;
        int caseNumber = 2;
        SandboxTaskEvent.Type type = SandboxTaskEvent.Type.RUN_FINISHED;
        switch (shape) {
            case "legacy-event" -> { version = null; attemptId = null; sequence = null; }
            case "missing-version" -> version = null;
            case "wrong-version" -> version = 1;
            case "missing-attempt" -> attemptId = null;
            case "foreign-attempt" -> attemptId = "00000000-0000-0000-0000-000000000002";
            case "missing-sequence" -> sequence = null;
            case "zero-sequence" -> sequence = 0L;
            case "negative-sequence" -> sequence = -1L;
            case "sequence-gap" -> sequence = 3L;
            case "huge-sequence" -> sequence = Long.MAX_VALUE;
            case "duplicate-case" -> caseNumber = 1;
            case "run-started-zero-case" -> { type = SandboxTaskEvent.Type.RUN_STARTED; caseNumber = 0; }
            case "run-started-out-of-range" -> { type = SandboxTaskEvent.Type.RUN_STARTED; caseNumber = 3; }
            default -> throw new IllegalArgumentException("unknown test shape");
        }
        assertRejectedWithoutPublication(judgeId, session, new SandboxTaskEvent(judgeId,
                type, Instant.now(), caseNumber, "AC", "invalid",
                new TestCaseResult(caseNumber, "AC", 2, 512), null, version, attemptId, sequence));

        session.accept(withEnvelope(runFinished(judgeId, 2, "AC"), handle, 2));
        JudgeProgress completed = session.accept(withEnvelope(SandboxTaskEvent.of(judgeId,
                SandboxTaskEvent.Type.COMPLETED, "completed"), handle, 3));
        assertThat(completed.getSummary().getCompletedCases()).isEqualTo(2);
        assertThat(completed.getSummary().getAc()).isEqualTo(2);
    }

    @Test
    void replayedV2SequencesAreIgnoredWithoutCountingOrPublishingAgain() throws Exception {
        String judgeId = "v2-replay";
        ResolvedTaskPolicy policy = policy(2, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        SandboxRunHandle handle = v2Handle(judgeId);
        session.bindRunHandle(handle);
        SandboxTaskEvent first = withEnvelope(runFinished(judgeId, 1, "AC"), handle, 1);
        JudgeProgress firstProgress = session.accept(first);
        clearInvocations(messagingTemplate);
        assertThat(session.accept(first)).isSameAs(firstProgress);
        verifyNoInteractions(messagingTemplate);
        JudgeProgress secondProgress = session.accept(withEnvelope(runFinished(judgeId, 2, "WA"), handle, 2));
        clearInvocations(messagingTemplate);
        assertThat(session.accept(first)).isSameAs(secondProgress);
        verifyNoInteractions(messagingTemplate);

        JudgeProgress completed = session.accept(withEnvelope(SandboxTaskEvent.of(judgeId,
                SandboxTaskEvent.Type.COMPLETED, "completed"), handle, 3));
        assertThat(completed.getStatus()).isEqualTo("WA");
        assertThat(completed.getSummary().getCompletedCases()).isEqualTo(2);
        assertThat(completed.getSummary().getAc()).isEqualTo(1);
        assertThat(completed.getSummary().getWa()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"foreign-task", "wrong-version", "missing-attempt", "invalid-attempt", "rebind"})
    void runBindingRejectsAnInvalidOrSecondRunHandle(String shape) throws Exception {
        String judgeId = "v2-binding";
        ResolvedTaskPolicy policy = policy(1, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        SandboxRunHandle valid = v2Handle(judgeId);
        SandboxRunHandle invalid = shape == null ? null : switch (shape) {
            case "foreign-task" -> v2Handle("another-task");
            case "wrong-version" -> SandboxRunHandle.builder().judgeId(judgeId).protocolVersion(1)
                    .attemptId(valid.attemptId()).build();
            case "missing-attempt" -> SandboxRunHandle.builder().judgeId(judgeId).protocolVersion(2).build();
            case "invalid-attempt" -> SandboxRunHandle.builder().judgeId(judgeId).protocolVersion(2)
                    .attemptId("invalid").build();
            case "rebind" -> valid;
            default -> throw new IllegalArgumentException("unknown test shape");
        };
        if ("rebind".equals(shape)) {
            session.bindRunHandle(valid);
        }

        assertThatThrownBy(() -> session.bindRunHandle(invalid)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(messagingTemplate);
        if (!"rebind".equals(shape)) {
            session.bindRunHandle(valid);
        }
        assertThat(session.accept(withEnvelope(runFinished(judgeId, 1, "AC"), valid, 1))
                .getSummary().getCompletedCases()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPILE_ERROR", "COMPILATION_ERROR", "COMPILATION_FAILED", "CE", "Compilation Error"})
    void compileFinishedCompileErrorBecomesTerminalCompilationError(String status) throws Exception {
        String judgeId = "ingest-compile-error";
        ResolvedTaskPolicy policy = policy(100_000, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);

        JudgeProgress progress = session.accept(new SandboxTaskEvent(
                judgeId,
                SandboxTaskEvent.Type.COMPILE_FINISHED,
                Instant.parse("2026-07-03T00:00:00Z"),
                null,
                status,
                "compile failed for USER: /work/user.cpp:1:1: error: 'aa' does not name a type",
                null,
                null
        ));

        assertThat(progress.getStatus()).isEqualTo("COMPILATION_ERROR");
        assertThat(progress.getProgress()).isEqualTo(100);
        assertThat(progress.getMessage())
                .contains("user.cpp:1:1")
                .doesNotContain("/work/");
        assertThat(taskStore.find(judgeId)).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.COMPILATION_ERROR);
        assertThat(session.isTerminal(progress)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AC", "WA", "RUNNING", "CANCELLED", "SYSTEM_ERROR", "unknown", ""})
    void compileFinishedRejectsUnexpectedStatusWithoutAdvancingTheRunnerSequence(String status) throws Exception {
        String judgeId = "v2-compile-status";
        ResolvedTaskPolicy policy = policy(1, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        SandboxRunHandle handle = v2Handle(judgeId);
        session.bindRunHandle(handle);
        SandboxTaskEvent invalid = withEnvelope(new SandboxTaskEvent(judgeId,
                SandboxTaskEvent.Type.COMPILE_FINISHED, Instant.now(), null, status,
                "invalid compile status", null, null), handle, 1);

        assertThatThrownBy(() -> session.accept(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compile status");
        verifyNoInteractions(messagingTemplate);
        assertThat(taskStore.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.CREATED);
        assertThat(taskStore.findSummary(judgeId)).isEmpty();

        JudgeProgress compiled = session.accept(withEnvelope(new SandboxTaskEvent(judgeId,
                SandboxTaskEvent.Type.COMPILE_FINISHED, Instant.now(), null, "OK",
                "compile finished", null, null), handle, 1));
        assertThat(compiled.getStatus()).isEqualTo("COMPILING");
        assertThat(compiled.getSummary().getCompletedCases()).isZero();
        session.accept(withEnvelope(runFinished(judgeId, 1, "AC"), handle, 2));
        JudgeProgress completed = session.accept(withEnvelope(SandboxTaskEvent.of(judgeId,
                SandboxTaskEvent.Type.COMPLETED, "completed"), handle, 3));
        assertThat(completed.getStatus()).isEqualTo("AC");
        assertThat(completed.getSummary().getCompletedCases()).isEqualTo(1);
        assertThat(completed.getSummary().getAc()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "OK")
    void compileFinishedRetainsSuccessfulAndStatuslessCompatibility(String status) throws Exception {
        String judgeId = "compile-status-compatible";
        ResolvedTaskPolicy policy = policy(1, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);
        SandboxRunHandle handle = v2Handle(judgeId);
        session.bindRunHandle(handle);

        JudgeProgress progress = session.accept(withEnvelope(new SandboxTaskEvent(judgeId,
                SandboxTaskEvent.Type.COMPILE_FINISHED, Instant.now(), null, status,
                "compile finished", null, null), handle, 1));

        assertThat(progress.getStatus()).isEqualTo("COMPILING");
        assertThat(progress.getSummary().getCompletedCases()).isZero();
        assertThat(session.isTerminal(progress)).isFalse();
    }

    private SandboxTaskEvent runFinished(String judgeId, int caseNumber, String status) {
        return new SandboxTaskEvent(
                judgeId,
                SandboxTaskEvent.Type.RUN_FINISHED,
                Instant.parse("2026-07-03T00:00:00Z"),
                caseNumber,
                status,
                status + " on " + caseNumber,
                new TestCaseResult(caseNumber, status, caseNumber % 97, 512),
                null
        );
    }

    private SandboxRunHandle v2Handle(String judgeId) {
        return SandboxRunHandle.builder().judgeId(judgeId).runId("test-run").provider("local-fake")
                .protocolVersion(2).attemptId("00000000-0000-0000-0000-000000000001").build();
    }

    private SandboxTaskEvent withEnvelope(SandboxTaskEvent event, SandboxRunHandle handle, long sequence) {
        return new SandboxTaskEvent(event.judgeId(), event.type(), event.occurredAt(), event.caseNumber(),
                event.status(), event.message(), event.result(), event.summary(), handle.protocolVersion(),
                handle.attemptId(), sequence);
    }

    private void assertRejectedWithoutPublication(String judgeId, SandboxEventIngestor.Session session,
                                                  SandboxTaskEvent invalid) throws Exception {
        byte[] before = objectMapper.writeValueAsBytes(List.of(taskStore.find(judgeId).orElseThrow(),
                taskStore.findSummary(judgeId).orElseThrow()));
        clearInvocations(messagingTemplate);
        assertThatThrownBy(() -> session.accept(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(objectMapper.writeValueAsBytes(List.of(taskStore.find(judgeId).orElseThrow(),
                taskStore.findSummary(judgeId).orElseThrow()))).isEqualTo(before);
        verifyNoInteractions(messagingTemplate);
    }

    private void createTask(String judgeId, ResolvedTaskPolicy policy) throws Exception {
        Path workDir = taskStore.taskDirectory(judgeId);
        taskStore.create(JudgeTask.builder()
                .judgeId(judgeId)
                .status(JudgeStatus.CREATED)
                .requestedCases(policy.requestedCases())
                .mode(policy.profile())
                .policy(policy)
                .workDir(workDir.toString())
                .createdAt(Instant.parse("2026-07-03T00:00:00Z"))
                .build());
    }

    private ResolvedTaskPolicy policy(int totalCases, boolean highVolume) {
        return new ResolvedTaskPolicy(
                "linux-prod",
                highVolume,
                totalCases,
                totalCases,
                100,
                4,
                Duration.ofSeconds(2),
                Duration.ofMinutes(30),
                268_435_456L,
                1_048_576L,
                true
        );
    }

    private static final class FixedClock extends Clock {
        private final Instant instant;

        private FixedClock(Instant instant) {
            this.instant = instant;
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
