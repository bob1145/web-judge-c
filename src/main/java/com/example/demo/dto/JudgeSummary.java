package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JudgeSummary {

    private int totalCases;
    private int completedCases;
    private int ac;
    private int wa;
    private int tle;
    private int mle;
    private int re;
    private int systemError;
    private int outputLimitExceeded;
    private Integer firstFailedCase;
    private List<JudgeProgressEvent> failureSamples;
    private List<JudgeProgressEvent> slowSamples;
    private String stoppedReason;
}
