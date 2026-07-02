package com.example.demo.controller;

import com.example.demo.dto.JudgeCreateResponse;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.dto.CancelJudgeResponse;
import com.example.demo.dto.TestCaseDetail;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.JudgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
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

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class JudgeController {

    private final JudgeService judgeService;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping(value = "/judge", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> judge(
            @RequestBody JudgeRequest judgeRequest,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader) {
        String judgeId = UUID.randomUUID().toString();
        // 创建判题任务
        JudgeCreateResponse response = judgeService.createJudgeTask(judgeRequest, judgeId);
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
    public ResponseEntity<?> startJudge(@PathVariable String judgeId) {
        try {
            judgeService.startJudgeTask(judgeId);
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
    public ResponseEntity<CancelJudgeResponse> cancelJudge(@PathVariable String judgeId) {
        CancelJudgeResponse response = judgeService.cancelJudgeTask(judgeId);
        if (!response.accepted() && "NOT_FOUND".equals(response.status())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/judge/status/{judgeId}")
    @ResponseBody
    public ResponseEntity<?> getJudgeStatus(@PathVariable String judgeId) {
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
            @PathVariable int caseNumber) {
        try {
            TestCaseDetail details = judgeService.getTestCaseDetails(judgeId, caseNumber);
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
            @PathVariable int caseNumber) {
        try {
            File inputFile = judgeService.getTestCaseInputFile(judgeId, caseNumber);
            Resource resource = new FileSystemResource(inputFile);

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + caseNumber + ".in\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/download/{judgeId}/all")
    public ResponseEntity<Resource> downloadAllTestCases(@PathVariable String judgeId) {
        try {
            Resource archive = judgeService.getAllTestCasesArchive(judgeId);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archive.getFilename() + "\"")
                    .body(archive);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/judge/cleanup/{judgeId}")
    @ResponseBody
    public ResponseEntity<String> cleanupJudge(@PathVariable String judgeId) {
        try {
            judgeService.cleanupJudgeTask(judgeId);
            return ResponseEntity.ok("Cleanup initiated");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Cleanup failed: " + e.getMessage());
        }
    }
} 
