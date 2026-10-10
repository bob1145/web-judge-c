package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class JudgeRequest {
    private String userCode;
    private String generatorCode;
    private String bruteForceCode;
    private long timeLimit;
    private long memoryLimit; // 添加内存限制配置（字节）
    private String precision = "0";
    private int testCases;
    private boolean stopOnFirstNonAc;
    private boolean useSpecialJudge; // 是否启用Special Judge
    private String specialJudgeCode; // Special Judge代码

    @JsonIgnore
    public double getPrecision() {
        return Double.parseDouble(precision);
    }

    @JsonIgnore
    public void setPrecision(double precision) {
        this.precision = Double.toString(precision);
    }

    @JsonSetter(value = "precision", nulls = Nulls.FAIL)
    public void setPrecision(String value) {
        SandboxComparison.parseDecimal(value);
        this.precision = value;
    }

    @JsonGetter("precision")
    public BigDecimal getDecimalPrecision() {
        return SandboxComparison.parseDecimal(precision);
    }

    public String precisionText() {
        return precision;
    }
}
