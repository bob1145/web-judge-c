package com.example.demo.service.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.LINUX)
@Timeout(60)
class LinuxStageJournalUpdateInteropTest {
    private static final Path PYTHON = Path.of("/usr/bin/python3");
    private static final Path PYTHON_SOURCES = Path.of("runner", "scripts");
    private static final String CONTAINER_ID = "c".repeat(64);
    private static final BigInteger EXIT_CODE = new BigInteger("123456789012345678901234567890");
    private static final String PYTHON_PROBE = """
            import json
            import os
            import sys
            from pathlib import Path
            from types import SimpleNamespace
            import linux_stage_executor as executor

            mode, stage_root, journal_root, attempt, volume = sys.argv[1:]
            config = SimpleNamespace(stage_root=Path(stage_root), stage_volume=volume)
            def reject_runtime(*arguments, **keywords):
                raise AssertionError("journal interop must not execute a runtime command")
            stage_executor = executor.LinuxStageExecutor(config, attempt, Path(journal_root), reject_runtime)
            if mode == "write":
                record = json.loads(sys.stdin.read())
                stage_executor._journal(record, record["state"])
                print(json.dumps({"written": True}, separators=(",", ":")))
            else:
                directory = executor._open_locked_stage_journal(Path(journal_root), os.geteuid())
                try:
                    if mode == "check":
                        try:
                            stage_executor._check_journal(directory)
                            accepted = True
                        except executor.StageSecurityError:
                            accepted = False
                        print(json.dumps({"accepted": accepted}, separators=(",", ":")))
                    elif mode == "admission":
                        closed = executor._admission_entry_exists(directory, executor._ADMISSION_CLOSED)
                        pending = executor._admission_entry_exists(directory, executor._ADMISSION_PENDING)
                        record = executor._read_closed_admission(directory, attempt, os.geteuid()) if closed else None
                        print(json.dumps({"state": "REJECTED" if closed or pending else "OPEN", "record": record},
                                         separators=(",", ":")))
                    else:
                        raise AssertionError("unexpected fixed probe mode")
                finally:
                    os.close(directory)
            """;

    @TempDir
    Path temporaryDirectory;
    private final ObjectMapper mapper = LinuxStageRecord.strictMapper(new ObjectMapper());
    private Path stageRoot;
    private Path journal;
    private String attemptId;
    private String stageId;
    private String volumeName;
    private String fileName;
    private int ownerUid;

