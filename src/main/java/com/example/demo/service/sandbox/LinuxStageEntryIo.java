package com.example.demo.service.sandbox;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Descriptor-relative entry operations executed while the stage journal
 * directory flock is held. File identities are read through trusted procfs
 * (/proc/self/fd/<n>) instead of guessing struct stat layouts. The admission
 * publication protocol mirrors close_existing_stage_admission byte for byte:
 * exclusive pending, fsync of file and directory, atomic rename, re-read and
 * identity reverification. Bad markers and interrupted publications are
 * retained, never repaired or reopened.
 */
final class LinuxStageEntryIo {
    private static final int READ_ONLY_FLAGS = 0 | 131072 | 2048 | 524288;
    private static final int CREATE_EXCLUSIVE_FLAGS = 1 | 64 | 128 | 131072 | 524288;
    private static final int DIRECTORY_PROBE_FLAGS = 0 | 131072 | 2048 | 524288 | 65536;
    private static final int ENOENT = 2;
    private static final int REGULAR_TYPE = 0100000;
    private static final int DIRECTORY_TYPE = 040000;
    private static final int TYPE_MASK = 0170000;
    private static final int GROUP_OTHER_WRITE = 0022;
    private static final long PRIVATE_MODE = 0600;
    private static final int ADMISSION_LIMIT_BYTES = 64 * 1024;
    private static final String CLOSED_NAME = "stage-admission.closed";
    private static final String PENDING_NAME = "stage-admission.pending";
    private static final String IDENTITY_ATTRIBUTES =
            "unix:dev,ino,uid,gid,mode,nlink,size,lastModifiedTime,ctime";
    private static final String REJECTION = "Linux stage entry operation is unavailable";

    private LinuxStageEntryIo() {
    }

    static Calls loadCalls() {
        Map<String, ?> options = Map.of(Library.OPTION_STRING_ENCODING, StandardCharsets.UTF_8.name());
        NativeLibrary library = NativeLibrary.getInstance("c", options);
        for (String symbol : Set.of("openat", "read", "write", "fsync", "fchmod", "renameat", "close")) {
            library.getFunction(symbol);
        }
        return Native.load("c", Calls.class, options);
    }

    static boolean exists(Calls calls, int directory, String name) throws IOException {
        requireDescriptorName(name);
        int descriptor = -1;
        try {
            descriptor = calls.openat(directory, name, READ_ONLY_FLAGS, 0);
            require(descriptor >= 0);
            int retained = descriptor;
            descriptor = -1;
            require(calls.close(retained) == 0);
            return true;
        } catch (com.sun.jna.LastErrorException failure) {
            if (descriptor >= 0) {
                closeQuietly(calls, descriptor);
            }
            if (failure.getErrorCode() == ENOENT) {
                return false;
            }
            throw unavailable();
        } catch (IOException | RuntimeException | LinkageError failure) {
            if (descriptor >= 0) {
                closeQuietly(calls, descriptor);
            }
            throw unavailable();
        }
    }

    static void requireTrustedEntryDirectory(Calls calls, int directory, int ownerUid, String name)
            throws IOException {
        requireDescriptorName(name);
        int descriptor = -1;
        try {
            descriptor = calls.openat(directory, name, DIRECTORY_PROBE_FLAGS, 0);
            require(descriptor >= 0);
            requireTrustedDirectoryIdentity(procIdentity(descriptor), ownerUid);
        } catch (com.sun.jna.LastErrorException failure) {
            throw unavailable();
        } catch (IOException | RuntimeException | LinkageError failure) {
            throw unavailable();
        } finally {
            if (descriptor >= 0) {
                closeQuietly(calls, descriptor);
            }
        }
    }

