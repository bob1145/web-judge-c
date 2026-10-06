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
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.LINUX)
@Timeout(20)
class LinuxStageJournalGuardTest {
    private static final Path PYTHON = Path.of("/usr/bin/python3");
    private static final String PYTHON_HOLDER = """
            import fcntl, os, sys
            descriptor = os.open(sys.argv[1], os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                print('READY', flush=True)
                sys.stdin.buffer.read(1)
            finally:
                os.close(descriptor)
            """;
    private static final String PYTHON_CONTENDER = """
            import fcntl, os, sys
            descriptor = os.open(sys.argv[1], os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
            try:
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    print('BUSY', flush=True)
                else:
                    print('ACQUIRED', flush=True)
            finally:
                os.close(descriptor)
            """;
    private static final String PYTHON_INHERITANCE = """
            import os, sys
            journal = os.stat(sys.argv[1])
            inherited = False
            for entry in os.listdir('/proc/self/fd'):
                try:
                    current = os.stat('/proc/self/fd/' + entry)
                except FileNotFoundError:
                    continue
                if (journal.st_dev, journal.st_ino) == (current.st_dev, current.st_ino):
                    inherited = True
            print('INHERITED' if inherited else 'NOT_INHERITED', flush=True)
            """;

    @TempDir
    Path temporaryDirectory;
    private Path journal;
    private int ownerUid;

    @BeforeEach
    void prepare() throws IOException {
        assumeTrue(List.of("amd64", "x86_64").contains(System.getProperty("os.arch")));
        assumeTrue(Files.isExecutable(PYTHON));
        temporaryDirectory = temporaryDirectory.toRealPath();
        journal = Files.createDirectory(temporaryDirectory.resolve("stage-journal"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        ownerUid = ((Number) Files.getAttribute(journal, "unix:uid")).intValue();
    }

    @Test
    void existingDirectoryCanBeLockedVerifiedAndReleased() throws Exception {
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.verifyIdentity();
        }
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.verifyIdentity();
        }
        try (var entries = Files.list(journal)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void realPythonDirectoryLockRejectsJavaUntilReleased() throws Exception {
        try (ChildProcess holder = python(PYTHON_HOLDER, "holder")) {
            holder.await("READY");
            assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(journal, ownerUid));
            holder.finish();
        }
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.verifyIdentity();
        }
    }

    @Test
    void realJavaDirectoryLockRejectsPythonUntilReleased() throws Exception {
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.verifyIdentity();
            assertPythonResult("BUSY", "locked");
        }
        assertPythonResult("ACQUIRED", "released");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realJavaHolderNormalOrForcedExitReleasesPythonLock(boolean force) throws Exception {
        try (ChildProcess holder = javaHolder()) {
            holder.await("READY");
            assertPythonResult("BUSY", "child-java-held");
            if (force) {
                holder.kill();
            } else {
                holder.finish();
            }
        }
        assertPythonResult("ACQUIRED", "child-java-released");
    }

