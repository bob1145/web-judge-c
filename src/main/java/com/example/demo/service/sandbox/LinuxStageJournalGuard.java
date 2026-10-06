package com.example.demo.service.sandbox;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

final class LinuxStageJournalGuard {
    private static final String REJECTION = "Linux stage journal guard is unavailable";
    private static final LinuxStageEntryIo.Calls ENTRY_CALLS = LinuxStageEntryIo.loadCalls();
    private static final int DIRECTORY_FLAGS = 0200000 | 0400000 | 02000000;
    private static final int EXCLUSIVE_NONBLOCKING = 2 | 4;
    private static final int GET_DESCRIPTOR_FLAGS = 1;
    private static final int CLOSE_ON_EXEC = 1;
    private static final Path PROC_ROOT = Path.of("/proc");
    private static final Path PROC_DESCRIPTORS = Path.of("/proc/self/fd");
    private static final String IDENTITY_ATTRIBUTES = "unix:dev,ino,uid,gid,mode";

    private LinuxStageJournalGuard() {
    }

    static Lease acquire(Path existingJournal, int expectedOwnerUid) throws IOException {
        LinuxCalls calls = null;
        int descriptor = -1;
        try {
            requireSupportedPlatform();
            requireNotInterrupted();
            require(existingJournal != null && existingJournal.getFileSystem() == FileSystems.getDefault()
                    && existingJournal.isAbsolute()
                    && existingJournal.equals(existingJournal.normalize()) && existingJournal.getParent() != null
                    && StandardCharsets.UTF_8.newEncoder().canEncode(existingJournal.toString())
                    && expectedOwnerUid >= 0);
            calls = loadCalls();
            verifyProcfs(calls);
            descriptor = openDirectory(calls, existingJournal);
            Identity identity = descriptorIdentity(calls, descriptor, expectedOwnerUid);
            require(calls.flock(descriptor, EXCLUSIVE_NONBLOCKING) == 0);
            Lease lease = new Lease(calls, existingJournal, expectedOwnerUid, descriptor, identity);
            lease.verifyIdentity();
            descriptor = -1;
            return lease;
        } catch (IOException | RuntimeException | LinkageError failure) {
            throw unavailable();
        } finally {
            if (descriptor >= 0) {
                closeDescriptor(calls, descriptor);
            }
        }
    }

    static final class Lease implements AutoCloseable {
        private final LinuxCalls calls;
        private final Path journal;
        private final int ownerUid;
        private final Identity identity;
        private int descriptor;
        private boolean poisoned;

        private Lease(LinuxCalls calls, Path journal, int ownerUid, int descriptor, Identity identity) {
            this.calls = calls;
            this.journal = journal;
            this.ownerUid = ownerUid;
            this.descriptor = descriptor;
            this.identity = identity;
        }

        synchronized void verifyIdentity() throws IOException {
            try {
                require(descriptor >= 0 && !poisoned);
                requireNotInterrupted();
                verifyProcfs(calls);
                require(identity.equals(descriptorIdentity(calls, descriptor, ownerUid)));
                int current = openDirectory(calls, journal);
                try {
                    require(identity.equals(descriptorIdentity(calls, current, ownerUid)));
                    require(identity.equals(descriptorIdentity(calls, descriptor, ownerUid)));
                    requireNotInterrupted();
                } finally {
                    closeDescriptor(calls, current);
                }
            } catch (IOException | RuntimeException | LinkageError failure) {
                poisoned = true;
                throw unavailable();
            }
        }

        synchronized boolean entryExists(String name) throws IOException {
            int descriptor = requireHeldDescriptor();
            return LinuxStageEntryIo.exists(entryCalls(), descriptor, name);
        }

        synchronized byte[] readRegularEntry(String name) throws IOException {
            int descriptor = requireHeldDescriptor();
            return LinuxStageEntryIo.readRegular(entryCalls(), descriptor, ownerUid, name);
        }

        synchronized void requireTrustedEntryDirectory(String name) throws IOException {
            int descriptor = requireHeldDescriptor();
            LinuxStageEntryIo.requireTrustedEntryDirectory(entryCalls(), descriptor, ownerUid, name);
        }

        synchronized void publishClosedAdmission(String attemptId) throws IOException {
            int descriptor = requireHeldDescriptor();
            LinuxStageEntryIo.publishClosedAdmission(this, entryCalls(), descriptor, ownerUid, attemptId);
        }

        synchronized void replaceStageRecord(String name, byte[] expected, byte[] updated) throws IOException {
            int descriptor = requireHeldDescriptor();
            LinuxStageEntryIo.replaceStageRecord(this, entryCalls(), descriptor, ownerUid, name, expected, updated);
        }

        synchronized Path journalPath() throws IOException {
            requireHeldDescriptor();
            return journal;
        }

        private int requireHeldDescriptor() throws IOException {
            require(descriptor >= 0 && !poisoned);
            requireNotInterrupted();
            verifyProcfs(calls);
            return descriptor;
        }

        private LinuxStageEntryIo.Calls entryCalls() {
            return ENTRY_CALLS;
        }

