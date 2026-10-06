package com.example.demo.service.sandbox;

import com.example.demo.dto.SandboxExecutionManifest;
import com.example.demo.service.TaskLifecycleGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class LinuxAttemptDiscovery {
    public static final long JOURNAL_READ_RESERVATION_BYTES = 2L * (64 * 1024 + 1);
    private static final Set<String> ATTEMPT_FILES = Set.of("runtime-state.json", "runtime-state.lock",
            "supervisor-config.json", "runner-summary.json", SandboxExecutionManifest.SPEC_FILE, SandboxExecutionManifest.EVENT_FILE);
    private final LinuxSupervisorLauncher launcher;
    private final LinuxSupervisorOptions options;
    private final ObjectMapper mapper;

    LinuxAttemptDiscovery(LinuxSupervisorLauncher launcher, LinuxSupervisorOptions options, ObjectMapper mapper) {
        this.launcher = Objects.requireNonNull(launcher);
        this.options = Objects.requireNonNull(options);
        this.mapper = Objects.requireNonNull(mapper);
    }

    Report inspect(Path storageRoot, Budget budget) throws IOException {
        Objects.requireNonNull(storageRoot, "storageRoot");
        Objects.requireNonNull(budget, "budget");
        if (!storageRoot.isAbsolute() || !storageRoot.equals(storageRoot.normalize())) {
            throw new IllegalArgumentException("discovery requires an explicit canonical absolute storage root");
        }
        Scan scan = new Scan(storageRoot, budget);
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.tryAcquireExisting(storageRoot)) {
            launcher.requireTrustedDirectory(storageRoot);
            scan.entries(storageRoot, entry -> {
                String name = entry.getFileName().toString();
                if (name.equals(".task-lifecycle.lock")) {
                    return;
                }
                if (!scan.boundedPath(entry)) {
                    return;
                }
                if (!name.startsWith("judge-") || name.length() > 262
                        || !name.substring(6).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                    scan.issue(entry, Reason.INVALID_ENTRY);
                    return;
                }
                try {
                    lease.requireTaskDirectory(entry);
                    launcher.requireTrustedDirectory(entry);
                    inspectTask(scan, entry, name.substring(6));
                    lease.requireTaskDirectory(entry);
                } catch (IOException | SecurityException failure) {
                    scan.issue(entry, Reason.TASK_UNREADABLE_OR_LOCKED);
                }
            });
        } catch (IOException | SecurityException | UnsupportedOperationException failure) {
            scan.issue(storageRoot, Reason.ROOT_UNREADABLE_OR_LOCKED);
        }
        return scan.report();
    }

    private void inspectTask(Scan scan, Path task, String judgeId) throws IOException {
        Path control = task.resolve("control");
        try {
            Files.readAttributes(control, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return;
        }
        try (LinuxSupervisorLauncher.TaskLock ignored = launcher.acquireExistingTaskLock(task)) {
            boolean[] attemptsSeen = {false};
            scan.entries(control, entry -> {
                if (!scan.boundedPath(entry)) {
                    return;
                }
                String name = entry.getFileName().toString();
                if (name.equals("linux-supervisor.lock")) {
                    return;
                }
                if (!name.equals("attempts")) {
                    scan.issue(entry, Reason.INVALID_ENTRY);
                    return;
                }
                attemptsSeen[0] = true;
                launcher.requireTrustedDirectory(entry);
                boolean[] anyAttempt = {false};
                scan.entries(entry, attempt -> {
                    anyAttempt[0] = true;
                    inspectAttempt(scan, task, judgeId, attempt);
                });
                if (!anyAttempt[0] && !scan.halted) {
                    scan.issue(entry, Reason.JOURNAL_MISSING);
                }
            });
            if (!attemptsSeen[0] && !scan.halted) {
                scan.issue(control, Reason.ATTEMPTS_MISSING);
            }
        }
    }

    private void inspectAttempt(Scan scan, Path task, String judgeId, Path attempt) throws IOException {
        if (!scan.boundedPath(attempt)) {
            return;
        }
        String attemptId = attempt.getFileName().toString();
        try {
            if (!UUID.fromString(attemptId).toString().equals(attemptId)) {
                scan.issue(attempt, Reason.INVALID_ENTRY);
                return;
            }
        } catch (IllegalArgumentException invalid) {
            scan.issue(attempt, Reason.INVALID_ENTRY);
            return;
        }
        try {
            launcher.requireTrustedDirectory(attempt);
            boolean[] runtimeStateSeen = {false};
            boolean[] stageEvidenceSeen = {false};
            boolean[] unsafe = {false};
            scan.entries(attempt, entry -> {
                if (!scan.boundedPath(entry)) {
                    unsafe[0] = true;
                    return;
                }
                String name = entry.getFileName().toString();
                if (name.equals("stage-journal")) {
                    stageEvidenceSeen[0] = true;
                    launcher.requireTrustedDirectory(entry);
                } else if (LinuxRuntimeJournal.CANCELLATION_NAME.equals(name)) {
                    requireControlFile(entry);
                } else if (ATTEMPT_FILES.contains(name)) {
                    requireControlFile(entry);
                    if (name.equals("runtime-state.json")) {
                        runtimeStateSeen[0] = true;
                    }
                } else {
                    unsafe[0] = true;
                    scan.issue(entry, Reason.INVALID_ENTRY);
                }
            });
            if (scan.halted || unsafe[0]) {
                return;
            }
            if (!runtimeStateSeen[0]) {
                scan.issue(attempt, Reason.JOURNAL_MISSING);
                return;
            }
            if (!scan.reserveJournalRead(attempt)) {
                return;
            }
            try (LinuxRuntimeJournal journal = LinuxRuntimeJournal.openExisting(attempt, judgeId, attemptId,
                    mapper, options.configWriterUid(), options.configWriterGid())) {
                ObjectNode snapshot = journal.read();
                journal.verifyCancellationRequestIfPresent();
                LinuxRuntimeState.SnapshotPhase phase = LinuxRuntimeState.classifyUnresolvedSnapshot(snapshot);
                JsonNode expected = mapper.readTree(mapper.writeValueAsBytes(launcher.configuration(task)));
                if (!snapshot.path("configuration").equals(expected)) {
                    throw new IOException("retained configuration differs from current explicit configuration");
                }
                scan.add(attempt, Reason.RECOVERY_REQUIRED, phase.name(), stageEvidenceSeen[0]);
            }
        } catch (IOException | SecurityException failure) {
            scan.issue(attempt, Reason.JOURNAL_UNREADABLE_OR_INVALID);
        }
    }

    private void requireControlFile(Path path) throws IOException {
        Map<String, Object> attributes = Files.readAttributes(path,
                "unix:isRegularFile,uid,gid,mode,nlink", LinkOption.NOFOLLOW_LINKS);
        if (!Boolean.TRUE.equals(attributes.get("isRegularFile"))
                || ((Number) attributes.get("uid")).intValue() != options.configWriterUid()
                || ((Number) attributes.get("gid")).intValue() != options.configWriterGid()
                || (((Number) attributes.get("mode")).intValue() & 0022) != 0
                || ((Number) attributes.get("nlink")).longValue() != 1) {
            throw new IOException("unsafe control evidence metadata");
        }
    }

    public record Budget(long maxDirectoryEntries, int maxReportEntries, long maxJournalReadBytes, int maxPathUtf8Bytes) {
        public Budget {
            if (maxDirectoryEntries < 1 || maxReportEntries < 1 || maxJournalReadBytes < 0 || maxPathUtf8Bytes < 64) {
                throw new IllegalArgumentException("discovery requires positive entry budgets, nonnegative read bytes and at least 64 path bytes");
            }
            Math.addExact(512L, Math.multiplyExact((long) maxReportEntries, 6L * maxPathUtf8Bytes + 256L));
        }

        public long maximumReportJsonBytes() {
            return 512L + maxReportEntries * (6L * maxPathUtf8Bytes + 256L);
        }
    }

    public enum Reason {
        SCAN_FINISHED, SCAN_INCOMPLETE, RECOVERY_REQUIRED, ROOT_UNREADABLE_OR_LOCKED, TASK_UNREADABLE_OR_LOCKED,
        INVALID_ENTRY, ATTEMPTS_MISSING, JOURNAL_MISSING, JOURNAL_UNREADABLE_OR_INVALID, PATH_BUDGET_EXHAUSTED,
        DIRECTORY_BUDGET_EXHAUSTED, REPORT_BUDGET_EXHAUSTED, JOURNAL_BUDGET_EXHAUSTED
    }

    public record Finding(String relativePath, Reason reason, String phase, boolean stageEvidenceRequiresFreeze) { }

    public record Report(boolean scanComplete, Reason stopReason, long directoryEntriesVisited,
                         long journalReadBytesReserved, List<Finding> findings) {
        public Report {
            findings = List.copyOf(findings);
        }
    }

    @FunctionalInterface
    private interface EntryVisitor {
        void visit(Path path) throws IOException;
    }

    private static final class Scan {
        private final Path root;
        private final Budget budget;
        private final List<Finding> findings = new ArrayList<>();
        private long entriesVisited;
        private long readBytesReserved;
        private boolean complete = true;
        private boolean halted;
        private Reason stopReason;

        private Scan(Path root, Budget budget) {
            this.root = root;
            this.budget = budget;
        }

        private void entries(Path directory, EntryVisitor visitor) throws IOException {
            try (var stream = Files.newDirectoryStream(directory)) {
                var iterator = stream.iterator();
                while (!halted) {
                    if (entriesVisited >= budget.maxDirectoryEntries()) {
                        stop(Reason.DIRECTORY_BUDGET_EXHAUSTED);
                        break;
                    }
                    if (!iterator.hasNext()) {
                        break;
                    }
                    entriesVisited++;
                    visitor.visit(iterator.next());
                }
            } catch (DirectoryIteratorException failure) {
                throw failure.getCause();
            }
        }

        private boolean boundedPath(Path path) {
            if (relative(path).getBytes(StandardCharsets.UTF_8).length <= budget.maxPathUtf8Bytes()) {
                return true;
            }
            issue(path, Reason.PATH_BUDGET_EXHAUSTED);
            return false;
        }

        private String relative(Path path) {
            return path.equals(root) ? "." : root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
        }

        private void issue(Path path, Reason reason) {
            complete = false;
            add(path, reason, "", false);
        }

        private void add(Path path, Reason reason, String phase, boolean stageEvidence) {
            if (findings.size() >= budget.maxReportEntries()) {
                stop(Reason.REPORT_BUDGET_EXHAUSTED);
                return;
            }
            String relative = relative(path);
            if (relative.getBytes(StandardCharsets.UTF_8).length > budget.maxPathUtf8Bytes()) {
                relative = "[PATH_EXCEEDS_BUDGET]";
                reason = Reason.PATH_BUDGET_EXHAUSTED;
                complete = false;
            }
            findings.add(new Finding(relative, reason, phase, stageEvidence));
        }

        private boolean reserveJournalRead(Path path) {
            if (JOURNAL_READ_RESERVATION_BYTES > budget.maxJournalReadBytes() - readBytesReserved) {
                issue(path, Reason.JOURNAL_BUDGET_EXHAUSTED);
                stop(Reason.JOURNAL_BUDGET_EXHAUSTED);
                return false;
            }
            readBytesReserved += JOURNAL_READ_RESERVATION_BYTES;
            return true;
        }

        private void stop(Reason reason) {
            complete = false;
            halted = true;
            if (stopReason == null) {
                stopReason = reason;
            }
        }

        private Report report() {
            Reason result = stopReason != null ? stopReason : complete ? Reason.SCAN_FINISHED : Reason.SCAN_INCOMPLETE;
            return new Report(complete, result, entriesVisited, readBytesReserved, findings);
        }
    }
}
