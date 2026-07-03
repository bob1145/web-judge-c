package com.example.demo.controller;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.AdminQueueSnapshot;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.AuditService;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.TaskStore;
import com.example.demo.service.sandbox.SandboxRunner;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequiredArgsConstructor
public class AdminController {

    private static final String SESSION_COOKIE_NAME = "JUDGE_SESSION";

    private final AccessCodeService accessCodeService;
    private final JudgeScheduler judgeScheduler;
    private final TaskStore taskStore;
    private final ExecutionProperties executionProperties;
    private final AuditService auditService;
    private final Optional<SandboxRunner> sandboxRunner;

    @GetMapping("/admin/queue")
    public ResponseEntity<?> queueSnapshot(HttpServletRequest request) throws IOException {
        UserSession session = currentSession(request);
        if (session == null || !session.isAdmin()) {
            auditService.record("security.denied", session, null, executionProperties.getProfile(), Map.of(
                    "operation", "admin.queue",
                    "reason", "admin role required"
            ));
            return ResponseEntity.status(403).body(Map.of(
                    "code", "ADMIN_REQUIRED",
                    "message", "Admin role is required"
            ));
        }

        AdminQueueSnapshot snapshot = buildSnapshot();
        auditService.record("admin.queue.view", session, null, executionProperties.getProfile(), Map.of(
                "queuedCount", snapshot.queuedCount(),
                "runningCount", snapshot.runningCount()
        ));
        return ResponseEntity.ok(snapshot);
    }

    private AdminQueueSnapshot buildSnapshot() throws IOException {
        JudgeScheduler.QueueSnapshot queue = judgeScheduler.snapshot();
        List<JudgeTask> tasks = taskStore.findAll();
        int persistedRunningCount = (int) tasks.stream()
                .filter(task -> isRunning(task.getStatus()))
                .count();
        int persistedQueuedCount = (int) tasks.stream()
                .filter(task -> isQueued(task.getStatus()))
                .count();

        return new AdminQueueSnapshot(
                Instant.now(),
                Math.max(queue.queuedCount(), persistedQueuedCount),
                Math.max(queue.runningCount(), persistedRunningCount),
                queue.peakRunningCount(),
                queue.peakQueueSize(),
                queue.maxConcurrentTasks(),
                queue.queueCapacity(),
                providerHealth(),
                failureCounts(tasks),
                taskSummaries(tasks),
                auditService.recentEvents(50)
        );
    }

    private AdminQueueSnapshot.ProviderHealth providerHealth() {
        if (sandboxRunner.isEmpty()) {
            return new AdminQueueSnapshot.ProviderHealth(
                    "not-configured",
                    "none",
                    false,
                    false,
                    false,
                    false,
                    null,
                    "SandboxRunner is not configured"
            );
        }
        try {
            SandboxCapabilities capabilities = sandboxRunner.get().probe();
            return new AdminQueueSnapshot.ProviderHealth(
                    valueOrUnknown(capabilities.provider()),
                    valueOrUnknown(capabilities.isolation()),
                    capabilities.productionSafe(),
                    capabilities.networkDisabled(),
                    capabilities.nonRoot(),
                    capabilities.resourceLimits(),
                    capabilities.securityProfile(),
                    firstNonBlank(capabilities.details(), capabilities.skipReason())
            );
        } catch (RuntimeException ex) {
            return new AdminQueueSnapshot.ProviderHealth(
                    "probe-failed",
                    "unknown",
                    false,
                    false,
                    false,
                    false,
                    null,
                    ex.getMessage()
            );
        }
    }

    private Map<String, Long> failureCounts(List<JudgeTask> tasks) {
        Map<String, Long> counts = tasks.stream()
                .filter(task -> isFailure(task.getStatus()))
                .collect(Collectors.groupingBy(
                        task -> task.getStatus().name(),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        return Map.copyOf(counts);
    }

    private List<AdminQueueSnapshot.TaskResourceSummary> taskSummaries(List<JudgeTask> tasks) {
        return tasks.stream()
                .sorted(Comparator.comparing(
                        JudgeTask::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .limit(50)
                .map(this::taskSummary)
                .toList();
    }

    private AdminQueueSnapshot.TaskResourceSummary taskSummary(JudgeTask task) {
        ResolvedTaskPolicy policy = task.getPolicy();
        return new AdminQueueSnapshot.TaskResourceSummary(
                task.getJudgeId(),
                userId(task),
                task.getStatus() == null ? "UNKNOWN" : task.getStatus().name(),
                task.getMode(),
                task.getRequestedCases(),
                estimatedRuntimeMillis(task),
                policy == null ? 0 : policy.memoryLimitBytes(),
                task.getCreatedAt(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getMessage()
        );
    }

    private long estimatedRuntimeMillis(JudgeTask task) {
        ResolvedTaskPolicy policy = task.getPolicy();
        long perCaseMillis = policy == null || policy.caseTimeLimit() == null
                ? Math.max(0, executionProperties.getDefaultTimeLimit().toMillis())
                : Math.max(0, policy.caseTimeLimit().toMillis());
        if (task.getRequestedCases() > Long.MAX_VALUE / Math.max(1, perCaseMillis)) {
            return Long.MAX_VALUE;
        }
        return task.getRequestedCases() * perCaseMillis;
    }

    private boolean isRunning(JudgeStatus status) {
        return status == JudgeStatus.PENDING
                || status == JudgeStatus.COMPILING
                || status == JudgeStatus.RUNNING;
    }

    private boolean isQueued(JudgeStatus status) {
        return status == JudgeStatus.CREATED
                || status == JudgeStatus.QUEUED;
    }

    private boolean isFailure(JudgeStatus status) {
        return status == JudgeStatus.WA
                || status == JudgeStatus.TLE
                || status == JudgeStatus.MLE
                || status == JudgeStatus.RE
                || status == JudgeStatus.COMPILATION_ERROR
                || status == JudgeStatus.SYSTEM_ERROR
                || status == JudgeStatus.SECURITY_VIOLATION
                || status == JudgeStatus.SANDBOX_UNAVAILABLE
                || status == JudgeStatus.BUDGET_EXCEEDED;
    }

    private String userId(JudgeTask task) {
        JudgeOwnership ownership = task.getOwnership();
        if (ownership == null || ownership.getUserId() == null || ownership.getUserId().isBlank()) {
            return "anonymous";
        }
        return ownership.getUserId();
    }

    private UserSession currentSession(HttpServletRequest request) {
        String sessionId = sessionIdFromRequest(request);
        return sessionId == null ? null : accessCodeService.getSession(sessionId);
    }

    private String sessionIdFromRequest(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (SESSION_COOKIE_NAME.equals(cookie.getName())
                        && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return cookie.getValue().trim();
                }
            }
        }
        String header = request.getHeader("X-Session-ID");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String parameter = request.getParameter("sessionId");
        if (parameter != null && !parameter.isBlank()) {
            return parameter.trim();
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
