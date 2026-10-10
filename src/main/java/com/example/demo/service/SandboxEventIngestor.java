package com.example.demo.service;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxExecutionManifest;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SandboxEventIngestor {

    private static final Set<String> CASE_STATUSES = Set.of("AC", "WA", "TLE", "MLE", "RE", "SYSTEM_ERROR",
            "System Error", "OUTPUT_LIMIT_EXCEEDED", "OLE");

    private final ProgressPublisher progressPublisher;
    private final ExecutionProperties executionProperties;

    public Session start(String judgeId, ResolvedTaskPolicy policy) {
        return start(judgeId, policy, false);
    }

    public Session start(String judgeId, ResolvedTaskPolicy policy, boolean stopOnFirstNonAc) {
        return new Session(judgeId, policy, stopOnFirstNonAc);
    }

    public final class Session {
        private final String judgeId;
        private final ResolvedTaskPolicy policy;
        private final boolean stopOnFirstNonAc;
        private final ResultAggregator aggregator;
        private final BitSet completedCaseNumbers = new BitSet();
        private Instant lastOrdinaryProgressAt;
        private JudgeProgress latestProgress;
        private String attemptId;
        private long lastSequence;

        private Session(String judgeId, ResolvedTaskPolicy policy, boolean stopOnFirstNonAc) {
            this.judgeId = judgeId;
            this.policy = policy;
            this.stopOnFirstNonAc = stopOnFirstNonAc;
            this.aggregator = new ResultAggregator(
                    policy.highVolume(),
                    policy.requestedCases(),
                    executionProperties.getMaxFailureSamples(),
                    executionProperties.getMaxSlowSamples()
            );
        }

        public synchronized JudgeProgress accept(SandboxTaskEvent event) {
            if (event == null || event.type() == null || !judgeId.equals(event.judgeId())) {
                throw new IllegalArgumentException("Sandbox event identity or type does not match the active task");
            }
            if (event.caseNumber() != null
                    && (event.caseNumber() < 1 || event.caseNumber() > policy.requestedCases())) {
                throw new IllegalArgumentException("Sandbox event case number is outside the active task");
            }
            if (attemptId != null) {
                if (event.protocolVersion() == null || event.protocolVersion() != SandboxExecutionManifest.VERSION
                        || !attemptId.equals(event.attemptId()) || event.sequence() == null || event.sequence() < 1) {
                    throw new IllegalArgumentException("Sandbox event does not match the active runner protocol or attempt");
                }
                if (event.sequence() <= lastSequence) {
                    return latestProgress;
                }
                if (event.sequence() - lastSequence != 1) {
                    throw new IllegalArgumentException("Sandbox event sequence is missing or out of order");
                }
            }
            if (isTerminal(latestProgress)) {
                return latestProgress;
            }
            String message = event.message() == null ? event.type().name() : event.message();
            JudgeProgress accepted = switch (event.type()) {
                case COMPILE_STARTED -> publishStatus("COMPILING", message, 5);
                case COMPILE_FINISHED -> acceptCompileFinished(event, message);
                case RUN_STARTED -> publishStatus("RUNNING", message, 15);
                case RUN_FINISHED -> acceptCaseResult(event);
                case SUMMARY -> publishSummary(event, message);
                case COMPLETED -> complete(event);
                case CANCELLED -> terminal(JudgeStatus.CANCELLED.name(), message);
                case BUDGET_EXCEEDED -> terminal(JudgeStatus.BUDGET_EXCEEDED.name(), message);
                case SECURITY_VIOLATION -> terminal(JudgeStatus.SECURITY_VIOLATION.name(), message);
                case SYSTEM_ERROR -> terminal(JudgeStatus.SYSTEM_ERROR.name(), message);
                case SANDBOX_UNAVAILABLE -> terminal(JudgeStatus.SANDBOX_UNAVAILABLE.name(), message);
            };
            if (attemptId != null) {
                lastSequence = event.sequence();
            }
            return accepted;
        }

        public void bindRunHandle(SandboxRunHandle handle) {
            if (attemptId != null || latestProgress != null || handle == null
                    || !judgeId.equals(handle.judgeId()) || handle.protocolVersion() != SandboxExecutionManifest.VERSION
                    || handle.attemptId() == null
                    || !UUID.fromString(handle.attemptId()).toString().equals(handle.attemptId())) {
                throw new IllegalArgumentException("Sandbox session requires one matching v2 run handle");
            }
            attemptId = handle.attemptId();
        }

        public synchronized JudgeProgress terminal(String status, String message) {
            if (isTerminal(latestProgress)) {
                return latestProgress;
            }
            String stoppedReason = switch (status) {
                case "CANCELLED" -> "Cancellation requested";
                case "BUDGET_EXCEEDED" -> "Task runtime budget exceeded";
                default -> message;
            };
            return publish(new JudgeProgress(status, message, 100, null, stoppedSummary(stoppedReason)));
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
            TestCaseResult result = event.result();
            if (result == null || event.caseNumber() == null
                    || event.caseNumber() != result.getCaseNumber()
                    || result.getCaseNumber() < 1 || result.getCaseNumber() > policy.requestedCases()
                    || result.getTimeUsed() < 0 || result.getMemoryUsed() < 0
                    || result.getStatus() == null
                    || !Objects.equals(event.status(), result.getStatus())
                    || !CASE_STATUSES.contains(result.getStatus())) {
                throw new IllegalArgumentException("Sandbox case result is incomplete or inconsistent");
            }
            int caseIndex = result.getCaseNumber() - 1;
            if (completedCaseNumbers.get(caseIndex)) {
                throw new IllegalArgumentException("Sandbox case result was already accepted");
            }
            aggregator.accept(result);
            completedCaseNumbers.set(caseIndex);
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
            if (latestProgress != null && "RUNNING".equals(latestProgress.getStatus())) {
                status = "RUNNING";
                progress = Math.max(progress, latestProgress.getProgress());
            }
            return publish(new JudgeProgress(status, message, progress, null, aggregator.toSummary()));
        }

        private JudgeProgress acceptCompileFinished(SandboxTaskEvent event, String message) {
            if (JudgeStatus.fromProgressStatus(event.status())
                    .filter(status -> status == JudgeStatus.COMPILATION_ERROR)
                    .isPresent()) {
                return terminal(JudgeStatus.COMPILATION_ERROR.name(), message);
            }
            if (event.status() != null && !"OK".equals(event.status())) {
                throw new IllegalArgumentException("Sandbox compile status is not recognized");
            }
            return publishStatus("COMPILING", message, 15);
        }

        private JudgeProgress publishSummary(SandboxTaskEvent event, String message) {
            JudgeSummary summary = completionSummary();
            validateCompletionStatus(event);
            validateSummary(event.summary(), summary);
            return publish(new JudgeProgress("RUNNING", message, 99, null, summary));
        }

        private JudgeProgress complete(SandboxTaskEvent event) {
            JudgeSummary summary = completionSummary();
            validateCompletionStatus(event);
            if (event.summary() != null) {
                validateSummary(event.summary(), summary);
            }
            JudgeProgress progress = aggregator.toFinalProgress();
            progress.setSummary(summary);
            return publish(progress);
        }

        private JudgeSummary completionSummary() {
            JudgeSummary summary = aggregator.toSummary();
            if (stopOnFirstNonAc && summary.getFirstFailedCase() != null
                    && summary.getCompletedCases() == summary.getFirstFailedCase()
                    && completedCaseNumbers.nextClearBit(0) == summary.getCompletedCases()) {
                summary.setStoppedReason("Stopped after first non-AC test case");
                return summary;
            }
            if (summary.getCompletedCases() == policy.requestedCases()) {
                return summary;
            }
            throw new IllegalArgumentException("Sandbox completion is missing case results");
        }

        private void validateCompletionStatus(SandboxTaskEvent event) {
            if (event.status() != null && !"DONE".equals(event.status())
                    && !JudgeStatus.COMPLETED.name().equals(event.status())
                    && !aggregator.toFinalProgress().getStatus().equals(event.status())) {
                throw new IllegalArgumentException("Sandbox completion status contradicts accepted results");
            }
        }

        private void validateSummary(JudgeSummary reported, JudgeSummary trusted) {
            if (reported == null || reported.getTotalCases() != trusted.getTotalCases()
                    || reported.getCompletedCases() != trusted.getCompletedCases()
                    || reported.getAc() != trusted.getAc() || reported.getWa() != trusted.getWa()
                    || reported.getTle() != trusted.getTle() || reported.getMle() != trusted.getMle()
                    || reported.getRe() != trusted.getRe() || reported.getSystemError() != trusted.getSystemError()
                    || reported.getOutputLimitExceeded() != trusted.getOutputLimitExceeded()
                    || !Objects.equals(reported.getFirstFailedCase(), trusted.getFirstFailedCase())
                    || !Objects.equals(reported.getStoppedReason(), trusted.getStoppedReason())
                    || !validSamples(reported.getFailureSamples(), trusted.getFailureSamples(),
                            executionProperties.getMaxFailureSamples())
                    || !validSamples(reported.getSlowSamples(), trusted.getSlowSamples(),
                            executionProperties.getMaxSlowSamples())) {
                throw new IllegalArgumentException("Sandbox summary contradicts accepted results");
            }
        }

        private boolean validSamples(List<?> reported, List<?> trusted, int limit) {
            return reported != null && reported.size() <= limit
                    && new HashSet<>(reported).size() == reported.size() && trusted.containsAll(reported);
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
            JudgeSummary summary = aggregator.toSummary();
            summary.setStoppedReason(message);
            return summary;
        }
    }
}
