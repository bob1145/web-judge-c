package com.example.demo.dto;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record SandboxExecutionManifest(
        int protocolVersion,
        String judgeId,
        String attemptId,
        Map<SandboxTaskSpec.SourceRole, String> sourcePaths,
        int testCases,
        SandboxComparison comparison,
        long caseTimeLimitMillis,
        long maxTaskRuntimeMillis,
        long memoryLimitBytes,
        long maxOutputBytesPerCase,
        Boolean stopOnFirstNonAc,
        RetentionMillis retention
) {
    public static final int VERSION = 2;
    public static final String SPEC_FILE = "task-spec.json";
    public static final String EVENT_FILE = "runner-events.jsonl";

    public SandboxExecutionManifest {
        if (protocolVersion != VERSION) {
            throw new IllegalArgumentException("unsupported runner protocolVersion");
        }
        if (judgeId == null || !judgeId.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("judgeId must be a task identifier");
        }
        if (attemptId == null || !UUID.fromString(attemptId).toString().equals(attemptId)) {
            throw new IllegalArgumentException("attemptId must be a canonical UUID");
        }
        Objects.requireNonNull(comparison, "comparison is required");
        Objects.requireNonNull(retention, "retention is required");
        Objects.requireNonNull(stopOnFirstNonAc, "stopOnFirstNonAc is required");
        if (testCases <= 0 || caseTimeLimitMillis <= 0 || maxTaskRuntimeMillis <= 0
                || memoryLimitBytes <= 0 || maxOutputBytesPerCase <= 0) {
            throw new IllegalArgumentException("runner case count and explicit limits must be positive");
        }
        sourcePaths = Map.copyOf(Objects.requireNonNull(sourcePaths, "sourcePaths is required"));
        if (!sourcePaths.containsKey(SandboxTaskSpec.SourceRole.GENERATOR)
                || !sourcePaths.containsKey(SandboxTaskSpec.SourceRole.USER)) {
            throw new IllegalArgumentException("generator and user sources are required");
        }
        boolean specialJudge = comparison.mode() == SandboxComparison.Mode.SPECIAL_JUDGE;
        if (sourcePaths.containsKey(SandboxTaskSpec.SourceRole.SPECIAL_JUDGE) != specialJudge
                || sourcePaths.containsKey(SandboxTaskSpec.SourceRole.ORACLE) == specialJudge) {
            throw new IllegalArgumentException("source roles do not match comparison mode");
        }
        sourcePaths.values().forEach(SandboxExecutionManifest::requireRelativePath);
        if (sourcePaths.values().stream().map(value -> value.toLowerCase(java.util.Locale.ROOT)).distinct().count() != sourcePaths.size()) {
            throw new IllegalArgumentException("source roles must use distinct files");
        }
    }

    public String attemptDirectory() {
        return "control/attempts/" + attemptId;
    }

    public String specPath() {
        return attemptDirectory() + "/" + SPEC_FILE;
    }

    public String eventPath() {
        return attemptDirectory() + "/" + EVENT_FILE;
    }

    public static void requireRelativePath(String value) {
        if (value == null || value.isEmpty() || value.chars().anyMatch(character -> character < 32)
                || value.chars().anyMatch(character -> "\\:<>\"|?*".indexOf(character) >= 0)) {
            throw new IllegalArgumentException("source path must be portable and relative");
        }
        for (String component : value.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                    || component.endsWith(".") || component.endsWith(" ")
                    || component.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
                throw new IllegalArgumentException("source path contains an unsafe component");
            }
        }
        String firstComponent = value.split("/", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (java.util.Set.of("control", "workspace", "artifacts").contains(firstComponent)) {
            throw new IllegalArgumentException("source path cannot name runner control or output files");
        }
    }

    public record RetentionMillis(long completedMillis, long failedMillis, long cancelledMillis) {
        public RetentionMillis {
            if (completedMillis <= 0 || failedMillis <= 0 || cancelledMillis <= 0) {
                throw new IllegalArgumentException("retention milliseconds must be positive");
            }
        }
    }
}
