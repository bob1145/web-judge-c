package com.example.demo.dto;

public record CancelJudgeResponse(
        String judgeId,
        boolean accepted,
        String status,
        String message,
        boolean running,
        boolean queued,
        int completedCases
) {
}