    static byte[] readRegular(Calls calls, int directory, int ownerUid, String name) throws IOException {
        requireDescriptorName(name);
        int descriptor = -1;
        try {
            descriptor = calls.openat(directory, name, READ_ONLY_FLAGS, 0);
            require(descriptor >= 0);
            Map<String, Object> before = procIdentity(descriptor);
            requireRegularPrivateIdentity(before, ownerUid);
            long size = ((Number) before.get("size")).longValue();
            require(size >= 1 && size <= ADMISSION_LIMIT_BYTES);
            byte[] payload = new byte[ADMISSION_LIMIT_BYTES + 1];
            int position = 0;
            while (position <= ADMISSION_LIMIT_BYTES) {
                byte[] chunk = new byte[Math.min(64 * 1024, payload.length - position)];
                int read = calls.read(descriptor, chunk, chunk.length);
                if (read == 0) {
                    break;
                }
                require(read > 0);
                System.arraycopy(chunk, 0, payload, position, read);
                position += read;
            }
            require(position <= ADMISSION_LIMIT_BYTES);
            Map<String, Object> after = procIdentity(descriptor);
            Map<String, Object> current = byNameIdentity(calls, directory, name);
            require(before.equals(after) && before.equals(current));
            int retained = descriptor;
            descriptor = -1;
            require(calls.close(retained) == 0);
            byte[] result = new byte[position];
            System.arraycopy(payload, 0, result, 0, position);
            return result;
        } catch (com.sun.jna.LastErrorException failure) {
            throw unavailable();
        } catch (IOException | RuntimeException | LinkageError failure) {
            throw unavailable();
        } finally {
            if (descriptor >= 0) {
                closeQuietly(calls, descriptor);
            }
        }
    }

    static void publishClosedAdmission(LinuxStageJournalGuard.Lease lease, Calls calls, int directory,
                                       int ownerUid, String attemptId) throws IOException {
        if (!LinuxStageRecord.isCanonicalUuid(attemptId)) {
            throw unavailable();
        }
        lease.verifyIdentity();
        if (exists(calls, directory, PENDING_NAME)) {
            throw new IOException("stage admission publication is incomplete");
        }
        byte[] payload = canonicalClosedAdmission(attemptId);
        if (exists(calls, directory, CLOSED_NAME)) {
            byte[] retained = readClosedMarker(calls, directory, ownerUid, payload);
            fsyncDirectory(calls, directory);
            byte[] verified = readClosedMarker(calls, directory, ownerUid, payload);
            require(java.util.Arrays.equals(retained, verified));
            lease.verifyIdentity();
            return;
        }
        int descriptor = -1;
        Map<String, Object> published;
        try {
            descriptor = calls.openat(directory, PENDING_NAME, CREATE_EXCLUSIVE_FLAGS, (int) PRIVATE_MODE);
            require(descriptor >= 0);
            calls.fchmod(descriptor, PRIVATE_MODE);
            fsyncDirectory(calls, directory);
            lease.verifyIdentity();
            int position = 0;
            while (position < payload.length) {
                byte[] remaining = java.util.Arrays.copyOfRange(payload, position, payload.length);
                int written = calls.write(descriptor, remaining, remaining.length);
                require(written > 0);
                position += written;
            }
            require(position == payload.length);
            calls.fsync(descriptor);
            Map<String, Object> written = procIdentity(descriptor);
            requireAdmissionFile(written, ownerUid);
            Map<String, Object> current = byNameIdentity(calls, directory, PENDING_NAME);
            require(written.equals(current));
            published = written;
        } catch (com.sun.jna.LastErrorException failure) {
            throw unavailable();
        } catch (IOException | RuntimeException | LinkageError failure) {
            throw unavailable();
        } finally {
            if (descriptor >= 0) {
                closeQuietly(calls, descriptor);
            }
        }
        lease.verifyIdentity();
        require(!exists(calls, directory, CLOSED_NAME));
        require(published.equals(byNameIdentity(calls, directory, PENDING_NAME)));
        try {
            calls.renameat(directory, PENDING_NAME, directory, CLOSED_NAME);
        } catch (com.sun.jna.LastErrorException failure) {
            throw unavailable();
        }
        fsyncDirectory(calls, directory);
        readClosedMarker(calls, directory, ownerUid, payload);
        lease.verifyIdentity();
    }

