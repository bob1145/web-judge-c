package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SandboxTaskEvent(
        String judgeId,
        Type type,
        Instant occurredAt,
        Integer caseNumber,
        String status,
        String message,
        TestCaseResult result,
        JudgeSummary summary,
        Integer protocolVersion,
        String attemptId,
        Long sequence
) {

    public SandboxTaskEvent(String judgeId, Type type, Instant occurredAt, Integer caseNumber, String status,
                            String message, TestCaseResult result, JudgeSummary summary) {
        this(judgeId, type, occurredAt, caseNumber, status, message, result, summary, null, null, null);
    }

    public enum Type {
        COMPILE_STARTED,
        COMPILE_FINISHED,
        RUN_STARTED,
        RUN_FINISHED,
        SUMMARY,
        COMPLETED,
        CANCELLED,
        BUDGET_EXCEEDED,
        SECURITY_VIOLATION,
        SYSTEM_ERROR,
        SANDBOX_UNAVAILABLE
    }

    public static SandboxTaskEvent of(String judgeId, Type type, String message) {
        return new SandboxTaskEvent(judgeId, type, Instant.now(), null, null, message, null, null);
    }

    public static ObjectReader jsonReader(ObjectMapper objectMapper) {
        ObjectMapper eventMapper = objectMapper.copy();
        eventMapper.addMixIn(TestCaseResult.class, RequiredCaseFields.class);
        eventMapper.addMixIn(JudgeSummary.class, RequiredSummaryFields.class);
        eventMapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        eventMapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return eventMapper.readerFor(SandboxTaskEvent.class)
                .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
                        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    private abstract static class RequiredCaseFields {
        @JsonCreator
        RequiredCaseFields(@JsonProperty(value = "caseNumber", required = true) int caseNumber,
                           @JsonProperty(value = "status", required = true) String status,
                           @JsonProperty(value = "timeUsed", required = true) long timeUsed,
                           @JsonProperty(value = "memoryUsed", required = true) long memoryUsed) { }
    }

    private abstract static class RequiredSummaryFields {
        @JsonCreator
        RequiredSummaryFields(@JsonProperty(value = "totalCases", required = true) int totalCases,
                              @JsonProperty(value = "completedCases", required = true) int completedCases,
                              @JsonProperty(value = "ac", required = true) int ac,
                              @JsonProperty(value = "wa", required = true) int wa,
                              @JsonProperty(value = "tle", required = true) int tle,
                              @JsonProperty(value = "mle", required = true) int mle,
                              @JsonProperty(value = "re", required = true) int re,
                              @JsonProperty(value = "systemError", required = true) int systemError,
                              @JsonProperty(value = "outputLimitExceeded", required = true) int outputLimitExceeded,
                              @JsonProperty("firstFailedCase") Integer firstFailedCase,
                              @JsonProperty(value = "failureSamples", required = true) List<JudgeProgressEvent> failureSamples,
                              @JsonProperty(value = "slowSamples", required = true) List<JudgeProgressEvent> slowSamples,
                              @JsonProperty("stoppedReason") String stoppedReason) { }
    }
}
