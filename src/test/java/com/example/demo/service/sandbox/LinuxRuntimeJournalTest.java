package com.example.demo.service.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
class LinuxRuntimeJournalTest {
    private static final String ATTEMPT_ID = "12345678-1234-4234-8234-123456789abc";

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper mapper = new ObjectMapper();
    private Path attemptDirectory;
    private int uid;
    private int gid;

    @BeforeEach
    void prepare() throws IOException {
        attemptDirectory = temporaryDirectory.resolve("task/control/attempts/" + ATTEMPT_ID);
        Files.createDirectories(attemptDirectory);
        Map<String, Object> attributes = Files.readAttributes(attemptDirectory, "unix:uid,gid");
        uid = ((Number) attributes.get("uid")).intValue();
        gid = ((Number) attributes.get("gid")).intValue();
    }

    @Test
    void roundTripPersistsAndCopiesSnapshots() throws IOException {
        ObjectNode snapshot = snapshot();
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot);
            snapshot.put("state", "MUTATED");
            ObjectNode read = journal.read();
            assertEquals("NEW", read.path("state").asText());
            read.put("state", "MUTATED_AGAIN");
            assertEquals("NEW", journal.read().path("state").asText());
            ObjectNode next = snapshot();
            next.put("state", "CREATE_UNKNOWN");
            journal.write(next);
            assertEquals(next, journal.read());
        }
        assertEquals("CREATE_UNKNOWN", retained().path("state").asText());
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(attemptDirectory.resolve("runtime-state.json")));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(attemptDirectory.resolve("runtime-state.lock")));
    }

    @Test
    void exclusiveLockRejectsCompetingReaderAndNewCreator() throws IOException {
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            assertThrows(IOException.class, this::retained);
            assertThrows(IOException.class, this::create);
        }
        assertNotNull(retained());
        assertThrows(IOException.class, this::create);
    }

    @Test
    void abandonedLockRetainsEvidenceWithoutJournal() throws IOException {
        create().close();
        assertThrows(IOException.class, this::create);
        assertThrows(IOException.class, this::retained);
        assertTrue(Files.exists(attemptDirectory.resolve("runtime-state.lock")));
    }

    @Test
    void recoveredJournalKeepsLockAcrossReadAndUpdate() throws IOException {
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
        }
        try (LinuxRuntimeJournal recovered = LinuxRuntimeJournal.openExisting(
                attemptDirectory, "judge-1", ATTEMPT_ID, mapper, uid, gid)) {
            assertEquals(snapshot(), recovered.read());
            assertThrows(IOException.class, this::retained);
            recovered.write(snapshot().put("state", "RETAINED"));
            assertThrows(IOException.class, this::retained);
        }
        assertEquals("RETAINED", retained().path("state").asText());
    }

    @Test
    void supervisorConfigurationIsBoundedPrivateAndCreateOnly() throws IOException {
        try (LinuxRuntimeJournal journal = create()) {
            ObjectNode configuration = mapper.createObjectNode().put("supervisorUid", uid);
            journal.writeControlFile("supervisor-config.json", configuration);
            Path configPath = attemptDirectory.resolve("supervisor-config.json");
            assertEquals(configuration, mapper.readTree(Files.readAllBytes(configPath)));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(configPath));
            assertThrows(IOException.class, () -> journal.writeControlFile("supervisor-config.json", configuration));
            assertThrows(IOException.class, () -> journal.writeControlFile("../escape.json", configuration));
            assertFalse(Files.exists(attemptDirectory.getParent().resolve("escape.json")));
        }
    }

    @Test
    void cancellationMarkerIsPrivateEmptyDurableAndIdempotentAcrossReopen() throws IOException {
        Path marker = attemptDirectory.resolve("cancel.requested");
        Object identity;
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            journal.requestCancellation();
            assertTrue(Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertEquals(0, Files.size(marker));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(marker));
            assertEquals(uid, ((Number) Files.getAttribute(marker, "unix:uid")).intValue());
            assertEquals(gid, ((Number) Files.getAttribute(marker, "unix:gid")).intValue());
            assertEquals(1, ((Number) Files.getAttribute(marker, "unix:nlink")).intValue());
            identity = Files.getAttribute(marker, "basic:fileKey");
            assertNotNull(identity);
            journal.requestCancellation();
            assertEquals(identity, Files.getAttribute(marker, "basic:fileKey"));
            assertEquals(snapshot(), journal.read());
        }
        try (LinuxRuntimeJournal journal = LinuxRuntimeJournal.openExisting(
                attemptDirectory, "judge-1", ATTEMPT_ID, mapper, uid, gid)) {
            journal.requestCancellation();
            assertEquals(identity, Files.getAttribute(marker, "basic:fileKey"));
        }
        assertEquals(snapshot(), retained());
    }

    @ParameterizedTest
    @ValueSource(strings = {"symlink", "dangling-symlink", "hard-link", "directory", "contents", "mode", "uid", "gid"})
    void cancellationRejectsUnsafeExistingMarkerPoisonsLeaseAndRetainsEvidence(String shape) throws IOException {
        Path marker = attemptDirectory.resolve("cancel.requested");
        Path other = Files.createFile(temporaryDirectory.resolve("other"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            byte[] before = Files.readAllBytes(attemptDirectory.resolve("runtime-state.json"));
            switch (shape) {
                case "symlink" -> Files.createSymbolicLink(marker, other);
                case "dangling-symlink" -> Files.createSymbolicLink(marker, temporaryDirectory.resolve("missing"));
                case "hard-link" -> Files.createLink(marker, other);
                case "directory" -> Files.createDirectory(marker);
                default -> {
                    Files.createFile(marker, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    switch (shape) {
                        case "contents" -> Files.writeString(marker, "unsafe");
                        case "mode" -> Files.setPosixFilePermissions(marker, PosixFilePermissions.fromString("rw-r--r--"));
                        case "uid", "gid" -> {
                            String fixtureDirectory = System.getProperty("judge.test.foreign-identity-directory");
                            if (fixtureDirectory == null) {
                                Files.setAttribute(marker, "unix:" + shape, shape.equals("uid") ? uid + 1 : gid + 1);
                            } else {
                                Path fixtureBase = Path.of(fixtureDirectory);
                                assertTrue(fixtureBase.isAbsolute());
                                assertEquals(fixtureBase.normalize(), fixtureBase);
                                Path foreign = fixtureBase.resolve(shape + ".marker");
                                Map<String, Object> ownership = Files.readAttributes(foreign,
                                        "unix:uid,gid,mode,nlink,size,fileKey,isRegularFile", java.nio.file.LinkOption.NOFOLLOW_LINKS);
                                assertEquals(true, ownership.get("isRegularFile"));
                                assertFalse(Files.isSymbolicLink(foreign));
                                if (shape.equals("uid")) {
                                    assertNotEquals(uid, ((Number) ownership.get("uid")).intValue());
                                    assertEquals(gid, ((Number) ownership.get("gid")).intValue());
                                } else {
                                    assertEquals(uid, ((Number) ownership.get("uid")).intValue());
                                    assertNotEquals(gid, ((Number) ownership.get("gid")).intValue());
                                }
                                assertEquals(0600, ((Number) ownership.get("mode")).intValue() & 07777);
                                assertEquals(1, ((Number) ownership.get("nlink")).intValue());
                                assertEquals(0L, ((Number) ownership.get("size")).longValue());
                                Files.move(foreign, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                                Map<String, Object> installed = Files.readAttributes(marker,
                                        "unix:uid,gid,mode,nlink,size,fileKey,isRegularFile", java.nio.file.LinkOption.NOFOLLOW_LINKS);
                                assertEquals(ownership, installed);
                                System.out.printf("Journal %s negative: actual uid=%d/gid=%d, expected writer uid=%d/gid=%d, native inode retained%n",
                                        shape, ((Number) installed.get("uid")).intValue(),
                                        ((Number) installed.get("gid")).intValue(), uid, gid);
                            }
                        }
                        default -> throw new AssertionError("unknown marker shape");
                    }
                }
            }
            Map<String, Object> attributes = Files.readAttributes(marker, "unix:*", java.nio.file.LinkOption.NOFOLLOW_LINKS);
            assertThrows(IOException.class, journal::requestCancellation);
            assertThrows(IOException.class, journal::read);
            assertEquals(attributes, Files.readAttributes(marker, "unix:*", java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertArrayEquals(before, Files.readAllBytes(attemptDirectory.resolve("runtime-state.json")));
            assertEquals(0, Files.size(other));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime-state.json.tmp", "cancel.requested.tmp"})
    void cancellationRefusesRetainedTemporaryEvidenceWithoutPublishingMarker(String filename) throws IOException {
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            Path temporary = attemptDirectory.resolve(filename);
            Files.writeString(temporary, "retained");
            assertThrows(IOException.class, journal::requestCancellation);
            assertFalse(Files.exists(attemptDirectory.resolve("cancel.requested")));
            assertEquals("retained", Files.readString(temporary));
        }
    }

    @Test
    void refusesWrongIdentityBeforeCreatingLock() {
        assertThrows(IOException.class, () -> LinuxRuntimeJournal.create(
                attemptDirectory, "judge-1", ATTEMPT_ID, mapper, uid + 1, gid));
        assertFalse(Files.exists(attemptDirectory.resolve("runtime-state.lock")));
    }

    @Test
    void refusesAttemptOutsideExactControlAttemptsLayout() throws IOException {
        for (String relative : new String[]{"task/other/attempts/", "task/control/other/",
                "task/control/attempts/nested/"}) {
            Path misplaced = temporaryDirectory.resolve(relative + ATTEMPT_ID);
            Files.createDirectories(misplaced);
            assertThrows(IOException.class, () -> LinuxRuntimeJournal.create(
                    misplaced, "judge-1", ATTEMPT_ID, mapper, uid, gid));
            assertFalse(Files.exists(misplaced.resolve("runtime-state.lock")));
        }
    }

    @Test
    void refusesNoncanonicalUuidEvenWhenItMatchesDirectoryName() throws IOException {
        for (String invalid : new String[]{"attempt-1", ATTEMPT_ID.toUpperCase(java.util.Locale.ROOT),
                "1-1-1-1-1"}) {
            Path invalidAttempt = attemptDirectory.getParent().resolve(invalid);
            Files.createDirectory(invalidAttempt);
            assertThrows(IOException.class, () -> LinuxRuntimeJournal.create(
                    invalidAttempt, "judge-1", invalid, mapper, uid, gid));
            assertFalse(Files.exists(invalidAttempt.resolve("runtime-state.lock")));
        }
    }

    @Test
    void refusesCanonicalUuidThatDoesNotMatchDirectoryName() throws IOException {
        String anotherAttempt = "87654321-1234-4234-8234-123456789abc";
        assertThrows(IOException.class, () -> LinuxRuntimeJournal.create(
                attemptDirectory, "judge-1", anotherAttempt, mapper, uid, gid));
        assertFalse(Files.exists(attemptDirectory.resolve("runtime-state.lock")));
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
        }
        assertThrows(IOException.class, () -> LinuxRuntimeJournal.openExisting(
                attemptDirectory, "judge-1", anotherAttempt, mapper, uid, gid));
        assertEquals(snapshot(), retained());
    }

    @Test
    void danglingStateLinkCountsAsRetainedEvidence() throws IOException {
        Path state = attemptDirectory.resolve("runtime-state.json");
        Files.createSymbolicLink(state, attemptDirectory.resolve("missing-target"));
        assertThrows(IOException.class, this::create);
        assertTrue(Files.isSymbolicLink(state));
        assertFalse(Files.exists(attemptDirectory.resolve("runtime-state.lock")));
    }

    @Test
    void rejectsIdentityAndVersionChangesWithoutReplacingValidJournal() throws IOException {
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            for (String field : new String[]{"judgeId", "attemptId", "provider", "version"}) {
                ObjectNode invalid = snapshot();
                invalid.put(field, "wrong");
                assertThrows(IOException.class, () -> journal.write(invalid));
                assertEquals(snapshot(), journal.read());
            }
            ObjectNode fractionalVersion = snapshot();
            fractionalVersion.put("version", 1.0);
            assertThrows(IOException.class, () -> journal.write(fractionalVersion));
        }
    }

    @Test
    void boundedWritesRejectLargeAndDeepTrees() throws IOException {
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            ObjectNode huge = snapshot();
            huge.put("data", "x".repeat(65536));
            assertThrows(IOException.class, () -> journal.write(huge));
            ObjectNode deep = snapshot();
            ObjectNode cursor = deep;
            for (int depth = 0; depth < 100; depth++) {
                cursor = cursor.putObject("nested");
            }
            assertThrows(IOException.class, () -> journal.write(deep));
            assertEquals(snapshot(), journal.read());
        }
    }

    @Test
    void retainedReadRejectsDuplicateTrailingDamagedAndLargeContent() throws IOException {
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
        }
        String valid = mapper.writeValueAsString(snapshot());
        for (String invalid : new String[]{valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
                valid + " {}", "{", "[]", "x".repeat(65537),
                valid.replace("\"judge-1\"", "\"other\"")}) {
            Files.writeString(attemptDirectory.resolve("runtime-state.json"), invalid);
            assertThrows(IOException.class, this::retained);
        }
    }

    @Test
    void retainedReadRejectsOverdeepJson() throws IOException {
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
        }
        String valid = mapper.writeValueAsString(snapshot());
        String deep = valid.substring(0, valid.length() - 1) + ",\"deep\":"
                + "[".repeat(64) + "0" + "]".repeat(64) + "}";
        Files.writeString(attemptDirectory.resolve("runtime-state.json"), deep);
        assertThrows(IOException.class, this::retained);
    }

    @Test
    void boundarySizedJournalIsAcceptedButOneMoreByteIsRejected() throws IOException {
        ObjectNode boundary = snapshot().put("data", "");
        int overhead = mapper.writeValueAsBytes(boundary).length;
        boundary.put("data", "x".repeat(65536 - overhead));
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(boundary);
            assertEquals(65536, Files.size(attemptDirectory.resolve("runtime-state.json")));
            assertEquals(boundary, journal.read());
            boundary.put("data", boundary.path("data").asText() + "x");
            assertThrows(IOException.class, () -> journal.write(boundary));
            assertEquals(65536, Files.size(attemptDirectory.resolve("runtime-state.json")));
        }
    }

    @Test
    void residualTemporaryFileBlocksCreationAndRead() throws IOException {
        Path temporary = attemptDirectory.resolve("runtime-state.json.tmp");
        Files.createFile(temporary);
        assertThrows(IOException.class, this::create);
        Files.delete(temporary);
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            Files.createFile(temporary);
            assertThrows(IOException.class, journal::read);
            assertThrows(IOException.class, () -> journal.write(snapshot()));
        }
        assertThrows(IOException.class, this::retained);
    }

    @Test
    void hardLinkedAndSymlinkedJournalAreRejected() throws IOException {
        Path journalPath = attemptDirectory.resolve("runtime-state.json");
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            Path alias = attemptDirectory.resolve("alias");
            Files.createLink(alias, journalPath);
            assertThrows(IOException.class, journal::read);
            assertThrows(IOException.class, () -> journal.write(snapshot()));
            Files.delete(alias);
            Files.move(journalPath, alias);
            Files.createSymbolicLink(journalPath, alias);
            assertThrows(IOException.class, journal::read);
        }
        assertThrows(IOException.class, this::retained);
    }

    @Test
    void hardLinkedLockAndReplacedLockAreRejected() throws IOException {
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            Path lock = attemptDirectory.resolve("runtime-state.lock");
            Path alias = attemptDirectory.resolve("lock-alias");
            Files.createLink(alias, lock);
            assertThrows(IOException.class, journal::read);
            Files.delete(alias);
            Files.move(lock, alias);
            Files.createFile(lock, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            assertThrows(IOException.class, journal::read);
        }
    }

    @Test
    void refusesWritableControlDirectoryAndLinkedAncestors() throws IOException {
        Path control = attemptDirectory.getParent().getParent();
        Files.setPosixFilePermissions(control, PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class, this::create);
        Files.setPosixFilePermissions(control, PosixFilePermissions.fromString("rwx------"));
        Path alias = temporaryDirectory.resolve("linked-task");
        Files.createSymbolicLink(alias, temporaryDirectory.resolve("task"));
        assertThrows(IOException.class, () -> LinuxRuntimeJournal.create(
                alias.resolve("control/attempts/" + ATTEMPT_ID), "judge-1", ATTEMPT_ID, mapper, uid, gid));
    }

    @Test
    void refusesInsecurePermissionsAndNonregularStateFile() throws IOException {
        Path journalPath = attemptDirectory.resolve("runtime-state.json");
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
            Files.setPosixFilePermissions(journalPath, PosixFilePermissions.fromString("rw-r--r--"));
            assertThrows(IOException.class, journal::read);
            Files.delete(journalPath);
            Files.createDirectory(journalPath);
            assertThrows(IOException.class, journal::read);
        }
    }

    @Test
    void retainedReadRejectsFifoWithoutOpeningIt() throws Exception {
        Path journalPath = attemptDirectory.resolve("runtime-state.json");
        try (LinuxRuntimeJournal journal = create()) {
            journal.write(snapshot());
        }
        Files.delete(journalPath);
        Process process = new ProcessBuilder("mkfifo", "-m", "600", journalPath.toString()).start();
        assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IOException.class, this::retained));
    }

    @Test
    void closedJournalRejectsOperations() throws IOException {
        LinuxRuntimeJournal journal = create();
        journal.close();
        journal.close();
        assertThrows(IOException.class, journal::read);
        assertThrows(IOException.class, () -> journal.write(snapshot()));
    }

    private LinuxRuntimeJournal create() throws IOException {
        return LinuxRuntimeJournal.create(attemptDirectory, "judge-1", ATTEMPT_ID, mapper, uid, gid);
    }

    private ObjectNode retained() throws IOException {
        return LinuxRuntimeJournal.readRetained(attemptDirectory, "judge-1", ATTEMPT_ID, mapper, uid, gid);
    }

    private ObjectNode snapshot() {
        return mapper.createObjectNode().put("version", 1).put("judgeId", "judge-1")
                .put("attemptId", ATTEMPT_ID).put("provider", "linux-container").put("state", "NEW");
    }
}