    @Test
    void renamedAndReplacedDirectoryIsRejectedButOriginalLockStillReleases() throws Exception {
        LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid);
        Path previous = temporaryDirectory.resolve("previous-journal");
        try {
            Files.move(journal, previous);
            Files.createDirectory(journal, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            assertThrows(IOException.class, lease::verifyIdentity);
            assertPythonResult("BUSY", "previous-held", previous);
            assertPythonResult("ACQUIRED", "replacement-unlocked");
        } finally {
            assertThrows(IOException.class, lease::close);
        }
        lease.close();
        assertPythonResult("ACQUIRED", "previous-released", previous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"leaf", "ancestor"})
    void symbolicDirectoryComponentsAreRejected(String kind) throws Exception {
        Path link = temporaryDirectory.resolve("journal-alias");
        Path attempted;
        if (kind.equals("leaf")) {
            Files.createSymbolicLink(link, journal);
            attempted = link;
        } else {
            Files.createSymbolicLink(link, temporaryDirectory);
            attempted = link.resolve(journal.getFileName());
        }
        assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(attempted, ownerUid));
        assertPythonResult("ACQUIRED", "symlink-rejected");
    }

    @ParameterizedTest
    @ValueSource(strings = {"rwxrwx---", "rwx---rwx"})
    void untrustedDirectoryWritePermissionsAreRejected(String permissions) throws Exception {
        Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString(permissions));
        assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(journal, ownerUid));
        Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rwx------"));
        assertPythonResult("ACQUIRED", "unsafe-mode-rejected");
    }

    @Test
    void wrongExpectedOwnerIsRejectedWithoutLockingTheDirectory() throws Exception {
        int differentOwner = ownerUid == 0 ? 1 : 0;
        assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(journal, differentOwner));
        assertPythonResult("ACQUIRED", "wrong-owner-rejected");
    }

    @Test
    void actualOwnerMutationPoisonsLeaseAndCloseReleasesOriginalDescriptor() throws Exception {
        assumeTrue(ownerUid == 0);
        LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid);
        try {
            Files.setAttribute(journal, "unix:uid", 1);
            assertThrows(IOException.class, lease::verifyIdentity);
        } finally {
            Files.setAttribute(journal, "unix:uid", ownerUid);
            assertThrows(IOException.class, lease::close);
        }
        assertPythonResult("ACQUIRED", "owner-mutation-released");
    }

    @Test
    void permissionMutationPoisonsLeaseEvenAfterPermissionsAreRestored() throws Exception {
        LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid);
        try {
            Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rwxrwx---"));
            assertThrows(IOException.class, lease::verifyIdentity);
            Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rwx------"));
            assertThrows(IOException.class, lease::verifyIdentity);
        } finally {
            Files.setPosixFilePermissions(journal, PosixFilePermissions.fromString("rwx------"));
            assertThrows(IOException.class, lease::close);
        }
        assertPythonResult("ACQUIRED", "permission-mutation-released");
    }

    @Test
    void missingAndNonDirectoryInputsNeverCreateOrModifyEntries() throws Exception {
        Path missing = journal.resolve("missing");
        Path file = Files.writeString(temporaryDirectory.resolve("regular-file"), "retained");
        for (Path path : List.of(missing, file, Path.of("relative-journal"), journal.resolve(".."), Path.of("/"))) {
            assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(path, ownerUid));
        }
        assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(null, ownerUid));
        assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(journal, -1));
        assertFalse(Files.exists(missing));
        assertEquals("retained", Files.readString(file));
        assertPythonResult("ACQUIRED", "invalid-input-rejected");
    }

    @Test
    void nonDefaultFilesystemCannotResolveToAnIdenticallyNamedHostDirectory() throws Exception {
        URI archive = URI.create("jar:" + temporaryDirectory.resolve("virtual.zip").toUri());
        try (var filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path virtual = Files.createDirectories(filesystem.getPath(journal.toString()));
            assertEquals(journal.toString(), virtual.toString());
            assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(virtual, ownerUid));
        }
        assertPythonResult("ACQUIRED", "foreign-filesystem-rejected");
    }

    @Test
    void presetInterruptIsPreservedAndDoesNotAcquireOrLeakDescriptor() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(journal, ownerUid));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertPythonResult("ACQUIRED", "interrupted-acquire-rejected");
    }

    @Test
    void interruptedCloseStillReleasesDescriptorAndPreservesInterrupt() throws Exception {
        LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid);
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, lease::close);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            lease.close();
        }
        assertPythonResult("ACQUIRED", "interrupted-close-released");
    }

    @Test
    void closedLeaseCannotBeUsedAndRepeatedCloseIsSafe() throws Exception {
        LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid);
        lease.close();
        assertThrows(IOException.class, lease::verifyIdentity);
        lease.close();
        assertPythonResult("ACQUIRED", "idempotent-close-released");
    }

    @Test
    void concurrentVerifyAndCloseSerializeDescriptorOwnership() throws Exception {
        LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid);
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var verifying = workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int attempt = 0; attempt < 32; attempt++) {
                    try {
                        lease.verifyIdentity();
                    } catch (IOException closed) {
                        return null;
                    }
                }
                return null;
            });
            var closing = workers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                lease.close();
                return null;
            });
            start.countDown();
            verifying.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            assertThrows(IOException.class, lease::verifyIdentity);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            lease.close();
        }
        assertPythonResult("ACQUIRED", "concurrent-close-released");
    }

    @Test
    void repeatedSuccessBusyAndInvalidAcquisitionsDoNotLeakDescriptors() throws Exception {
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.verifyIdentity();
        }
        long baseline = descriptorCount();
        for (int attempt = 0; attempt < 32; attempt++) {
            try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
                lease.verifyIdentity();
                assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(journal, ownerUid));
                assertEquals(1, descriptorsFor(journal).size());
            }
            assertThrows(IOException.class, () -> LinuxStageJournalGuard.acquire(journal.resolve("missing"), ownerUid));
        }
        assertTrue(descriptorCount() <= baseline);
        assertTrue(descriptorsFor(journal).isEmpty());
    }

    @Test
    void procFdinfoConfirmsCloseOnExecAndChildDoesNotInheritHeldInode() throws Exception {
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(journal, ownerUid)) {
            lease.verifyIdentity();
            List<String> descriptors = descriptorsFor(journal);
            assertEquals(1, descriptors.size());
            for (String descriptor : descriptors) {
                String flags = Files.readAllLines(Path.of("/proc/self/fdinfo", descriptor)).stream()
                        .filter(line -> line.startsWith("flags:")).findFirst().orElseThrow();
                long actual = Long.parseLong(flags.substring("flags:".length()).trim(), 8);
                assertTrue((actual & 02000000L) != 0);
            }
            try (ChildProcess child = python(PYTHON_INHERITANCE, "inheritance")) {
                child.finish();
                assertEquals("NOT_INHERITED", Files.readString(child.output()).trim());
            }
            assertPythonResult("BUSY", "inheritance-parent-still-locked");
        }
        assertTrue(descriptorsFor(journal).isEmpty());
        assertPythonResult("ACQUIRED", "inheritance-parent-released");
    }

    private long descriptorCount() throws IOException {
        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
            return descriptors.count();
        }
    }

    private List<String> descriptorsFor(Path directory) throws IOException {
        Map<String, Object> expected = Files.readAttributes(directory, "unix:dev,ino");
        List<String> selected = new ArrayList<>();
        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
            for (Path descriptor : descriptors.toList()) {
                try {
                    if (expected.equals(Files.readAttributes(descriptor, "unix:dev,ino"))) {
                        selected.add(descriptor.getFileName().toString());
                    }
                } catch (java.nio.file.NoSuchFileException disappeared) {
                    continue;
                }
            }
        }
        return selected;
    }

    private void assertPythonResult(String expected, String name) throws Exception {
        assertPythonResult(expected, name, journal);
    }

    private void assertPythonResult(String expected, String name, Path directory) throws Exception {
        try (ChildProcess contender = python(PYTHON_CONTENDER, name, directory)) {
            contender.finish();
            assertEquals(expected, Files.readString(contender.output()).trim());
        }
    }

    private ChildProcess python(String script, String name) throws IOException {
        return python(script, name, journal);
    }

    private ChildProcess python(String script, String name, Path directory) throws IOException {
        Path output = temporaryDirectory.resolve(name + ".log");
        Process process = new ProcessBuilder(PYTHON.toString(), "-B", "-c", script, directory.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new ChildProcess(process, output);
    }

    private ChildProcess javaHolder() throws IOException {
        Path output = temporaryDirectory.resolve("java-holder.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-XX:ActiveProcessorCount=1", "-XX:MaxDirectMemorySize=16m", "-cp",
                System.getProperty("java.class.path"), JavaHolder.class.getName(), journal.toString(), Integer.toString(ownerUid))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        return new ChildProcess(process, output);
    }

    private record ChildProcess(Process process, Path output) implements AutoCloseable {
        void await(String marker) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                assertTrue(Files.size(output) <= 8192);
                String text = Files.readString(output);
                if (text.lines().anyMatch(marker::equals)) {
                    return;
                }
                assertTrue(process.isAlive(), text);
                Thread.sleep(10);
            }
            throw new AssertionError("Directory lock process did not become ready");
        }

        void finish() throws Exception {
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), () -> "Lock process exit was " + process.exitValue());
            assertTrue(Files.size(output) <= 8192);
        }

        void kill() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertTrue(Files.size(output) <= 8192);
        }

        @Override
        public void close() throws Exception {
            process.getOutputStream().close();
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    public static final class JavaHolder {
        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 2) {
                throw new IllegalArgumentException("Expected journal path and explicit owner");
            }
            try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(
                    Path.of(arguments[0]), Integer.parseInt(arguments[1]))) {
                lease.verifyIdentity();
                System.out.println("READY");
                System.out.flush();
                if (System.in.read() != -1) {
                    throw new IOException("Unexpected lock process input");
                }
            }
        }
    }
}
