package com.example.demo.service;

import com.example.demo.dto.JudgeProgress;
import com.example.demo.dto.JudgeSummary;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.TestCaseResult;
import com.example.demo.model.DailyQuotaCharge;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.security.SessionAuditId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class FileTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(FileTaskStore.class);
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
        if (!Files.exists(storageBase, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(storageBase, false)) {
            synchronized (lock(judgeId)) {
                return deleteTaskDirectory(judgeId, admission, true);
            }
        }
    }

    private boolean deleteTaskDirectory(String judgeId, TaskLifecycleGuard.Lease admission, boolean retainDailyCharge) throws IOException {
        Path directory = taskDirectory(judgeId);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        admission.requireTaskDirectory(directory);
        List<Path> paths = safeDeletePaths(directory);
        ensureNoManagedAttemptEvidence(directory);
        if (retainDailyCharge) {
            retainDeletedQuotaCharge(judgeId);
        }
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

    @Override
    public List<DailyQuotaCharge> findDeletedQuotaCharges(LocalDate date, ZoneId zone) throws IOException {
        if (!Files.exists(storageBase, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(storageBase, false);
             DirectoryStream<Path> entries = Files.newDirectoryStream(storageBase, "quota-*.json")) {
            List<DailyQuotaCharge> charges = new ArrayList<>();
            for (Path path : entries) {
                validateRegularFile(path);
                DailyQuotaCharge charge = objectMapper.readValue(path.toFile(), DailyQuotaCharge.class);
                validateQuotaCharge(path, charge);
                LocalDate chargeDate = LocalDate.ofInstant(charge.createdAt(), zone);
                if (chargeDate.isBefore(date)) {
                    Files.delete(path);
                } else if (chargeDate.equals(date)) {
                    charges.add(charge);
                }
            }
            return charges;
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
    }

    private void retainDeletedQuotaCharge(String judgeId) throws IOException {
        Optional<JudgeTask> storedTask = find(judgeId);
        if (storedTask.isEmpty()) {
            return;
        }
        JudgeTask task = storedTask.get();
        requireStoredTaskDirectory(judgeId, task);
        DailyQuotaCharge charge = DailyQuotaCharge.fromTask(task);
        ZoneId zone = ZoneId.systemDefault();
        if (charge.ownerFingerprint() == null || charge.createdAt() == null
                || !LocalDate.ofInstant(charge.createdAt(), zone).equals(LocalDate.now(zone))) {
            return;
        }
        Path path = quotaChargeFile(charge.chargeId());
        validateQuotaCharge(path, charge);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            validateRegularFile(path);
            DailyQuotaCharge existing = objectMapper.readValue(path.toFile(), DailyQuotaCharge.class);
            if (!charge.equals(existing)) {
                throw new IOException("Conflicting daily quota charge");
            }
            return;
        }
        writeJsonAtomically(path, charge);
    }

    private Path quotaChargeFile(String chargeId) {
        String fingerprint = SessionAuditId.from(chargeId);
        return storageBase.resolve("quota-" + fingerprint.substring("session-sha256:".length()) + ".json");
    }

    private void validateQuotaCharge(Path path, DailyQuotaCharge charge) throws IOException {
        if (charge == null || charge.chargeId() == null || charge.chargeId().isBlank()
                || charge.ownerFingerprint() == null || !charge.ownerFingerprint().matches("session-sha256:[0-9a-f]{64}")
                || charge.createdAt() == null || charge.requestedCases() < 0 || charge.runtimeCases() < 0
                || (charge.caseTimeLimitMillis() != null && charge.caseTimeLimitMillis() < 0)
                || !path.equals(quotaChargeFile(charge.chargeId()))) {
            throw new IOException("Invalid daily quota charge");
        }
    }

    @Override
    public JudgeTask create(JudgeTask task) throws IOException {
        return create(task, () -> {});
    }

    @Override
    public JudgeTask create(JudgeTask task, Runnable registerPendingTask) throws IOException {
        Objects.requireNonNull(registerPendingTask, "Pending task registration is required");
        String judgeId = task.getJudgeId();
        validateJudgeId(judgeId);
        Path workDir = Path.of(task.getWorkDir()).toAbsolutePath().normalize();
        ensureInsideStorageBase(workDir);
        if (!workDir.equals(taskDirectory(judgeId))) {
            throw new IllegalArgumentException("Task workDir must match its canonical task directory");
        }
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(storageBase, true)) {
            synchronized (lock(judgeId)) {
                Files.createDirectory(workDir);
                try {
                    admission.requireTaskDirectory(workDir);
                    validateTaskDirectory(workDir);
                    JudgeTask normalized = copyForWrite(task);
                    if (normalized.getCreatedAt() == null) {
                        normalized.setCreatedAt(Instant.now());
                    }
                    normalized.setQuotaChargeId(UUID.randomUUID().toString());
                    if (normalized.getStatus() == null) {
                        normalized.setStatus(JudgeStatus.CREATED);
                    }
                    writeJsonAtomically(metadataFile(workDir), normalized);
                    appendEvent(workDir, event(normalized.getJudgeId(), normalized.getStatus(), normalized.getMessage()));
                    registerPendingTask.run();
                    return normalized;
                } catch (IOException | RuntimeException failure) {
                    try {
                        deleteTaskDirectory(judgeId, admission, false);
                    } catch (IOException | RuntimeException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                    throw failure;
                }
            }
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
            JudgeTask task = objectMapper.readValue(metadata.toFile(), JudgeTask.class);
            requireStoredTaskDirectory(judgeId, task);
            applyStoredTerminal(workDir, task);
            return Optional.of(task);
        }
    }

    @Override
    public JudgeTask updateStatus(String judgeId, JudgeStatus status, String message) throws IOException {
        validateJudgeId(judgeId);
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(storageBase, false)) {
            synchronized (lock(judgeId)) {
                JudgeTask task = find(judgeId)
                        .orElseThrow(() -> new IllegalArgumentException("Judge task not found: " + judgeId));
                JudgeStatus current = task.getStatus();
                validateTransition(current, status);
                Path workDir = requireStoredTaskDirectory(judgeId, task);
                admission.requireTaskDirectory(workDir);
                if (current != null && current.isTerminal()) {
                    return task;
                }

                if (status != null && status.isTerminal()) {
                    JudgeProgress existing = findSummary(judgeId).orElse(new JudgeProgress(status.name(), message, 0));
                    if (existing.getSummary() != null) {
                        existing.getSummary().setStoppedReason(message);
                    }
                    JudgeProgress snapshot = finalizeTaskLocked(judgeId, task,
                            new JudgeProgress(status.name(), message, existing.getProgress(),
                                    existing.getResults(), existing.getSummary()), admission);
                    applyFinal(task, snapshot);
                    return task;
                }

                JudgeStatus previousStatus = task.getStatus();
                String previousMessage = task.getMessage();
                Instant previousStartedAt = task.getStartedAt();
                task.setStatus(status);
                task.setMessage(message);
                Instant now = Instant.now();
                if (task.getStartedAt() == null && startsExecution(status)) {
                    task.setStartedAt(now);
                }
                writeJsonAtomically(metadataFile(workDir), task);
                try {
                    appendEvent(workDir, event(judgeId, status, message));
                } catch (IOException failure) {
                    task.setStatus(previousStatus);
                    task.setMessage(previousMessage);
                    task.setStartedAt(previousStartedAt);
                    try {
                        writeJsonAtomically(metadataFile(workDir), task);
                    } catch (IOException | RuntimeException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                    throw failure;
                }
                return task;
            }
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
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(storageBase, false)) {
            synchronized (lock(judgeId)) {
                JudgeTask task = find(judgeId)
                        .orElseThrow(() -> new IllegalArgumentException("Judge task not found: " + judgeId));
                if (task.getStatus() != null && task.getStatus().isTerminal()
                        && !Objects.equals(task.getSandboxRunHandle(), handle)) {
                    throw new IllegalStateException("Cannot replace a terminal task run handle");
                }
                task.setSandboxRunHandle(handle);

                Path workDir = requireStoredTaskDirectory(judgeId, task);
                admission.requireTaskDirectory(workDir);
                writeJsonAtomically(metadataFile(workDir), task);
                return task;
            }
        }
    }

    @Override
    public void saveSummary(String judgeId, JudgeProgress summary) throws IOException {
        validateJudgeId(judgeId);
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(storageBase, false)) {
            synchronized (lock(judgeId)) {
                JudgeTask task = find(judgeId)
                        .orElseThrow(() -> new IllegalArgumentException("Judge task not found: " + judgeId));
                Path workDir = requireStoredTaskDirectory(judgeId, task);
                admission.requireTaskDirectory(workDir);
                if (JudgeStatus.fromProgressStatus(summary.getStatus()).map(JudgeStatus::isTerminal).orElse(false)) {
                    finalizeTaskLocked(judgeId, task, summary, admission);
                    return;
                }
                Optional<JudgeProgress> existing = findSummary(judgeId);
                if ((task.getStatus() != null && task.getStatus().isTerminal())
                        || existing.filter(this::isTerminal).isPresent()) {
                    return;
                }
                JudgeProgress frozen = readProgress(objectMapper.valueToTree(summary));
                JudgeSummary previous = existing.map(JudgeProgress::getSummary).orElse(null);
                if (previous != null && (frozen.getSummary() == null
                        || frozen.getSummary().getCompletedCases() < previous.getCompletedCases())) {
                    return;
                }
                frozen.setAttemptId(task.getSandboxRunHandle() == null ? null : task.getSandboxRunHandle().attemptId());
                frozen.setRevision(nextRevision(existing));
                frozen.setFinalizedAt(null);
                writeJsonAtomically(summaryFile(workDir), frozen);
            }
        }
    }

    @Override
    public JudgeProgress finalizeTask(String judgeId, JudgeProgress snapshot) throws IOException {
        validateJudgeId(judgeId);
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(storageBase, false)) {
            synchronized (lock(judgeId)) {
                JudgeTask task = find(judgeId)
                        .orElseThrow(() -> new IllegalArgumentException("Judge task not found: " + judgeId));
                return finalizeTaskLocked(judgeId, task, snapshot, admission);
            }
        }
    }

    private JudgeProgress finalizeTaskLocked(String judgeId, JudgeTask task, JudgeProgress snapshot,
                                             TaskLifecycleGuard.Lease admission) throws IOException {
        Path workDir = requireStoredTaskDirectory(judgeId, task);
        admission.requireTaskDirectory(workDir);
        Optional<JudgeProgress> committed = readFinal(workDir, task);
        if (committed.isPresent()) {
            forceFinalDirectory(workDir);
            repairFinalProjections(task, committed.get(), admission);
            return committed.get();
        }
        Optional<JudgeProgress> existing = readProgress(summaryFile(workDir));
        if (existing.filter(this::isTerminal).isPresent()) {
            validateTerminalSnapshot(existing.get());
            return existing.get();
        }
        if (snapshot == null) {
            throw new IOException("Final judge snapshot is required");
        }
        JudgeProgress frozen = readProgress(objectMapper.valueToTree(snapshot));
        JudgeSummary persisted = existing.map(JudgeProgress::getSummary).orElse(null);
        if (frozen.getSummary() == null && persisted != null) {
            frozen.setSummary(persisted);
            frozen.getSummary().setStoppedReason(frozen.getMessage());
        }
        if (persisted != null && frozen.getSummary() != null
                && frozen.getSummary().getCompletedCases() < persisted.getCompletedCases()) {
            throw new IOException("Final snapshot cannot discard completed cases");
        }
        if (task.getStatus() != null && task.getStatus().isTerminal()) {
            frozen.setStatus(task.getStatus().name());
            frozen.setMessage(task.getMessage());
        }
        frozen.setAttemptId(task.getSandboxRunHandle() == null ? null : task.getSandboxRunHandle().attemptId());
        frozen.setRevision(nextRevision(existing));
        frozen.setFinalizedAt(Instant.now());
        validateTerminalSnapshot(frozen);
        writeJsonAtomically(finalFile(workDir), frozen);
        admission.requireTaskDirectory(workDir);
        repairFinalProjections(task, frozen, admission);
        return frozen;
    }

    private long nextRevision(Optional<JudgeProgress> existing) throws IOException {
        long previous = existing.map(JudgeProgress::getRevision).orElse(0L);
        if (previous < 0) {
            throw new IOException("Invalid judge progress revision");
        }
        try {
            return Math.addExact(previous, 1L);
        } catch (ArithmeticException exhausted) {
            throw new IOException("Judge progress revision is exhausted", exhausted);
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
            if (Files.exists(finalFile(workDir), LinkOption.NOFOLLOW_LINKS)) {
                JudgeTask task = find(judgeId)
                        .orElseThrow(() -> new IOException("Final snapshot has no trusted task metadata"));
                return readFinal(workDir, task);
            }
            return readProgress(summaryFile(workDir));
        }
    }

    private Optional<JudgeProgress> readFinal(Path workDir, JudgeTask task) throws IOException {
        Optional<JudgeProgress> snapshot = readProgress(finalFile(workDir));
        if (snapshot.isPresent()) {
            JudgeProgress progress = snapshot.get();
            validateTerminalSnapshot(progress);
            String attemptId = task.getSandboxRunHandle() == null ? null : task.getSandboxRunHandle().attemptId();
            if (progress.getRevision() == null || progress.getRevision() < 1 || progress.getFinalizedAt() == null
                    || !Objects.equals(attemptId, progress.getAttemptId())) {
                throw new IOException("Invalid final snapshot identity or commit metadata");
            }
        }
        return snapshot;
    }

    private void applyStoredTerminal(Path workDir, JudgeTask task) throws IOException {
        Optional<JudgeProgress> snapshot = readFinal(workDir, task);
        if (snapshot.isEmpty() && Files.isRegularFile(summaryFile(workDir), LinkOption.NOFOLLOW_LINKS)) {
            snapshot = readProgress(summaryFile(workDir)).filter(this::isTerminal);
            if (snapshot.isPresent()) {
                validateTerminalSnapshot(snapshot.get());
            }
        }
        snapshot.ifPresent(progress -> applyFinal(task, progress));
    }

    private Optional<JudgeProgress> readProgress(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        validateRegularFile(path);
        return Optional.of(readProgress(objectMapper.readTree(path.toFile())));
    }

    private JudgeProgress readProgress(JsonNode json) throws IOException {
        if (json == null || !json.isObject()) {
            throw new IOException("Invalid judge progress snapshot");
        }
        JudgeSummary summary = json.path("summary").isObject()
                ? objectMapper.treeToValue(json.path("summary"), JudgeSummary.class) : null;
        return new JudgeProgress(json.path("status").asText(), json.path("message").asText(null),
                json.path("progress").asInt(), readResults(json.path("results")), summary,
                json.path("attemptId").asText(null),
                json.path("revision").isNull() || json.path("revision").isMissingNode()
                        ? null : objectMapper.treeToValue(json.path("revision"), Long.class),
                json.path("finalizedAt").isNull() || json.path("finalizedAt").isMissingNode()
                        ? null : objectMapper.treeToValue(json.path("finalizedAt"), Instant.class));
    }

    private boolean isTerminal(JudgeProgress snapshot) {
        return JudgeStatus.fromProgressStatus(snapshot.getStatus()).map(JudgeStatus::isTerminal).orElse(false);
    }

    private void validateTerminalSnapshot(JudgeProgress snapshot) throws IOException {
        if (!isTerminal(snapshot) || snapshot.getProgress() < 0 || snapshot.getProgress() > 100) {
            throw new IOException("Invalid final judge status or progress");
        }
        JudgeSummary summary = snapshot.getSummary();
        if (summary == null) {
            return;
        }
        long classified = (long) summary.getAc() + summary.getWa() + summary.getTle() + summary.getMle()
                + summary.getRe() + summary.getSystemError() + summary.getOutputLimitExceeded();
        if (summary.getTotalCases() < 0 || summary.getCompletedCases() < 0
                || summary.getCompletedCases() > summary.getTotalCases() || summary.getAc() < 0
                || summary.getWa() < 0 || summary.getTle() < 0 || summary.getMle() < 0 || summary.getRe() < 0
                || summary.getSystemError() < 0 || summary.getOutputLimitExceeded() < 0
                || classified != summary.getCompletedCases()
                || (summary.getFirstFailedCase() != null && (summary.getFirstFailedCase() < 1
                    || summary.getFirstFailedCase() > summary.getTotalCases()))) {
            throw new IOException("Invalid final judge aggregate");
        }
    }

    private void applyFinal(JudgeTask task, JudgeProgress snapshot) {
        task.setStatus(JudgeStatus.fromProgressStatus(snapshot.getStatus()).orElseThrow());
        task.setMessage(snapshot.getMessage());
        if (snapshot.getFinalizedAt() != null) {
            task.setFinishedAt(snapshot.getFinalizedAt());
        }
    }

    private void repairFinalProjections(JudgeTask task, JudgeProgress snapshot,
                                        TaskLifecycleGuard.Lease admission) throws IOException {
        Path workDir = requireStoredTaskDirectory(task.getJudgeId(), task);
        admission.requireTaskDirectory(workDir);
        applyFinal(task, snapshot);
        try {
            if (!objectMapper.readValue(metadataFile(workDir).toFile(), JudgeTask.class).equals(task)) {
                writeJsonAtomically(metadataFile(workDir), task);
                appendEvent(workDir, event(task.getJudgeId(), task.getStatus(), task.getMessage()));
            }
            Path summaryPath = summaryFile(workDir);
            boolean summaryMatches = false;
            if (Files.exists(summaryPath, LinkOption.NOFOLLOW_LINKS)) {
                validateRegularFile(summaryPath);
                try {
                    summaryMatches = objectMapper.valueToTree(snapshot).equals(objectMapper.readTree(summaryPath.toFile()));
                } catch (JsonProcessingException invalidProjection) {
                    log.warn("Repairing malformed summary projection for judgeId={}", task.getJudgeId());
                }
            }
            if (!summaryMatches) {
                writeJsonAtomically(summaryPath, snapshot);
            }
        } catch (IOException failure) {
            log.warn("Final snapshot committed; projections require repair for judgeId={}: {}",
                    task.getJudgeId(), failure.getMessage());
        }
        admission.requireTaskDirectory(workDir);
    }

    @Override
    public List<JudgeTask> markStaleRunningTasksOnStartup() throws IOException {
        List<JudgeTask> existingTasks;
        try (TaskLifecycleGuard.Lease admission = TaskLifecycleGuard.acquire(storageBase, true)) {
            existingTasks = loadAllTasks();
            for (JudgeTask task : existingTasks) {
                if (task.getStatus() == null) {
                    throw new IOException("Task status is missing for judgeId: " + task.getJudgeId());
                }
                if (task.getSandboxRunHandle() == null) {
                    Path directory = requireStoredTaskDirectory(task.getJudgeId(), task);
                    try {
                        ensureNoManagedAttemptEvidence(directory);
                    } catch (IOException ex) {
                        throw new IOException("Startup recovery requires trusted reconciliation for judgeId: "
                                + task.getJudgeId(), ex);
                    }
                    if (!task.getStatus().isTerminal()
                            || (task.getStartedAt() != null && !hasValidatedTrustedLocalFinal(directory, task))) {
                        throw new IOException("Execution stop is unconfirmed for judgeId: " + task.getJudgeId());
                    }
                }
            }
            for (JudgeTask task : existingTasks) {
                Optional<JudgeProgress> snapshot = readFinal(taskDirectory(task.getJudgeId()), task);
                if (snapshot.isPresent()) {
                    forceFinalDirectory(taskDirectory(task.getJudgeId()));
                    repairFinalProjections(task, snapshot.get(), admission);
                }
            }
        }
        List<JudgeTask> staleTasks = new ArrayList<>();
        for (JudgeTask task : existingTasks) {
            if (!task.getStatus().isTerminal()) {
                staleTasks.add(updateStatus(task.getJudgeId(), JudgeStatus.STALE,
                        "RESTART_INTERRUPTED: Task marked stale after service restart"));
            }
        }
        return staleTasks;
    }

    private boolean hasValidatedTrustedLocalFinal(Path directory, JudgeTask task) throws IOException {
        return "trusted-local".equals(task.getMode())
                && task.getPolicy() != null
                && "trusted-local".equals(task.getPolicy().profile())
                && readFinal(directory, task).isPresent();
    }

    protected void moveAtomically(Path tempFile, Path targetFile) throws IOException {
        try {
            Files.move(tempFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            if (targetFile.getFileName().toString().equals("final.json")) {
                throw ex;
            }
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeJsonAtomically(Path target, Object value) throws IOException {
        validateTaskDirectory(target.getParent());
        Path tempFile = target.getParent().resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        boolean moved = false;
        try {
            boolean finalSnapshot = target.getFileName().toString().equals("final.json");
            if (finalSnapshot) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    validateRegularFile(target);
                }
                try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer bytes = ByteBuffer.wrap(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
                    while (bytes.hasRemaining()) {
                        channel.write(bytes);
                    }
                    channel.force(true);
                }
                validateTaskDirectory(target.getParent());
                validateRegularFile(tempFile);
            } else {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), value);
            }
            moveAtomically(tempFile, target);
            moved = true;
            if (finalSnapshot) {
                forceFinalDirectory(target.getParent());
            }
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    protected void forceFinalDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    private Path requireStoredTaskDirectory(String judgeId, JudgeTask task) throws IOException {
        Path expected = taskDirectory(judgeId);
        try {
            if (!judgeId.equals(task.getJudgeId()) || task.getWorkDir() == null
                    || !expected.equals(Path.of(task.getWorkDir()).toAbsolutePath().normalize())) {
                throw new IOException("Stored task identity or workDir does not match its directory");
            }
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IOException("Stored task workDir is invalid", invalid);
        }
        validateTaskDirectory(expected);
        return expected;
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
                            if (!Files.exists(metadataFile(path), LinkOption.NOFOLLOW_LINKS)) {
                                if (Files.exists(finalFile(path), LinkOption.NOFOLLOW_LINKS)) {
                                    throw new IOException("Orphan task directory with final snapshot requires trusted reconciliation: "
                                            + path.getFileName());
                                }
                                try {
                                    ensureNoManagedAttemptEvidence(path);
                                } catch (IOException ex) {
                                    throw new IOException("Orphan task directory requires trusted reconciliation: "
                                            + path.getFileName(), ex);
                                }
                            }
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
                JudgeTask task = objectMapper.readValue(metadata.toFile(), JudgeTask.class);
                Path directory = requireStoredTaskDirectory(task.getJudgeId(), task);
                if (!directory.equals(metadata.getParent())) {
                    throw new IOException("Stored task identity does not match scanned directory");
                }
                applyStoredTerminal(directory, task);
                tasks.add(task);
            }
            return tasks;
        } catch (UnsafeTaskDirectoryException ex) {
            throw ex.asIOException();
        }
    }

    private void ensureNoManagedAttemptEvidence(Path directory) throws IOException {
        try {
            Path control = directory.resolve("control");
            if (!managedDirectoryExists(control)) {
                return;
            }
            if (managedEntryAttributes(control.resolve("linux-supervisor.lock")) != null) {
                throw new IOException("Managed attempt startup lock requires trusted recovery");
            }
            Path attempts = control.resolve("attempts");
            if (!managedDirectoryExists(attempts)) {
                return;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(attempts)) {
                if (entries.iterator().hasNext()) {
                    throw new IOException("Managed attempt recovery evidence is present");
                }
            }
        } catch (IOException | SecurityException | DirectoryIteratorException ex) {
            throw new IOException("Refusing to delete task directory with managed attempt evidence or unsafe managed attempt path", ex);
        }
    }

    private boolean managedDirectoryExists(Path directory) throws IOException {
        BasicFileAttributes attributes = managedEntryAttributes(directory);
        if (attributes == null) {
            return false;
        }
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || !directory.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(directory.toRealPath())) {
            throw new IOException("Unsafe managed attempt directory");
        }
        return true;
    }

    private BasicFileAttributes managedEntryAttributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) {
            return null;
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

    private Path finalFile(Path workDir) {
        return workDir.resolve("final.json");
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
