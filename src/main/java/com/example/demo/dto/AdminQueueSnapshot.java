package com.example.demo.dto;

import com.example.demo.service.AuditService;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminQueueSnapshot(
        Instant timestamp,
        int queuedCount,
        int runningCount,
        int peakRunningCount,
        int peakQueueSize,
        int maxConcurrentTasks,
        int queueCapacity,
        ProviderHealth providerHealth,
        Map<String, Long> recentFailureCounts,
        List<TaskResourceSummary> taskResourceSummaries,
        List<AuditService.AuditEvent> recentAuditEvents
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProviderHealth(
            String provider,
            String isolation,
            boolean productionSafe,
            boolean networkDisabled,
            boolean nonRoot,
            boolean resourceLimits,
            String securityProfile,
            String details
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskResourceSummary(
            String judgeId,
            String userId,
            String status,
            String provider,
            int requestedCases,
            long estimatedRuntimeMillis,
            long memoryLimitBytes,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            String message
    ) {
    }
}
