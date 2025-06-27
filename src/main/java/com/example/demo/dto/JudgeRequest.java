package com.example.demo.dto;

import lombok.Data;
import java.util.List;

@Data
public class JudgeRequest {
    private String userCode;
    private String generatorCode;
    private String bruteForceCode;
    private long timeLimit;
    private double precision;
    private int testCases;
    private List<String> customTestInputs;
    private boolean spjEnabled;
    private String spjCode;
} 