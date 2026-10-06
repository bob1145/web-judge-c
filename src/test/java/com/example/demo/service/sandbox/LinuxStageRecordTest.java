package com.example.demo.service.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict consumption of every Python stage journal creation/state shape documented
 * for the journalVersion=1 writer (14 creation/state combinations, 20 structural
 * shapes once optional containerId/outcome presence is considered).
 */
class LinuxStageRecordTest {
    private static final String ATTEMPT = "1b6e288e-4a51-4a3d-9d1a-2f5c7d9b1a01";
    private static final String VOLUME = "wjc-stage-volume-" + ATTEMPT;
    private static final ObjectMapper PLAIN = new ObjectMapper().findAndRegisterModules();
    private final ObjectMapper strict = LinuxStageRecord.strictMapper(PLAIN);
    private String stage;
    private String fileName;

    enum Shape {
        NOT_REQUESTED_INTENT("NOT_REQUESTED", "INTENT", false, false),
        NOT_REQUESTED_CLEANED("NOT_REQUESTED", "CLEANED", false, false),
        NOT_REQUESTED_CLEANUP_FAILED("NOT_REQUESTED", "CLEANUP_FAILED", false, false),
        UNKNOWN_CREATE_REQUESTED("UNKNOWN", "CREATE_REQUESTED", false, false),
        UNKNOWN_REMOVING("UNKNOWN", "REMOVING", true, false),
        UNKNOWN_CLEANUP_PENDING_WITHOUT_ID("UNKNOWN", "CLEANUP_PENDING", false, false),
        UNKNOWN_CLEANUP_PENDING_WITH_ID("UNKNOWN", "CLEANUP_PENDING", true, false),
        UNKNOWN_CREATE_UNKNOWN_WITHOUT_ID("UNKNOWN", "CREATE_UNKNOWN", false, false),
        UNKNOWN_CREATE_UNKNOWN_WITH_ID("UNKNOWN", "CREATE_UNKNOWN", true, false),
        CONFIRMED_CREATED("CONFIRMED", "CREATED", true, false),
        CONFIRMED_STARTING("CONFIRMED", "STARTING", true, false),
        CONFIRMED_STOPPING("CONFIRMED", "STOPPING", true, false),
        CONFIRMED_REMOVING_WITHOUT_OUTCOME("CONFIRMED", "REMOVING", true, false),
        CONFIRMED_REMOVING_WITH_OUTCOME("CONFIRMED", "REMOVING", true, true),
        CONFIRMED_REMOVED_WITHOUT_OUTCOME("CONFIRMED", "REMOVED", true, false),
        CONFIRMED_REMOVED_WITH_OUTCOME("CONFIRMED", "REMOVED", true, true),
        CONFIRMED_CLEANED_WITHOUT_OUTCOME("CONFIRMED", "CLEANED", true, false),
        CONFIRMED_CLEANED_WITH_OUTCOME("CONFIRMED", "CLEANED", true, true),
        CONFIRMED_CLEANUP_FAILED_WITHOUT_OUTCOME("CONFIRMED", "CLEANUP_FAILED", true, false),
        CONFIRMED_CLEANUP_FAILED_WITH_OUTCOME("CONFIRMED", "CLEANUP_FAILED", true, true);

        final String creationState;
        final String state;
        final boolean containerId;
        final boolean outcome;

        Shape(String creationState, String state, boolean containerId, boolean outcome) {
            this.creationState = creationState;
            this.state = state;
            this.containerId = containerId;
            this.outcome = outcome;
        }
    }