    static void replaceStageRecord(LinuxStageJournalGuard.Lease lease, Calls calls, int directory,
                                   int ownerUid, String name, byte[] expected, byte[] updated) throws IOException {
        require(lease != null && calls != null && name != null && LinuxStageRecord.isJournalFileName(name)
                && expected != null && expected.length >= 1 && expected.length <= ADMISSION_LIMIT_BYTES
                && updated != null && updated.length >= 1 && updated.length <= ADMISSION_LIMIT_BYTES);
        byte[] previous = expected.clone();
        byte[] replacement = updated.clone();
        synchronized (lease) {
            try {
                lease.verifyIdentity();
                StageSnapshot original = readStageSnapshot(calls, directory, ownerUid, name);
                require(Arrays.equals(original.payload(), previous));
                String temporary = name.substring(0, name.length() - 5) + "."
                        + UUID.randomUUID().toString().replace("-", "") + ".tmp";
                Map<String, Object> written;
                try (StageDescriptor output = openStageDescriptor(calls, directory, temporary,
                        CREATE_EXCLUSIVE_FLAGS, (int) PRIVATE_MODE)) {
                    requireStageResult(calls.fchmod(output.descriptor, PRIVATE_MODE) == 0, "fchmod");
                    Map<String, Object> created = procIdentity(output.descriptor);
                    requireRegularPrivateIdentity(created, ownerUid);
                    require((((Number) created.get("mode")).longValue() & 07777) == PRIVATE_MODE
                            && ((Number) created.get("size")).longValue() == 0);
                    requireStageResult(calls.fsync(directory) == 0, "initial directory fsync");
                    lease.verifyIdentity();
                    int position = 0;
                    while (position < replacement.length) {
                        byte[] remaining = Arrays.copyOfRange(replacement, position, replacement.length);
                        int count = calls.write(output.descriptor, remaining, remaining.length);
                        requireStageResult(count > 0 && count <= remaining.length, "write");
                        position += count;
                    }
                    requireStageResult(calls.fsync(output.descriptor) == 0, "file fsync");
                    written = procIdentity(output.descriptor);
                    requireAdmissionFile(written, ownerUid);
                    requireStageObject(created, written);
                    require(((Number) written.get("size")).longValue() == replacement.length);
                    require(written.equals(stageNameIdentity(calls, directory, temporary)));
                }
                try (StageDescriptor publication = openStageDescriptor(calls, directory, temporary,
                        READ_ONLY_FLAGS, 0)) {
                    StageSnapshot pending = readStageSnapshot(calls, directory, ownerUid, temporary,
                            publication.descriptor);
                    require(written.equals(pending.identity()) && Arrays.equals(pending.payload(), replacement));
                    StageSnapshot retained = readStageSnapshot(calls, directory, ownerUid, name);
                    require(original.identity().equals(retained.identity())
                            && Arrays.equals(retained.payload(), previous));
                    require(written.equals(stageNameIdentity(calls, directory, temporary)));
                    lease.verifyIdentity();
                    requireStageResult(calls.renameat(directory, temporary, directory, name) == 0, "renameat");
                    requireStageResult(calls.fsync(directory) == 0, "final directory fsync");
                    Map<String, Object> renamed = procIdentity(publication.descriptor);
                    requireAdmissionFile(renamed, ownerUid);
                    requireStageObject(written, renamed);
                    for (String attribute : Set.of("size", "lastModifiedTime")) {
                        require(Objects.equals(written.get(attribute), renamed.get(attribute)));
                    }
                    StageSnapshot published = readStageSnapshot(calls, directory, ownerUid, name);
                    require(renamed.equals(published.identity())
                            && renamed.equals(procIdentity(publication.descriptor))
                            && Arrays.equals(published.payload(), replacement));
                }
                lease.verifyIdentity();
            } catch (IOException | RuntimeException | LinkageError failure) {
                throw stageFailure(failure);
            }
        }
    }

    private static StageSnapshot readStageSnapshot(Calls calls, int directory, int ownerUid, String name)
            throws IOException {
        try (StageDescriptor input = openStageDescriptor(calls, directory, name, READ_ONLY_FLAGS, 0)) {
            return readStageSnapshot(calls, directory, ownerUid, name, input.descriptor);
        }
    }