    @BeforeEach
    void prepare() throws IOException {
        assumeTrue(List.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        assumeTrue(Files.isExecutable(PYTHON));
        assumeTrue(Files.isRegularFile(PYTHON_SOURCES.resolve("linux_stage_executor.py")));
        temporaryDirectory = temporaryDirectory.toRealPath();
        stageRoot = Files.createDirectory(temporaryDirectory.resolve("stages"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        journal = Files.createDirectory(temporaryDirectory.resolve("journal"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        ownerUid = ((Number) Files.getAttribute(journal, "unix:uid")).intValue();
        attemptId = UUID.randomUUID().toString();
        stageId = UUID.randomUUID().toString();
        volumeName = "wjc-stage-" + attemptId;
        fileName = stageId + ".json";
    }

    static Stream<Arguments> rolePhaseOutcomeShapes() {
        return Stream.of("GENERATOR", "USER", "ORACLE", "SPECIAL_JUDGE")
                .flatMap(role -> Stream.of("compile", "execute")
                        .flatMap(phase -> Stream.of(false, true)
                                .map(outcomePresent -> Arguments.of(role, phase, outcomePresent))));
    }

    @ParameterizedTest
    @MethodSource("rolePhaseOutcomeShapes")
    void pythonWrittenRecordsRemainConsumableAcrossJavaRecoveryStates(String role, String phase,
                                                                      boolean outcomePresent) throws Exception {
        ObjectNode expected = record(role, phase, outcomePresent);
        assertTrue(runPython("write", mapper.writeValueAsBytes(expected)).path("written").booleanValue());
        assertEquals(expected, mapper.readTree(Files.readAllBytes(journal.resolve(fileName))));
        for (String state : List.of("REMOVING", "REMOVED", "CLEANED")) {
            ObjectNode updated = expected.deepCopy();
            updated.put("state", state);
            try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
                byte[] original = lease.readRegularEntry(fileName);
                assertEquals(expected, mapper.readTree(original));
                byte[] replacement = mapper.writeValueAsBytes(updated);
                lease.replaceStageRecord(fileName, original, replacement);
                assertArrayEquals(replacement, lease.readRegularEntry(fileName));
                LinuxStageRecord.Parsed parsed = parse(lease.readRegularEntry(fileName));
                assertEquals(role, parsed.role());
                assertEquals(phase, parsed.phase());
                assertEquals("CONFIRMED", parsed.creationState());
                assertEquals(CONTAINER_ID, parsed.containerId());
                assertEquals(state, parsed.state());
                assertEquals(outcomePresent ? updated.path("outcome") : null, parsed.outcome());
            }
            assertEquals(state.equals("CLEANED"), runPython("check", null).path("accepted").booleanValue());
            assertEquals(updated, mapper.readTree(Files.readAllBytes(journal.resolve(fileName))));
            expected = updated;
        }
        assertOnlyExpectedEntries(false);
    }

    @Test
    void pythonCanContinueFromJavaPublishedBytesWithoutLosingOptionalOutcome() throws Exception {
        ObjectNode initial = record("USER", "execute", true);
        runPython("write", mapper.writeValueAsBytes(initial));
        ObjectNode removing = initial.deepCopy();
        removing.put("state", "REMOVING");
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.replaceStageRecord(fileName, lease.readRegularEntry(fileName), mapper.writeValueAsBytes(removing));
        }
        ObjectNode removed = (ObjectNode) mapper.readTree(Files.readAllBytes(journal.resolve(fileName)));
        removed.put("state", "REMOVED");
        runPython("write", mapper.writeValueAsBytes(removed));
        ObjectNode cleaned = removed.deepCopy();
        cleaned.put("state", "CLEANED");
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            byte[] pythonBytes = lease.readRegularEntry(fileName);
            assertEquals(removed, mapper.readTree(pythonBytes));
            lease.replaceStageRecord(fileName, pythonBytes, mapper.writeValueAsBytes(cleaned));
            LinuxStageRecord.Parsed parsed = parse(lease.readRegularEntry(fileName));
            assertEquals(CONTAINER_ID, parsed.containerId());
            assertEquals(EXIT_CODE, parsed.outcome().path("exitCode").bigIntegerValue());
        }
        assertTrue(runPython("check", null).path("accepted").booleanValue());
        assertEquals(cleaned, mapper.readTree(Files.readAllBytes(journal.resolve(fileName))));
        assertOnlyExpectedEntries(false);
    }

    @Test
    void javaRecordUpdateNeverReopensOrReplacesClosedAdmission() throws Exception {
        ObjectNode initial = record("SPECIAL_JUDGE", "execute", false);
        runPython("write", mapper.writeValueAsBytes(initial));
        Path closedMarker = journal.resolve("stage-admission.closed");
        byte[] marker;
        Map<String, Object> markerIdentity;
        ObjectNode removing = initial.deepCopy();
        removing.put("state", "REMOVING");
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.publishClosedAdmission(attemptId);
            marker = Files.readAllBytes(closedMarker);
            markerIdentity = Files.readAttributes(closedMarker, "unix:dev,ino,uid,mode,nlink");
            lease.replaceStageRecord(fileName, lease.readRegularEntry(fileName), mapper.writeValueAsBytes(removing));
            assertEquals("REMOVING", parse(lease.readRegularEntry(fileName)).state());
        }
        assertArrayEquals(marker, Files.readAllBytes(closedMarker));
        assertEquals(markerIdentity, Files.readAttributes(closedMarker, "unix:dev,ino,uid,mode,nlink"));
        JsonNode pythonState = runPython("admission", null);
        assertEquals("REJECTED", pythonState.path("state").textValue());
        assertEquals("CLOSED", pythonState.path("record").path("state").textValue());
        assertEquals(attemptId, pythonState.path("record").path("attemptId").textValue());
        assertFalse(Files.exists(journal.resolve("stage-admission.pending")));
        assertOnlyExpectedEntries(true);
    }

    private ObjectNode record(String role, String phase, boolean outcomePresent) {
        ObjectNode record = mapper.createObjectNode();
        record.put("journalVersion", 1);
        record.put("creationState", "CONFIRMED");
        record.put("stage", stageId);
        record.put("attempt", attemptId);
        record.put("role", role);
        record.put("phase", phase);
        record.put("name", LinuxStageRecord.expectedName(stageId));
        record.set("labels", mapper.valueToTree(LinuxStageRecord.expectedLabels(attemptId, stageId, role)));
        record.put("volume", volumeName);
        record.put("state", outcomePresent ? "REMOVING" : "CREATED");
        record.put("containerId", CONTAINER_ID);
        if (outcomePresent) {
            ObjectNode outcome = record.putObject("outcome");
            outcome.put("status", "CANCELLED");
            outcome.put("exitCode", EXIT_CODE);
            outcome.put("oomKilled", false);
        }
        return record;
    }

    private LinuxStageRecord.Parsed parse(byte[] bytes) throws IOException {
        return LinuxStageRecord.parse(bytes, bytes.length, fileName, attemptId, volumeName, mapper);
    }

    private JsonNode runPython(String mode, byte[] input) throws Exception {
        Process process = new ProcessBuilder(PYTHON.toString(), "-B", "-c", PYTHON_PROBE, mode,
                stageRoot.toString(), journal.toString(), attemptId, volumeName)
                .directory(PYTHON_SOURCES.toAbsolutePath().toFile()).redirectErrorStream(true).start();
        try {
            try (var output = process.getOutputStream()) {
                if (input != null) {
                    output.write(input);
                }
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "fixed Python journal probe timed out");
            byte[] payload = process.getInputStream().readNBytes(4097);
            String text = new String(payload, StandardCharsets.UTF_8);
            assertTrue(payload.length <= 4096, "fixed Python journal response exceeded its bound");
            assertEquals(0, process.exitValue(), text);
            return mapper.readTree(payload);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "owned Python probe did not terminate");
            }
        }
    }

    private void assertOnlyExpectedEntries(boolean closed) throws IOException {
        List<String> expected = closed ? List.of(fileName, "runtime-client", "stage-admission.closed")
                : List.of(fileName, "runtime-client");
        try (var entries = Files.list(journal)) {
            assertEquals(expected.stream().sorted().toList(),
                    entries.map(entry -> entry.getFileName().toString()).sorted().toList());
        }
    }
}
