package com.example.demo.service.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.LINUX)
@Timeout(20)
class LinuxStageJournalUpdateTest {
    private static final String NAME = "8ed136b9-68ae-45e9-855d-38ec769f294c.json";
    private static final byte[] EXPECTED = "{\"creationState\":\"CONFIRMED\",\"state\":\"CREATED\"}"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] UPDATED = "{\"creationState\":\"CONFIRMED\",\"state\":\"REMOVING\"}"
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;
    private Path journal;
    private Path record;
    private int ownerUid;

    @BeforeEach
    void prepare() throws IOException {
        assumeTrue(List.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        temporaryDirectory = temporaryDirectory.toRealPath();
        journal = Files.createDirectory(temporaryDirectory.resolve("stage-journal"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        ownerUid = ((Number) Files.getAttribute(journal, "unix:uid")).intValue();
        record = Files.createFile(journal.resolve(NAME),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(record, EXPECTED);
    }

    @Test
    void existingPrivateRecordIsAtomicallyReplaced() throws Exception {
        Map<String, Object> before = Files.readAttributes(record, "unix:dev,ino");
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.replaceStageRecord(NAME, EXPECTED, UPDATED);
            lease.verifyIdentity();
            assertArrayEquals(UPDATED, lease.readRegularEntry(NAME));
        }
        assertArrayEquals(UPDATED, Files.readAllBytes(record));
        assertEquals(before.get("dev"), Files.getAttribute(record, "unix:dev"));
        assertNotEquals(before.get("ino"), Files.getAttribute(record, "unix:ino"));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(record));
        assertEquals(1, ((Number) Files.getAttribute(record, "unix:nlink")).intValue());
        assertEquals(ownerUid, ((Number) Files.getAttribute(record, "unix:uid")).intValue());
        assertOnlyOriginalEntry();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65536})
    void bothInclusivePayloadBoundsCanBePublished(int size) throws Exception {
        byte[] replacement = new byte[size];
        Arrays.fill(replacement, (byte) 'x');
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.replaceStageRecord(NAME, EXPECTED, replacement);
            lease.replaceStageRecord(NAME, replacement, EXPECTED);
        }
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertOnlyOriginalEntry();
    }

    @Test
    void differentExpectedBytesDoNotOverwriteOrCreateTemporaryEvidence() throws Exception {
        byte[] different = EXPECTED.clone();
        different[0] = '[';
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            assertThrows(IOException.class, () -> lease.replaceStageRecord(NAME, different, UPDATED));
        }
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertOnlyOriginalEntry();
    }

    @Test
    void absentRecordIsNeverCreated() throws Exception {
        String absent = "998f8b2c-bc85-42a6-984e-40b024b3b958.json";
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            assertThrows(IOException.class, () -> lease.replaceStageRecord(absent, EXPECTED, UPDATED));
        }
        assertFalse(Files.exists(journal.resolve(absent)));
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertOnlyOriginalEntry();
    }

    @Test
    void closedLeaseCannotPublishAnyRecord() throws Exception {
        LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid);
        lease.close();
        assertThrows(IOException.class, () -> lease.replaceStageRecord(NAME, EXPECTED, UPDATED));
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertOnlyOriginalEntry();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "../outside.json", "stage-admission.closed", "stage-admission.pending",
            "8ED136B9-68AE-45E9-855D-38EC769F294C.json", "8ed136b968ae45e9855d38ec769f294c.json",
            "8ed136b9-68ae-45e9-855d-38ec769f294c.json.json", "8ed136b9-68ae-45e9-855d-38ec769f294c.tmp"})
    void nonCanonicalRecordNamesAreRejected(String name) throws Exception {
        assertRejected(name, EXPECTED, UPDATED);
    }

    @Test
    void nullNameAndPayloadsAreRejected() throws Exception {
        assertRejected(null, EXPECTED, UPDATED);
        assertRejected(NAME, null, UPDATED);
        assertRejected(NAME, EXPECTED, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65537})
    void outOfBoundsExpectedAndUpdatedPayloadsAreRejected(int size) throws Exception {
        assertRejected(NAME, new byte[size], UPDATED);
        assertRejected(NAME, EXPECTED, new byte[size]);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65537})
    void outOfBoundsExistingRecordIsNotOverwritten(int size) throws Exception {
        byte[] retained = new byte[size];
        Files.write(record, retained);
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            assertThrows(IOException.class, () -> lease.replaceStageRecord(NAME, EXPECTED, UPDATED));
        }
        assertArrayEquals(retained, Files.readAllBytes(record));
        assertOnlyOriginalEntry();
    }

    @ParameterizedTest
    @ValueSource(ints = {0644, 0620, 0700, 0400, 04600, 02600, 01600})
    void existingRecordMustHaveExactlyPrivateReadWriteMode(int mode) throws Exception {
        Files.setAttribute(record, "unix:mode", mode);
        assertRejected(NAME, EXPECTED, UPDATED);
        assertEquals(mode, ((Number) Files.getAttribute(record, "unix:mode")).intValue() & 07777);
    }

    @Test
    void symbolicLinkRecordDoesNotOverwriteItsTarget() throws Exception {
        String linked = "998f8b2c-bc85-42a6-984e-40b024b3b958.json";
        Files.createSymbolicLink(journal.resolve(linked), Path.of(NAME));
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            assertThrows(IOException.class, () -> lease.replaceStageRecord(linked, EXPECTED, UPDATED));
        }
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertEquals(Path.of(NAME), Files.readSymbolicLink(journal.resolve(linked)));
        assertEntries(NAME, linked);
    }

    @Test
    void hardLinkedRecordDoesNotOverwriteEitherName() throws Exception {
        Path alias = temporaryDirectory.resolve("record-alias.json");
        Files.createLink(alias, record);
        assertRejected(NAME, EXPECTED, UPDATED);
        assertArrayEquals(EXPECTED, Files.readAllBytes(alias));
        assertEquals(Files.getAttribute(record, "unix:ino"), Files.getAttribute(alias, "unix:ino"));
    }

    @Test
    void wrongOwnerRecordIsRejectedWhenNativeFixtureCanChangeOwnership() throws Exception {
        assumeTrue(ownerUid == 0);
        Files.setAttribute(record, "unix:uid", 65534);
        assertRejected(NAME, EXPECTED, UPDATED);
        assertEquals(65534, ((Number) Files.getAttribute(record, "unix:uid")).intValue());
    }

    @Test
    void directoryEntryIsNeverReplacedByAStageFile() throws Exception {
        String directoryName = "998f8b2c-bc85-42a6-984e-40b024b3b958.json";
        Files.createDirectory(journal.resolve(directoryName),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            assertThrows(IOException.class, () -> lease.replaceStageRecord(directoryName, EXPECTED, UPDATED));
        }
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertEntries(NAME, directoryName);
    }

    @Test
    void directoryReplacementCannotPublishIntoEitherDirectory() throws Exception {
        LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid);
        Path retained = temporaryDirectory.resolve("retained-journal");
        try {
            Files.move(journal, retained);
            Files.createDirectory(journal,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path substituted = privateFile(journal.resolve(NAME), EXPECTED);
            assertThrows(IOException.class, () -> lease.replaceStageRecord(NAME, EXPECTED, UPDATED));
            assertArrayEquals(EXPECTED, Files.readAllBytes(retained.resolve(NAME)));
            assertArrayEquals(EXPECTED, Files.readAllBytes(substituted));
            assertOnlyOriginalEntry();
            try (var entries = Files.list(retained)) {
                assertEquals(List.of(NAME), entries.map(entry -> entry.getFileName().toString()).sorted().toList());
            }
        } finally {
            assertThrows(IOException.class, lease::close);
        }
    }

    private void assertRejected(String name, byte[] expected, byte[] updated) throws Exception {
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            assertThrows(IOException.class, () -> lease.replaceStageRecord(name, expected, updated));
        }
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertOnlyOriginalEntry();
    }

    private Path privateFile(Path target, byte[] bytes) throws IOException {
        Files.createFile(target,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.write(target, bytes);
    }

    private void assertOnlyOriginalEntry() throws IOException {
        assertEntries(NAME);
    }

    private void assertEntries(String... expected) throws IOException {
        try (var entries = Files.list(journal)) {
            assertEquals(Arrays.stream(expected).sorted().toList(),
                    entries.map(entry -> entry.getFileName().toString()).sorted().toList());
        }
    }
}
