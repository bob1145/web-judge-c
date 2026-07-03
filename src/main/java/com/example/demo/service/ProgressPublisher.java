package com.example.demo.service;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.model.JudgeStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ProgressPublisher {

    private static final int MAX_INLINE_RESULTS = 1_000;

    private final SimpMessagingTemplate messagingTemplate;
    private final TaskStore taskStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration throttleInterval;
    private final ConcurrentMap<String, SentState> lastSentByJudgeId = new ConcurrentHashMap<>();

    @Autowired
    public ProgressPublisher(
            SimpMessagingTemplate messagingTemplate,
            TaskStore taskStore,
            ObjectMapper objectMapper,
            ExecutionProperties executionProperties
    ) {
        this(
                messagingTemplate,
                taskStore,
                objectMapper,
                Clock.systemUTC(),
                executionProperties.getProgressPublishInterval()
        );
    }

    public ProgressPublisher(
            SimpMessagingTemplate messagingTemplate,
            TaskStore taskStore,
            ObjectMapper objectMapper,
            Clock clock,
            Duration throttleInterval
    ) {
        this.messagingTemplate = messagingTemplate;
        this.taskStore = taskStore;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.clock = clock;
        this.throttleInterval = throttleInterval == null ? Duration.ofSeconds(1) : throttleInterval;
    }

    public JudgeProgress publish(String judgeId, JudgeProgress progress) {
        JudgeProgress publishable = sanitize(progress);
        persist(judgeId, publishable);
        if (shouldSend(judgeId, publishable)) {
            try {
                messagingTemplate.convertAndSend("/topic/progress/" + judgeId, publishable);
            } catch (Exception e) {
                log.warn("Failed to send judge progress for judgeId={}: {}", judgeId, e.getMessage());
            }
        }
        return publishable;
    }

    private void persist(String judgeId, JudgeProgress progress) {
        try {
            Optional<JudgeStatus> status = JudgeStatus.fromProgressStatus(progress.getStatus());
            if (status.isPresent()) {
                taskStore.updateStatus(judgeId, status.get(), progress.getMessage());
            }
            taskStore.saveSummary(judgeId, progress);
        } catch (IllegalStateException e) {
            log.warn("Ignoring invalid persisted status transition for judgeId={}: {}", judgeId, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to persist judge progress for judgeId={}: {}", judgeId, e.getMessage());
        }
    }

    private boolean shouldSend(String judgeId, JudgeProgress progress) {
        Instant now = clock.instant();
        String status = normalize(progress.getStatus());
        AtomicBoolean send = new AtomicBoolean(false);
        lastSentByJudgeId.compute(judgeId, (ignored, previous) -> {
            boolean statusChanged = previous == null || !Objects.equals(previous.status(), status);
            boolean intervalElapsed = previous == null
                    || Duration.between(previous.sentAt(), now).compareTo(throttleInterval) >= 0;
            boolean shouldSend = statusChanged || intervalElapsed || isTerminal(status);
            send.set(shouldSend);
            return shouldSend ? new SentState(status, now) : previous;
        });
        return send.get();
    }

    private JudgeProgress sanitize(JudgeProgress progress) {
        if (progress == null) {
            return new JudgeProgress("UNKNOWN", "", 0);
        }
        if (shouldDropResults(progress)) {
            return progress.withoutResults();
        }
        return progress;
    }

    private boolean shouldDropResults(JudgeProgress progress) {
        if (progress.getResults() == null || progress.getResults().isEmpty()) {
            return false;
        }
        JudgeSummary summary = progress.getSummary();
        return progress.getResults().size() > MAX_INLINE_RESULTS
                || (summary != null && summary.getTotalCases() > MAX_INLINE_RESULTS);
    }

    private boolean isTerminal(String status) {
        return JudgeStatus.fromProgressStatus(status)
                .map(JudgeStatus::isTerminal)
                .orElse(false);
    }

    private String normalize(String status) {
        if (status == null) {
            return "";
        }
        return status.trim()
                .replace(' ', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    private record SentState(String status, Instant sentAt) {
    }
}
