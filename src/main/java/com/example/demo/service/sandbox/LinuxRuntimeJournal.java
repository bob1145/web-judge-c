package com.example.demo.service.sandbox;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class LinuxRuntimeJournal implements AutoCloseable {
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final String STATE_NAME = "runtime-state.json";
    private static final String TEMPORARY_NAME = STATE_NAME + ".tmp";
    private static final String LOCK_NAME = "runtime-state.lock";
    static final String CANCELLATION_NAME = "cancel.requested";
    private static final String CANCELLATION_TEMPORARY_NAME = CANCELLATION_NAME + ".tmp";
    private static final FileAttribute<Set<PosixFilePermission>> PRIVATE_FILE =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    private static final String ATTRIBUTES =
            "unix:uid,gid,mode,nlink,fileKey,size,lastModifiedTime,ctime,isRegularFile,isDirectory";

    private final Path directory;
    private final String judgeId;
    private final String attemptId;
    private final ObjectMapper mapper;
    private final int uid;
    private final int gid;
    private final Object directoryKey;
    private FileChannel lockChannel;
    private FileLock lock;
    private Object lockKey;
    private boolean hasWritten;
    private boolean poisoned;
    private boolean closed;

    private LinuxRuntimeJournal(Path directory, String judgeId, String attemptId, ObjectMapper mapper,
                                int uid, int gid) throws IOException {
        if (!Platform.isLinux() || directory == null || !directory.isAbsolute()
                || !directory.equals(directory.normalize()) || directory.getNameCount() < 4
                || uid < 0 || gid < 0 || !validIdentity(judgeId) || !validIdentity(attemptId) || mapper == null) {
            throw new IOException("Linux journal requires an absolute normalized directory and explicit identities");
        }
        validateAttemptPath(directory, attemptId);
        if (LinuxIdentity.INSTANCE.geteuid() != uid || LinuxIdentity.INSTANCE.getegid() != gid) {
            throw new IOException("Journal writer effective UID/GID do not match configuration");
        }
        this.directory = directory;
        this.judgeId = judgeId;
        this.attemptId = attemptId;
        this.uid = uid;
        this.gid = gid;
        this.mapper = mapper.copy();
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_DEPTH).maxStringLength(MAX_BYTES).maxNumberLength(1000).build());
        validateDirectories();
        directoryKey = attributes(directory).get("fileKey");
        if (directoryKey == null) {
            throw new IOException("Journal directory identity is unavailable");
        }
    }

    public static LinuxRuntimeJournal create(Path attemptDirectory, String judgeId, String attemptId,
                                             ObjectMapper mapper, int writerUid, int writerGid) throws IOException {
        LinuxRuntimeJournal journal = new LinuxRuntimeJournal(
                attemptDirectory, judgeId, attemptId, mapper, writerUid, writerGid);
        journal.requireAbsent(STATE_NAME);
        journal.requireAbsent(TEMPORARY_NAME);
        journal.requireAbsent(LOCK_NAME);
        try {
            journal.lockChannel = FileChannel.open(attemptDirectory.resolve(LOCK_NAME),
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS), PRIVATE_FILE);
            journal.acquireLock();
            journal.lockChannel.force(true);
            journal.forceDirectory();
            return journal;
        } catch (IOException | RuntimeException failure) {
            journal.closeAfterFailure(failure);
            throw failure;
        }
    }

    public static ObjectNode readRetained(Path attemptDirectory, String judgeId, String attemptId,
                                          ObjectMapper mapper, int uid, int gid) throws IOException {
        try (LinuxRuntimeJournal journal = openExisting(attemptDirectory, judgeId, attemptId, mapper, uid, gid)) {
            return journal.read();
        }
    }

    public static LinuxRuntimeJournal openExisting(Path attemptDirectory, String judgeId, String attemptId,
                                                   ObjectMapper mapper, int uid, int gid) throws IOException {
        LinuxRuntimeJournal journal = new LinuxRuntimeJournal(attemptDirectory, judgeId, attemptId, mapper, uid, gid);
        try {
            Map<String, Object> before = journal.requirePrivateFile(attemptDirectory.resolve(LOCK_NAME));
            journal.lockChannel = FileChannel.open(attemptDirectory.resolve(LOCK_NAME),
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            journal.acquireLock();
            journal.requireSame(before, journal.requirePrivateFile(attemptDirectory.resolve(LOCK_NAME)));
            journal.hasWritten = true;
            journal.read();
            return journal;
        } catch (IOException | RuntimeException failure) {
            journal.closeAfterFailure(failure);
            throw failure;
        }
    }

    public synchronized void write(ObjectNode snapshot) throws IOException {
        ensureUsable();
        requireAbsent(TEMPORARY_NAME);
        if (hasWritten) {
            requirePrivateFile(directory.resolve(STATE_NAME));
        } else {
            requireAbsent(STATE_NAME);
        }
        validateIdentity(snapshot);
        byte[] bytes = serializeBounded(snapshot);
        parseSnapshot(bytes, bytes.length);
        Path temporary = directory.resolve(TEMPORARY_NAME);
        try {
            writeNewFile(temporary, bytes);
            ensureUsable();
            if (hasWritten) {
                requirePrivateFile(directory.resolve(STATE_NAME));
            } else {
                requireAbsent(STATE_NAME);
            }
            Files.move(temporary, directory.resolve(STATE_NAME),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory();
            requirePrivateFile(directory.resolve(STATE_NAME));
            hasWritten = true;
        } catch (IOException | RuntimeException failure) {
            poisoned = true;
            throw failure;
        }
    }

    public synchronized ObjectNode read() throws IOException {
        ensureUsable();
        requireAbsent(TEMPORARY_NAME);
        Path state = directory.resolve(STATE_NAME);
        Map<String, Object> before = requirePrivateFile(state);
        long size = ((Number) before.get("size")).longValue();
        if (size < 1 || size > MAX_BYTES) {
            throw new IOException("Journal exceeds the bounded document size");
        }
        ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES + 1);
        try (FileChannel channel = FileChannel.open(state, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                if (buffer.position() > MAX_BYTES) {
                    throw new IOException("Journal exceeds the bounded document size");
                }
            }
        }
        requireSame(before, requirePrivateFile(state));
        ensureUsable();
        requireAbsent(TEMPORARY_NAME);
        if (buffer.position() != size) {
            throw new IOException("Journal changed while reading");
        }
        return parseSnapshot(buffer.array(), buffer.position()).deepCopy();
    }

    public synchronized void writeControlFile(String filename, JsonNode value) throws IOException {
        ensureUsable();
        requireAbsent(TEMPORARY_NAME);
        if (!"supervisor-config.json".equals(filename)) {
            throw new IOException("Unsupported journal control filename");
        }
        requireAbsent(filename);
        byte[] bytes = serializeBounded(value);
        try {
            writeNewFile(directory.resolve(filename), bytes);
            forceDirectory();
        } catch (IOException | RuntimeException failure) {
            poisoned = true;
            throw failure;
        }
    }

    public synchronized void requestCancellation() throws IOException {
        try {
            read();
            requireAbsent(CANCELLATION_TEMPORARY_NAME);
            Path marker = directory.resolve(CANCELLATION_NAME);
            boolean present;
            try {
                attributes(marker);
                present = true;
            } catch (NoSuchFileException absent) {
                present = false;
            }
            if (!present) {
                writeNewFile(marker, new byte[0]);
            }
            Map<String, Object> before = requireCancellationFile();
            forceDirectory();
            ensureUsable();
            requireAbsent(TEMPORARY_NAME);
            requireAbsent(CANCELLATION_TEMPORARY_NAME);
            requireSame(before, requireCancellationFile());
        } catch (IOException | RuntimeException failure) {
            poisoned = true;
            throw failure;
        }
    }

    synchronized void verifyCancellationRequestIfPresent() throws IOException {
        try {
            ensureUsable();
            requireAbsent(TEMPORARY_NAME);
            requireAbsent(CANCELLATION_TEMPORARY_NAME);
            try {
                attributes(directory.resolve(CANCELLATION_NAME));
            } catch (NoSuchFileException absent) {
                return;
            }
            requireCancellationFile();
            ensureUsable();
        } catch (IOException | RuntimeException failure) {
            poisoned = true;
            throw failure;
        }
    }

    private Map<String, Object> requireCancellationFile() throws IOException {
        Path marker = directory.resolve(CANCELLATION_NAME);
        Map<String, Object> before = requirePrivateFile(marker);
        if (number(before, "size") != 0) {
            throw new IOException("Cancellation marker must be empty");
        }
        try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != 0 || channel.read(ByteBuffer.allocate(1)) != -1) {
                throw new IOException("Cancellation marker must remain empty");
            }
        }
        requireSame(before, requirePrivateFile(marker));
        return before;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void acquireLock() throws IOException {
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException exception) {
            throw new IOException("Journal is already locked", exception);
        }
        if (lock == null) {
            throw new IOException("Journal is already locked");
        }
        lockKey = requirePrivateFile(directory.resolve(LOCK_NAME)).get("fileKey");
    }

    private void ensureUsable() throws IOException {
        if (closed || poisoned || lock == null || !lock.isValid()) {
            throw new IOException("Journal is closed, failed, or unlocked");
        }
        validateDirectories();
        if (!Objects.equals(directoryKey, attributes(directory).get("fileKey"))
                || !Objects.equals(lockKey, requirePrivateFile(directory.resolve(LOCK_NAME)).get("fileKey"))) {
            throw new IOException("Journal directory or lock identity changed");
        }
    }

    private void validateDirectories() throws IOException {
        Path current = directory.getRoot();
        for (Path component : directory) {
            current = current.resolve(component);
            Map<String, Object> attributes = attributes(current);
            if (!Boolean.TRUE.equals(attributes.get("isDirectory")) || Files.isSymbolicLink(current)) {
                throw new IOException("Journal ancestors must be existing non-linked directories");
            }
        }
        current = directory;
        for (int depth = 0; depth < 4; depth++) {
            Map<String, Object> attributes = attributes(current);
            if (number(attributes, "uid") != uid || number(attributes, "gid") != gid
                    || (number(attributes, "mode") & 0022) != 0) {
                throw new IOException("Task and control directories must belong to the writer and reject other writers");
            }
            current = current.getParent();
        }
    }

    private Map<String, Object> requirePrivateFile(Path path) throws IOException {
        Map<String, Object> attributes = attributes(path);
        if (!Boolean.TRUE.equals(attributes.get("isRegularFile")) || Files.isSymbolicLink(path)
                || number(attributes, "nlink") != 1 || number(attributes, "uid") != uid
                || number(attributes, "gid") != gid || (number(attributes, "mode") & 07777) != 0600
                || attributes.get("fileKey") == null) {
            throw new IOException("Journal file must be a private single-link regular writer-owned file");
        }
        return attributes;
    }

    private void requireAbsent(String filename) throws IOException {
        try {
            attributes(directory.resolve(filename));
        } catch (NoSuchFileException absent) {
            return;
        }
        throw new IOException("Retained journal evidence already exists: " + filename);
    }

    private void writeNewFile(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, Set.of(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), PRIVATE_FILE)) {
            requirePrivateFile(path);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        requirePrivateFile(path);
    }

    private void forceDirectory() throws IOException {
        validateDirectories();
        if (!Objects.equals(directoryKey, attributes(directory).get("fileKey"))) {
            throw new IOException("Journal directory identity changed");
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private byte[] serializeBounded(JsonNode value) throws IOException {
        if (value == null) {
            throw new IOException("JSON value is required");
        }
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.add(new NodeDepth(value, 1));
        int count = 0;
        while (!pending.isEmpty()) {
            NodeDepth entry = pending.removeLast();
            if (++count > MAX_BYTES || entry.depth() > MAX_DEPTH) {
                throw new IOException("JSON tree exceeds journal structural bounds");
            }
            JsonNode node = entry.node();
            if (!node.isObject() && !node.isArray() && !node.isTextual() && !node.isNumber()
                    && !node.isBoolean() && !node.isNull()) {
                throw new IOException("Journal requires plain JSON nodes");
            }
            for (JsonNode child : entry.node()) {
                if (pending.size() >= MAX_BYTES) {
                    throw new IOException("JSON tree exceeds journal structural bounds");
                }
                pending.add(new NodeDepth(child, entry.depth() + 1));
            }
        }
        BoundedOutput output = new BoundedOutput();
        mapper.writeValue(output, value.deepCopy());
        return output.bytes.toByteArray();
    }

    private ObjectNode parseSnapshot(byte[] bytes, int length) throws IOException {
        JsonNode parsed = mapper.readTree(bytes, 0, length);
        if (!(parsed instanceof ObjectNode snapshot)) {
            throw new IOException("Journal must contain one JSON object");
        }
        validateIdentity(snapshot);
        return snapshot;
    }

    private void validateIdentity(ObjectNode snapshot) throws IOException {
        if (snapshot == null || !snapshot.path("version").isIntegralNumber()
                || !snapshot.path("version").canConvertToInt() || snapshot.path("version").intValue() != 1
                || !textMatches(snapshot, "judgeId", judgeId) || !textMatches(snapshot, "attemptId", attemptId)
                || !textMatches(snapshot, "provider", "linux-container")) {
            throw new IOException("Journal version or execution identity does not match");
        }
    }

    private static boolean textMatches(ObjectNode snapshot, String field, String expected) {
        return snapshot.path(field).isTextual() && expected.equals(snapshot.path(field).textValue());
    }

    private static boolean validIdentity(String value) {
        return value != null && !value.isBlank() && value.length() <= 256;
    }

    private static void validateAttemptPath(Path directory, String attemptId) throws IOException {
        try {
            if (!UUID.fromString(attemptId).toString().equals(attemptId)) {
                throw new IOException("Journal attempt ID must be a canonical UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Journal attempt ID must be a canonical UUID", invalid);
        }
        if (!directory.getFileName().toString().equals(attemptId)
                || !directory.getParent().getFileName().toString().equals("attempts")
                || !directory.getParent().getParent().getFileName().toString().equals("control")) {
            throw new IOException("Journal directory must be control/attempts/<attemptId>");
        }
    }

    private static Map<String, Object> attributes(Path path) throws IOException {
        return Files.readAttributes(path, ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
    }

    private static long number(Map<String, Object> attributes, String key) {
        return ((Number) attributes.get(key)).longValue();
    }

    private void requireSame(Map<String, Object> before, Map<String, Object> after) throws IOException {
        if (!before.equals(after)) {
            throw new IOException("Journal file changed during access");
        }
    }

    private void closeAfterFailure(Exception failure) {
        try {
            close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private interface LinuxIdentity extends Library {
        LinuxIdentity INSTANCE = Native.load("c", LinuxIdentity.class);

        int geteuid();

        int getegid();
    }

    private record NodeDepth(JsonNode node, int depth) {
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(int value) throws IOException {
            if (bytes.size() >= MAX_BYTES) {
                throw new IOException("Journal exceeds the bounded document size");
            }
            bytes.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            if (length > MAX_BYTES - bytes.size()) {
                throw new IOException("Journal exceeds the bounded document size");
            }
            bytes.write(buffer, offset, length);
        }
    }
}
