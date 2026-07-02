package com.example.demo.service;

public record ProcessResult(
        Status status,
        String output,
        String error,
        long executionTime,
        long memoryUsed,
        int exitCode
) {

    public enum Status {
        SUCCESS,
        TIME_LIMIT_EXCEEDED,
        MEMORY_LIMIT_EXCEEDED,
        OUTPUT_LIMIT_EXCEEDED,
        RUNTIME_ERROR,
        SECURITY_VIOLATION,
        SANDBOX_UNAVAILABLE
    }

    public ProcessResult {
        output = output == null ? "" : output;
        error = error == null ? "" : error;
    }

    public static ProcessResult failure(Status status, String error) {
        return new ProcessResult(status, "", error, 0, 0, -1);
    }
}