    private static StageSnapshot readStageSnapshot(Calls calls, int directory, int ownerUid, String name,
                                                   int descriptor) throws IOException {
        Map<String, Object> before = procIdentity(descriptor);
        requireAdmissionFile(before, ownerUid);
        require(before.equals(stageNameIdentity(calls, directory, name)));
        byte[] payload = new byte[ADMISSION_LIMIT_BYTES + 1];
        int position = 0;
        while (position < payload.length) {
            byte[] chunk = new byte[payload.length - position];
            int count = calls.read(descriptor, chunk, chunk.length);
            requireStageResult(count >= 0 && count <= chunk.length, "read");
            if (count == 0) {
                break;
            }
            System.arraycopy(chunk, 0, payload, position, count);
            position += count;
        }
        require(position >= 1 && position <= ADMISSION_LIMIT_BYTES
                && position == ((Number) before.get("size")).longValue());
        require(before.equals(procIdentity(descriptor))
                && before.equals(stageNameIdentity(calls, directory, name)));
        return new StageSnapshot(before, Arrays.copyOf(payload, position));
    }

    private static Map<String, Object> stageNameIdentity(Calls calls, int directory, String name)
            throws IOException {
        try (StageDescriptor named = openStageDescriptor(calls, directory, name, READ_ONLY_FLAGS, 0)) {
            return procIdentity(named.descriptor);
        }
    }

    private static StageDescriptor openStageDescriptor(Calls calls, int directory, String name, int flags, int mode)
            throws IOException {
        int descriptor = calls.openat(directory, name, flags, mode);
        requireStageResult(descriptor >= 0, "openat");
        return new StageDescriptor(calls, descriptor);
    }

    private static void requireStageObject(Map<String, Object> expected, Map<String, Object> current)
            throws IOException {
        for (String attribute : Set.of("dev", "ino", "uid", "gid", "mode", "nlink")) {
            require(Objects.equals(expected.get(attribute), current.get(attribute)));
        }
    }

    private static void requireStageResult(boolean condition, String operation) throws IOException {
        if (!condition) {
            throw new IOException(REJECTION + ": " + operation + " failed");
        }
    }

    private static IOException stageFailure(Throwable failure) {
        return failure instanceof IOException invalid ? invalid : new IOException(REJECTION, failure);
    }

    private record StageSnapshot(Map<String, Object> identity, byte[] payload) { }

    private static final class StageDescriptor implements AutoCloseable {
        private final Calls calls;
        private int descriptor;

        private StageDescriptor(Calls calls, int descriptor) {
            this.calls = calls;
            this.descriptor = descriptor;
        }

        @Override
        public void close() throws IOException {
            if (descriptor < 0) {
                return;
            }
            int retained = descriptor;
            descriptor = -1;
            try {
                requireStageResult(calls.close(retained) == 0, "close");
            } catch (RuntimeException | LinkageError failure) {
                throw stageFailure(failure);
            }
        }
    }

    private static byte[] readClosedMarker(Calls calls, int directory, int ownerUid, byte[] canonical)
            throws IOException {
        int descriptor = -1;
        try {
            descriptor = calls.openat(directory, CLOSED_NAME, READ_ONLY_FLAGS, 0);
            require(descriptor >= 0);
            Map<String, Object> before = procIdentity(descriptor);
            requireAdmissionFile(before, ownerUid);
            byte[] payload = new byte[ADMISSION_LIMIT_BYTES + 1];
            int position = 0;
            while (position <= ADMISSION_LIMIT_BYTES) {
                byte[] chunk = new byte[Math.min(64 * 1024, payload.length - position)];
                int read = calls.read(descriptor, chunk, chunk.length);
                if (read == 0) {
                    break;
                }
                require(read > 0);
                System.arraycopy(chunk, 0, payload, position, read);
                position += read;
            }
            require(position <= ADMISSION_LIMIT_BYTES);
            Map<String, Object> after = procIdentity(descriptor);
            Map<String, Object> current = byNameIdentity(calls, directory, CLOSED_NAME);
            require(before.equals(after) && before.equals(current));
            int retained = descriptor;
            descriptor = -1;
            require(calls.close(retained) == 0);
            require(java.util.Arrays.equals(canonical, java.util.Arrays.copyOf(payload, position)));
            return java.util.Arrays.copyOf(payload, position);
        } catch (com.sun.jna.LastErrorException failure) {
            throw unavailable();
        } catch (IOException | RuntimeException | LinkageError failure) {
            throw unavailable();
        } finally {
            if (descriptor >= 0) {
                closeQuietly(calls, descriptor);
            }
        }
    }

