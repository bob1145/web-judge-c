package com.example.demo.dto;

public record JudgeErrorResponse(
        String code,
        String message,
        int submitted,
        int max,
        String profile
) {
}
