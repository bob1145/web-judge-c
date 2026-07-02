package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JudgeProgressEvent {

    private int caseNumber;
    private String status;
    private long timeUsed;
    private long memoryUsed;

    public static JudgeProgressEvent from(TestCaseResult result) {
        return new JudgeProgressEvent(
                result.getCaseNumber(),
                result.getStatus(),
                result.getTimeUsed(),
                result.getMemoryUsed()
        );
    }
}