    static byte[] canonicalClosedAdmission(String attemptId) {
        return ("{\"admissionVersion\":1,\"attemptId\":\"" + attemptId + "\",\"state\":\"CLOSED\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void fsyncDirectory(Calls calls, int directory) throws IOException {
        try {
            require(calls.fsync(directory) == 0);
        } catch (com.sun.jna.LastErrorException failure) {
            throw unavailable();
        }
    }

    private static Map<String, Object> byNameIdentity(Calls calls, int directory, String name) throws IOException {
        int descriptor = -1;
        try {
            descriptor = calls.openat(directory, name, READ_ONLY_FLAGS, 0);
            require(descriptor >= 0);
            Map<String, Object> identity = procIdentity(descriptor);
            int retained = descriptor;
            descriptor = -1;
            require(calls.close(retained) == 0);
            return identity;
        } catch (com.sun.jna.LastErrorException failure) {
            throw unavailable();
        } catch (IOException | RuntimeException | LinkageError failure) {
            throw unavailable();
        } finally {
            if (descriptor >= 0) {
                closeQuietly(calls, descriptor);
            }
        }
    }

    private static Map<String, Object> procIdentity(int descriptor) throws IOException {
        Path descriptorPath = Path.of("/proc/self/fd", Integer.toString(descriptor));
        Map<String, Object> attributes = Files.readAttributes(descriptorPath, IDENTITY_ATTRIBUTES);
        Map<String, Object> repeat = Files.readAttributes(descriptorPath, IDENTITY_ATTRIBUTES);
        require(attributes.equals(repeat));
        return attributes;
    }

    private static void requireRegularPrivateIdentity(Map<String, Object> identity, int ownerUid)
            throws IOException {
        require((((Number) identity.get("mode")).longValue() & TYPE_MASK) == REGULAR_TYPE);
        require(((Number) identity.get("nlink")).longValue() == 1);
        require(((Number) identity.get("uid")).longValue() == ownerUid);
        require((((Number) identity.get("mode")).longValue() & GROUP_OTHER_WRITE) == 0);
    }

    private static void requireTrustedDirectoryIdentity(Map<String, Object> identity, int ownerUid)
            throws IOException {
        require((((Number) identity.get("mode")).longValue() & TYPE_MASK) == DIRECTORY_TYPE);
        require(((Number) identity.get("uid")).longValue() == ownerUid);
        require((((Number) identity.get("mode")).longValue() & GROUP_OTHER_WRITE) == 0);
    }

    private static void requireAdmissionFile(Map<String, Object> identity, int ownerUid) throws IOException {
        requireRegularPrivateIdentity(identity, ownerUid);
        require((((Number) identity.get("mode")).longValue() & 07777) == PRIVATE_MODE);
        long size = ((Number) identity.get("size")).longValue();
        require(size >= 1 && size <= ADMISSION_LIMIT_BYTES);
    }

    private static void requireDescriptorName(String name) throws IOException {
        require(name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && !name.contains("/") && StandardCharsets.UTF_8.newEncoder().canEncode(name));
    }

    private static void closeQuietly(Calls calls, int descriptor) throws IOException {
        try {
            calls.close(descriptor);
        } catch (RuntimeException | LinkageError ignored) {
            throw unavailable();
        }
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) {
            throw unavailable();
        }
    }

    private static IOException unavailable() {
        return new IOException(REJECTION);
    }

    interface Calls extends Library {
        int openat(int directory, String path, int flags, int mode) throws com.sun.jna.LastErrorException;

        int read(int descriptor, byte[] buffer, int length) throws com.sun.jna.LastErrorException;

        int write(int descriptor, byte[] buffer, int length) throws com.sun.jna.LastErrorException;

        int fsync(int descriptor) throws com.sun.jna.LastErrorException;

        int fchmod(int descriptor, long mode) throws com.sun.jna.LastErrorException;

        int renameat(int oldDirectory, String oldName, int newDirectory, String newName)
                throws com.sun.jna.LastErrorException;

        int close(int descriptor) throws com.sun.jna.LastErrorException;
    }
}
