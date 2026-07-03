package com.example.demo;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeProgressEvent;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.service.ResultAggregator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultAggregatorTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    void smallTaskKeepsAllResultsSortedAndSummarized() {
        ResultAggregator aggregator = new ResultAggregator(false, 3, 100, 10);

        aggregator.accept(new TestCaseResult(3, "AC", 30, 300));
        aggregator.accept(new TestCaseResult(1, "AC", 10, 100));
        aggregator.accept(new TestCaseResult(2, "WA", 20, 200));

        JudgeProgress progress = aggregator.toFinalProgress();

        assertThat(progress.getStatus()).isEqualTo("WA");
        assertThat(progress.getMessage()).isEqualTo("WA on Test Case #2");
        assertThat(progress.getProgress()).isEqualTo(100);
        assertThat(progress.getResults()).extracting(TestCaseResult::getCaseNumber)
                .containsExactly(1, 2, 3);
        assertThat(progress.getSummary().getTotalCases()).isEqualTo(3);
        assertThat(progress.getSummary().getCompletedCases()).isEqualTo(3);
        assertThat(progress.getSummary().getAc()).isEqualTo(2);
        assertThat(progress.getSummary().getWa()).isEqualTo(1);
        assertThat(progress.getSummary().getFirstFailedCase()).isEqualTo(2);
    }

    @Test
    void highVolumeSummaryDoesNotExposeFullResultsAndKeepsPayloadBounded() throws Exception {
        ResultAggregator aggregator = new ResultAggregator(true, 100_000, 100, 10);

        for (int caseNumber = 1; caseNumber <= 100_000; caseNumber++) {
            aggregator.accept(resultForLargeRun(caseNumber));
        }

        JudgeProgress progress = aggregator.toFinalProgress();
        JudgeSummary summary = progress.getSummary();
        byte[] payload = objectMapper.writeValueAsString(progress).getBytes(StandardCharsets.UTF_8);

        assertThat(progress.getResults()).isNull();
        assertThat(summary.getTotalCases()).isEqualTo(100_000);
        assertThat(summary.getCompletedCases()).isEqualTo(100_000);
        assertThat(summary.getAc()).isEqualTo(99_991);
        assertThat(summary.getWa()).isEqualTo(5);
        assertThat(summary.getTle()).isEqualTo(2);
        assertThat(summary.getMle()).isZero();
        assertThat(summary.getRe()).isEqualTo(1);
        assertThat(summary.getSystemError()).isEqualTo(1);
        assertThat(summary.getOutputLimitExceeded()).isZero();
        assertThat(summary.getFirstFailedCase()).isEqualTo(42);
        assertThat(summary.getFailureSamples()).hasSize(9);
        assertThat(summary.getSlowSamples()).hasSizeLessThanOrEqualTo(10);
        assertThat(payload.length).isLessThan(65_536);
    }

    @Test
    void highVolumeFailureSamplesAreLimitedAndOrderedByCaseNumber() {
        ResultAggregator aggregator = new ResultAggregator(true, 1_000, 3, 10);

        aggregator.accept(new TestCaseResult(900, "WA", 9, 1));
        aggregator.accept(new TestCaseResult(10, "WA", 10, 1));
        aggregator.accept(new TestCaseResult(500, "TLE", 50, 1));
        aggregator.accept(new TestCaseResult(3, "RE", 30, 1));
        aggregator.accept(new TestCaseResult(700, "MLE", 70, 1));

        List<JudgeProgressEvent> samples = aggregator.toFinalProgress().getSummary().getFailureSamples();

        assertThat(aggregator.toFinalProgress().getSummary().getFirstFailedCase()).isEqualTo(3);
        assertThat(samples).extracting(JudgeProgressEvent::getCaseNumber)
                .containsExactly(3, 10, 500);
    }

    @Test
    void slowSamplesKeepOnlySlowestCases() {
        ResultAggregator aggregator = new ResultAggregator(true, 5, 100, 2);

        aggregator.accept(new TestCaseResult(1, "AC", 10, 100));
        aggregator.accept(new TestCaseResult(2, "AC", 50, 200));
        aggregator.accept(new TestCaseResult(3, "AC", 30, 300));
        aggregator.accept(new TestCaseResult(4, "AC", 70, 400));
        aggregator.accept(new TestCaseResult(5, "AC", 20, 500));

        assertThat(aggregator.toFinalProgress().getSummary().getSlowSamples())
                .extracting(JudgeProgressEvent::getCaseNumber)
                .containsExactly(4, 2);
    }

    private TestCaseResult resultForLargeRun(int caseNumber) {
        if (caseNumber == 42) {
            return new TestCaseResult(caseNumber, "RE", 42, 1024);
        }
        if (caseNumber == 99_999) {
            return new TestCaseResult(caseNumber, "System Error", 99, 1024);
        }
        if (caseNumber % 40_000 == 0) {
            return new TestCaseResult(caseNumber, "TLE", caseNumber, 1024);
        }
        if (caseNumber == 777 || caseNumber % 25_000 == 0) {
            return new TestCaseResult(caseNumber, "WA", caseNumber, 1024);
        }
        return new TestCaseResult(caseNumber, "AC", caseNumber % 100, 512);
    }
}
