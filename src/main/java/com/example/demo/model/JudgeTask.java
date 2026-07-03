package com.example.demo.model;

import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.service.ResolvedTaskPolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeTask {

    private String judgeId;
    private JudgeStatus status;
    private int requestedCases;
    private String mode;
    private ResolvedTaskPolicy policy;
    private SandboxRunHandle sandboxRunHandle;
    private JudgeOwnership ownership;
    private String workDir;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private String message;
}
