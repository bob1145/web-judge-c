package com.example.demo.service;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeProgressEvent;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.TestCaseResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class ResultAggregator {

    private static final String ACCEPTED = "AC";
    private static final String ALL_ACCEPTED_MESSAGE = "全部通过！";

    private static final Comparator<JudgeProgressEvent> FAILURE_SAMPLE_ORDER =
            Comparator.comparingInt(JudgeProgressEvent::getCaseNumber);
    private static final Comparator<JudgeProgressEvent> LARGEST_CASE_FIRST =
            FAILURE_SAMPLE_ORDER.reversed();
    private static final Comparator<JudgeProgressEvent> SLOW_SAMPLE_WEAK_FIRST =
            Comparator.comparingLong(JudgeProgressEvent::getTimeUsed)
                    .thenComparing((left, right) -> Integer.compare(right.getCaseNumber(), left.getCaseNumber()));
    private static final Comparator<JudgeProgressEvent> SLOW_SAMPLE_OUTPUT_ORDER =
            Comparator.comparingLong(JudgeProgressEvent::getTimeUsed).reversed()
                    .thenComparingInt(JudgeProgressEvent::getCaseNumber);

    private final boolean highVolume;
    private final int totalCases;
    private final int maxFailureSamples;
    private final int maxSlowSamples;
    private final List<TestCaseResult> fullResults;
    private final PriorityQueue<JudgeProgressEvent> failureSamples;
    private final PriorityQueue<JudgeProgressEvent> slowSamples;

    private int completedCases;
    private int ac;
    private int wa;
    private int tle;
    private int mle;
    private int re;
    private int systemError;
    private int outputLimitExceeded;
    private JudgeProgressEvent firstFailure;

    public ResultAggregator(boolean highVolume, int totalCases, int maxFailureSamples, int maxSlowSamples) {
        this.highVolume = highVolume;
        this.totalCases = totalCases;
        this.maxFailureSamples = Math.max(0, maxFailureSamples);
        this.maxSlowSamples = Math.max(0, maxSlowSamples);
        this.fullResults = highVolume ? null : new ArrayList<>();
        this.failureSamples = new PriorityQueue<>(LARGEST_CASE_FIRST);
        this.slowSamples = new PriorityQueue<>(SLOW_SAMPLE_WEAK_FIRST);
    }

    public synchronized void accept(TestCaseResult result) {
        JudgeProgressEvent event = JudgeProgressEvent.from(result);
        completedCases++;
        incrementStatus(result.getStatus());
        if (!isAccepted(result.getStatus())) {
            recordFailure(event);
        }
        recordSlowSample(event);
        if (!highVolume) {
            fullResults.add(result);
        }
    }

    public synchronized JudgeProgress toFinalProgress() {
        JudgeSummary summary = toSummary();
        List<TestCaseResult> results = null;
        if (!highVolume) {
            results = fullResults.stream()
                    .sorted(Comparator.comparingInt(TestCaseResult::getCaseNumber))
                    .toList();
        }

        String status = firstFailure == null ? ACCEPTED : firstFailure.getStatus();
        String message = firstFailure == null
                ? ALL_ACCEPTED_MESSAGE
                : firstFailure.getStatus() + " on Test Case #" + firstFailure.getCaseNumber();
        return new JudgeProgress(status, message, 100, results, summary);
    }

    public synchronized JudgeSummary toSummary() {
        return new JudgeSummary(
                totalCases,
                completedCases,
                ac,
                wa,
                tle,
                mle,
                re,
                systemError,
                outputLimitExceeded,
                firstFailure == null ? null : firstFailure.getCaseNumber(),
                sortedFailureSamples(),
                sortedSlowSamples(),
                null
        );
    }

    public synchronized int completedCases() {
        return completedCases;
    }

    private void incrementStatus(String status) {
        switch (normalize(status)) {
            case "AC" -> ac++;
            case "WA" -> wa++;
            case "TLE" -> tle++;
            case "MLE" -> mle++;
            case "RE" -> re++;
            case "OUTPUT_LIMIT_EXCEEDED", "OLE" -> outputLimitExceeded++;
            case "SYSTEM_ERROR" -> systemError++;
            default -> systemError++;
        }
    }

    private boolean isAccepted(String status) {
        return ACCEPTED.equals(normalize(status));
    }

    private String normalize(String status) {
        if (status == null) {
            return "";
        }
        return status.trim()
                .toUpperCase()
                .replace(' ', '_')
                .replace('-', '_');
    }

    private void recordFailure(JudgeProgressEvent event) {
        if (firstFailure == null || event.getCaseNumber() < firstFailure.getCaseNumber()) {
            firstFailure = event;
        }
        if (maxFailureSamples == 0) {
            return;
        }
        if (failureSamples.size() < maxFailureSamples) {
            failureSamples.offer(event);
            return;
        }
        JudgeProgressEvent largestSample = failureSamples.peek();
        if (largestSample != null && event.getCaseNumber() < largestSample.getCaseNumber()) {
            failureSamples.poll();
            failureSamples.offer(event);
        }
    }

    private void recordSlowSample(JudgeProgressEvent event) {
        if (maxSlowSamples == 0) {
            return;
        }
        if (slowSamples.size() < maxSlowSamples) {
            slowSamples.offer(event);
            return;
        }
        JudgeProgressEvent weakest = slowSamples.peek();
        if (weakest != null && SLOW_SAMPLE_WEAK_FIRST.compare(event, weakest) > 0) {
            slowSamples.poll();
            slowSamples.offer(event);
        }
    }

    private List<JudgeProgressEvent> sortedFailureSamples() {
        return failureSamples.stream()
                .sorted(FAILURE_SAMPLE_ORDER)
                .toList();
    }

    private List<JudgeProgressEvent> sortedSlowSamples() {
        return slowSamples.stream()
                .sorted(SLOW_SAMPLE_OUTPUT_ORDER)
                .toList();
    }
}
