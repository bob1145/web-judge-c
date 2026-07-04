package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
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
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Test
    void compileFinishedCompileErrorBecomesTerminalCompilationError() throws Exception {
        String judgeId = "ingest-compile-error";
        ResolvedTaskPolicy policy = policy(100_000, true);
        createTask(judgeId, policy);
        SandboxEventIngestor.Session session = ingestor.start(judgeId, policy);

        JudgeProgress progress = session.accept(new SandboxTaskEvent(
                judgeId,
                SandboxTaskEvent.Type.COMPILE_FINISHED,
                Instant.parse("2026-07-03T00:00:00Z"),
                null,
                "COMPILE_ERROR",
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
