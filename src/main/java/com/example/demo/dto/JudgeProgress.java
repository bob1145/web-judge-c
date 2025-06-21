package com.example.demo.dto;

import lombok.Data;

import java.util.List;
@Data
public class JudgeProgress {
    private String status;
    private String message;
    private int progress;
    private List<TestCaseResult> results;

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

} 