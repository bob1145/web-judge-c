package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
@Data
@NoArgsConstructor
public class JudgeProgress {
    private String status;
    private String message;
    private int progress;
    private List<TestCaseResult> results;
    private JudgeSummary summary;
    private String attemptId;
    private Long revision;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant finalizedAt;

    public JudgeProgress(String status, String message, int progress, List<TestCaseResult> results) {
        this.status = status;
        this.message = message;
        this.progress = progress;
        this.results = results;
    }

    public JudgeProgress(String status, String message, int progress) {
        this.status = status;
        this.message = message;
        this.progress = progress;
    }

    public JudgeProgress(String status, String message, int progress, List<TestCaseResult> results, JudgeSummary summary) {
        this(status, message, progress, results, summary, null, null, null);
    }

    public JudgeProgress(String status, String message, int progress, List<TestCaseResult> results, JudgeSummary summary,
                         String attemptId, Long revision, Instant finalizedAt) {
        this.status = status;
        this.message = message;
        this.progress = progress;
        this.results = results;
        this.summary = summary;
        this.attemptId = attemptId;
        this.revision = revision;
        this.finalizedAt = finalizedAt;
    }

    public JudgeProgress withoutResults() {
        return new JudgeProgress(status, message, progress, null, summary, attemptId, revision, finalizedAt);
    }

}
