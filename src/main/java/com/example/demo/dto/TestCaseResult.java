package com.example.demo.dto;

import lombok.Data;

@Data
public class TestCaseResult {
    private int caseNumber;
    private String status;
    private long timeUsed;  // ms
    private long memoryUsed; // KB

    public TestCaseResult(int caseNumber, String status, long timeUsed, long memoryUsed) {
        this.caseNumber = caseNumber;
        this.status = status;
        this.timeUsed = timeUsed;
        this.memoryUsed = memoryUsed;
    }

}