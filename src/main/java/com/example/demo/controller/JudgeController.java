package com.example.demo.controller;

import com.example.demo.dto.JudgeRequest;
import com.example.demo.dto.TestCaseDetail;
import com.example.demo.service.JudgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class JudgeController {

    private final JudgeService judgeService;

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @PostMapping("/judge")
    @ResponseBody
    public String judge(@RequestBody JudgeRequest judgeRequest) {
        String judgeId = UUID.randomUUID().toString();
        // 创建判题任务
        judgeService.createJudgeTask(judgeRequest, judgeId);
        return judgeId;
    }

    @PostMapping("/judge/start/{judgeId}")
    @ResponseBody
    public ResponseEntity<String> startJudge(@PathVariable String judgeId) {
        try {
            judgeService.startJudgeTask(judgeId);
            return ResponseEntity.ok("Judge task started");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
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
    public ResponseEntity<TestCaseDetail> getDetails(
            @PathVariable String judgeId,
            @PathVariable int caseNumber) {
        try {
            TestCaseDetail details = judgeService.getTestCaseDetails(judgeId, caseNumber);
            return ResponseEntity.ok(details);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
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
} 