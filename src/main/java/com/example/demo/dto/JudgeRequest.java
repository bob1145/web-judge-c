package com.example.demo.dto;

import lombok.Data;

@Data
public class JudgeRequest {
    private String userCode;
    private String generatorCode;
    private String bruteForceCode;
    private long timeLimit;
    private double precision;
    private int testCases;
} 