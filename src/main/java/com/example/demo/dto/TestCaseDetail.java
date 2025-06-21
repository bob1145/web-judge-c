package com.example.demo.dto;

import lombok.Data;

@Data
public class TestCaseDetail {
    private String input;
    private String userOutput;
    private String correctOutput;

    public TestCaseDetail(String input, String userOutput, String correctOutput) {
        this.input = input;
        this.userOutput = userOutput;
        this.correctOutput = correctOutput;
    }

}