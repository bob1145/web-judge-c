package com.example.demo;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.service.ResolvedTaskPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

        publisher.publish("progress-task", new JudgeProgress("PENDING", "queued", 0));
        publisher.publish("progress-task", new JudgeProgress("RUNNING", "10 done", 10));
        clock.advance(Duration.ofMillis(100));
        publisher.publish("progress-task", new JudgeProgress("RUNNING", "20 done", 20));

        verify(messagingTemplate, times(2)).convertAndSend(anyString(), any(Object.class));
        assertThat(taskStore.find("progress-task")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.RUNNING);
        assertThat(taskStore.findSummary("progress-task")).isPresent().get()
                .extracting(JudgeProgress::getProgress)
                .isEqualTo(20);

        clock.advance(Duration.ofSeconds(1));
        publisher.publish("progress-task", new JudgeProgress("RUNNING", "30 done", 30));

        verify(messagingTemplate, times(3)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void publishesStatusTransitionsImmediatelyInsideThrottleWindow() throws Exception {
        createTask("transition-task", 10, false);

        publisher.publish("transition-task", new JudgeProgress("PENDING", "pending", 0));
        publisher.publish("transition-task", new JudgeProgress("COMPILING", "compiling", 5));
        publisher.publish("transition-task", new JudgeProgress("RUNNING", "running", 10));
        publisher.publish("transition-task", new JudgeProgress("CANCELLED", "cancelled", 100));

        verify(messagingTemplate, times(4)).convertAndSend(anyString(), any(Object.class));
        assertThat(taskStore.find("transition-task")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.CANCELLED);
    }

    @Test
    void websocketFailuresDoNotFailTasksAndStillPersistState() throws Exception {
        createTask("ws-failure-task", 10, false);
        doThrow(new IllegalStateException("session closed"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        assertThatCode(() -> publisher.publish("ws-failure-task", new JudgeProgress("PENDING", "pending", 0)))
                .doesNotThrowAnyException();

        assertThat(taskStore.find("ws-failure-task")).isPresent().get()
                .extracting(JudgeTask::getStatus)
                .isEqualTo(JudgeStatus.PENDING);
        assertThat(taskStore.findSummary("ws-failure-task")).isPresent().get()
                .extracting(JudgeProgress::getStatus)
                .isEqualTo("PENDING");
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
        verify(messagingTemplate).convertAndSend(anyString(), payloadCaptor.capture());
        JudgeProgress sent = (JudgeProgress) payloadCaptor.getValue();
        assertThat(sent.getResults()).isNull();
        assertThat(objectMapper.writeValueAsBytes(sent).length).isLessThan(65_536);
        assertThat(taskStore.findSummary("large-final-task")).isPresent().get()
                .extracting(JudgeProgress::getResults)
                .isNull();
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
