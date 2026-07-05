package com.example.demo;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.service.CaseBatchRunner;
import com.example.demo.service.CancellationToken;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.ResultAggregator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CaseBatchRunnerTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void stopExecutors() {
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    @Test
    void hundredThousandCasesNeverExceedConfiguredInFlightLimit() {
        int maxConcurrentCases = 3;
        ResolvedTaskPolicy policy = policy(100_000, 128, maxConcurrentCases);
        AtomicInteger activeCases = new AtomicInteger();
        AtomicInteger peakActiveCases = new AtomicInteger();
        AtomicInteger completedCases = new AtomicInteger();
        CaseBatchRunner runner = runner(Executors.newFixedThreadPool(16));

        CaseBatchRunner.RunOutcome outcome = runner.run(
                policy.requestedCases(),
                policy,
                new CancellationToken(),
                caseNumber -> {
                    int active = activeCases.incrementAndGet();
                    peakActiveCases.accumulateAndGet(active, Math::max);
                    try {
                        return new TestCaseResult(caseNumber, "AC", 1, 1);
                    } finally {
                        activeCases.decrementAndGet();
                    }
                },
                result -> completedCases.incrementAndGet()
        );

        assertThat(outcome.isCancelled()).isFalse();
        assertThat(outcome.getSubmittedCases()).isEqualTo(100_000);
        assertThat(outcome.getCompletedCases()).isEqualTo(100_000);
        assertThat(outcome.getPeakScheduledFutures()).isLessThanOrEqualTo(maxConcurrentCases);
        assertThat(outcome.getPeakScheduledFutures())
                .isLessThanOrEqualTo(policy.batchSize() + policy.maxConcurrentCasesPerTask());
        assertThat(peakActiveCases.get()).isLessThanOrEqualTo(maxConcurrentCases);
        assertThat(completedCases.get()).isEqualTo(100_000);
    }

    @Test
    void cancellationStopsDispatchingNewBatchesAndLeavesOutcomeCancellable() {
        CancellationToken token = new CancellationToken();
        ResolvedTaskPolicy policy = policy(100, 10, 2);
        AtomicInteger startedCases = new AtomicInteger();
        CaseBatchRunner runner = runner(Executors.newFixedThreadPool(4));

        CaseBatchRunner.RunOutcome outcome = runner.run(
                policy.requestedCases(),
                policy,
                token,
                caseNumber -> {
                    startedCases.incrementAndGet();
                    token.cancel();
                    return new TestCaseResult(caseNumber, "AC", 1, 1);
                },
                result -> {
                }
        );

        assertThat(outcome.isCancelled()).isTrue();
        assertThat(startedCases.get()).isLessThanOrEqualTo(policy.maxConcurrentCasesPerTask());
        assertThat(outcome.getSubmittedCases()).isLessThanOrEqualTo(policy.maxConcurrentCasesPerTask());
        assertThat(outcome.getCompletedCases()).isEqualTo(outcome.getSubmittedCases());
    }

    @Test
    void stopPredicateStopsDispatchingAfterFirstNonAcceptedResult() {
        ResolvedTaskPolicy policy = policy(10, 10, 1);
        List<TestCaseResult> results = new ArrayList<>();
        CaseBatchRunner runner = new CaseBatchRunner(Runnable::run);

        CaseBatchRunner.RunOutcome outcome = runner.run(
                policy.requestedCases(),
                policy,
                new CancellationToken(),
                caseNumber -> new TestCaseResult(caseNumber, caseNumber == 2 ? "WA" : "AC", 1, 1),
                results::add,
                result -> !"AC".equals(result.getStatus())
        );

        assertThat(outcome.isCancelled()).isFalse();
        assertThat(outcome.isStoppedAfterResult()).isTrue();
        assertThat(outcome.getSubmittedCases()).isEqualTo(2);
        assertThat(outcome.getCompletedCases()).isEqualTo(2);
        assertThat(results).extracting(TestCaseResult::getCaseNumber).containsExactly(1, 2);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 5, 6, 100_000})
    void batchBoundariesExecuteEveryCaseExactlyOnce(int totalCases) {
        ResolvedTaskPolicy policy = policy(totalCases, 5, 3);
        BitSet seenCases = new BitSet(totalCases + 1);
        CaseBatchRunner runner = new CaseBatchRunner(Runnable::run);

        CaseBatchRunner.RunOutcome outcome = runner.run(
                totalCases,
                policy,
                new CancellationToken(),
                caseNumber -> new TestCaseResult(caseNumber, "AC", 1, 1),
                result -> {
                    synchronized (seenCases) {
                        assertThat(seenCases.get(result.getCaseNumber())).isFalse();
                        seenCases.set(result.getCaseNumber());
                    }
                }
        );

        assertThat(outcome.getSubmittedCases()).isEqualTo(totalCases);
        assertThat(outcome.getCompletedCases()).isEqualTo(totalCases);
        assertThat(seenCases.cardinality()).isEqualTo(totalCases);
        assertThat(seenCases.nextClearBit(1)).isEqualTo(totalCases + 1);
    }

    @Test
    void outOfOrderCaseCompletionStillProducesStableAggregatedResults() {
        ResolvedTaskPolicy policy = policy(4, 4, 4);
        ResultAggregator aggregator = new ResultAggregator(false, 4, 10, 10);
        CountDownLatch allStarted = new CountDownLatch(4);
        List<Integer> completionOrder = Collections.synchronizedList(new ArrayList<>());
        CaseBatchRunner runner = runner(Executors.newFixedThreadPool(4));

        CaseBatchRunner.RunOutcome outcome = runner.run(
                policy.requestedCases(),
                policy,
                new CancellationToken(),
                caseNumber -> {
                    allStarted.countDown();
                    if (!allStarted.await(1, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("cases did not start concurrently");
                    }
                    Thread.sleep((5L - caseNumber) * 10L);
                    String status = caseNumber == 2 ? "WA" : "AC";
                    return new TestCaseResult(caseNumber, status, caseNumber, caseNumber);
                },
                result -> {
                    completionOrder.add(result.getCaseNumber());
                    aggregator.accept(result);
                }
        );

        JudgeProgress finalProgress = aggregator.toFinalProgress();

        assertThat(outcome.getCompletedCases()).isEqualTo(4);
        assertThat(completionOrder).isNotEqualTo(List.of(1, 2, 3, 4));
        assertThat(finalProgress.getResults())
                .extracting(TestCaseResult::getCaseNumber)
                .containsExactly(1, 2, 3, 4);
        assertThat(finalProgress.getSummary().getFirstFailedCase()).isEqualTo(2);
    }

    private CaseBatchRunner runner(ExecutorService executor) {
        executors.add(executor);
        return new CaseBatchRunner(executor);
    }

    private ResolvedTaskPolicy policy(int requestedCases, int batchSize, int maxConcurrentCases) {
        return new ResolvedTaskPolicy(
                "local-large",
                requestedCases >= 5_000,
                100_000,
                requestedCases,
                batchSize,
                maxConcurrentCases,
                Duration.ofSeconds(2),
                Duration.ofMinutes(30),
                268_435_456L,
                1_048_576L,
                false
        );
    }
}