        @Override
        public synchronized void close() throws IOException {
            if (descriptor < 0) {
                return;
            }
            IOException failure = null;
            try {
                verifyIdentity();
            } catch (IOException invalid) {
                failure = invalid;
            } finally {
                int retained = descriptor;
                descriptor = -1;
                try {
                    closeDescriptor(calls, retained);
                } catch (IOException invalid) {
                    if (failure == null) {
                        failure = invalid;
                    } else {
                        failure.addSuppressed(invalid);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static void requireSupportedPlatform() throws IOException {
        String architecture = System.getProperty("os.arch", "");
        require(Platform.isLinux() && (architecture.equals("amd64") || architecture.equals("x86_64"))
                && Native.POINTER_SIZE == 8 && Native.LONG_SIZE == 8 && Native.getNativeSize(int.class) == 4
                && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN);
    }

    private static LinuxCalls loadCalls() {
        Map<String, ?> options = Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name());
        NativeLibrary library = NativeLibrary.getInstance("c", options);
        for (String symbol : Set.of("open", "openat", "flock", "fcntl", "close", "geteuid")) {
            library.getFunction(symbol);
        }
        return Native.load("c", LinuxCalls.class, options);
    }

    private static void verifyProcfs(LinuxCalls calls) throws IOException {
        require(Files.isDirectory(PROC_ROOT, LinkOption.NOFOLLOW_LINKS)
                && PROC_ROOT.toRealPath().equals(PROC_ROOT)
                && "proc".equals(Files.getFileStore(PROC_ROOT).type())
                && "proc".equals(Files.getFileStore(PROC_DESCRIPTORS).type())
                && Files.isDirectory(PROC_DESCRIPTORS, LinkOption.NOFOLLOW_LINKS));
        require(number(Files.readAttributes(PROC_ROOT, "unix:uid", LinkOption.NOFOLLOW_LINKS), "uid") == 0);
        require(number(Files.readAttributes(PROC_DESCRIPTORS, "unix:uid", LinkOption.NOFOLLOW_LINKS), "uid")
                == Integer.toUnsignedLong(calls.geteuid()));
        requirePrivateWrites(PROC_ROOT);
        requirePrivateWrites(PROC_DESCRIPTORS);
    }

    private static int openDirectory(LinuxCalls calls, Path path) throws IOException {
        int current = -1;
        try {
            requireNotInterrupted();
            current = calls.open("/", DIRECTORY_FLAGS);
            require(current >= 0);
            for (Path component : path) {
                requireNotInterrupted();
                int child = calls.openat(current, component.toString(), DIRECTORY_FLAGS);
                require(child >= 0);
                int parent = current;
                current = child;
                closeDescriptor(calls, parent);
            }
            int opened = current;
            current = -1;
            return opened;
        } finally {
            if (current >= 0) {
                closeDescriptor(calls, current);
            }
        }
    }

    private static Identity descriptorIdentity(LinuxCalls calls, int descriptor, int ownerUid) throws IOException {
        int flags = calls.fcntl(descriptor, GET_DESCRIPTOR_FLAGS);
        require(flags >= 0 && (flags & CLOSE_ON_EXEC) == CLOSE_ON_EXEC);
        Path descriptorPath = PROC_DESCRIPTORS.resolve(Integer.toString(descriptor));
        Map<String, Object> before = Files.readAttributes(descriptorPath, IDENTITY_ATTRIBUTES);
        require(Files.isDirectory(descriptorPath));
        requirePrivateWrites(descriptorPath);
        Map<String, Object> after = Files.readAttributes(descriptorPath, IDENTITY_ATTRIBUTES);
        require(before.equals(after) && number(before, "uid") == ownerUid);
        return new Identity(number(before, "dev"), number(before, "ino"), number(before, "uid"),
                number(before, "gid"), number(before, "mode"));
    }

    private static void requirePrivateWrites(Path path) throws IOException {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
        require(!permissions.contains(PosixFilePermission.GROUP_WRITE)
                && !permissions.contains(PosixFilePermission.OTHERS_WRITE));
    }

    private static long number(Map<String, Object> attributes, String name) throws IOException {
        Object value = attributes.get(name);
        require(value instanceof Number);
        return ((Number) value).longValue();
    }

    private static void closeDescriptor(LinuxCalls calls, int descriptor) throws IOException {
        try {
            require(calls.close(descriptor) == 0);
        } catch (RuntimeException | LinkageError failure) {
            throw unavailable();
        }
    }

    private static void requireNotInterrupted() throws IOException {
        require(!Thread.currentThread().isInterrupted());
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) {
            throw unavailable();
        }
    }

    private static IOException unavailable() {
        return new IOException(REJECTION);
    }

    private interface LinuxCalls extends Library {
        int open(String path, int flags);

        int openat(int directory, String path, int flags);

        int flock(int descriptor, int operation);

        int fcntl(int descriptor, int command);

        int close(int descriptor);

        int geteuid();
    }

    private record Identity(long device, long inode, long ownerUid, long ownerGid, long mode) { }
}
