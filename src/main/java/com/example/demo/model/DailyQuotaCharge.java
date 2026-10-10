package com.example.demo.model;

import com.example.demo.security.SessionAuditId;
import com.example.demo.service.ResolvedTaskPolicy;

import java.time.Instant;

public record DailyQuotaCharge(
        String chargeId,
        String ownerFingerprint,
        Instant createdAt,
        long requestedCases,
        long runtimeCases,
        Long caseTimeLimitMillis
) {

    public static DailyQuotaCharge fromTask(JudgeTask task) {
        JudgeOwnership ownership = task.getOwnership();
        String ownerId = ownership == null ? null : ownership.getUserId();
        String ownerFingerprint = ownerId == null || ownerId.isBlank() ? null : SessionAuditId.from(ownerId);
        String chargeId = task.getQuotaChargeId() == null
                ? "legacy:" + task.getJudgeId() + ":" + task.getCreatedAt() : task.getQuotaChargeId();
        ResolvedTaskPolicy policy = task.getPolicy();
        long runtimeCases = policy == null ? task.getRequestedCases() : policy.requestedCases();
        Long caseTimeLimitMillis = policy == null ? null
                : policy.caseTimeLimit() == null ? 0L : Math.max(0, policy.caseTimeLimit().toMillis());
        return new DailyQuotaCharge(chargeId, ownerFingerprint, task.getCreatedAt(),
                task.getRequestedCases(), runtimeCases, caseTimeLimitMillis);
    }
}
