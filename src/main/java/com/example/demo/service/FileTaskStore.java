package com.example.demo.service;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class FileTaskStore implements TaskStore {

    private static final Pattern SAFE_JUDGE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final ObjectMapper objectMapper;
    private final Path storageBase;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public FileTaskStore(ObjectMapper objectMapper) {
        this(objectMapper, Path.of(System.getProperty("java.io.tmpdir"), "online-judge"));
    }

    public FileTaskStore(ObjectMapper objectMapper, Path storageBase) {
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.storageBase = storageBase.toAbsolutePath().normalize();
    }

    @Override
    public Path taskDirectory(String judgeId) {
        validateJudgeId(judgeId);
        Path directory = storageBase.resolve("judge-" + judgeId).toAbsolutePath().normalize();
        ensureInsideStorageBase(directory);
        return directory;
    }

    public Path storageBase() {
        return storageBase;
    }

    public String relativeTaskPath(String judgeId) {
        return storageBase.relativize(taskDirectory(judgeId)).toString();
    }

    @Override
    public List<JudgeTask> findAll() throws IOException {
        return loadAllTasks();
    }

    public boolean deleteTaskDirectory(String judgeId) throws IOException {
        validateJudgeId(judgeId);
        synchronized (lock(judgeId)) {
            Path directory = taskDirectory(judgeId);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }

            List<Path> paths = safeDeletePaths(directory);

            IOException failure = null;
            Path realStorageBase = storageBase.toRealPath();
            Path realDirectory = directory.toRealPath();
            for (Path path : paths) {
                try {
                    validateSafeDeleteEntry(path, realStorageBase, realDirectory);
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    if (failure == null) {
                        failure = ex;
                    } else {
                        failure.addSuppressed(ex);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
            locks.remove(judgeId);
            return true;
        }
    }

    @Override
    public JudgeTask create(JudgeTask task) throws IOException {
        validateJudgeId(task.getJudgeId());
        Path workDir = Path.of(task.getWorkDir()).toAbsolutePath().normalize();
        ensureInsideStorageBase(workDir);
        synchronized (lock(task.getJudgeId())) {
            Files.createDirectories(workDir);
            validateTaskDirectory(workDir);
            JudgeTask normalized = copyForWrite(task);
            if (normalized.getCreatedAt() == null) {
                normalized.setCreatedAt(Instant.now());
            }
            if (normalized.getStatus() == null) {
                normalized.setStatus(JudgeStatus.CREATED);
            }
            writeJsonAtomically(metadataFile(workDir), normalized);
            appendEvent(workDir, event(normalized.getJudgeId(), normalized.getStatus(), normalized.getMessage()));
            return normalized;
        }
    }

    @Override
    public Optional<JudgeTask> find(String judgeId) throws IOException {
        validateJudgeId(judgeId);
        synchronized (lock(judgeId)) {
            Path workDir = taskDirectory(judgeId);
            if (!Files.exists(workDir, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            validateTaskDirectory(workDir);
            Path metadata = metadataFile(workDir);
            if (!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            validateRegularFile(metadata);
            return Optional.of(objectMapper.readValue(metadata.toFile(), JudgeTask.class));
        }
    }

    @Override
    public JudgeTask updateStatus(String judgeId, JudgeStatus status, String message) throws IOException {
        validateJudgeId(judgeId);
        synchronized (lock(judgeId)) {
            JudgeTask task = find(judgeId)
                    .orElseThrow(() -> new IllegalArgumentException("Judge task not found: " + judgeId));
            JudgeStatus current = task.getStatus();
            validateTransition(current, status);

            task.setStatus(status);
            task.setMessage(message);
            Instant now = Instant.now();
            if (task.getStartedAt() == null && startsExecution(status)) {
                task.setStartedAt(now);
            }
            if (status.isTerminal()) {
                task.setFinishedAt(now);
            }

            Path workDir = Path.of(task.getWorkDir()).toAbsolutePath().normalize();
            ensureInsideStorageBase(workDir);
            validateTaskDirectory(workDir);
            writeJsonAtomically(metadataFile(workDir), task);
            appendEvent(workDir, event(judgeId, status, message));
            return task;
        }
    }

    @Override
    public JudgeTask saveRunHandle(String judgeId, SandboxRunHandle handle) throws IOException {
        validateJudgeId(judgeId);
        if (handle == null) {
            throw new IllegalArgumentException("Sandbox run handle is required");
        }
        if (handle.judgeId() != null && !judgeId.equals(handle.judgeId())) {
            throw new IllegalArgumentException("Sandbox run handle judgeId does not match task");
        }
        synchronized (lock(judgeId)) {
            JudgeTask task = find(judgeId)
                    .orElseThrow(() -> new IllegalArgumentException("Judge task not found: " + judgeId));
            task.setSandboxRunHandle(handle);

            Path workDir = Path.of(task.getWorkDir()).toAbsolutePath().normalize();
            ensureInsideStorageBase(workDir);
            validateTaskDirectory(workDir);
            writeJsonAtomically(metadataFile(workDir), task);
            return task;
        }
    }

    @Override
    public void saveSummary(String judgeId, JudgeProgress summary) throws IOException {
        validateJudgeId(judgeId);
        synchronized (lock(judgeId)) {
            JudgeTask task = find(judgeId)
                    .orElseThrow(() -> new IllegalArgumentException("Judge task not found: " + judgeId));
            Path workDir = Path.of(task.getWorkDir()).toAbsolutePath().normalize();
            ensureInsideStorageBase(workDir);
            validateTaskDirectory(workDir);
            writeJsonAtomically(summaryFile(workDir), summary);
        }
    }

    @Override
    public Optional<JudgeProgress> findSummary(String judgeId) throws IOException {
        validateJudgeId(judgeId);
        synchronized (lock(judgeId)) {
            Path workDir = taskDirectory(judgeId);
            if (!Files.exists(workDir, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            validateTaskDirectory(workDir);
            Path summaryPath = summaryFile(workDir);
            if (!Files.exists(summaryPath, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            validateRegularFile(summaryPath);

            JsonNode json = objectMapper.readTree(summaryPath.toFile());
            String status = json.path("status").asText();
            String message = json.path("message").asText();
            int progress = json.path("progress").asInt();
            List<TestCaseResult> results = readResults(json.path("results"));
            JudgeSummary judgeSummary = null;
            JsonNode summaryNode = json.path("summary");
            if (summaryNode != null && summaryNode.isObject()) {
                judgeSummary = objectMapper.treeToValue(summaryNode, JudgeSummary.class);
            }
            return Optional.of(new JudgeProgress(status, message, progress, results, judgeSummary));
        }
    }

    @Override
    public List<JudgeTask> markStaleRunningTasksOnStartup() throws IOException {
        Files.createDirectories(storageBase);
        List<JudgeTask> staleTasks = new ArrayList<>();
        for (JudgeTask task : loadAllTasks()) {
            if (task.getStatus() == JudgeStatus.RUNNING || task.getStatus() == JudgeStatus.QUEUED) {
                staleTasks.add(updateStatus(task.getJudgeId(), JudgeStatus.STALE, "Task marked stale after service restart"));
            }
        }
        return staleTasks;
    }

    protected void moveAtomically(Path tempFile, Path targetFile) throws IOException {
        try {
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeJsonAtomically(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path tempFile = target.getParent().resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
            moveAtomically(tempFile, target);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private List<JudgeTask> loadAllTasks() throws IOException {
        if (!Files.exists(storageBase)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(storageBase)) {
            List<Path> metadataFiles = stream
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .peek(path -> {
                        try {
                            validateTaskDirectory(path);
                        } catch (IOException ex) {
                            throw new UnsafeTaskDirectoryException(ex);
                        }
                    })
                    .map(this::metadataFile)
                    .filter(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                    .peek(path -> {
                        try {
                            validateRegularFile(path);
                        } catch (IOException ex) {
                            throw new UnsafeTaskDirectoryException(ex);
                        }
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            List<JudgeTask> tasks = new ArrayList<>();
            for (Path metadata : metadataFiles) {
                tasks.add(objectMapper.readValue(metadata.toFile(), JudgeTask.class));
            }
            return tasks;
        } catch (UnsafeTaskDirectoryException ex) {
            throw ex.asIOException();
        }
    }

    private List<Path> safeDeletePaths(Path directory) throws IOException {
        ensureInsideStorageBase(directory);
        if (Files.isSymbolicLink(directory)) {
            throw new SecurityException("Refusing to delete symbolic link task directory");
        }
        Path realStorageBase = storageBase.toRealPath();
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(realStorageBase)) {
            throw new SecurityException("Refusing to delete task directory outside storage base");
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(directory)) {
            paths = stream.toList();
        }
        for (Path path : paths) {
            validateSafeDeleteEntry(path, realStorageBase, realDirectory);
        }
        return paths.stream()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    private void validateSafeDeleteEntry(Path path, Path realStorageBase, Path realDirectory) throws IOException {
        ensureInsideStorageBase(path);
        if (Files.isSymbolicLink(path)) {
            Path target = path.getParent()
                    .resolve(Files.readSymbolicLink(path))
                    .toAbsolutePath()
                    .normalize();
            if (!target.startsWith(storageBase) || !target.startsWith(realDirectory)) {
                throw new SecurityException("Refusing to delete symlink outside task directory");
            }
            return;
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realStorageBase) || !realPath.startsWith(realDirectory)) {
            throw new SecurityException("Refusing to delete reparse point outside task directory");
        }
    }

    private void validateTaskDirectory(Path directory) throws IOException {
        ensureInsideStorageBase(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe judge task directory");
        }
        Path realStorageBase = storageBase.toRealPath();
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(realStorageBase)) {
            throw new IOException("Unsafe judge task directory");
        }
    }

    private void validateRegularFile(Path file) throws IOException {
        ensureInsideStorageBase(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe judge task file");
        }
        Path realStorageBase = storageBase.toRealPath();
        Path realFile = file.toRealPath();
        if (!realFile.startsWith(realStorageBase)) {
            throw new IOException("Unsafe judge task file");
        }
    }

    private List<TestCaseResult> readResults(JsonNode resultsNode) {
        if (resultsNode == null || !resultsNode.isArray()) {
            return null;
        }
        List<TestCaseResult> results = new ArrayList<>();
        for (JsonNode resultNode : resultsNode) {
            results.add(new TestCaseResult(
                    resultNode.path("caseNumber").asInt(),
                    resultNode.path("status").asText(),
                    resultNode.path("timeUsed").asLong(),
                    resultNode.path("memoryUsed").asLong()
            ));
        }
        return results;
    }

    private JudgeTask copyForWrite(JudgeTask task) {
        JudgeTask copy = new JudgeTask();
        copy.setJudgeId(task.getJudgeId());
        copy.setStatus(task.getStatus());
        copy.setRequestedCases(task.getRequestedCases());
        copy.setMode(task.getMode());
        copy.setPolicy(task.getPolicy());
        copy.setSandboxRunHandle(task.getSandboxRunHandle());
        copy.setOwnership(task.getOwnership());
        copy.setWorkDir(Path.of(task.getWorkDir()).toAbsolutePath().normalize().toString());
        copy.setCreatedAt(task.getCreatedAt());
        copy.setStartedAt(task.getStartedAt());
        copy.setFinishedAt(task.getFinishedAt());
        copy.setMessage(task.getMessage());
        return copy;
    }

    private void appendEvent(Path workDir, Map<String, Object> event) throws IOException {
        String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
        Files.writeString(eventsFile(workDir), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private Map<String, Object> event(String judgeId, JudgeStatus status, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("at", Instant.now());
        event.put("judgeId", judgeId);
        event.put("status", status);
        if (message != null) {
            event.put("message", message);
        }
        return event;
    }

    private boolean startsExecution(JudgeStatus status) {
        return status == JudgeStatus.PENDING
                || status == JudgeStatus.COMPILING
                || status == JudgeStatus.RUNNING;
    }

    private void validateTransition(JudgeStatus current, JudgeStatus next) {
        if (current == null || next == null || current == next) {
            return;
        }
        if (current.isTerminal()) {
            throw new IllegalStateException("Cannot transition judge task from " + current + " to " + next);
        }
    }

    private Path metadataFile(Path workDir) {
        return workDir.resolve("metadata.json");
    }

    private Path summaryFile(Path workDir) {
        return workDir.resolve("summary.json");
    }

    private Path eventsFile(Path workDir) {
        return workDir.resolve("events.jsonl");
    }

    private Object lock(String judgeId) {
        return locks.computeIfAbsent(judgeId, ignored -> new Object());
    }

    private void validateJudgeId(String judgeId) {
        if (judgeId == null || !SAFE_JUDGE_ID.matcher(judgeId).matches()) {
            throw new IllegalArgumentException("Invalid judgeId");
        }
    }

    private void ensureInsideStorageBase(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(storageBase)) {
            throw new IllegalArgumentException("Path is outside storage base");
        }
    }

    private static final class UnsafeTaskDirectoryException extends RuntimeException {

        private UnsafeTaskDirectoryException(IOException cause) {
            super(cause);
        }

        private IOException asIOException() {
            return (IOException) getCause();
        }
    }
}
