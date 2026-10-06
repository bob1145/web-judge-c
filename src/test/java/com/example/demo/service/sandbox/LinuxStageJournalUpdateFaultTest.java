package com.example.demo.service.sandbox;

import com.sun.jna.LastErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.LINUX)
@Timeout(20)
class LinuxStageJournalUpdateFaultTest {
    private static final String STAGE = "8ed136b9-68ae-45e9-855d-38ec769f294c";
    private static final String NAME = STAGE + ".json";
    private static final byte[] EXPECTED = """
            {"creationState":"CONFIRMED","state":"CREATED"}
            """.strip().getBytes(StandardCharsets.UTF_8);
    private static final byte[] UPDATED = """
            {"creationState":"CONFIRMED","state":"REMOVING"}
            """.strip().getBytes(StandardCharsets.UTF_8);
    private static final String DIRECTORY = "<journal-directory>";

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
        record = privateFile(journal.resolve(NAME), EXPECTED);
    }

    @Test
    void realCallsPublishExclusiveTemporaryEntryInDurableOrder() throws Exception {
        NativeFixture fixture;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, NativeCall::proceed);
            replace(lease, fixture, EXPECTED, UPDATED);
        }
        assertTrue(fixture.temporary.matches(STAGE + "[.][0-9a-f]{32}[.]tmp"));
        int created = fixture.index("openat", fixture.temporary, true);
        int directorySynced = fixture.index("fsync", DIRECTORY, false);
        int written = fixture.index("write", fixture.temporary, true);
        int fileSynced = fixture.index("fsync", fixture.temporary, true);
        int closed = fixture.index("close", fixture.temporary, true);
        int renamed = fixture.index("renameat", fixture.temporary, false);
        int finalSynced = fixture.lastIndex("fsync", DIRECTORY);
        assertTrue(created < directorySynced && directorySynced < written && written < fileSynced
                && fileSynced < closed && closed < renamed && renamed < finalSynced);
        assertEquals(1, fixture.events.stream().filter(event -> event.operation().equals("close")
                && event.output()).count());
        assertEquals(2, fixture.events.stream().filter(event -> event.operation().equals("fsync")
                && event.entry().equals(DIRECTORY)).count());
        assertTrue(fixture.events.stream().filter(event -> event.operation().equals("openat")
                || event.operation().equals("renameat")).allMatch(event -> event.directory() == fixture.directory));
        assertTrue(fixture.handles.keySet().equals(java.util.Set.of(fixture.directory)));
        assertArrayEquals(UPDATED, Files.readAllBytes(record));
        assertEntries(NAME);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 32})
    void genuinePartialWritesAndReadsStillPublishCompleteBytes(int limit) throws Exception {
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            NativeFixture fixture = new NativeFixture(lease, call -> {
                if (call.is("write") || call.is("read")) {
                    return call.proceedWith(call.arguments[0], call.arguments[1],
                            Math.min(limit, (Integer) call.arguments[2]));
                }
                return call.proceed();
            });
            replace(lease, fixture, EXPECTED, UPDATED);
        }
        assertArrayEquals(UPDATED, Files.readAllBytes(record));
        assertEntries(NAME);
    }

    @Test
    void callerArraysAreSnapshottedBeforeFirstEntryOperation() throws Exception {
        byte[] expected = EXPECTED.clone();
        byte[] updated = UPDATED.clone();
        AtomicBoolean changed = new AtomicBoolean();
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            NativeFixture fixture = new NativeFixture(lease, call -> {
                if (changed.compareAndSet(false, true)) {
                    Arrays.fill(expected, (byte) '?');
                    Arrays.fill(updated, (byte) '!');
                }
                return call.proceed();
            });
            replace(lease, fixture, expected, updated);
        }
        assertTrue(changed.get());
        assertArrayEquals(UPDATED, Files.readAllBytes(record));
        assertEntries(NAME);
    }

    @ParameterizedTest
    @MethodSource("nativeFailures")
    void nativeNegativeReturnsAndErrnoDoNotEraseEvidence(FailurePoint point, boolean errno) throws Exception {
        AtomicInteger injected = new AtomicInteger();
        NativeFixture fixture;
        IOException failure;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, call -> {
                if (point.matches(call) && injected.compareAndSet(0, 1)) {
                    if (call.is("close")) {
                        assertEquals(0, call.proceed());
                    }
                    if (errno) {
                        throw new LastErrorException(5);
                    }
                    return -1;
                }
                return call.proceed();
            });
            failure = assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertEquals(1, injected.get());
        assertEquals(errno, hasErrno(failure, 5));
        assertTrue(fixture.handles.keySet().equals(java.util.Set.of(fixture.directory)));
        if (point.published) {
            assertArrayEquals(UPDATED, Files.readAllBytes(record));
            assertEntries(NAME);
        } else {
            assertArrayEquals(EXPECTED, Files.readAllBytes(record));
            if (fixture.temporary == null) {
                assertEntries(NAME);
            } else {
                assertEntries(NAME, fixture.temporary);
                byte[] retained = Files.readAllBytes(journal.resolve(fixture.temporary));
                assertArrayEquals(point.completeTemporary ? UPDATED : new byte[0], retained);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lostRenameConfirmationRetainsPublishedRecordAndClosesPublicationOnce(boolean errno) throws Exception {
        AtomicInteger renames = new AtomicInteger();
        LastErrorException injectedError = new LastErrorException(5);
        NativeFixture fixture;
        IOException failure;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, call -> {
                Object result = call.proceed();
                if (call.is("renameat")) {
                    renames.incrementAndGet();
                    assertEquals(0, result);
                    assertTrue(call.fixture.published);
                    if (errno) {
                        throw injectedError;
                    }
                    return -1;
                }
                return result;
            });
            failure = assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertEquals(1, renames.get());
        assertTrue(fixture.published);
        assertEquals(errno, hasErrno(failure, 5));
        if (errno) {
            assertEquals(injectedError, failure.getCause());
        } else {
            assertTrue(failure.getMessage().contains("renameat"));
            assertNull(failure.getCause());
        }
        assertEquals(1, fixture.events.stream().filter(event -> event.operation().equals("close")
                && event.entry().equals(fixture.temporary) && !event.output() && event.published()).count());
        assertEquals(java.util.Set.of(fixture.directory), fixture.handles.keySet());
        assertArrayEquals(UPDATED, Files.readAllBytes(record));
        assertFalse(Files.exists(journal.resolve(fixture.temporary)));
        assertEntries(NAME);
        try (LinuxStageJournalGuard.Lease reacquired = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            reacquired.verifyIdentity();
            assertArrayEquals(UPDATED, reacquired.readRegularEntry(NAME));
        }
        assertArrayEquals(UPDATED, Files.readAllBytes(record));
    }

    private static Stream<Arguments> nativeFailures() {
        return Arrays.stream(FailurePoint.values()).flatMap(point -> Stream.of(
                Arguments.of(point, false), Arguments.of(point, true)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "overreported", "runtime", "linkage"})
    void invalidWriteResultsAndUncheckedFailuresLeaveEmptyTemporaryEvidence(String failureKind) throws Exception {
        NativeFixture fixture;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, call -> {
                if (call.is("write")) {
                    return switch (failureKind) {
                        case "zero" -> 0;
                        case "overreported" -> (Integer) call.arguments[2] + 1;
                        case "runtime" -> throw new IllegalStateException("fixture write failure");
                        default -> throw new UnsatisfiedLinkError("fixture write linkage failure");
                    };
                }
                return call.proceed();
            });
            assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertArrayEquals(new byte[0], Files.readAllBytes(journal.resolve(fixture.temporary)));
        assertEntries(NAME, fixture.temporary);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedSecondWriteRetainsActualPartialBytes(boolean errno) throws Exception {
        AtomicInteger writes = new AtomicInteger();
        NativeFixture fixture;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, call -> {
                if (call.is("write")) {
                    if (writes.incrementAndGet() == 1) {
                        return call.proceedWith(call.arguments[0], call.arguments[1], 7);
                    }
                    if (errno) {
                        throw new LastErrorException(5);
                    }
                    return 0;
                }
                return call.proceed();
            });
            assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertEquals(2, writes.get());
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertArrayEquals(Arrays.copyOf(UPDATED, 7), Files.readAllBytes(journal.resolve(fixture.temporary)));
        assertEntries(NAME, fixture.temporary);
    }

    @Test
    void primaryErrnoAndCloseErrnoRemainObservableWithoutClosingTwice() throws Exception {
        AtomicInteger outputCloses = new AtomicInteger();
        NativeFixture fixture;
        IOException failure;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, call -> {
                if (call.is("write")) {
                    throw new LastErrorException(5);
                }
                if (call.is("close") && call.event.output()) {
                    outputCloses.incrementAndGet();
                    assertEquals(0, call.proceed());
                    throw new LastErrorException(9);
                }
                return call.proceed();
            });
            failure = assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertTrue(hasErrno(failure, 5));
        assertTrue(hasErrno(failure, 9));
        assertEquals(1, outputCloses.get());
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertEntries(NAME, fixture.temporary);
    }

    @ParameterizedTest
    @ValueSource(strings = {"new-inode", "new-bytes", "restored-bytes-and-mtime", "permissions", "hard-link", "missing"})
    void originalIdentityAndBytesAreRevalidatedAfterOutputClose(String mutation) throws Exception {
        Object originalInode = Files.getAttribute(record, "unix:ino");
        Object originalCtime = Files.getAttribute(record, "unix:ctime");
        FileTime originalMtime = Files.getLastModifiedTime(record);
        byte[] changed = EXPECTED.clone();
        changed[0] = '[';
        AtomicBoolean mutated = new AtomicBoolean();
        Path archived = temporaryDirectory.resolve("retained-original");
        NativeFixture fixture;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, call -> {
                Object result = call.proceed();
                if (call.is("close") && call.event.output() && mutated.compareAndSet(false, true)) {
                    switch (mutation) {
                        case "new-inode" -> {
                            Files.move(record, archived);
                            privateFile(record, EXPECTED);
                        }
                        case "new-bytes" -> Files.write(record, changed);
                        case "restored-bytes-and-mtime" -> {
                            Files.write(record, changed);
                            Files.write(record, EXPECTED);
                            Files.setLastModifiedTime(record, originalMtime);
                            assertNotEquals(originalCtime, Files.getAttribute(record, "unix:ctime"));
                        }
                        case "permissions" -> Files.setAttribute(record, "unix:mode", 0644);
                        case "hard-link" -> Files.createLink(temporaryDirectory.resolve("original-alias"), record);
                        default -> Files.move(record, archived);
                    }
                }
                return result;
            });
            assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertTrue(mutated.get());
        assertFalse(fixture.published);
        assertArrayEquals(UPDATED, Files.readAllBytes(journal.resolve(fixture.temporary)));
        if (mutation.equals("missing")) {
            assertFalse(Files.exists(record));
            assertArrayEquals(EXPECTED, Files.readAllBytes(archived));
            assertEntries(fixture.temporary);
        } else {
            assertArrayEquals(mutation.equals("new-bytes") ? changed : EXPECTED, Files.readAllBytes(record));
            if (mutation.equals("new-inode")) {
                assertNotEquals(originalInode, Files.getAttribute(record, "unix:ino"));
                assertArrayEquals(EXPECTED, Files.readAllBytes(archived));
            } else {
                assertEquals(originalInode, Files.getAttribute(record, "unix:ino"));
            }
            if (mutation.equals("permissions")) {
                assertEquals(0644, ((Number) Files.getAttribute(record, "unix:mode")).intValue() & 07777);
            } else if (mutation.equals("hard-link")) {
                assertEquals(2, ((Number) Files.getAttribute(record, "unix:nlink")).intValue());
            }
            assertEntries(NAME, fixture.temporary);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"new-inode", "new-bytes", "restored-bytes-and-mtime"})
    void originalMutationWhileReadingIsRejectedBeforeCreatingTemporaryEntry(String mutation) throws Exception {
        FileTime originalMtime = Files.getLastModifiedTime(record);
        Object originalCtime = Files.getAttribute(record, "unix:ctime");
        byte[] changed = EXPECTED.clone();
        changed[0] = '[';
        AtomicBoolean mutated = new AtomicBoolean();
        NativeFixture fixture;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, call -> {
                Object result = call.proceed();
                if (call.is("read") && call.event.entry().equals(NAME) && (Integer) result > 0
                        && mutated.compareAndSet(false, true)) {
                    switch (mutation) {
                        case "new-inode" -> {
                            Files.move(record, temporaryDirectory.resolve("original-read-inode"));
                            privateFile(record, EXPECTED);
                        }
                        case "new-bytes" -> Files.write(record, changed);
                        default -> {
                            Files.write(record, changed);
                            Files.write(record, EXPECTED);
                            Files.setLastModifiedTime(record, originalMtime);
                            assertNotEquals(originalCtime, Files.getAttribute(record, "unix:ctime"));
                        }
                    }
                }
                return result;
            });
            assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertTrue(mutated.get());
        assertNull(fixture.temporary);
        assertArrayEquals(mutation.equals("new-bytes") ? changed : EXPECTED, Files.readAllBytes(record));
        assertEntries(NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"new-inode", "symbolic-link", "hard-link", "permissions", "new-bytes", "missing"})
    void temporaryIdentityCannotBeSubstitutedAfterOutputClose(String mutation) throws Exception {
        AtomicBoolean mutated = new AtomicBoolean();
        byte[] changed = UPDATED.clone();
        changed[0] = '[';
        Path archived = temporaryDirectory.resolve("retained-temporary");
        NativeFixture fixture;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, call -> {
                Object result = call.proceed();
                if (call.is("close") && call.event.output() && mutated.compareAndSet(false, true)) {
                    Path temporary = journal.resolve(call.event.entry());
                    switch (mutation) {
                        case "new-inode" -> {
                            Files.move(temporary, archived);
                            privateFile(temporary, UPDATED);
                        }
                        case "symbolic-link" -> {
                            Files.move(temporary, archived);
                            Files.createSymbolicLink(temporary, archived);
                        }
                        case "hard-link" -> Files.createLink(temporaryDirectory.resolve("temporary-alias"), temporary);
                        case "permissions" -> Files.setAttribute(temporary, "unix:mode", 0644);
                        case "new-bytes" -> Files.write(temporary, changed);
                        default -> Files.move(temporary, archived);
                    }
                }
                return result;
            });
            assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertTrue(mutated.get());
        assertFalse(fixture.published);
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        if (mutation.equals("missing")) {
            assertEntries(NAME);
        } else {
            assertEntries(NAME, fixture.temporary);
            assertArrayEquals(mutation.equals("new-bytes") ? changed : UPDATED,
                    Files.readAllBytes(journal.resolve(fixture.temporary)));
            if (mutation.equals("symbolic-link")) {
                assertEquals(archived, Files.readSymbolicLink(journal.resolve(fixture.temporary)));
            } else if (mutation.equals("hard-link")) {
                assertEquals(2, ((Number) Files.getAttribute(journal.resolve(fixture.temporary), "unix:nlink")).intValue());
            } else if (mutation.equals("permissions")) {
                assertEquals(0644,
                        ((Number) Files.getAttribute(journal.resolve(fixture.temporary), "unix:mode")).intValue() & 07777);
            }
        }
        if (List.of("new-inode", "symbolic-link", "missing").contains(mutation)) {
            assertArrayEquals(UPDATED, Files.readAllBytes(archived));
        }
    }

    @Test
    void exclusiveCreateCollisionDoesNotTruncateOrRemoveExistingTemporaryEntry() throws Exception {
        byte[] retained = "existing collision evidence".getBytes(StandardCharsets.UTF_8);
        String[] collisionName = new String[1];
        IOException failure;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            NativeFixture fixture = new NativeFixture(lease, call -> {
                if (call.is("openat") && call.event.output()) {
                    collisionName[0] = call.event.entry();
                    privateFile(journal.resolve(collisionName[0]), retained);
                }
                return call.proceed();
            });
            failure = assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertTrue(hasErrno(failure, 17));
        assertArrayEquals(retained, Files.readAllBytes(journal.resolve(collisionName[0])));
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertEntries(NAME, collisionName[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "overreported"})
    void invalidInitialReadResultsFailBeforeCreatingTemporaryEvidence(String failureKind) throws Exception {
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            NativeFixture fixture = new NativeFixture(lease, call -> {
                if (call.is("read")) {
                    return failureKind.equals("zero") ? 0 : (Integer) call.arguments[2] + 1;
                }
                return call.proceed();
            });
            assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertArrayEquals(EXPECTED, Files.readAllBytes(record));
        assertEntries(NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"new-inode", "new-bytes", "permissions", "hard-link"})
    void publishedRecordMustStillMatchRetainedPublicationDescriptor(String mutation) throws Exception {
        byte[] changed = UPDATED.clone();
        changed[0] = '[';
        Path archived = temporaryDirectory.resolve("retained-publication");
        AtomicBoolean mutated = new AtomicBoolean();
        NativeFixture fixture;
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            fixture = new NativeFixture(lease, call -> {
                Object result = call.proceed();
                if (call.is("renameat") && mutated.compareAndSet(false, true)) {
                    switch (mutation) {
                        case "new-inode" -> {
                            Files.move(record, archived);
                            privateFile(record, UPDATED);
                        }
                        case "new-bytes" -> Files.write(record, changed);
                        case "permissions" -> Files.setAttribute(record, "unix:mode", 0644);
                        default -> Files.createLink(temporaryDirectory.resolve("published-alias"), record);
                    }
                }
                return result;
            });
            assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
        }
        assertTrue(mutated.get());
        assertTrue(fixture.published);
        assertArrayEquals(mutation.equals("new-bytes") ? changed : UPDATED, Files.readAllBytes(record));
        assertEntries(NAME);
        if (mutation.equals("new-inode")) {
            assertArrayEquals(UPDATED, Files.readAllBytes(archived));
        } else if (mutation.equals("permissions")) {
            assertEquals(0644, ((Number) Files.getAttribute(record, "unix:mode")).intValue() & 07777);
        } else if (mutation.equals("hard-link")) {
            assertEquals(2, ((Number) Files.getAttribute(record, "unix:nlink")).intValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directoryReplacementDuringPublicationNeverTouchesReplacementDirectory(boolean afterRename) throws Exception {
        LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid);
        Path retainedDirectory = temporaryDirectory.resolve("retained-journal");
        AtomicBoolean replaced = new AtomicBoolean();
        NativeFixture fixture = new NativeFixture(lease, call -> {
            Object result = call.proceed();
            if (call.is("fsync") && call.event.entry().equals(DIRECTORY)
                    && call.event.published() == afterRename && replaced.compareAndSet(false, true)) {
                Files.move(journal, retainedDirectory);
                Files.createDirectory(journal,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                privateFile(journal.resolve(NAME), EXPECTED);
            }
            return result;
        });
        try {
            assertThrows(IOException.class, () -> replace(lease, fixture, EXPECTED, UPDATED));
            assertTrue(replaced.get());
            assertArrayEquals(EXPECTED, Files.readAllBytes(journal.resolve(NAME)));
            assertEntries(NAME);
            assertArrayEquals(afterRename ? UPDATED : EXPECTED, Files.readAllBytes(retainedDirectory.resolve(NAME)));
            if (afterRename) {
                assertDirectoryEntries(retainedDirectory, NAME);
            } else {
                assertDirectoryEntries(retainedDirectory, NAME, fixture.temporary);
                assertArrayEquals(new byte[0], Files.readAllBytes(retainedDirectory.resolve(fixture.temporary)));
            }
        } finally {
            assertThrows(IOException.class, lease::close);
        }
    }

    private void replace(LinuxStageJournalGuard.Lease lease, NativeFixture fixture, byte[] expected, byte[] updated)
            throws IOException {
        LinuxStageEntryIo.replaceStageRecord(lease, fixture.calls, fixture.directory, ownerUid, NAME,
                expected, updated);
    }

    private Path privateFile(Path target, byte[] bytes) throws IOException {
        Files.createFile(target,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.write(target, bytes);
    }

    private void assertEntries(String... expected) throws IOException {
        assertDirectoryEntries(journal, expected);
    }

    private void assertDirectoryEntries(Path directory, String... expected) throws IOException {
        try (var entries = Files.list(directory)) {
            assertEquals(Arrays.stream(expected).sorted().toList(),
                    entries.map(entry -> entry.getFileName().toString()).sorted().toList());
        }
    }

    private static boolean hasErrno(Throwable failure, int expected) {
        if (failure instanceof LastErrorException error && error.getErrorCode() == expected) {
            return true;
        }
        if (failure.getCause() != null && hasErrno(failure.getCause(), expected)) {
            return true;
        }
        return Arrays.stream(failure.getSuppressed()).anyMatch(suppressed -> hasErrno(suppressed, expected));
    }

    private enum FailurePoint {
        ORIGINAL_OPEN(false, false), ORIGINAL_READ(false, false), ORIGINAL_CLOSE(false, false),
        CREATE(false, false), FCHMOD(false, false), INITIAL_DIRECTORY_FSYNC(false, false), WRITE(false, false),
        FILE_FSYNC(false, true), OUTPUT_CLOSE(false, true), RENAME(false, true),
        FINAL_DIRECTORY_FSYNC(true, true), FINAL_OPEN(true, true), FINAL_READ(true, true), FINAL_CLOSE(true, true);

        private final boolean published;
        private final boolean completeTemporary;

        FailurePoint(boolean published, boolean completeTemporary) {
            this.published = published;
            this.completeTemporary = completeTemporary;
        }

        boolean matches(NativeCall call) {
            NativeEvent event = call.event;
            return switch (this) {
                case ORIGINAL_OPEN -> call.is("openat") && event.entry().equals(NAME) && !event.published();
                case ORIGINAL_READ -> call.is("read") && event.entry().equals(NAME) && !event.published();
                case ORIGINAL_CLOSE -> call.is("close") && event.entry().equals(NAME) && !event.published();
                case CREATE -> call.is("openat") && event.output();
                case FCHMOD -> call.is("fchmod");
                case INITIAL_DIRECTORY_FSYNC -> call.is("fsync") && event.entry().equals(DIRECTORY)
                        && !event.published();
                case WRITE -> call.is("write");
                case FILE_FSYNC -> call.is("fsync") && event.output();
                case OUTPUT_CLOSE -> call.is("close") && event.output();
                case RENAME -> call.is("renameat");
                case FINAL_DIRECTORY_FSYNC -> call.is("fsync") && event.entry().equals(DIRECTORY)
                        && event.published();
                case FINAL_OPEN -> call.is("openat") && event.entry().equals(NAME) && event.published();
                case FINAL_READ -> call.is("read") && event.entry().equals(NAME) && event.published();
                case FINAL_CLOSE -> call.is("close") && event.entry().equals(NAME) && event.published();
            };
        }
    }

    @FunctionalInterface
    private interface NativeFault {
        Object invoke(NativeCall call) throws Throwable;
    }

    private record NativeHandle(String name, boolean output) { }

    private record NativeEvent(String operation, String entry, boolean output, boolean published, int directory) { }

    private static final class NativeFixture {
        private final LinuxStageEntryIo.Calls real = LinuxStageEntryIo.loadCalls();
        private final LinuxStageEntryIo.Calls calls;
        private final int directory;
        private final List<NativeEvent> events = new ArrayList<>();
        private final Map<Integer, NativeHandle> handles = new HashMap<>();
        private String temporary;
        private boolean published;

        private NativeFixture(LinuxStageJournalGuard.Lease lease, NativeFault fault) throws Exception {
            Field descriptor = LinuxStageJournalGuard.Lease.class.getDeclaredField("descriptor");
            descriptor.setAccessible(true);
            directory = descriptor.getInt(lease);
            handles.put(directory, new NativeHandle(DIRECTORY, false));
            calls = (LinuxStageEntryIo.Calls) Proxy.newProxyInstance(LinuxStageEntryIo.Calls.class.getClassLoader(),
                    new Class<?>[]{LinuxStageEntryIo.Calls.class}, (proxy, method, arguments) -> {
                        String operation = method.getName();
                        String entry;
                        boolean output;
                        int currentDirectory = -1;
                        if (operation.equals("openat") || operation.equals("renameat")) {
                            currentDirectory = (Integer) arguments[0];
                            entry = (String) arguments[1];
                            output = operation.equals("openat") && (((Integer) arguments[2] & 128) != 0);
                        } else {
                            NativeHandle handle = handles.get((Integer) arguments[0]);
                            entry = handle == null ? "<unknown-descriptor>" : handle.name();
                            output = handle != null && handle.output();
                        }
                        NativeEvent event = new NativeEvent(operation, entry, output, published, currentDirectory);
                        events.add(event);
                        return fault.invoke(new NativeCall(this, method, arguments, event));
                    });
        }

        int index(String operation, String entry, boolean output) {
            for (int index = 0; index < events.size(); index++) {
                NativeEvent event = events.get(index);
                if (event.operation().equals(operation) && event.entry().equals(entry) && event.output() == output) {
                    return index;
                }
            }
            throw new AssertionError("Missing native event: " + operation + ":" + entry);
        }

        int lastIndex(String operation, String entry) {
            for (int index = events.size() - 1; index >= 0; index--) {
                NativeEvent event = events.get(index);
                if (event.operation().equals(operation) && event.entry().equals(entry)) {
                    return index;
                }
            }
            throw new AssertionError("Missing native event: " + operation + ":" + entry);
        }
    }

    private static final class NativeCall {
        private final NativeFixture fixture;
        private final Method method;
        private final Object[] arguments;
        private final NativeEvent event;

        private NativeCall(NativeFixture fixture, Method method, Object[] arguments, NativeEvent event) {
            this.fixture = fixture;
            this.method = method;
            this.arguments = arguments;
            this.event = event;
        }

        boolean is(String operation) {
            return method.getName().equals(operation);
        }

        Object proceed() throws Throwable {
            return proceedWith(arguments);
        }

        Object proceedWith(Object... supplied) throws Throwable {
            try {
                Object result = method.invoke(fixture.real, supplied);
                if (is("openat") && (Integer) result >= 0) {
                    fixture.handles.put((Integer) result, new NativeHandle(event.entry(), event.output()));
                    if (event.output()) {
                        fixture.temporary = event.entry();
                    }
                } else if (is("renameat") && (Integer) result == 0) {
                    fixture.published = true;
                }
                return result;
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            } finally {
                if (is("close")) {
                    fixture.handles.remove((Integer) supplied[0]);
                }
            }
        }
    }
}
