package com.example.demo.service.sandbox;

import com.example.demo.dto.SandboxExecutionManifest;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.TaskLifecycleGuard;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class LinuxSupervisorLauncher {
    private static final List<String> ENTRYPOINT = List.of("python3", "-B", "/opt/web-judge/linux_supervisor.py");
    private final LinuxSupervisorOptions options;
    private final LinuxContainerRunner.CommandExecutor executor;
    private final ObjectMapper mapper;

    public LinuxSupervisorLauncher(LinuxSupervisorOptions options,
                                   LinuxContainerRunner.CommandExecutor executor, ObjectMapper mapper) {
        this.options = Objects.requireNonNull(options);
        this.executor = Objects.requireNonNull(executor);
        this.mapper = Objects.requireNonNull(mapper).copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public Launch start(SandboxTaskSpec spec) throws IOException, InterruptedException {
        return start(spec, spec.maxTaskRuntime());
    }

    public Launch start(SandboxTaskSpec spec, Duration timeout) throws IOException, InterruptedException {
        require(timeout != null && !timeout.isZero() && !timeout.isNegative(), "invalid startup timeout");
        long started = System.nanoTime();
        long budgetNanos = timeout.toNanos();
        require("trusted-local".equals(spec.profile()), "supervisor execution is restricted to trusted-local");
        Path workDir = validateLocalIdentityAndPaths(spec.workDir());
        try (TaskLock taskLock = acquireGuardedTaskLock(workDir, true)) {
            rejectManagedAttempts(workDir);
            return startLocked(spec, workDir, started, budgetNanos);
        }
    }

    private Launch startLocked(SandboxTaskSpec spec, Path workDir, long started, long budgetNanos)
            throws IOException, InterruptedException {
        SandboxManifestFactory.Prepared prepared = SandboxManifestFactory.prepare(spec);
        verifyImage(options.executorImageId(), false, remainingStartupBudget(started, budgetNanos));
        verifyImage(options.supervisorImageId(), true, remainingStartupBudget(started, budgetNanos));
        prepared.write(mapper);
        Path attemptDir = workDir.resolve(prepared.manifest().attemptDirectory());
        Files.setPosixFilePermissions(attemptDir, PosixFilePermissions.fromString("rwx------"));
        Path manifestPath = workDir.resolve(prepared.manifest().specPath());
        Files.setPosixFilePermissions(manifestPath, PosixFilePermissions.fromString("rw-------"));
        force(manifestPath);
        for (Path directory = attemptDir; directory != null && directory.startsWith(workDir); directory = directory.getParent()) {
            requireTrustedDirectory(directory);
            force(directory);
        }
        force(workDir.getParent());
        String attempt = prepared.manifest().attemptId();
        try (LinuxRuntimeJournal journal = LinuxRuntimeJournal.create(attemptDir, spec.judgeId(), attempt,
                mapper, options.configWriterUid(), options.configWriterGid())) {
            ObjectNode state = initialState(spec.judgeId(), attempt, workDir);
            journal.write(state);
            ObjectNode volume = object(state, "volume");
            List<String> volumeCommand = new ArrayList<>(List.of("volume", "create", "--driver", "local",
                    "--opt", "type=tmpfs", "--opt", "device=tmpfs", "--opt", "o=" + volumeOptions()));
            addLabels(volumeCommand, spec.judgeId(), attempt, "stage-volume");
            volumeCommand.add(text(volume, "name"));
            beforeCreate(journal, state, volume);
            require(command(volumeCommand, remainingStartupBudget(started, budgetNanos)).trim().equals(text(volume, "name")),
                    "volume create returned a different identity");
            JsonNode inspectedVolume = inspect("volume", text(volume, "name"), remainingStartupBudget(started, budgetNanos));
            String stageRoot = verifyVolume(inspectedVolume, state);
            volume.put("mountpoint", stageRoot);
            confirmed(journal, state, volume, text(volume, "name"));

            ObjectNode holder = object(state, "holder");
            List<String> holderArguments = List.of("/bin/sleep", Long.toString(options.holderLifetime().toSeconds()));
            List<Mount> holderMounts = List.of(new Mount("volume", text(volume, "name"), stageRoot, "/stage-root"));
            List<String> holderCommand = containerCommand(state, "holder", options.executorImageId(),
                    options.holderUid(), options.holderGid(), options.holderLimits(), holderMounts, holderArguments);
            beforeCreate(journal, state, holder);
            String holderId = containerId(command(holderCommand, remainingStartupBudget(started, budgetNanos)));
            verifyContainer(inspect("container", holderId, remainingStartupBudget(started, budgetNanos)), state, "holder", holderId,
                    options.executorImageId(),
                    options.holderUid(), options.holderGid(), options.holderLimits(), holderMounts, holderArguments);
            confirmed(journal, state, holder, holderId);
            beforeStart(journal, state, holder);
            command(List.of("container", "start", holderId), remainingStartupBudget(started, budgetNanos));
            JsonNode runningHolder = inspect("container", holderId, remainingStartupBudget(started, budgetNanos));
            verifyContainer(runningHolder, state, "holder", holderId, options.executorImageId(),
                    options.holderUid(), options.holderGid(), options.holderLimits(), holderMounts, holderArguments);
            require(runningHolder.path("State").path("Running").isBoolean()
                    && runningHolder.path("State").path("Running").booleanValue(), "volume holder did not start");
            requireHealthyState(runningHolder.path("State"));
            holder.put("state", "STARTED");
            journal.write(state);
            journal.writeControlFile("supervisor-config.json", supervisorConfig(state));

            ObjectNode supervisor = object(state, "supervisor");
            List<String> arguments = supervisorArguments(attempt);
            List<Mount> mounts = supervisorMounts(state);
            List<String> create = containerCommand(state, "supervisor", options.supervisorImageId(),
                    options.supervisorUid(), options.supervisorGid(), options.supervisorLimits(), mounts, arguments);
            beforeCreate(journal, state, supervisor);
            String supervisorId = containerId(command(create, remainingStartupBudget(started, budgetNanos)));
            verifyContainer(inspect("container", supervisorId, remainingStartupBudget(started, budgetNanos)), state, "supervisor", supervisorId,
                    options.supervisorImageId(), options.supervisorUid(), options.supervisorGid(),
                    options.supervisorLimits(), mounts, arguments);
            confirmed(journal, state, supervisor, supervisorId);
            beforeStart(journal, state, supervisor);
            command(List.of("container", "start", supervisorId), remainingStartupBudget(started, budgetNanos));
            JsonNode observed = inspect("container", supervisorId, remainingStartupBudget(started, budgetNanos));
            verifyContainer(observed, state, "supervisor", supervisorId, options.supervisorImageId(),
                    options.supervisorUid(), options.supervisorGid(), options.supervisorLimits(), mounts, arguments);
            JsonNode runtimeState = observed.path("State");
            requireHealthyState(runtimeState);
            require(runtimeState.path("Running").isBoolean()
                    && (runtimeState.path("Running").booleanValue()
                    || "exited".equals(runtimeState.path("Status").asText())), "supervisor start was not confirmed");
            supervisor.put("state", "STARTED");
            state.put("state", "STARTED");
            journal.write(state);
            SandboxRunHandle handle = new SandboxRunHandle(spec.judgeId(), supervisorId, "linux-container",
                    Instant.now(), "0", SandboxExecutionManifest.VERSION, attempt);
            return new Launch(handle, workDir, prepared.manifest().eventPath());
        }
    }

    public ContainerReclamation recoverConfirmedContainers(Path workDir, String judgeId, String attemptId)
            throws IOException, InterruptedException {
        Path root = validateLocalIdentityAndPaths(workDir);
        try (TaskLock taskLock = acquireGuardedTaskLock(root, false)) {
            return new LinuxConfirmedContainerRecovery(this, options, mapper).recoverLocked(root, judgeId, attemptId);
        }
    }

    public SandboxRunner.ExecutionState executionState(SandboxRunHandle handle, Path workDir, Duration timeout)
            throws IOException, InterruptedException {
        require(handle != null && "linux-container".equals(handle.provider())
                && handle.protocolVersion() == SandboxExecutionManifest.VERSION, "invalid supervisor handle");
        containerId(handle.runId());
        require(handle.attemptId() != null && UUID.fromString(handle.attemptId()).toString().equals(handle.attemptId()),
                "invalid supervisor attempt");
        require(timeout != null && !timeout.isNegative() && !timeout.isZero(), "invalid observation timeout");
        Path root = validateLocalIdentityAndPaths(workDir);
        try (TaskLock taskLock = acquireGuardedTaskLock(root, false);
             LinuxRuntimeJournal journal = LinuxRuntimeJournal.openExisting(
                     root.resolve("control/attempts").resolve(handle.attemptId()), handle.judgeId(), handle.attemptId(),
                     mapper, options.configWriterUid(), options.configWriterGid())) {
            ObjectNode state = journal.read();
            LinuxRuntimeState.classifyUnresolvedSnapshot(state);
            require(state.path("configuration").equals(mapper.readTree(mapper.writeValueAsBytes(configuration(root)))),
                    "runtime configuration differs from durable identity");
            require(handle.runId().equals(state.path("supervisor").path("runtimeId").asText()), "supervisor handle differs");
            JsonNode observed = inspect("container", handle.runId(),
                    timeout.compareTo(options.commandTimeout()) < 0 ? timeout : options.commandTimeout());
            verifyRecoveryContainer(observed, state, "supervisor");
            return supervisorRunning(observed) ? SandboxRunner.ExecutionState.ACTIVE : SandboxRunner.ExecutionState.EXITED;
        }
    }

    public void cancelAndStop(SandboxRunHandle handle, Path workDir) throws IOException, InterruptedException {
        cancelAndStop(handle, workDir, options.commandTimeout());
    }

    public void cancelAndStop(SandboxRunHandle handle, Path workDir, Duration timeout) throws IOException, InterruptedException {
        try {
            stop(handle, workDir, true, timeout);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    public void stop(SandboxRunHandle handle, Path workDir) throws IOException, InterruptedException {
        stop(handle, workDir, false, options.commandTimeout());
    }

    private void stop(SandboxRunHandle handle, Path workDir, boolean cancellation, Duration timeout)
            throws IOException, InterruptedException {
        require(timeout != null && !timeout.isZero() && !timeout.isNegative(), "supervisor stop requires a positive timeout");
        long started = System.nanoTime();
        long budgetNanos = timeout.toNanos();
        require(handle != null && "linux-container".equals(handle.provider())
                && handle.protocolVersion() == SandboxExecutionManifest.VERSION, "invalid supervisor handle");
        containerId(handle.runId());
        require(handle.attemptId() != null && UUID.fromString(handle.attemptId()).toString().equals(handle.attemptId()),
                "invalid supervisor attempt");
        Path root = validateLocalIdentityAndPaths(workDir);
        try (TaskLock taskLock = acquireGuardedTaskLock(root, false)) {
            stopLocked(handle, root, cancellation, started, budgetNanos);
        }
    }

    private void stopLocked(SandboxRunHandle handle, Path root, boolean cancellation, long started, long budgetNanos)
            throws IOException, InterruptedException {
        Path attemptDir = root.resolve("control/attempts").resolve(handle.attemptId());
        try (LinuxRuntimeJournal journal = LinuxRuntimeJournal.openExisting(attemptDir, handle.judgeId(),
                handle.attemptId(), mapper, options.configWriterUid(), options.configWriterGid())) {
            ObjectNode state = journal.read();
            LinuxRuntimeState.classifyUnresolvedSnapshot(state);
            JsonNode expectedConfiguration = mapper.readTree(mapper.writeValueAsBytes(configuration(root)));
            require(state.path("configuration").equals(expectedConfiguration), "runtime configuration differs from durable identity");
            for (String resource : List.of("volume", "holder", "supervisor")) {
                JsonNode retained = state.path(resource);
                require("CONFIRMED".equals(retained.path("creationState").asText()), "unconfirmed runtime resource must be retained");
                require(LinuxRuntimeState.resourceName(resource, handle.attemptId()).equals(retained.path("name").asText()), "resource name differs");
            }
            require(handle.runId().equals(state.path("supervisor").path("runtimeId").asText()), "supervisor handle differs");
            containerId(text(object(state, "holder"), "runtimeId"));
            require(text(object(state, "volume"), "name").equals(text(object(state, "volume"), "runtimeId")), "volume identity differs");
            validateStageRoot(text(object(state, "volume"), "mountpoint"), root);
            JsonNode initial = inspect("container", handle.runId(), remainingCleanupBudget(started, budgetNanos));
            verifyRecoveryContainer(initial, state, "supervisor");
            if (cancellation) {
                supervisorRunning(initial);
            }
            object(state, "supervisor").put("state", "STOP_REQUESTED");
            state.put("state", "CLEANUP_PENDING");
            journal.write(state);
            List<String> stopCommand = List.of("container", "stop", "-t", "0", handle.runId());
            if (cancellation) {
                journal.requestCancellation();
                awaitCooperativeStop(handle.runId(), state, started, budgetNanos);
                requireCancellationNotInterrupted();
                require((handle.runId() + "\n").equals(recoveryCommand(stopCommand, remainingCleanupBudget(started, budgetNanos))),
                        "supervisor stop acknowledgement differs");
            } else {
                command(stopCommand, remainingCleanupBudget(started, budgetNanos));
            }
            JsonNode stopped = inspect("container", handle.runId(), remainingCleanupBudget(started, budgetNanos));
            verifyContainer(stopped, state, "supervisor", handle.runId(), options.supervisorImageId(),
                    options.supervisorUid(), options.supervisorGid(), options.supervisorLimits(),
                    supervisorMounts(state), supervisorArguments(handle.attemptId()));
            require(stopped.path("State").path("Running").isBoolean()
                    && !stopped.path("State").path("Running").booleanValue()
                    && stopped.path("State").path("Pid").isIntegralNumber()
                    && stopped.path("State").path("Pid").canConvertToLong()
                    && stopped.path("State").path("Pid").longValue() == 0, "supervisor stop was not confirmed");
            object(state, "supervisor").put("state", "STOPPED");
            journal.write(state);
        }
    }

    private void awaitCooperativeStop(String supervisorId, ObjectNode state, long started, long budgetNanos)
            throws IOException, InterruptedException {
        while (true) {
            requireCancellationNotInterrupted();
            long remaining = budgetNanos - (System.nanoTime() - started);
            if (remaining <= 0) {
                return;
            }
            JsonNode observed = inspect("container", supervisorId, remainingCleanupBudget(started, budgetNanos));
            verifyRecoveryContainer(observed, state, "supervisor");
            boolean running = supervisorRunning(observed);
            requireCancellationNotInterrupted();
            if (!running) {
                return;
            }
            remaining = budgetNanos - (System.nanoTime() - started);
            if (remaining > 0) {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
        }
    }

    private static boolean supervisorRunning(JsonNode container) throws IOException {
        JsonNode state = container.path("State");
        requireHealthyState(state);
        JsonNode running = state.path("Running");
        JsonNode pid = state.path("Pid");
        require(running.isBoolean() && pid.isIntegralNumber() && pid.canConvertToLong()
                && (running.booleanValue() ? pid.longValue() > 0 : pid.longValue() == 0),
                "supervisor runtime state is not authoritative");
        return running.booleanValue();
    }

    private static void requireCancellationNotInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Supervisor cancellation observation interrupted");
        }
    }

    private TaskLock acquireGuardedTaskLock(Path workDir, boolean allowCreate) throws IOException {
        TaskLock taskLock = null;
        try (TaskLifecycleGuard.Lease lease = TaskLifecycleGuard.acquire(workDir.getParent(), false)) {
            lease.requireTaskDirectory(workDir);
            validateLocalIdentityAndPaths(workDir.toString());
            taskLock = acquireTaskLock(workDir, allowCreate);
            lease.requireTaskDirectory(workDir);
            return taskLock;
        } catch (IOException | RuntimeException | Error failure) {
            if (taskLock != null) {
                try {
                    taskLock.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    TaskLock acquireExistingTaskLock(Path workDir) throws IOException {
        return acquireTaskLock(workDir, false);
    }

    LinuxAttemptDiscovery.Report inspectRetainedAttempts(Path storageRoot, LinuxAttemptDiscovery.Budget budget) throws IOException {
        return new LinuxAttemptDiscovery(this, options, mapper).inspect(storageRoot, budget);
    }

    LinuxStageRecoveryPreparation.Preparation prepareStageRecovery(Path storageRoot, String judgeId,
                                                                   String attemptId) throws IOException {
        return new LinuxStageRecoveryPreparation(this, options, mapper).prepare(storageRoot, judgeId, attemptId);
    }

    private TaskLock acquireTaskLock(Path workDir, boolean allowCreate) throws IOException {
        Path control = workDir.resolve("control");
        if (allowCreate && !Files.exists(control, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(control, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (FileAlreadyExistsException ignored) {
                requireTrustedDirectory(control);
            }
        }
        requireTrustedDirectory(control);
        if (allowCreate) {
            force(control);
            force(workDir);
            force(workDir.getParent());
        }
        Path path = control.resolve("linux-supervisor.lock");
        FileChannel channel;
        if (allowCreate) {
            try {
                channel = FileChannel.open(path,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (FileAlreadyExistsException existing) {
                requireTaskLock(path);
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            }
        } else {
            requireTaskLock(path);
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
        FileLock lock = null;
        try {
            Object fileKey = requireTaskLock(path);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException conflict) {
                throw new IOException("supervisor task is already locked", conflict);
            }
            require(lock != null, "supervisor task is already locked");
            require(Objects.equals(fileKey, requireTaskLock(path)), "supervisor task lock identity changed");
            if (allowCreate) {
                channel.force(true);
                force(control);
            }
            return new TaskLock(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try {
                if (lock != null) {
                    lock.release();
                }
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private Object requireTaskLock(Path path) throws IOException {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().equals(path),
                "supervisor task lock must be an unlinked regular file");
        Map<String, Object> attributes = Files.readAttributes(path, "unix:mode,uid,gid,nlink,size,fileKey", LinkOption.NOFOLLOW_LINKS);
        require(((Number) attributes.get("uid")).intValue() == options.configWriterUid()
                && ((Number) attributes.get("gid")).intValue() == options.configWriterGid()
                && (((Number) attributes.get("mode")).intValue() & 07777) == 0600
                && ((Number) attributes.get("nlink")).intValue() == 1
                && ((Number) attributes.get("size")).longValue() == 0, "unsafe supervisor task lock metadata");
        return attributes.get("fileKey");
    }

    private void rejectManagedAttempts(Path workDir) throws IOException {
        Path attempts = workDir.resolve("control/attempts");
        if (!Files.exists(attempts, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireTrustedDirectory(attempts);
        try (var entries = Files.newDirectoryStream(attempts)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                try {
                    require(UUID.fromString(name).toString().equals(name), "noncanonical supervisor attempt directory");
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("invalid supervisor attempt directory", invalid);
                }
                requireTrustedDirectory(entry);
                // Any retained attempt directory freezes the creation entry: crash windows before
                // runtime-state.json exists (empty directory or manifest-only residue) are managed
                // remains, indistinguishable from legacy layouts and never deletable by retry.
                require(false, "prior managed attempt requires recovery before another supervisor start");
            }
        }
    }

    private Path validateLocalIdentityAndPaths(Path path) throws IOException {
        require(path != null && path.getFileSystem() == FileSystems.getDefault()
                && path.isAbsolute() && path.equals(path.normalize()),
                "local runtime operations require a canonical default-filesystem task directory");
        return validateLocalIdentityAndPaths(path.toString());
    }

    private Path validateLocalIdentityAndPaths(String value) throws IOException {
        require(System.getProperty("os.name", "").equalsIgnoreCase("Linux"), "supervisor requires a local Linux writer");
        require(options.configWriterUid() == 0 && options.configWriterGid() == 0
                && options.supervisorUid() == 0 && options.supervisorGid() == 0,
                "only explicitly configured root writer and supervisor are currently supported");
        List<String> identity = Files.readAllLines(Path.of("/proc/self/status"));
        for (String field : List.of("Uid:", "Gid:")) {
            String line = identity.stream().filter(candidate -> candidate.startsWith(field)).findFirst()
                    .orElseThrow(() -> new IOException("writer identity unavailable"));
            String[] values = line.substring(field.length()).trim().split("\\s+");
            require(values.length == 4 && java.util.Arrays.stream(values).allMatch("0"::equals), "writer identity differs");
        }
        Path workDir = localPath(value);
        requireTrustedDirectory(workDir);
        for (String parent : List.of("control", "control/attempts", "workspace")) {
            Path path = workDir.resolve(parent);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                requireTrustedDirectory(path);
            }
        }
        Path socket = localPath(socketPath(options.hostRuntimeEndpoint()));
        require(socket.toRealPath().equals(socket), "runtime socket must not traverse links");
        Map<String, Object> attributes = Files.readAttributes(socket, "unix:mode,uid", LinkOption.NOFOLLOW_LINKS);
        require((((Number) attributes.get("mode")).intValue() & 0170000) == 0140000
                && ((Number) attributes.get("uid")).intValue() == options.configWriterUid(), "runtime endpoint is not a trusted Unix socket");
        requireDisjoint(workDir.toString(), socket.toString());
        requireDisjoint(options.workMount(), socketPath(options.supervisorRuntimeEndpoint()));
        requireDisjoint(options.workMount(), "/opt/web-judge");
        requireDisjoint(options.workMount(), options.supervisorRuntimeCommand());
        return workDir;
    }

    void requireTrustedDirectory(Path path) throws IOException {
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && path.toRealPath().equals(path), "trusted directory must not traverse links");
        Map<String, Object> attributes = Files.readAttributes(path, "unix:mode,uid,gid", LinkOption.NOFOLLOW_LINKS);
        require(((Number) attributes.get("uid")).intValue() == options.configWriterUid()
                && ((Number) attributes.get("gid")).intValue() == options.configWriterGid()
                && (((Number) attributes.get("mode")).intValue() & 0022) == 0, "unsafe trusted directory ownership or permissions");
    }

    private void verifyImage(String imageId, boolean supervisor, Duration timeout) throws IOException, InterruptedException {
        JsonNode image = inspect("image", imageId, timeout);
        require(imageId.equals(image.path("Id").asText()) && "linux".equals(image.path("Os").asText()), "image identity or operating system differs");
        JsonNode config = image.path("Config");
        require(empty(config.path("Volumes")), "image declares implicit volumes");
        require(supervisor ? config.path("Entrypoint").equals(mapper.valueToTree(ENTRYPOINT))
                : empty(config.path("Entrypoint")), "image entrypoint differs");
    }

    private ObjectNode initialState(String judge, String attempt, Path workDir) {
        ObjectNode state = mapper.createObjectNode().put("version", 1).put("judgeId", judge)
                .put("attemptId", attempt).put("provider", "linux-container").put("state", "PREPARED");
        state.set("configuration", configuration(workDir));
        for (String resource : List.of("volume", "holder", "supervisor")) {
            state.putObject(resource).put("name", LinuxRuntimeState.resourceName(resource, attempt))
                    .put("creationState", "NOT_REQUESTED").put("state", "NOT_REQUESTED");
        }
        return state;
    }

    ObjectNode configuration(Path workDir) {
        ObjectNode config = mapper.createObjectNode().put("hostRuntimeCommand", options.hostRuntimeCommand())
                .put("hostRuntimeEndpoint", options.hostRuntimeEndpoint())
                .put("supervisorRuntimeCommand", options.supervisorRuntimeCommand())
                .put("supervisorRuntimeEndpoint", options.supervisorRuntimeEndpoint())
                .put("supervisorImageId", options.supervisorImageId()).put("executorImageId", options.executorImageId())
                .put("workDir", workDir.toString()).put("workMount", options.workMount())
                .put("configWriterUid", options.configWriterUid()).put("configWriterGid", options.configWriterGid())
                .put("supervisorUid", options.supervisorUid()).put("supervisorGid", options.supervisorGid())
                .put("holderUid", options.holderUid()).put("holderGid", options.holderGid())
                .put("workloadUid", options.workloadUid()).put("workloadGid", options.workloadGid())
                .put("holderLifetimeSeconds", options.holderLifetime().toSeconds()).put("stagePidsLimit", options.stagePidsLimit())
                .put("stageCpus", options.stageCpus()).put("inputLimitBytes", options.inputLimitBytes())
                .put("artifactLimitBytes", options.artifactLimitBytes()).put("volumeLimitBytes", options.volumeLimitBytes())
                .put("controlOutputLimitBytes", options.controlOutputLimitBytes())
                .put("commandTimeoutMillis", options.commandTimeout().toMillis())
                .put("stageCommandTimeoutMillis", options.stageCommandTimeout().toMillis());
        config.set("supervisorLimits", mapper.valueToTree(options.supervisorLimits()));
        config.set("holderLimits", mapper.valueToTree(options.holderLimits()));
        return config;
    }

    private void beforeCreate(LinuxRuntimeJournal journal, ObjectNode state, ObjectNode resource) throws IOException {
        resource.put("creationState", "UNKNOWN").put("state", "CREATE_REQUESTED");
        state.put("state", "CREATE_UNKNOWN");
        journal.write(state);
    }

    private void confirmed(LinuxRuntimeJournal journal, ObjectNode state, ObjectNode resource, String identity) throws IOException {
        resource.put("creationState", "CONFIRMED").put("state", "CREATED").put("runtimeId", identity);
        state.put("state", "PREPARING");
        journal.write(state);
    }

    private void beforeStart(LinuxRuntimeJournal journal, ObjectNode state, ObjectNode resource) throws IOException {
        resource.put("state", "START_REQUESTED");
        journal.write(state);
    }

    private String verifyVolume(JsonNode volume, ObjectNode state) throws IOException {
        String name = text(object(state, "volume"), "name");
        require(name.equals(volume.path("Name").asText()) && "local".equals(volume.path("Driver").asText())
                && "local".equals(volume.path("Scope").asText()), "volume identity or driver differs");
        require(volume.path("Labels").equals(labels(state, "stage-volume")), "volume labels differ");
        ObjectNode expected = mapper.createObjectNode().put("type", "tmpfs").put("device", "tmpfs").put("o", volumeOptions());
        require(volume.path("Options").equals(expected), "volume options differ");
        String root = volume.path("Mountpoint").asText();
        validateStageRoot(root, Path.of(state.path("configuration").path("workDir").asText()));
        return root;
    }

    private void validateStageRoot(String stageRoot, Path workDir) throws IOException {
        localPath(stageRoot);
        for (String path : List.of(workDir.toString(), options.workMount(), socketPath(options.hostRuntimeEndpoint()),
                socketPath(options.supervisorRuntimeEndpoint()), "/opt/web-judge", options.supervisorRuntimeCommand())) {
            requireDisjoint(stageRoot, path);
        }
    }

    private String volumeOptions() {
        return "size=" + options.volumeLimitBytes() + ",nodev,nosuid,uid=0,gid=0,mode=0755";
    }

    private ObjectNode supervisorConfig(ObjectNode state) throws IOException {
        ObjectNode volume = object(state, "volume");
        return mapper.createObjectNode().put("configVersion", 1).put("attemptId", text(state, "attemptId"))
                .put("runtime_command", options.supervisorRuntimeCommand()).put("runtime_endpoint", options.supervisorRuntimeEndpoint())
                .put("executor_image", options.executorImageId()).put("stage_volume", text(volume, "name"))
                .put("stage_root", text(volume, "mountpoint")).put("uid", options.workloadUid()).put("gid", options.workloadGid())
                .put("pids_limit", options.stagePidsLimit()).put("cpus", options.stageCpus())
                .put("input_limit_bytes", options.inputLimitBytes()).put("artifact_limit_bytes", options.artifactLimitBytes())
                .put("command_timeout_seconds", options.stageCommandTimeout().toMillis() / 1000.0)
                .put("volume_limit_bytes", options.volumeLimitBytes()).put("volume_holder_id", text(object(state, "holder"), "runtimeId"));
    }

    private List<String> supervisorArguments(String attempt) {
        String control = options.workMount() + "/control/attempts/" + attempt;
        return List.of(control + "/task-spec.json", "--work-root", options.workMount(), "--stage-config",
                control + "/supervisor-config.json", "--config-owner-uid", Integer.toString(options.configWriterUid()));
    }

    private List<Mount> supervisorMounts(ObjectNode state) throws IOException {
        ObjectNode volume = object(state, "volume");
        return List.of(new Mount("bind", "", state.path("configuration").path("workDir").asText(), options.workMount()),
                new Mount("volume", text(volume, "name"), text(volume, "mountpoint"), text(volume, "mountpoint")),
                new Mount("bind", "", socketPath(options.hostRuntimeEndpoint()), socketPath(options.supervisorRuntimeEndpoint())));
    }

    private List<String> containerCommand(ObjectNode state, String resource, String image, int uid, int gid,
                                           LinuxSupervisorOptions.ResourceLimits limits, List<Mount> mounts, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("container", "create", "--name", text(object(state, resource), "name"),
                "--network", "none", "--ipc", "private", "--cgroupns", "private", "--read-only", "--user", uid + ":" + gid,
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", Long.toString(limits.memoryBytes()),
                "--memory-swap", Long.toString(limits.memoryBytes()), "--pids-limit", Integer.toString(limits.pidsLimit()),
                "--cpus", Double.toString(limits.cpus()), "--log-driver", "none"));
        addLabels(command, text(state, "judgeId"), text(state, "attemptId"), managed(resource));
        for (Mount mount : mounts) {
            command.add("--mount");
            command.add("type=" + mount.type() + ",source=" + (mount.type().equals("volume") ? mount.name() : mount.source())
                    + ",target=" + mount.target() + (mount.type().equals("volume") ? ",volume-nocopy" : ""));
        }
        command.add(image);
        command.addAll(arguments);
        return command;
    }

    private void verifyContainer(JsonNode container, ObjectNode state, String resource, String identity, String image,
                                 int uid, int gid, LinuxSupervisorOptions.ResourceLimits limits,
                                 List<Mount> expectedMounts, List<String> arguments) throws IOException {
        require(identity.equals(container.path("Id").asText()) && image.equals(container.path("Image").asText())
                && ("/" + text(object(state, resource), "name")).equals(container.path("Name").asText()), "container identity differs");
        JsonNode config = container.path("Config");
        require(config.path("Labels").equals(labels(state, managed(resource)))
                && (uid + ":" + gid).equals(config.path("User").asText())
                && config.path("Cmd").equals(mapper.valueToTree(arguments)), "container configuration differs");
        require(resource.equals("supervisor") ? config.path("Entrypoint").equals(mapper.valueToTree(ENTRYPOINT))
                : empty(config.path("Entrypoint")), "container entrypoint differs");
        JsonNode host = container.path("HostConfig");
        exactBoolean(host, "Privileged", false);
        exactBoolean(host, "ReadonlyRootfs", true);
        require("none".equals(host.path("NetworkMode").asText()) && "".equals(host.path("PidMode").asText("invalid"))
                && "private".equals(host.path("IpcMode").asText()) && "private".equals(host.path("CgroupnsMode").asText())
                && emptyText(host.path("UTSMode")) && emptyText(host.path("UsernsMode")), "container namespaces differ");
        exactLong(host, "Memory", limits.memoryBytes());
        exactLong(host, "MemorySwap", limits.memoryBytes());
        exactLong(host, "PidsLimit", limits.pidsLimit());
        exactLong(host, "NanoCpus", Math.round(limits.cpus() * 1_000_000_000));
        require(host.path("CapDrop").equals(mapper.valueToTree(List.of("ALL"))) && empty(host.path("CapAdd"))
                && host.path("SecurityOpt").equals(mapper.valueToTree(List.of("no-new-privileges")))
                && "none".equals(host.path("LogConfig").path("Type").asText())
                && (host.path("OomKillDisable").isNull() || booleanFalse(host.path("OomKillDisable"))),
                "container privilege or log settings differ");
        for (String field : List.of("Devices", "DeviceRequests", "Binds", "Tmpfs", "VolumesFrom", "Links", "PortBindings")) {
            require(empty(host.path(field)), "container has unexpected " + field);
        }
        JsonNode mounts = container.path("Mounts");
        JsonNode declarations = host.path("Mounts");
        require(mounts.isArray() && mounts.size() == expectedMounts.size()
                && declarations.isArray() && declarations.size() == expectedMounts.size(), "container mount count differs");
        for (Mount expected : expectedMounts) {
            JsonNode mount = uniqueMount(mounts, "Destination", expected.target());
            require(expected.type().equals(mount.path("Type").asText()) && expected.source().equals(mount.path("Source").asText()), "container mount source differs");
            exactBoolean(mount, "RW", true);
            JsonNode declaration = uniqueMount(declarations, "Target", expected.target());
            require(expected.type().equals(declaration.path("Type").asText())
                    && (declaration.path("ReadOnly").isMissingNode() || booleanFalse(declaration.path("ReadOnly")))
                    && (expected.type().equals("volume") ? expected.name() : expected.source()).equals(declaration.path("Source").asText()), "container mount declaration differs");
            if (expected.type().equals("volume")) {
                require(expected.name().equals(mount.path("Name").asText()), "container volume name differs");
                exactBoolean(declaration.path("VolumeOptions"), "NoCopy", true);
            } else {
                require(empty(declaration.path("BindOptions")) || "rprivate".equals(declaration.path("BindOptions").path("Propagation").asText()), "bind propagation differs");
                require("rprivate".equals(mount.path("Propagation").asText()), "bind propagation is not private");
            }
        }
    }

    private JsonNode uniqueMount(JsonNode mounts, String field, String value) throws IOException {
        JsonNode selected = null;
        for (JsonNode mount : mounts) {
            if (value.equals(mount.path(field).asText())) {
                require(selected == null, "duplicate mount destination");
                selected = mount;
            }
        }
        require(selected != null, "required mount absent");
        return selected;
    }

    private JsonNode inspect(String kind, String identity) throws IOException, InterruptedException {
        return parseInspection(command(List.of(kind, "inspect", identity)));
    }

    private JsonNode inspect(String kind, String identity, Duration timeout) throws IOException, InterruptedException {
        LinuxContainerRunner.CommandResult response = commandResult(List.of(kind, "inspect", identity), timeout);
        require(response.stderr() != null && response.stderr().isEmpty(), "unexpected cancellation inspect error output");
        return parseInspection(response.stdout());
    }

    private JsonNode parseInspection(String response) throws IOException {
        JsonNode result = mapper.readTree(response);
        require(result != null && result.isArray() && result.size() == 1 && result.get(0).isObject(), "invalid runtime inspect response");
        return result.get(0);
    }

    void validateRecoveryVolume(ObjectNode state, Path task) throws IOException {
        validateStageRoot(text(object(state, "volume"), "mountpoint"), task);
    }

    void verifyRecoveryContainer(JsonNode container, ObjectNode state, String resource) throws IOException {
        String identity = text(object(state, resource), "runtimeId");
        if (resource.equals("supervisor")) {
            verifyContainer(container, state, resource, identity, options.supervisorImageId(), options.supervisorUid(),
                    options.supervisorGid(), options.supervisorLimits(), supervisorMounts(state),
                    supervisorArguments(text(state, "attemptId")));
        } else {
            require(resource.equals("holder"), "unsupported recovery resource");
            ObjectNode volume = object(state, "volume");
            verifyContainer(container, state, resource, identity, options.executorImageId(), options.holderUid(),
                    options.holderGid(), options.holderLimits(),
                    List.of(new Mount("volume", text(volume, "name"), text(volume, "mountpoint"), "/stage-root")),
                    List.of("/bin/sleep", Long.toString(options.holderLifetime().toSeconds())));
        }
    }

    String recoveryCommand(List<String> arguments) throws IOException, InterruptedException {
        return recoveryCommand(arguments, options.commandTimeout());
    }

    private String recoveryCommand(List<String> arguments, Duration timeout) throws IOException, InterruptedException {
        LinuxContainerRunner.CommandResult result = commandResult(arguments, timeout);
        require(result.stderr() != null && result.stderr().isEmpty(), "unexpected runtime recovery error output");
        return result.stdout();
    }

    private String command(List<String> arguments) throws IOException, InterruptedException {
        return commandResult(arguments).stdout();
    }

    private String command(List<String> arguments, Duration timeout) throws IOException, InterruptedException {
        return commandResult(arguments, timeout).stdout();
    }

    private Duration remainingStartupBudget(long started, long budgetNanos) throws IOException {
        long remaining = budgetNanos - (System.nanoTime() - started);
        require(remaining > 0, "Supervisor startup deadline exceeded");
        Duration timeout = Duration.ofNanos(remaining);
        return timeout.compareTo(options.commandTimeout()) < 0 ? timeout : options.commandTimeout();
    }

    private Duration remainingCleanupBudget(long started, long budgetNanos) throws IOException {
        long remaining = budgetNanos - (System.nanoTime() - started);
        require(remaining > 0, "Supervisor cleanup deadline exceeded; stop is not confirmed");
        Duration timeout = Duration.ofNanos(remaining);
        return timeout.compareTo(options.commandTimeout()) < 0 ? timeout : options.commandTimeout();
    }

    private LinuxContainerRunner.CommandResult commandResult(List<String> arguments) throws IOException, InterruptedException {
        return commandResult(arguments, options.commandTimeout());
    }

    private LinuxContainerRunner.CommandResult commandResult(List<String> arguments, Duration timeout)
            throws IOException, InterruptedException {
        require(timeout != null && !timeout.isZero() && !timeout.isNegative()
                && timeout.compareTo(options.commandTimeout()) <= 0, "runtime timeout is outside configured budget");
        List<String> command = new ArrayList<>(List.of(options.hostRuntimeCommand(), "--host", options.hostRuntimeEndpoint()));
        command.addAll(arguments);
        LinuxContainerRunner.CommandResult result = executor.run(List.copyOf(command), timeout);
        require(result != null && result.exitCode() == 0, "runtime command failed; durable resources retained");
        require(result.stdout() != null && result.stdout().length() <= options.controlOutputLimitBytes(), "runtime response exceeded control bound");
        return result;
    }

    private ObjectNode labels(ObjectNode state, String managed) throws IOException {
        return mapper.createObjectNode().put("io.webjudge.judge", text(state, "judgeId"))
                .put("io.webjudge.attempt", text(state, "attemptId")).put("io.webjudge.managed", managed);
    }

    private static void addLabels(List<String> command, String judge, String attempt, String managed) {
        for (String label : List.of("io.webjudge.judge=" + judge, "io.webjudge.attempt=" + attempt, "io.webjudge.managed=" + managed)) {
            command.add("--label");
            command.add(label);
        }
    }

    private static String managed(String resource) {
        return resource.equals("holder") ? "stage-volume-holder" : resource.equals("volume") ? "stage-volume" : "supervisor";
    }

    private static void requireHealthyState(JsonNode state) throws IOException {
        exactBoolean(state, "OOMKilled", false);
        exactBoolean(state, "Dead", false);
        require(emptyText(state.path("Error")), "container runtime reported an error");
    }

    private static Path localPath(String value) throws IOException {
        try {
            LinuxSupervisorOptions.requireAbsolutePath(value, "localRuntimePath");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid local runtime path", invalid);
        }
        Path path = Path.of(value);
        require(path.toString().equals(value) && path.normalize().equals(path), "runtime path must be normalized");
        return path;
    }

    private static void requireDisjoint(String first, String second) throws IOException {
        Path firstPath = localPath(first);
        Path secondPath = localPath(second);
        require(!firstPath.startsWith(secondPath) && !secondPath.startsWith(firstPath), "runtime mounts overlap");
    }

    private static String socketPath(String endpoint) throws IOException {
        require(endpoint != null && endpoint.startsWith("unix:///"), "explicit local Unix socket required");
        return localPath(endpoint.substring("unix://".length())).toString();
    }

    private static String containerId(String value) throws IOException {
        require(value != null && value.trim().matches("[0-9a-f]{64}"), "runtime must return a complete container ID");
        return value.trim();
    }

    private static ObjectNode object(ObjectNode parent, String name) throws IOException {
        require(parent.path(name).isObject(), "runtime journal object is missing: " + name);
        return (ObjectNode) parent.get(name);
    }

    private static String text(ObjectNode parent, String name) throws IOException {
        require(parent.path(name).isTextual() && !parent.path(name).textValue().isEmpty(), "runtime journal identity is missing: " + name);
        return parent.path(name).textValue();
    }

    private static boolean empty(JsonNode value) {
        return value.isMissingNode() || value.isNull() || ((value.isArray() || value.isObject()) && value.isEmpty());
    }

    private static boolean emptyText(JsonNode value) {
        return value.isTextual() && value.textValue().isEmpty();
    }

    private static boolean booleanFalse(JsonNode value) {
        return value.isBoolean() && !value.booleanValue();
    }

    private static void exactBoolean(JsonNode node, String field, boolean expected) throws IOException {
        require(node.path(field).isBoolean() && node.path(field).booleanValue() == expected, "runtime boolean differs: " + field);
    }

    private static void exactLong(JsonNode node, String field, long expected) throws IOException {
        require(node.path(field).isIntegralNumber() && node.path(field).canConvertToLong()
                && node.path(field).longValue() == expected, "runtime limit differs: " + field);
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    public record Launch(SandboxRunHandle handle, Path workDir, String eventFile) { }

    public record ContainerReclamation(int confirmedStages, String retainedVolumeName) { }

    record TaskLock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    private record Mount(String type, String name, String source, String target) { }
}
