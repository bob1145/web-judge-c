package com.example.demo.service.sandbox;

import com.example.demo.service.TaskLifecycleGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.Platform;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Trusted recovery-side preparation for one retained supervisor attempt: takes the
 * short root admission, acquires the task lock, releases the root admission, then
 * takes the Python-compatible stage journal directory flock. Under that flock it
 * strictly consumes every persisted Python stage record and durably closes stage
 * admission using the marker protocol shared with close_existing_stage_admission.
 * It never calls the container runtime, never resolves UNKNOWN creation outcomes,
 * and never grants deletion or recovery.
 */
public final class LinuxStageRecoveryPreparation {
    private static final int MAX_JOURNAL_ENTRIES = 4096;
    private static final String CLOSED_NAME = "stage-admission.closed";
    private static final String PENDING_NAME = "stage-admission.pending";

    private final LinuxSupervisorLauncher launcher;
    private final LinuxSupervisorOptions options;
    private final ObjectMapper mapper;

    LinuxStageRecoveryPreparation(LinuxSupervisorLauncher launcher, LinuxSupervisorOptions options,
                                  ObjectMapper mapper) {
        this.launcher = Objects.requireNonNull(launcher);
        this.options = Objects.requireNonNull(options);
        this.mapper = Objects.requireNonNull(mapper);
    }

    Preparation prepare(Path storageRoot, String judgeId, String attemptId) throws IOException {
        Objects.requireNonNull(storageRoot, "storageRoot");
        Objects.requireNonNull(judgeId, "judgeId");
        Objects.requireNonNull(attemptId, "attemptId");
        require(Platform.isLinux(), "stage recovery preparation requires a local Linux writer");
        require(storageRoot.isAbsolute() && storageRoot.equals(storageRoot.normalize()),
                "storage root must be canonical and absolute");
        require(!judgeId.isBlank() && judgeId.length() <= 256 && judgeId.matches("[A-Za-z0-9][A-Za-z0-9._-]*"),
                "judge identity must match the stored task layout");
        require(LinuxStageRecord.isCanonicalUuid(attemptId), "attempt identity must be a canonical UUID");
        Path task = storageRoot.resolve("judge-" + judgeId);
        TaskLockHolder holder = null;
        try (TaskLifecycleGuard.Lease root = TaskLifecycleGuard.tryAcquireExisting(storageRoot)) {
            root.requireTaskDirectory(task);
            launcher.requireTrustedDirectory(task);
            LinuxSupervisorLauncher.TaskLock taskLock = launcher.acquireExistingTaskLock(task);
            root.requireTaskDirectory(task);
            holder = new TaskLockHolder(taskLock);
        } catch (IOException | RuntimeException | Error failure) {
            if (holder != null) {
                try {
                    holder.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
        try (TaskLockHolder locked = holder) {
            return prepareLocked(task, judgeId, attemptId);
        }
    }

    private Preparation prepareLocked(Path task, String judgeId, String attemptId) throws IOException {
        launcher.requireTrustedDirectory(task.resolve("control"));
        Path attempts = task.resolve("control/attempts");
        launcher.requireTrustedDirectory(attempts);
        Path attemptDirectory = attempts.resolve(attemptId);
        launcher.requireTrustedDirectory(attemptDirectory);
        String expectedVolume;
        try (LinuxRuntimeJournal journal = LinuxRuntimeJournal.openExisting(attemptDirectory, judgeId, attemptId,
                mapper, options.configWriterUid(), options.configWriterGid())) {
            ObjectNode snapshot = journal.read();
            LinuxRuntimeState.classifyUnresolvedSnapshot(snapshot);
            JsonNode expectedConfiguration = mapper.readTree(mapper.writeValueAsBytes(launcher.configuration(task)));
            if (!snapshot.path("configuration").equals(expectedConfiguration)) {
                throw new IOException("retained configuration differs from current explicit configuration");
            }
            JsonNode volume = snapshot.path("volume").path("name");
            if (!volume.isTextual() || volume.textValue().isEmpty()) {
                throw new IOException("retained stage volume identity is missing");
            }
            expectedVolume = volume.textValue();
        }
        Path stageJournal = attemptDirectory.resolve("stage-journal");
        launcher.requireTrustedDirectory(stageJournal);
        List<StageFinding> stages = new ArrayList<>();
        List<String> unresolvedEntries = new ArrayList<>();
        boolean[] unknown = {false};
        boolean[] complete = {true};
        try (LinuxStageJournalGuard.Lease lease = LinuxStageJournalGuard.acquire(stageJournal,
                options.configWriterUid())) {
            lease.verifyIdentity();
            for (String name : boundedEntryNames(lease, complete)) {
                if (name.equals("runtime-client")) {
                    try {
                        lease.requireTrustedEntryDirectory(name);
                    } catch (IOException invalid) {
                        unresolvedEntries.add(name);
                    }
                    continue;
                }
                if (name.equals(CLOSED_NAME) || name.equals(PENDING_NAME)) {
                    continue;
                }
                if (!LinuxStageRecord.isJournalFileName(name)) {
                    unresolvedEntries.add(name);
                    continue;
                }
                try {
                    byte[] payload = lease.readRegularEntry(name);
                    LinuxStageRecord.Parsed parsed = LinuxStageRecord.parse(payload, payload.length, name,
                            attemptId, expectedVolume, LinuxStageRecord.strictMapper(mapper));
                    stages.add(new StageFinding(parsed.stage(), parsed.creationState(), parsed.state(),
                            parsed.containerId() != null, parsed.outcome() != null));
                    unknown[0] |= "UNKNOWN".equals(parsed.creationState());
                } catch (IOException invalid) {
                    unresolvedEntries.add(name);
                }
            }
            lease.publishClosedAdmission(attemptId);
            lease.verifyIdentity();
        }
        return new Preparation(true, complete[0], stages, unresolvedEntries, unknown[0]);
    }

    private List<String> boundedEntryNames(LinuxStageJournalGuard.Lease lease, boolean[] complete)
            throws IOException {
        lease.verifyIdentity();
        List<String> names = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(lease.journalPath())) {
            for (Path entry : stream) {
                if (names.size() >= MAX_JOURNAL_ENTRIES) {
                    complete[0] = false;
                    break;
                }
                names.add(entry.getFileName().toString());
            }
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        lease.verifyIdentity();
        return names;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    public record StageFinding(String stage, String creationState, String state,
                               boolean containerIdPresent, boolean outcomePresent) {
    }

    public record Preparation(boolean admissionClosed, boolean scanComplete, List<StageFinding> stages,
                              List<String> unresolvedEntries, boolean unresolvedUnknown) {
        public Preparation {
            stages = List.copyOf(stages);
            unresolvedEntries = List.copyOf(unresolvedEntries);
        }
    }

    private record TaskLockHolder(LinuxSupervisorLauncher.TaskLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            lock.close();
        }
    }
}
