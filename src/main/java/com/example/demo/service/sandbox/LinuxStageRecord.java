package com.example.demo.service.sandbox;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Strict single-snapshot consumer for the Python stage journal writer contract
 * (linux_stage_executor.LinuxStageExecutor, journalVersion=1). Validates only the
 * structure and bound identity of one observed record; it never grants deletion,
 * recovery, or a generation barrier.
 */
final class LinuxStageRecord {
    private static final int MAX_BYTES = 64 * 1024;
    private static final Set<String> REQUIRED_FIELDS = Set.of("journalVersion", "creationState", "stage",
            "attempt", "role", "phase", "name", "labels", "volume", "state");
    private static final Set<String> OPTIONAL_FIELDS = Set.of("containerId", "outcome");
    private static final Set<String> OUTCOME_FIELDS = Set.of("status", "exitCode", "oomKilled");
    private static final Set<String> ROLES = Set.of("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE");
    private static final Set<String> PHASES = Set.of("compile", "execute");

    private LinuxStageRecord() {
    }

    static ObjectMapper strictMapper(ObjectMapper source) {
        ObjectMapper mapper = source.copy();
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(MAX_BYTES).maxNumberLength(1000).build());
        return mapper;
    }

    static Parsed parse(byte[] payload, int length, String fileName, String attemptId,
                        String expectedVolume, ObjectMapper strictMapper) throws IOException {
        if (payload == null || length < 1 || length > MAX_BYTES || payload.length < length
                || strictMapper == null || !isCanonicalUuid(attemptId)
                || expectedVolume == null || expectedVolume.isEmpty()) {
            throw new IOException("stage record requires bounded bytes and an explicit bound identity");
        }
        if (!isJournalFileName(fileName)) {
            throw new IOException("stage record must live in a canonical <uuid>.json entry");
        }
        String stage = fileName.substring(0, fileName.length() - 5);
        JsonNode record = strictMapper.readTree(payload, 0, length);
        if (!record.isObject()) {
            throw new IOException("stage record must be one JSON object");
        }
        Set<String> fields = new HashSet<>();
        record.fieldNames().forEachRemaining(fields::add);
        Set<String> extra = new HashSet<>(fields);
        extra.removeAll(REQUIRED_FIELDS);
        if (!fields.containsAll(REQUIRED_FIELDS) || !OPTIONAL_FIELDS.containsAll(extra)) {
            throw new IOException("stage record fields differ from the journalVersion=1 writer contract");
        }
        if (!record.path("journalVersion").isIntegralNumber() || !record.path("journalVersion").canConvertToInt()
                || record.path("journalVersion").intValue() != 1) {
            throw new IOException("unsupported stage journal version");
        }
        String creationState = requireText(record, "creationState");
        String state = requireText(record, "state");
        boolean containerIdPresent = fields.contains("containerId");
        boolean outcomePresent = fields.contains("outcome");
        requireWriterCombination(creationState, state, containerIdPresent, outcomePresent);
        if (!stage.equals(requireText(record, "stage"))) {
            throw new IOException("stage record identity differs from its entry name");
        }
        if (!attemptId.equals(requireText(record, "attempt"))) {
            throw new IOException("stage record attempt differs from the retained attempt");
        }
        if (!expectedName(stage).equals(requireText(record, "name"))) {
            throw new IOException("stage record container name is not attempt-bound");
        }
        String role = requireText(record, "role");
        if (!ROLES.contains(role)) {
            throw new IOException("unknown executable role in stage record");
        }
        if (!PHASES.contains(requireText(record, "phase"))) {
            throw new IOException("unknown stage phase in stage record");
        }
        if (!expectedVolume.equals(requireText(record, "volume"))) {
            throw new IOException("stage record volume differs from the retained stage volume");
        }
        requireBoundLabels(record.path("labels"), attemptId, stage, role);
        String containerId = null;
        if (containerIdPresent) {
            containerId = requireText(record, "containerId");
            if (!containerId.matches("[0-9a-f]{64}")) {
                throw new IOException("stage record container identity is not a complete ID");
            }
        }
        JsonNode outcome = null;
        if (outcomePresent) {
            outcome = requireBoundedOutcome(record.path("outcome"));
        }
        return new Parsed(stage, role, requireText(record, "phase"), creationState, state, containerId, outcome);
    }

    private static void requireWriterCombination(String creationState, String state,
                                                  boolean containerId, boolean outcome) throws IOException {
        boolean containerAllowed = false;
        boolean containerRequired = false;
        boolean outcomeAllowed = false;
        switch (creationState) {
            case "NOT_REQUESTED" -> {
                require(Set.of("INTENT", "CLEANED", "CLEANUP_FAILED").contains(state),
                        "impossible NOT_REQUESTED stage state");
            }
            case "UNKNOWN" -> {
                switch (state) {
                    case "CREATE_REQUESTED" -> {
                    }
                    case "REMOVING" -> {
                        containerAllowed = true;
                        containerRequired = true;
                    }
                    case "CLEANUP_PENDING", "CREATE_UNKNOWN" -> containerAllowed = true;
                    default -> throw new IOException("impossible UNKNOWN stage state");
                }
            }
            case "CONFIRMED" -> {
                switch (state) {
                    case "CREATED", "STARTING", "STOPPING" -> {
                        containerAllowed = true;
                        containerRequired = true;
                    }
                    case "REMOVING", "REMOVED", "CLEANED", "CLEANUP_FAILED" -> {
                        containerAllowed = true;
                        containerRequired = true;
                        outcomeAllowed = true;
                    }
                    default -> throw new IOException("impossible CONFIRMED stage state");
                }
            }
            default -> throw new IOException("unknown stage creation state");
        }
        require(outcomeAllowed || !outcome, "stage record outcome is forbidden in this creation state");
        require(containerAllowed || !containerId, "stage record container identity is forbidden in this state");
        require(!containerRequired || containerId, "stage record container identity is required in this state");
    }

    private static void requireBoundLabels(JsonNode labels, String attemptId, String stage, String role)
            throws IOException {
        if (labels == null || !labels.isObject() || labels.size() != 4) {
            throw new IOException("stage record labels must be the four bound labels");
        }
        Map<String, String> expected = expectedLabels(attemptId, stage, role);
        Set<String> actual = new HashSet<>();
        labels.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected.keySet())) {
            throw new IOException("stage record labels differ from the bound identity");
        }
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(labels.path(entry.getKey()).asText(null))
                    || !labels.path(entry.getKey()).isTextual()) {
                throw new IOException("stage record label value differs from the bound identity");
            }
        }
    }

    private static JsonNode requireBoundedOutcome(JsonNode outcome) throws IOException {
        if (outcome == null || !outcome.isObject() || outcome.size() != 3) {
            throw new IOException("stage record outcome must contain exactly status, exitCode and oomKilled");
        }
        Set<String> actual = new HashSet<>();
        outcome.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(OUTCOME_FIELDS)) {
            throw new IOException("stage record outcome fields differ from the writer contract");
        }
        if (!outcome.path("status").isTextual() || outcome.path("status").textValue().isEmpty()) {
            throw new IOException("stage record outcome status must be a non-empty string");
        }
        if (!outcome.path("exitCode").isIntegralNumber()) {
            throw new IOException("stage record outcome exit code must stay an untruncated integer");
        }
        if (!outcome.path("oomKilled").isBoolean()) {
            throw new IOException("stage record outcome oom flag must be a boolean");
        }
        return outcome;
    }

    private static String requireText(JsonNode record, String field) throws IOException {
        JsonNode value = record.path(field);
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw new IOException("stage record text field is missing or empty: " + field);
        }
        return value.textValue();
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    record Parsed(String stage, String role, String phase, String creationState, String state,
                  String containerId, JsonNode outcome) {
    }

    static String expectedName(String stage) {
        return "wjc-stage-" + stage;
    }

    static Map<String, String> expectedLabels(String attemptId, String stage, String role) {
        return Map.of("io.webjudge.attempt", attemptId, "io.webjudge.stage", stage,
                "io.webjudge.role", role, "io.webjudge.managed", "stage");
    }

    static boolean isCanonicalUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static boolean isJournalFileName(String name) {
        return name.endsWith(".json") && isCanonicalUuid(name.substring(0, name.length() - 5));
    }
}
