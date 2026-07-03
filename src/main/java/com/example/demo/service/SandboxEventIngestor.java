package com.example.demo.service;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.model.JudgeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SandboxEventIngestor {

    private final ProgressPublisher progressPublisher;
    private final ExecutionProperties executionProperties;

    public Session start(String judgeId, ResolvedTaskPolicy policy) {
        return new Session(judgeId, policy);
    }

    public final class Session {
        private final String judgeId;
        private final ResolvedTaskPolicy policy;
        private final ResultAggregator aggregator;
        private Instant lastOrdinaryProgressAt;
        private JudgeProgress latestProgress;

        private Session(String judgeId, ResolvedTaskPolicy policy) {
            this.judgeId = judgeId;
            this.policy = policy;
            this.aggregator = new ResultAggregator(
                    policy.highVolume(),
                    policy.requestedCases(),
                    executionProperties.getMaxFailureSamples(),
                    executionProperties.getMaxSlowSamples()
            );
        }

        public JudgeProgress accept(SandboxTaskEvent event) {
            if (event == null || event.type() == null) {
                return latestProgress;
            }
            String message = event.message() == null ? event.type().name() : event.message();
            return switch (event.type()) {
                case COMPILE_STARTED -> publishStatus("COMPILING", message, 5);
                case COMPILE_FINISHED -> publishStatus("COMPILING", message, 15);
                case RUN_STARTED -> publishStatus("RUNNING", message, 15);
                case RUN_FINISHED -> acceptCaseResult(event);
                case SUMMARY -> publishSummary(event, message);
                case COMPLETED -> publish(aggregator.toFinalProgress());
                case CANCELLED -> terminal(JudgeStatus.CANCELLED.name(), message);
                case BUDGET_EXCEEDED -> terminal(JudgeStatus.BUDGET_EXCEEDED.name(), message);
                case SECURITY_VIOLATION -> terminal(JudgeStatus.SECURITY_VIOLATION.name(), message);
                case SYSTEM_ERROR -> terminal(JudgeStatus.SYSTEM_ERROR.name(), message);
                case SANDBOX_UNAVAILABLE -> terminal(JudgeStatus.SANDBOX_UNAVAILABLE.name(), message);
            };
        }

        public JudgeProgress terminal(String status, String message) {
            return publish(new JudgeProgress(status, message, 100, null, stoppedSummary(message)));
        }

        public boolean isTerminal(JudgeProgress progress) {
            if (progress == null) {
                return false;
            }
            return JudgeStatus.fromProgressStatus(progress.getStatus())
                    .map(JudgeStatus::isTerminal)
                    .orElse(false);
        }

        private JudgeProgress acceptCaseResult(SandboxTaskEvent event) {
            if (event.result() != null) {
                aggregator.accept(event.result());
            }
            int completedCases = aggregator.completedCases();
            int progress = Math.min(99, 15 + (int) ((double) completedCases / policy.requestedCases() * 85));
            JudgeProgress ordinary = new JudgeProgress(
                    "RUNNING",
                    completedCases + " / " + policy.requestedCases(),
                    progress,
                    null,
                    aggregator.toSummary()
            );
            if (shouldPublishOrdinaryProgress(event.occurredAt(), completedCases)) {
                return publish(ordinary);
            }
            latestProgress = ordinary;
            return ordinary;
        }

        private JudgeProgress publishStatus(String status, String message, int progress) {
            return publish(new JudgeProgress(status, message, progress, null, aggregator.toSummary()));
        }

        private JudgeProgress publishSummary(SandboxTaskEvent event, String message) {
            String status = event.status() == null ? JudgeStatus.COMPLETED.name() : event.status();
            JudgeSummary summary = event.summary() == null ? aggregator.toSummary() : event.summary();
            return publish(new JudgeProgress(status, message, 100, null, summary));
        }

        private JudgeProgress publish(JudgeProgress progress) {
            latestProgress = progressPublisher.publish(judgeId, progress);
            return latestProgress;
        }

        private boolean shouldPublishOrdinaryProgress(Instant occurredAt, int completedCases) {
            if (completedCases == policy.requestedCases()) {
                return false;
            }
            Instant eventTime = occurredAt == null ? Instant.now() : occurredAt;
            if (lastOrdinaryProgressAt == null) {
                lastOrdinaryProgressAt = eventTime;
                return true;
            }
            Duration interval = executionProperties.getProgressPublishInterval();
            if (interval == null || interval.isZero() || interval.isNegative()) {
                lastOrdinaryProgressAt = eventTime;
                return true;
            }
            if (Duration.between(lastOrdinaryProgressAt, eventTime).compareTo(interval) >= 0) {
                lastOrdinaryProgressAt = eventTime;
                return true;
            }
            return false;
        }

        private JudgeSummary stoppedSummary(String message) {
            return new JudgeSummary(
                    policy.requestedCases(),
                    aggregator.completedCases(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null,
                    List.of(),
                    List.of(),
                    message
            );
        }
    }
}
