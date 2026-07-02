package com.example.demo.dto;

import com.example.demo.service.ResolvedTaskPolicy;

public record JudgeCreateResponse(
        String judgeId,
        String mode,
        int requestedCases,
        int maxCasesPerTask,
        boolean highVolume,
        String status
) {

    public static JudgeCreateResponse created(String judgeId, ResolvedTaskPolicy policy) {
        return new JudgeCreateResponse(
                judgeId,
                policy.profile(),
                policy.requestedCases(),
                policy.maxCasesPerTask(),
                policy.highVolume(),
                "CREATED"
        );
    }
}