    @BeforeEach
    void prepare() {
        stage = UUID.nameUUIDFromBytes(("stage-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8)).toString();
        fileName = stage + ".json";
    }

    @ParameterizedTest
    @EnumSource(Shape.class)
    void everyWriterShapeIsAcceptedWithBoundIdentity(Shape shape) {
        LinuxStageRecord.Parsed parsed = assertDoesNotThrow(() -> parse(record(shape)));
        assertEquals(stage, parsed.stage());
        assertEquals("USER", parsed.role());
        assertEquals("execute", parsed.phase());
        assertEquals(shape.creationState, parsed.creationState());
        assertEquals(shape.state, parsed.state());
        if (shape.containerId) {
            assertEquals("a".repeat(64), parsed.containerId());
        } else {
            assertNull(parsed.containerId());
        }
        if (shape.outcome) {
            JsonNode outcome = parsed.outcome();
            assertNotNull(outcome);
            assertEquals("SUCCESS", outcome.path("status").asText());
            assertTrue(outcome.path("oomKilled").isBoolean());
        } else {
            assertNull(parsed.outcome());
        }
    }

    @Test
    void pythonWriterBytesAreConsumedDirectly() throws IOException {
        String python = PLAIN.writeValueAsString(record(Shape.CONFIRMED_CLEANED_WITH_OUTCOME));
        byte[] bytes = python.getBytes(StandardCharsets.UTF_8);
        LinuxStageRecord.Parsed parsed = LinuxStageRecord.parse(bytes, bytes.length, fileName, ATTEMPT, VOLUME, strict);
        assertEquals("CLEANED", parsed.state());
        assertEquals("CONFIRMED", parsed.creationState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate-field", "trailing-token", "empty", "oversize", "two-documents"})
    void malformedPayloadsAreRejected(String mutation) throws IOException {
        String json = PLAIN.writeValueAsString(record(Shape.NOT_REQUESTED_INTENT));
        byte[] bytes = switch (mutation) {
            case "duplicate-field" -> json.replaceFirst("\\{", "{\"stage\":\"" + stage + "\",").getBytes(StandardCharsets.UTF_8);
            case "trailing-token" -> (json + " null").getBytes(StandardCharsets.UTF_8);
            case "empty" -> new byte[0];
            case "two-documents" -> (json + json).getBytes(StandardCharsets.UTF_8);
            default -> ("{\"padding\":\"" + "x".repeat(64 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        };
        assertThrows(IOException.class, () -> LinuxStageRecord.parse(bytes, bytes.length, fileName, ATTEMPT, VOLUME, strict));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-field", "missing-state", "journalVersion-2", "journalVersion-text",
            "wrong-stage", "wrong-attempt", "wrong-name", "wrong-volume", "wrong-role", "wrong-phase",
            "missing-label", "extra-label", "labels-not-object", "not-an-object", "bad-containerId",
            "outcome-extra-field", "outcome-missing-field", "outcome-status-empty", "outcome-exit-text",
            "outcome-oom-text", "impossible-creation-state", "impossible-state"})
    void identityAndContractViolationsAreRejected(String mutation) throws IOException {
        Map<String, Object> record = record(Shape.CONFIRMED_CLEANED_WITH_OUTCOME);
        switch (mutation) {
            case "unknown-field" -> record.put("unexpected", true);
            case "missing-state" -> record.remove("state");
            case "journalVersion-2" -> record.put("journalVersion", 2);
            case "journalVersion-text" -> record.put("journalVersion", "1");
            case "wrong-stage" -> record.put("stage", UUID.randomUUID().toString());
            case "wrong-attempt" -> record.put("attempt", UUID.randomUUID().toString());
            case "wrong-name" -> record.put("name", "other-" + stage);
            case "wrong-volume" -> record.put("volume", "wjc-stage-volume-other");
            case "wrong-role" -> record.put("role", "ATTACKER");
            case "wrong-phase" -> record.put("phase", "destroy");
            case "missing-label" -> ((Map<String, Object>) record.get("labels")).remove("io.webjudge.managed");
            case "extra-label" -> ((Map<String, Object>) record.get("labels")).put("io.webjudge.extra", "x");
            case "labels-not-object" -> record.put("labels", "stage");
            case "not-an-object" -> record.clear();            case "bad-containerId" -> record.put("containerId", "XYZ");
            case "outcome-extra-field" -> ((Map<String, Object>) record.get("outcome")).put("extra", 1);
            case "outcome-missing-field" -> ((Map<String, Object>) record.get("outcome")).remove("oomKilled");
            case "outcome-status-empty" -> ((Map<String, Object>) record.get("outcome")).put("status", "");
            case "outcome-exit-text" -> ((Map<String, Object>) record.get("outcome")).put("exitCode", "0");
            case "outcome-oom-text" -> ((Map<String, Object>) record.get("outcome")).put("oomKilled", "false");
            case "impossible-creation-state" -> record.put("creationState", "CONFIRMED_MAYBE");
            case "impossible-state" -> record.put("state", "RESTARTED");
            default -> {
            }
        }
        byte[] bytes = PLAIN.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> LinuxStageRecord.parse(bytes, bytes.length, fileName, ATTEMPT, VOLUME, strict));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_REQUESTED_INTENT", "UNKNOWN_CREATE_REQUESTED", "CONFIRMED_CREATED"})
    void forbiddenOptionalFieldsAreRejected(String name) throws IOException {
        Shape shape = Shape.valueOf(name);
        Map<String, Object> record = record(shape);
        if (shape.containerId) {
            return;
        }
        record.put("containerId", "b".repeat(64));
        byte[] bytes = PLAIN.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> LinuxStageRecord.parse(bytes, bytes.length, fileName, ATTEMPT, VOLUME, strict));
    }

    @Test
    void unknownCreationStateNeverUpgradesThroughContainerIdPresence() throws IOException {
        for (Shape shape : new Shape[]{Shape.UNKNOWN_CLEANUP_PENDING_WITH_ID, Shape.UNKNOWN_CREATE_UNKNOWN_WITH_ID,
                Shape.UNKNOWN_REMOVING}) {
            Map<String, Object> record = record(shape);
            byte[] bytes = PLAIN.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
            LinuxStageRecord.Parsed parsed = assertDoesNotThrow(
                    () -> LinuxStageRecord.parse(bytes, bytes.length, fileName, ATTEMPT, VOLUME, strict));
            assertEquals("UNKNOWN", parsed.creationState(), shape.name());
        }
    }

    @Test
    void outcomeExitCodeKeepsUntruncatedLargePythonIntegers() throws IOException {
        Map<String, Object> record = record(Shape.CONFIRMED_CLEANED_WITH_OUTCOME);
        ((Map<String, Object>) record.get("outcome")).put("exitCode", 4294967296L);
        byte[] bytes = PLAIN.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
        LinuxStageRecord.Parsed parsed = assertDoesNotThrow(
                () -> LinuxStageRecord.parse(bytes, bytes.length, fileName, ATTEMPT, VOLUME, strict));
        assertEquals(4294967296L, parsed.outcome().path("exitCode").longValue());
        assertFalse(parsed.outcome().path("exitCode").canConvertToInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"uppercase-uuid", "non-uuid-file", "dotfile", "no-suffix", "double-suffix"})
    void nonCanonicalFileNamesAreRejectedBeforeParsing(String kind) throws IOException {
        Map<String, Object> record = record(Shape.NOT_REQUESTED_INTENT);
        String name = switch (kind) {
            case "uppercase-uuid" -> stage.toUpperCase() + ".json";
            case "non-uuid-file" -> "not-a-uuid.json";
            case "dotfile" -> ".hidden.json";
            case "no-suffix" -> stage;
            default -> stage + ".json.json";
        };
        byte[] bytes = PLAIN.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> LinuxStageRecord.parse(bytes, bytes.length, name, ATTEMPT, VOLUME, strict));
    }

    @Test
    void helperPredicatesMatchTheWriterContract() {
        assertTrue(LinuxStageRecord.isJournalFileName(fileName));
        assertFalse(LinuxStageRecord.isJournalFileName("stage-admission.closed"));
        assertFalse(LinuxStageRecord.isJournalFileName("runtime-client"));
        assertEquals("wjc-stage-" + stage, LinuxStageRecord.expectedName(stage));
        Map<String, String> labels = LinuxStageRecord.expectedLabels(ATTEMPT, stage, "ORACLE");
        assertEquals(4, labels.size());
        assertEquals("stage", labels.get("io.webjudge.managed"));
    }

    private LinuxStageRecord.Parsed parse(Map<String, Object> record) throws IOException {
        byte[] bytes = PLAIN.writeValueAsString(record).getBytes(StandardCharsets.UTF_8);
        return LinuxStageRecord.parse(bytes, bytes.length, fileName, ATTEMPT, VOLUME, strict);
    }

    private Map<String, Object> record(Shape shape) {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("io.webjudge.attempt", ATTEMPT);
        labels.put("io.webjudge.stage", stage);
        labels.put("io.webjudge.role", "USER");
        labels.put("io.webjudge.managed", "stage");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("journalVersion", 1);
        record.put("creationState", shape.creationState);
        record.put("stage", stage);
        record.put("attempt", ATTEMPT);
        record.put("role", "USER");
        record.put("phase", "execute");
        record.put("name", "wjc-stage-" + stage);
        record.put("labels", labels);
        record.put("volume", VOLUME);
        record.put("state", shape.state);
        if (shape.containerId) {
            record.put("containerId", "a".repeat(64));
        }
        if (shape.outcome) {
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("status", "SUCCESS");
            outcome.put("exitCode", 0);
            outcome.put("oomKilled", false);
            record.put("outcome", outcome);
        }
        return record;
    }
}
