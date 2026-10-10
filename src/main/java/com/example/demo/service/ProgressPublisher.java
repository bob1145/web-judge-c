package com.example.demo.service;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.model.JudgeStatus;
import com.example.demo.security.WebSocketSessionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ProgressPublisher {

    private static final int MAX_INLINE_RESULTS = 1_000;
    private static final Pattern WINDOWS_SOURCE_PATH = Pattern.compile(
            "(?i)(?:[A-Z]:[\\\\/](?:[^\\\\/\\r\\n:]+[\\\\/])*)(generator|user|bruteforce|oracle|special_judge)\\.cpp"
    );
    private static final Pattern UNIX_SOURCE_PATH = Pattern.compile(
            "(?i)(?<!\\S)/(?:[^\\s:]+/)*(generator|user|bruteforce|oracle|special_judge)\\.cpp"
    );

    private final SimpMessagingTemplate messagingTemplate;
    private final TaskStore taskStore;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration throttleInterval;
    private final Duration cacheTtl;
    private final Semaphore cacheSlots;
    private final ConcurrentMap<String, SentState> lastSentByJudgeId = new ConcurrentHashMap<>();

    @Autowired
    public ProgressPublisher(
            @Qualifier("brokerMessagingTemplate")
            SimpMessagingTemplate messagingTemplate,
            TaskStore taskStore,
            ObjectMapper objectMapper,
            ExecutionProperties executionProperties,
            @Qualifier("progressNotificationChannel") MessageChannel notificationChannel
    ) {
        this(notificationTemplate(messagingTemplate, notificationChannel), taskStore, objectMapper, executionProperties);
    }

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
                executionProperties.getProgressPublishInterval(),
                executionProperties
        );
    }

    private static SimpMessagingTemplate notificationTemplate(SimpMessagingTemplate source, MessageChannel channel) {
        SimpMessagingTemplate template = new SimpMessagingTemplate(channel);
        template.setMessageConverter(source.getMessageConverter());
        template.setHeaderInitializer(source.getHeaderInitializer());
        template.setSendTimeout(WebSocketSessionGuard.PROGRESS_SEND_BUDGET.toMillis());
        return template;
    }

    public ProgressPublisher(
            SimpMessagingTemplate messagingTemplate,
            TaskStore taskStore,
            ObjectMapper objectMapper,
            Clock clock,
            Duration throttleInterval
    ) {
        this(messagingTemplate, taskStore, objectMapper, clock, throttleInterval, new ExecutionProperties());
    }

    public ProgressPublisher(
            SimpMessagingTemplate messagingTemplate,
            TaskStore taskStore,
            ObjectMapper objectMapper,
            Clock clock,
            Duration throttleInterval,
            ExecutionProperties executionProperties
    ) {
        executionProperties.validateProgressCachePolicy();
        this.messagingTemplate = messagingTemplate;
        this.taskStore = taskStore;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.clock = clock;
        this.throttleInterval = throttleInterval == null ? Duration.ofSeconds(1) : throttleInterval;
        this.cacheTtl = executionProperties.getProgressCacheTtl();
        this.cacheSlots = new Semaphore(executionProperties.getProgressCacheMaxEntries());
    }

    public JudgeProgress publish(String judgeId, JudgeProgress progress) {
        AtomicReference<JudgeProgress> snapshot = new AtomicReference<>();
        AtomicBoolean releaseSlot = new AtomicBoolean();
        lastSentByJudgeId.compute(judgeId, (ignored, cached) -> {
            JudgeProgress publishable = persist(judgeId, sanitize(progress));
            snapshot.set(publishable);
            Instant now = clock.instant();
            SentState previous = isExpired(cached, now) ? null : cached;
            String status = normalize(publishable.getStatus());
            boolean terminal = isTerminal(status);
            boolean statusChanged = previous == null || !Objects.equals(previous.status(), status);
            boolean intervalElapsed = previous == null
                    || Duration.between(previous.sentAt(), now).compareTo(throttleInterval) >= 0;
            boolean hasRunningResult = "RUNNING".equals(status) && publishable.getSummary() != null
                    && publishable.getSummary().getCompletedCases() > 0;
            boolean firstRunningResult = hasRunningResult
                    && (previous == null || !previous.runningResultPublished());
            boolean shouldSend = statusChanged || intervalElapsed || terminal || firstRunningResult;
            if (shouldSend) {
                sendProgress(judgeId, publishable);
            }
            if (terminal) {
                releaseSlot.set(cached != null);
                return null;
            }
            if (cached == null && !cacheSlots.tryAcquire()) {
                return null;
            }
            return new SentState(status, shouldSend ? now : previous.sentAt(), hasRunningResult
                    || (previous != null && previous.runningResultPublished()), now);
        });
        if (releaseSlot.get()) {
            cacheSlots.release();
        }
        return snapshot.get();
    }

    private void sendProgress(String judgeId, JudgeProgress progress) {
        long deadline = System.nanoTime() + WebSocketSessionGuard.PROGRESS_SEND_BUDGET.toNanos();
        String noticeId = UUID.randomUUID().toString();
        AtomicBoolean cancelled = new AtomicBoolean();
        Exception failure = null;
        for (int attempt = 0; attempt < 3 && deadline - System.nanoTime() > 0
                && !Thread.currentThread().isInterrupted(); attempt++) {
            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setHeader(WebSocketSessionGuard.PROGRESS_NOTICE_HEADER, noticeId);
            headers.setHeader(WebSocketSessionGuard.PROGRESS_DEADLINE_HEADER, deadline);
            headers.setHeader(WebSocketSessionGuard.PROGRESS_CANCELLED_HEADER, cancelled);
            headers.setLeaveMutable(true);
            try {
                messagingTemplate.convertAndSend("/topic/progress/" + judgeId, progress, headers.getMessageHeaders());
                return;
            } catch (Exception exception) {
                failure = exception;
            }
        }
        cancelled.set(true);
        log.warn("Failed to send judge progress for judgeId={}: {}", judgeId,
                failure == null ? "notification budget exhausted or interrupted" : failure.getMessage());
    }

    public void clearThrottleState(String judgeId) {
        if (lastSentByJudgeId.remove(judgeId) != null) {
            cacheSlots.release();
        }
    }

    public void expireThrottleState() {
        Instant now = clock.instant();
        lastSentByJudgeId.forEach((judgeId, state) -> {
            if (isExpired(state, now) && lastSentByJudgeId.remove(judgeId, state)) {
                cacheSlots.release();
            }
        });
    }

    private boolean isExpired(SentState state, Instant now) {
        return state != null && Duration.between(state.touchedAt(), now).compareTo(cacheTtl) >= 0;
    }

    private JudgeProgress persist(String judgeId, JudgeProgress progress) {
        try {
            Optional<JudgeProgress> existing = terminalSnapshot(judgeId);
            if (existing.isPresent()) {
                return sanitize(taskStore.finalizeTask(judgeId, existing.get()));
            }
            Optional<JudgeStatus> status = JudgeStatus.fromProgressStatus(progress.getStatus());
            if (status.map(JudgeStatus::isTerminal).orElse(false)) {
                return sanitize(taskStore.finalizeTask(judgeId, progress));
            }
            if (status.isPresent()) {
                taskStore.updateStatus(judgeId, status.get(), progress.getMessage());
            }
            taskStore.saveSummary(judgeId, progress);
            return sanitize(taskStore.findSummary(judgeId).orElseThrow(() ->
                    new IOException("Persisted judge progress is missing for judgeId=" + judgeId)));
        } catch (IllegalStateException e) {
            try {
                Optional<JudgeProgress> existing = terminalSnapshot(judgeId);
                if (existing.isPresent()) {
                    return sanitize(existing.get());
                }
            } catch (IOException readFailure) {
                e.addSuppressed(readFailure);
            }
            throw new IllegalStateException("Failed to persist judge progress for judgeId=" + judgeId, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist judge progress for judgeId=" + judgeId, e);
        }
    }

    private Optional<JudgeProgress> terminalSnapshot(String judgeId) throws IOException {
        return taskStore.findSummary(judgeId).filter(snapshot ->
                JudgeStatus.fromProgressStatus(snapshot.getStatus()).map(JudgeStatus::isTerminal).orElse(false));
    }

    private JudgeProgress sanitize(JudgeProgress progress) {
        if (progress == null) {
            return new JudgeProgress("UNKNOWN", "", 0);
        }
        String message = sanitizeMessage(progress.getMessage());
        JudgeSummary summary = sanitizeSummary(progress.getSummary());
        if (shouldDropResults(progress)) {
            return new JudgeProgress(progress.getStatus(), message, progress.getProgress(), null, summary,
                    progress.getAttemptId(), progress.getRevision(), progress.getFinalizedAt());
        }
        if (Objects.equals(message, progress.getMessage()) && summary == progress.getSummary()) {
            return progress;
        }
        return new JudgeProgress(progress.getStatus(), message, progress.getProgress(), progress.getResults(), summary,
                progress.getAttemptId(), progress.getRevision(), progress.getFinalizedAt());
    }

    private boolean shouldDropResults(JudgeProgress progress) {
        if ("RUNNING".equals(normalize(progress.getStatus()))) {
            return progress.getResults() != null;
        }
        if (progress.getResults() == null || progress.getResults().isEmpty()) {
            return false;
        }
        JudgeSummary summary = progress.getSummary();
        return progress.getResults().size() > MAX_INLINE_RESULTS
                || (summary != null && summary.getTotalCases() > MAX_INLINE_RESULTS);
    }

    private JudgeSummary sanitizeSummary(JudgeSummary summary) {
        if (summary == null) {
            return null;
        }
        String stoppedReason = sanitizeMessage(summary.getStoppedReason());
        if (Objects.equals(stoppedReason, summary.getStoppedReason())) {
            return summary;
        }
        return new JudgeSummary(
                summary.getTotalCases(),
                summary.getCompletedCases(),
                summary.getAc(),
                summary.getWa(),
                summary.getTle(),
                summary.getMle(),
                summary.getRe(),
                summary.getSystemError(),
                summary.getOutputLimitExceeded(),
                summary.getFirstFailedCase(),
                summary.getFailureSamples(),
                summary.getSlowSamples(),
                stoppedReason
        );
    }

    private String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String sanitized = WINDOWS_SOURCE_PATH.matcher(message).replaceAll("$1.cpp");
        return UNIX_SOURCE_PATH.matcher(sanitized).replaceAll("$1.cpp");
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

    private record SentState(String status, Instant sentAt, boolean runningResultPublished, Instant touchedAt) {
    }
}
