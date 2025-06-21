package com.example.demo.service;

enum RunStatus {
    SUCCESS,
    TIME_LIMIT_EXCEEDED,
    RUNTIME_ERROR
}

record ProcessResult(RunStatus status, String output, String error, long executionTime) {} 