package com.example.demo.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
@Data
@NoArgsConstructor
public class JudgeProgress {
    private String status;
    private String message;
    private int progress;
    private List<TestCaseResult> results;
    private JudgeSummary summary;

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
        this.status = status;
        this.message = message;
        this.progress = progress;
        this.results = results;
        this.summary = summary;
    }

    public JudgeProgress withoutResults() {
        return new JudgeProgress(status, message, progress, null, summary);
    }

}
