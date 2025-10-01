package com.example.demo.dto;

import lombok.Data;

@Data
public class JudgeRequest {
    private String userCode;
    private String generatorCode;
    private String bruteForceCode;
    private long timeLimit;
    private long memoryLimit; // 添加内存限制配置（字节）
    private double precision;
    private int testCases;
    private boolean useSpecialJudge; // 是否启用Special Judge
    private String specialJudgeCode; // Special Judge代码
} 