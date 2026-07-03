package com.example.demo.controller;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.SecurityModeStartupValidator;
import com.example.demo.dto.JudgeCreateResponse;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.dto.CancelJudgeResponse;
import com.example.demo.dto.TestCaseDetail;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.AuditService;
import com.example.demo.service.JudgeFileService;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.JudgeService;
import com.example.demo.service.QuotaService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Controller
public class JudgeController {

    private final JudgeService judgeService;
    private final JudgeFileService judgeFileService;
    private final AccessCodeService accessCodeService;
    private final AuditService auditService;
    private final ExecutionProperties executionProperties;
    private static final String SESSION_COOKIE_NAME = "JUDGE_SESSION";

    @Autowired
    public JudgeController(JudgeService judgeService,
                           JudgeFileService judgeFileService,
                           AccessCodeService accessCodeService,
                           AuditService auditService,
                           ExecutionProperties executionProperties) {
        this.judgeService = judgeService;
        this.judgeFileService = judgeFileService;
        this.accessCodeService = accessCodeService;
        this.auditService = auditService;
        this.executionProperties = executionProperties;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping(value = "/judge", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> judge(
            @RequestBody JudgeRequest judgeRequest,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletRequest request) {
        UserSession session = currentSession(request);
        if (session == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "code", "AUTH_REQUIRED",
                    "message", "Authentication is required"
            ));
        }
        String judgeId = UUID.randomUUID().toString();
        // 创建判题任务
        JudgeCreateResponse response;
        try {
            response = judgeService.createJudgeTask(judgeRequest, judgeId, session);
            auditService.record("task.create", session, judgeId, response.mode(), Map.of(
                    "requestedCases", response.requestedCases(),
                    "highVolume", response.highVolume(),
                    "status", response.status()
            ));
        } catch (SecurityModeStartupValidator.PublicJudgeDisabledException e) {
            auditSecurityDenied(session, judgeId, "task.create");
            return ResponseEntity.status(503).body(Map.of(
                    "code", "JUDGE_DISABLED",
                    "message", e.getMessage()
            ));
        } catch (QuotaService.QuotaExceededException e) {
            auditService.record("quota.reject", session, judgeId, executionProperties.getProfile(), Map.of(
                    "quota", e.getQuota(),
                    "used", e.getUsed(),
                    "requested", e.getRequested(),
                    "limit", e.getLimit()
            ));
            throw e;
        }
        if (wantsStructuredJson(acceptHeader)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(response.judgeId());
    }

    private boolean wantsStructuredJson(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return false;
        }
        try {
            return MediaType.parseMediaTypes(acceptHeader).stream()
                    .filter(mediaType -> !mediaType.isWildcardType() && !mediaType.isWildcardSubtype())
                    .anyMatch(mediaType -> MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
                            || mediaType.getSubtype().endsWith("+json"));
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
    }

    @PostMapping("/judge/start/{judgeId}")
    @ResponseBody
    public ResponseEntity<?> startJudge(@PathVariable String judgeId, HttpServletRequest request) {
        UserSession session = currentSession(request);
        if (!canAccess(judgeId, session)) {
            auditSecurityDenied(session, judgeId, "task.start");
            return ResponseEntity.notFound().build();
        }
        try {
            judgeService.startJudgeTask(judgeId);
            auditService.record("task.start", session, judgeId, executionProperties.getProfile(), Map.of(
                    "accepted", true
            ));
            return ResponseEntity.ok("Judge task started");
        } catch (JudgeScheduler.QueueFullException e) {
            return ResponseEntity.status(429).body(Map.of(
                    "code", "JUDGE_QUEUE_FULL",
                    "message", e.getMessage(),
                    "queueCapacity", e.getQueueCapacity(),
                    "currentQueueSize", e.getCurrentQueueSize(),
                    "retryAfter", "Please retry later"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/judge/cancel/{judgeId}")
    @ResponseBody
    public ResponseEntity<CancelJudgeResponse> cancelJudge(@PathVariable String judgeId, HttpServletRequest request) {
        UserSession session = currentSession(request);
        if (!canAccess(judgeId, session)) {
            auditSecurityDenied(session, judgeId, "task.cancel");
            return ResponseEntity.notFound().build();
        }
        CancelJudgeResponse response = judgeService.cancelJudgeTask(judgeId);
        auditService.record("task.cancel", session, judgeId, executionProperties.getProfile(), Map.of(
                "accepted", response.accepted(),
                "status", response.status(),
                "running", response.running(),
                "queued", response.queued()
        ));
        if (!response.accepted() && "NOT_FOUND".equals(response.status())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/judge/status/{judgeId}")
    @ResponseBody
    public ResponseEntity<?> getJudgeStatus(@PathVariable String judgeId, HttpServletRequest request) {
        UserSession session = currentSession(request);
        if (!canAccess(judgeId, session)) {
            auditSecurityDenied(session, judgeId, "task.status");
            return ResponseEntity.notFound().build();
        }
        try {
            Object status = judgeService.getJudgeStatus(judgeId);
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/details/{judgeId}/{caseNumber}")
    @ResponseBody
    public ResponseEntity<?> getDetails(
            @PathVariable String judgeId,
            @PathVariable int caseNumber,
            HttpServletRequest request) {
        UserSession session = currentSession(request);
        if (!canAccess(judgeId, session)) {
            auditSecurityDenied(session, judgeId, "task.details");
            return ResponseEntity.notFound().build();
        }
        try {
            TestCaseDetail details = judgeFileService.getTestCaseDetails(judgeId, caseNumber);
            return ResponseEntity.ok(details);
        } catch (IOException e) {
            // 返回详细的错误信息给前端
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            // 对于其他异常，返回500错误
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/download/{judgeId}/{caseNumber}")
    public ResponseEntity<Resource> downloadInput(
            @PathVariable String judgeId,
            @PathVariable int caseNumber,
            HttpServletRequest request) {
        UserSession session = currentSession(request);
        if (!canAccess(judgeId, session)) {
            auditSecurityDenied(session, judgeId, "task.download");
            return ResponseEntity.notFound().build();
        }
        try {
            Resource resource = new org.springframework.core.io.PathResource(
                    judgeFileService.getTestCaseInputFile(judgeId, caseNumber));
            auditService.record("task.download", session, judgeId, executionProperties.getProfile(), Map.of(
                    "caseNumber", caseNumber,
                    "archive", false
            ));

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + caseNumber + ".in\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/download/{judgeId}/all")
    public ResponseEntity<StreamingResponseBody> downloadAllTestCases(
            @PathVariable String judgeId,
            HttpServletRequest request) {
        UserSession session = currentSession(request);
        if (!canAccess(judgeId, session)) {
            auditSecurityDenied(session, judgeId, "task.download-all");
            return ResponseEntity.notFound().build();
        }
        try {
            StreamingResponseBody archive = judgeFileService.streamAllTestCasesArchive(judgeId);
            auditService.record("task.download", session, judgeId, executionProperties.getProfile(), Map.of(
                    "archive", true
            ));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + judgeFileService.archiveFilename(judgeId) + "\"")
                    .body(archive);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/judge/cleanup/{judgeId}")
    @ResponseBody
    public ResponseEntity<String> cleanupJudge(@PathVariable String judgeId, HttpServletRequest request) {
        UserSession session = currentSession(request);
        if (!canAccess(judgeId, session)) {
            auditSecurityDenied(session, judgeId, "task.cleanup");
            return ResponseEntity.notFound().build();
        }
        try {
            judgeService.cleanupJudgeTask(judgeId);
            return ResponseEntity.ok("Cleanup initiated");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Cleanup failed: " + e.getMessage());
        }
    }

    private boolean canAccess(String judgeId, UserSession session) {
        return judgeService.canAccessJudgeTask(judgeId, session);
    }

    private void auditSecurityDenied(UserSession session, String judgeId, String operation) {
        auditService.record("security.denied", session, judgeId, executionProperties.getProfile(), Map.of(
                "operation", operation,
                "reason", "access denied"
        ));
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
} 
