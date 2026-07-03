package com.example.demo.service;

import com.example.demo.config.AsyncConfig;
import com.example.demo.dto.TestCaseResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

@Service
public class CaseBatchRunner {

    private final Executor executor;

    public CaseBatchRunner(@Qualifier(AsyncConfig.TEST_CASE_EXECUTOR) Executor executor) {
        this.executor = executor;
    }

    public RunOutcome run(
            int totalCases,
            ResolvedTaskPolicy policy,
            CancellationToken cancellationToken,
            CaseExecution caseExecution,
            Consumer<TestCaseResult> resultConsumer
    ) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(cancellationToken, "cancellationToken must not be null");
        Objects.requireNonNull(caseExecution, "caseExecution must not be null");
        Objects.requireNonNull(resultConsumer, "resultConsumer must not be null");

        int batchSize = Math.max(1, policy.batchSize());
        int maxConcurrentCases = Math.max(1, Math.min(policy.maxConcurrentCasesPerTask(), batchSize));
        ExecutorCompletionService<CaseCompletion> completionService = new ExecutorCompletionService<>(executor);

        int submittedCases = 0;
        int completedCases = 0;
        int peakScheduledFutures = 0;

        for (int batchStart = 1; batchStart <= totalCases; batchStart += batchSize) {
            if (cancellationToken.isCancellationRequested()) {
                break;
            }

            int batchEnd = Math.min(totalCases, batchStart + batchSize - 1);
            int nextCase = batchStart;
            int submittedInBatch = 0;
            int completedInBatch = 0;

            while (completedInBatch < submittedInBatch
                    || (nextCase <= batchEnd && !cancellationToken.isCancellationRequested())) {
                while (!cancellationToken.isCancellationRequested()
                        && nextCase <= batchEnd
                        && submittedInBatch - completedInBatch < maxConcurrentCases) {
                    int caseNumber = nextCase++;
                    completionService.submit(() -> runSingleCase(caseNumber, caseExecution));
                    submittedCases++;
                    submittedInBatch++;
                    peakScheduledFutures = Math.max(peakScheduledFutures, submittedInBatch - completedInBatch);
                }

                if (completedInBatch == submittedInBatch) {
                    break;
                }

                CaseCompletion completion = takeCompletedCase(completionService, cancellationToken);
                if (completion == null) {
                    break;
                }
                completedInBatch++;
                completedCases++;
                resultConsumer.accept(completion.result());
            }

            if (cancellationToken.isCancellationRequested()) {
                completedCases += drainSubmittedCases(
                        completionService,
                        submittedInBatch - completedInBatch,
                        resultConsumer
                );
                break;
            }
        }

        return new RunOutcome(
                totalCases,
                submittedCases,
                completedCases,
                cancellationToken.isCancellationRequested(),
                peakScheduledFutures
        );
    }

    private CaseCompletion takeCompletedCase(
            ExecutorCompletionService<CaseCompletion> completionService,
            CancellationToken cancellationToken
    ) {
        try {
            Future<CaseCompletion> future = completionService.take();
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancellationToken.cancel();
            return null;
        } catch (ExecutionException e) {
            throw new IllegalStateException("Case execution failed unexpectedly", e);
        }
    }

    private int drainSubmittedCases(
            ExecutorCompletionService<CaseCompletion> completionService,
            int remainingCases,
            Consumer<TestCaseResult> resultConsumer
    ) {
        int drainedCases = 0;
        for (int i = 0; i < remainingCases; i++) {
            try {
                Future<CaseCompletion> future = completionService.take();
                resultConsumer.accept(future.get().result());
                drainedCases++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return drainedCases;
            } catch (ExecutionException e) {
                throw new IllegalStateException("Case execution failed unexpectedly", e);
            }
        }
        return drainedCases;
    }

    private CaseCompletion runSingleCase(int caseNumber, CaseExecution caseExecution) {
        try {
            TestCaseResult result = caseExecution.run(caseNumber);
            if (result == null) {
                return new CaseCompletion(caseNumber, systemError(caseNumber));
            }
            return new CaseCompletion(caseNumber, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CaseCompletion(caseNumber, systemError(caseNumber));
        } catch (Exception e) {
            return new CaseCompletion(caseNumber, systemError(caseNumber));
        }
    }

    private TestCaseResult systemError(int caseNumber) {
        return new TestCaseResult(caseNumber, "System Error", 0, 0);
    }

    @FunctionalInterface
    public interface CaseExecution {
        TestCaseResult run(int caseNumber) throws Exception;
    }

    private record CaseCompletion(int caseNumber, TestCaseResult result) {
    }

    public static class RunOutcome {

        private final int totalCases;
        private final int submittedCases;
        private final int completedCases;
        private final boolean cancelled;
        private final int peakScheduledFutures;

        RunOutcome(int totalCases, int submittedCases, int completedCases, boolean cancelled, int peakScheduledFutures) {
            this.totalCases = totalCases;
            this.submittedCases = submittedCases;
            this.completedCases = completedCases;
            this.cancelled = cancelled;
            this.peakScheduledFutures = peakScheduledFutures;
        }

        public int getTotalCases() {
            return totalCases;
        }

        public int getSubmittedCases() {
            return submittedCases;
        }

        public int getCompletedCases() {
            return completedCases;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public int getPeakScheduledFutures() {
            return peakScheduledFutures;
        }
    }
}
