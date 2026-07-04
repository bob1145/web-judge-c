package com.example.demo.dto;

import lombok.Data;

@Data
public class TestCaseDetail {
    private String input;
    private String userOutput;
    private String correctOutput;
    private boolean inputTruncated;
    private boolean userOutputTruncated;
    private boolean correctOutputTruncated;

    public TestCaseDetail(String input, String userOutput, String correctOutput) {
        this(input, userOutput, correctOutput, false, false, false);
    }

    public TestCaseDetail(
            String input,
            String userOutput,
            String correctOutput,
            boolean inputTruncated,
            boolean userOutputTruncated,
            boolean correctOutputTruncated
    ) {
        this.input = input;
        this.userOutput = userOutput;
        this.correctOutput = correctOutput;
        this.inputTruncated = inputTruncated;
        this.userOutputTruncated = userOutputTruncated;
        this.correctOutputTruncated = correctOutputTruncated;
    }

}
