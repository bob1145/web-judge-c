package com.example.demo.service.sandbox;

import com.example.demo.config.SandboxProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.RunnerProtocolCapabilities;
import com.example.demo.dto.SandboxExecutionManifest;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileSystems;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "judge.sandbox.production.provider", havingValue = "linux-container")
public class LinuxContainerRunner implements SandboxRunner {

    private static final String PROVIDER = "linux-container";
    private static final long PROBE_MEMORY_BYTES = 64L * 1024 * 1024;
    private static final String PROBE_SCRIPT = """
            uid="$(id -u 2>/dev/null || echo 0)"
            interfaces="$(awk -F: 'NR>2 {gsub(/ /,"",$1); print $1}' /proc/net/dev 2>/dev/null | paste -sd, -)"
            if [ "$interfaces" = "lo" ] || [ -z "$interfaces" ]; then network="disabled"; else network="enabled:$interfaces"; fi
            if [ -f /sys/fs/cgroup/cgroup.controllers ] || [ -f /sys/fs/cgroup/memory.max ] || [ -f /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then cgroup="present"; else cgroup="missing"; fi
            seccomp="$(awk '/^Seccomp:/ {print $2}' /proc/self/status 2>/dev/null || echo 0)"
            apparmor="$(cat /proc/self/attr/current 2>/dev/null || true)"
            printf 'PROBE_UID=%s\\n' "$uid"
            printf 'PROBE_NETWORK=%s\\n' "$network"
            printf 'PROBE_CGROUP=%s\\n' "$cgroup"
            printf 'PROBE_SECCOMP=%s\\n' "$seccomp"
            printf 'PROBE_APPARMOR=%s\\n' "$apparmor"
            """;

    private final Options options;
    private final CommandExecutor commandExecutor;
    private final ObjectMapper objectMapper;
    private final ObjectReader eventReader;
    private final LinuxSupervisorLauncher supervisorLauncher;
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public LinuxContainerRunner(SandboxProperties sandboxProperties, ObjectMapper objectMapper) {
        this(Options.fromProperties(sandboxProperties), null, objectMapper);
    }

    public LinuxContainerRunner(Options options, CommandExecutor commandExecutor, ObjectMapper objectMapper) {
        this.options = options == null ? Options.builder().build() : options;
        this.commandExecutor = commandExecutor != null ? commandExecutor
                : this.options.supervisor() == null ? new ProcessCommandExecutor()
                : new LinuxSupervisorCommandExecutor(this.options.supervisor().controlOutputLimitBytes());
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.eventReader = SandboxTaskEvent.jsonReader(this.objectMapper);
        this.supervisorLauncher = this.options.supervisor() == null ? null
                : new LinuxSupervisorLauncher(this.options.supervisor(), this.commandExecutor, this.objectMapper);
    }

    public static LinuxContainerRunner withDefaults(ObjectMapper objectMapper) {
        return new LinuxContainerRunner(Options.builder().build(), new ProcessCommandExecutor(), objectMapper);
    }

    public LinuxAttemptDiscovery.Report inspectRetainedAttempts(Path storageRoot, LinuxAttemptDiscovery.Budget budget) throws IOException {
        if (supervisorLauncher == null) {
            throw new IllegalStateException("retained attempt discovery requires explicit supervisor configuration");
        }
        return supervisorLauncher.inspectRetainedAttempts(storageRoot, budget);
    }

    public LinuxStageRecoveryPreparation.Preparation prepareRetainedStageRecovery(Path storageRoot,
                                                                                  String judgeId, String attemptId) throws IOException {
        if (supervisorLauncher == null) {
            throw new IllegalStateException("stage recovery preparation requires explicit supervisor configuration");
        }
        return supervisorLauncher.prepareStageRecovery(storageRoot, judgeId, attemptId);
    }

    public LinuxSupervisorLauncher.ContainerReclamation recoverRetainedConfirmedContainers(Path storageRoot,
                                                                                            String judgeId, String attemptId)
            throws IOException, InterruptedException {
        if (supervisorLauncher == null) {
            throw new IllegalStateException("confirmed recovery requires explicit supervisor configuration");
        }
        if (storageRoot == null || storageRoot.getFileSystem() != FileSystems.getDefault()
                || !storageRoot.isAbsolute() || !storageRoot.equals(storageRoot.normalize())
                || judgeId == null || judgeId.length() > 256 || !judgeId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("confirmed recovery requires a canonical storage root and bound judge identity");
        }
        return supervisorLauncher.recoverConfirmedContainers(storageRoot.resolve("judge-" + judgeId), judgeId, attemptId);
    }

    @Override
    public SandboxCapabilities probe() {
        if (supervisorLauncher != null) {
            return unavailable("isolated supervisor integration is experimental; production acceptance is incomplete",
                    "supervisor lifecycle integration; production gate remains closed");
        }
        try {
            CommandResult runtime = commandExecutor.run(runtimeVersionCommand(), options.commandTimeout());
            if (!runtime.succeeded()) {
                return unavailable("container runtime unavailable: " + compact(runtime.combinedOutput()),
                        "runtime=" + options.runtimeCommand());
            }
            RuntimeInfo runtimeInfo = RuntimeInfo.parse(runtime.stdout());
            if (!"linux".equals(runtimeInfo.os())) {
                return unavailable("container runtime is not Linux: " + runtimeInfo.os(),
                        "runtime=" + options.runtimeCommand() + " serverOs=" + runtimeInfo.os());
            }

            CommandResult image = commandExecutor.run(imageInspectCommand(), options.commandTimeout());
            if (!image.succeeded()) {
                return unavailable("linux runner image unavailable: " + compact(image.combinedOutput()),
                        "runtime=" + options.runtimeCommand() + " image=" + options.image());
            }
            ImageInfo imageInfo = ImageInfo.parse(image.stdout());
            if (!"linux".equals(imageInfo.os())) {
                return unavailable("runner image is not Linux: " + imageInfo.os(),
                        "image=" + options.image() + " imageOs=" + imageInfo.os());
            }

            CommandResult probe = commandExecutor.run(probeCommand(), options.commandTimeout());
            if (!probe.succeeded()) {
                return unavailable("container capability probe failed: " + compact(probe.combinedOutput()),
                        "runtime=" + options.runtimeCommand() + " image=" + options.image());
            }

            ProbeInfo probeInfo = ProbeInfo.parse(probe.stdout());
            List<String> reasons = new ArrayList<>();
            if (!probeInfo.networkDisabled()) {
                reasons.add("network must be disabled");
            }
            if (!probeInfo.nonRoot()) {
                reasons.add("non-root user is required");
            }
            if (!probeInfo.resourceLimits()) {
                reasons.add("cgroup resource limits are required");
            }
            if (!probeInfo.securityProfileActive()) {
                reasons.add("security profile is required");
            }
            reasons.add("control-plane isolation is not implemented by the shared-workdir adapter");
            boolean productionSafe = reasons.isEmpty();
            return SandboxCapabilities.builder()
                    .provider(PROVIDER)
                    .isolation("container")
                    .productionSafe(productionSafe)
                    .controlPlaneIsolated(false)
                    .networkDisabled(probeInfo.networkDisabled())
                    .nonRoot(probeInfo.nonRoot())
                    .resourceLimits(probeInfo.resourceLimits())
                    .securityProfile(options.securityProfile())
                    .details("runtime=" + options.runtimeCommand()
                            + " serverOs=" + runtimeInfo.os()
                            + " version=" + runtimeInfo.version()
                            + " image=" + options.image()
                            + " imageOs=" + imageInfo.os()
                            + " cgroup=" + probeInfo.cgroup())
                    .protocol(probeProtocol())
                    .skipReason(productionSafe ? null : String.join("; ", reasons))
                    .build();
        } catch (IOException e) {
            return unavailable("container runtime unavailable: " + e.getMessage(),
                    "runtime=" + options.runtimeCommand());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("container runtime probe interrupted",
                    "runtime=" + options.runtimeCommand());
        } catch (RuntimeException e) {
            return unavailable("container capability probe failed: " + e.getMessage(),
                    "runtime=" + options.runtimeCommand() + " image=" + options.image());
        }
    }

    @Override
    public SandboxRunHandle start(SandboxTaskSpec spec) {
        return start(spec, spec.maxTaskRuntime());
    }

    @Override
    public SandboxRunHandle start(SandboxTaskSpec spec, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Sandbox startup requires a positive timeout");
        }
        long started = System.nanoTime();
        long budgetNanos = timeout.toNanos();
        if (!"trusted-local".equals(spec.profile())) {
            throw new IllegalStateException("Linux execution outside trusted-local requires control-plane isolation");
        }
        if (supervisorLauncher != null) {
            try {
                LinuxSupervisorLauncher.Launch launch = supervisorLauncher.start(spec, timeout);
                runs.put(launch.handle().runId(), new RunState(launch.workDir(), launch.eventFile()));
                return launch.handle();
            } catch (IOException e) {
                throw new IllegalStateException("Linux supervisor launch requires trusted recovery", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Linux supervisor launch interrupted; trusted recovery required", e);
            }
        }
        try {
            SandboxManifestFactory.Prepared prepared = SandboxManifestFactory.prepare(spec);
            Path workDir = prepared.workDir();
            if (workDir.toString().contains(",")) {
                throw new IllegalArgumentException("host workDir cannot contain a mount delimiter");
            }
            SandboxExecutionManifest manifest = prepared.manifest();
            probeProtocol(remainingStartupBudget(started, budgetNanos)).require(manifest);
            prepared.write(objectMapper);

            String containerName = containerName(spec.judgeId());
            CommandResult result = commandExecutor.run(startCommand(spec, workDir, containerName, manifest),
                    remainingStartupBudget(started, budgetNanos));
            if (!result.succeeded()) {
                throw new IllegalStateException("linux container start failed: " + compact(result.combinedOutput()));
            }
            String containerId = firstLine(result.stdout());
            if (!StringUtils.hasText(containerId)) {
                throw new IllegalStateException("linux container start did not return a container id");
            }
            runs.put(containerId, new RunState(workDir, manifest.eventPath()));
            return SandboxRunHandle.builder()
                    .judgeId(spec.judgeId())
                    .runId(containerId)
                    .provider(PROVIDER)
                    .startedAt(Instant.now())
                    .eventCursor("0")
                    .protocolVersion(manifest.protocolVersion())
                    .attemptId(manifest.attemptId())
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("linux container start failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("linux container start interrupted", e);
        }
    }

    @Override
    public List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle) {
        if (handle == null || !PROVIDER.equals(handle.provider())) {
            throw new IllegalArgumentException("Sandbox run handle provider does not match " + PROVIDER);
        }
        RunState state = runs.get(handle.runId());
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            Path eventFile = state.workDir().resolve(state.eventFile());
            try {
                BasicFileAttributes previous = state.fileAttributes().get();
                BasicFileAttributes attributes;
                try {
                    attributes = checkedEventFileAttributes(eventFile, previous);
                } catch (NoSuchFileException e) {
                    if (previous == null) {
                        return List.of();
                    }
                    throw e;
                }
                long cursor = state.cursor().get();
                byte[] previousLine = state.pendingLine().get();
                long readOffset = Math.addExact(cursor, previousLine.length);
                if (readOffset > attributes.size()) {
                    throw new IOException("Runner event file lost previously observed bytes");
                }
                long completedOffset = cursor;
                List<SandboxTaskEvent> events = new ArrayList<>();
                byte[] remainingLine;
                try (FileChannel channel = FileChannel.open(eventFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    channel.position(cursor);
                    ByteBuffer buffer = ByteBuffer.allocate(Math.min(8192, options.eventBatchLimitBytes()));
                    int verifiedBytes = 0;
                    while (verifiedBytes < previousLine.length) {
                        buffer.clear();
                        buffer.limit(Math.min(buffer.capacity(), previousLine.length - verifiedBytes));
                        if (channel.read(buffer) <= 0) {
                            throw new IOException("Runner event file lost its cached pending prefix");
                        }
                        buffer.flip();
                        while (buffer.hasRemaining()) {
                            if (buffer.get() != previousLine[verifiedBytes]) {
                                throw new IOException("Runner event file changed its cached pending prefix");
                            }
                            verifiedBytes++;
                        }
                    }
                    ByteArrayOutputStream pendingLine = new ByteArrayOutputStream();
                    pendingLine.writeBytes(previousLine);
                    int readBytes = previousLine.length;
                    while (readBytes < options.eventBatchLimitBytes() && events.size() < options.eventBatchLimitCount()) {
                        buffer.clear();
                        buffer.limit(Math.min(buffer.capacity(), options.eventBatchLimitBytes() - readBytes));
                        int blockBytes = channel.read(buffer);
                        if (blockBytes <= 0) {
                            break;
                        }
                        readBytes += blockBytes;
                        buffer.flip();
                        while (buffer.hasRemaining() && events.size() < options.eventBatchLimitCount()) {
                            byte nextByte = buffer.get();
                            readOffset++;
                            if (nextByte != '\n') {
                                if (pendingLine.size() == options.eventLineLimitBytes()) {
                                    throw new IOException("Runner event line exceeds byte limit");
                                }
                                pendingLine.write(nextByte);
                                continue;
                            }
                            byte[] lineBytes = pendingLine.toByteArray();
                            int length = lineBytes.length;
                            if (length > 0 && lineBytes[length - 1] == '\r') {
                                length--;
                            }
                            String line = StandardCharsets.UTF_8.newDecoder()
                                    .decode(ByteBuffer.wrap(lineBytes, 0, length)).toString();
                            if (StringUtils.hasText(line)) {
                                SandboxTaskEvent event = eventReader.readValue(line);
                                if (event == null || event.type() == null) {
                                    throw new IOException("Runner event record has no valid type");
                                }
                                events.add(event);
                            }
                            completedOffset = readOffset;
                            pendingLine.reset();
                        }
                    }
                    remainingLine = pendingLine.toByteArray();
                }
                state.fileAttributes().set(checkedEventFileAttributes(eventFile, attributes));
                state.cursor().set(completedOffset);
                state.pendingLine().set(remainingLine);
                return events;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read linux container events", e);
            }
        }
    }

    private static BasicFileAttributes checkedEventFileAttributes(Path eventFile, BasicFileAttributes previous) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(eventFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.fileKey() == null) {
            throw new IOException("Runner event file must have a regular, identifiable file identity");
        }
        if (previous != null && (!previous.fileKey().equals(attributes.fileKey()) || attributes.size() < previous.size())) {
            throw new IOException("Runner event file was replaced or shortened");
        }
        return attributes;
    }

    @Override
    public ExecutionState executionState(SandboxRunHandle handle, Duration timeout) {
        if (handle == null || !PROVIDER.equals(handle.provider())) {
            throw new IllegalArgumentException("Sandbox run handle provider does not match " + PROVIDER);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Execution state observation requires a positive timeout");
        }
        RunState state = runs.get(handle.runId());
        if (state == null) {
            return ExecutionState.UNKNOWN;
        }
        try {
            if (supervisorLauncher != null) {
                return supervisorLauncher.executionState(handle, state.workDir(), timeout);
            }
            CommandResult result = commandExecutor.run(List.of(options.runtimeCommand(), "inspect", "--format",
                    "{{.State.Running}}|{{.State.Pid}}", handle.runId()),
                    timeout.compareTo(options.commandTimeout()) < 0 ? timeout : options.commandTimeout());
            if (result.exitCode() != 0 || StringUtils.hasText(result.stderr())) {
                return ExecutionState.UNKNOWN;
            }
            String[] status = firstLine(result.stdout()).split("\\|", -1);
            if (status.length != 2) {
                return ExecutionState.UNKNOWN;
            }
            long pid;
            try {
                pid = Long.parseLong(status[1]);
            } catch (NumberFormatException invalid) {
                return ExecutionState.UNKNOWN;
            }
            if ("true".equals(status[0]) && pid > 0) {
                return ExecutionState.ACTIVE;
            }
            return "false".equals(status[0]) && pid == 0 ? ExecutionState.EXITED : ExecutionState.UNKNOWN;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to observe linux container execution state", failure);
        }
    }

    @Override
    public void cancel(SandboxRunHandle handle) {
        cancel(handle, options.commandTimeout());
    }

    @Override
    public void cancel(SandboxRunHandle handle, Duration timeout) {
        if (handle == null || !PROVIDER.equals(handle.provider())) {
            throw new IllegalArgumentException("Sandbox run handle provider does not match " + PROVIDER);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Sandbox cancellation requires a positive timeout");
        }
        if (supervisorLauncher != null) {
            RunState state = handle == null ? null : runs.get(handle.runId());
            if (state == null) {
                throw new IllegalStateException("Linux supervisor attempt requires persistent recovery");
            }
            try {
                supervisorLauncher.cancelAndStop(handle, state.workDir(), timeout);
            } catch (IOException e) {
                throw new IllegalStateException("Linux supervisor stop requires trusted recovery", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Linux supervisor stop interrupted; trusted recovery required", e);
            }
            throw new IllegalStateException("Linux supervisor stopped; stage and volume recovery remains pending");
        }
        cleanupContainer(handle.runId(), timeout.compareTo(options.commandTimeout()) < 0 ? timeout : options.commandTimeout());
    }

    @Override
    public void cleanupResidual(SandboxRunHandle handle, Duration timeout) {
        cancel(handle, timeout);
        throw new IllegalStateException("Sandbox execution domain stop is not confirmed");
    }

    private SandboxCapabilities unavailable(String reason, String details) {
        return SandboxCapabilities.builder()
                .provider(PROVIDER)
                .isolation("container")
                .productionSafe(false)
                .networkDisabled(false)
                .nonRoot(false)
                .resourceLimits(false)
                .securityProfile(options.securityProfile())
                .details(details)
                .skipReason(reason)
                .build();
    }

    private RunnerProtocolCapabilities probeProtocol() throws IOException, InterruptedException {
        return probeProtocol(options.commandTimeout());
    }

    private RunnerProtocolCapabilities probeProtocol(Duration timeout) throws IOException, InterruptedException {
        List<String> command = baseRunCommand(true, null, PROBE_MEMORY_BYTES);
        command.add(options.image());
        command.add(options.runnerCommand());
        command.add("--capabilities");
        CommandResult result = commandExecutor.run(command, timeout);
        if (!result.succeeded()) {
            throw new IllegalStateException("runner protocol capability probe failed");
        }
        RunnerProtocolCapabilities protocol = objectMapper.readValue(result.stdout(), RunnerProtocolCapabilities.class);
        protocol.requireVersion2();
        return protocol;
    }

    private Duration remainingStartupBudget(long started, long budgetNanos) throws IOException {
        long remaining = budgetNanos - (System.nanoTime() - started);
        if (remaining <= 0) {
            throw new IOException("Sandbox startup deadline exceeded");
        }
        Duration timeout = Duration.ofNanos(remaining);
        return timeout.compareTo(options.commandTimeout()) < 0 ? timeout : options.commandTimeout();
    }

    private List<String> runtimeVersionCommand() {
        return List.of(
                options.runtimeCommand(),
                "version",
                "--format",
                "{{.Server.Os}}|{{.Server.Version}}"
        );
    }

    private List<String> imageInspectCommand() {
        return List.of(
                options.runtimeCommand(),
                "image",
                "inspect",
                options.image(),
                "--format",
                "{{.Os}}|{{.Architecture}}"
        );
    }

    private List<String> probeCommand() {
        List<String> command = baseRunCommand(true, null, PROBE_MEMORY_BYTES);
        command.add(options.image());
        command.add("/bin/sh");
        command.add("-lc");
        command.add(PROBE_SCRIPT);
        return command;
    }

    private List<String> startCommand(SandboxTaskSpec spec, Path workDir, String containerName,
                                      SandboxExecutionManifest manifest) {
        List<String> command = baseRunCommand(false, containerName, spec.memoryLimitBytes());
        command.add("--mount");
        command.add("type=bind,source=" + workDir + ",target=" + options.workMount());
        command.add("--workdir");
        command.add(options.workMount());
        command.add(options.image());
        command.add(options.runnerCommand());
        command.add(options.workMount() + "/" + manifest.specPath());
        return command;
    }

    private List<String> baseRunCommand(boolean removeAfterExit, String containerName, long memoryBytes) {
        List<String> command = new ArrayList<>();
        command.add(options.runtimeCommand());
        command.add("run");
        if (removeAfterExit) {
            command.add("--rm");
        } else {
            command.add("-d");
            command.add("--name");
            command.add(containerName);
        }
        command.add("--network");
        command.add("none");
        command.add("--user");
        command.add(options.containerUser());
        command.add("--read-only");
        command.add("--tmpfs");
        command.add("/tmp:rw,noexec,nosuid,size=64m");
        command.add("--tmpfs");
        command.add("/run:rw,noexec,nosuid,size=16m");
        command.add("--memory");
        command.add(String.valueOf(memoryBytes));
        command.add("--memory-swap");
        command.add(String.valueOf(memoryBytes));
        command.add("--cpus");
        command.add(formatCpus(options.cpus()));
        command.add("--pids-limit");
        command.add(String.valueOf(options.pidsLimit()));
        command.add("--cap-drop");
        command.add("ALL");
        addSecurityOptions(command);
        return command;
    }

    private void addSecurityOptions(List<String> command) {
        command.add("--security-opt");
        command.add("no-new-privileges");
        for (String token : options.securityProfile().split(",")) {
            String value = token.trim();
            if (!StringUtils.hasText(value)
                    || "docker-default".equalsIgnoreCase(value)
                    || "default".equalsIgnoreCase(value)) {
                continue;
            }
            if (value.startsWith("seccomp:")) {
                command.add("--security-opt");
                command.add("seccomp=" + value.substring("seccomp:".length()));
            } else if (value.startsWith("apparmor:")) {
                command.add("--security-opt");
                command.add("apparmor=" + value.substring("apparmor:".length()));
            } else if (value.startsWith("seccomp=") || value.startsWith("apparmor=")) {
                command.add("--security-opt");
                command.add(value);
            }
        }
    }

    private void cleanupContainer(String containerId) {
        cleanupContainer(containerId, options.commandTimeout());
    }

    private void cleanupContainer(String containerId, Duration timeout) {
        if (!StringUtils.hasText(containerId)) {
            return;
        }
        try {
            commandExecutor.run(
                    List.of(options.runtimeCommand(), "rm", "-f", containerId),
                    timeout
            );
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            runs.remove(containerId);
        }
    }



    private String containerName(String judgeId) {
        String safeJudgeId = judgeId == null ? "unknown" : judgeId.replaceAll("[^a-zA-Z0-9_.-]", "-");
        if (safeJudgeId.length() > 40) {
            safeJudgeId = safeJudgeId.substring(0, 40);
        }
        return "cpp-judge-" + safeJudgeId + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String formatCpus(double cpus) {
        if (cpus == Math.rint(cpus)) {
            return String.valueOf((long) cpus);
        }
        return String.format(Locale.ROOT, "%.2f", cpus);
    }

    private static String firstLine(String output) {
        return output == null
                ? ""
                : output.lines().findFirst().orElse("").trim();
    }

    private static String compact(String value) {
        if (!StringUtils.hasText(value)) {
            return "no output";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) : compact;
    }

    public interface CommandExecutor {
        CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {
        boolean succeeded() {
            return exitCode == 0;
        }

        String combinedOutput() {
            return (valueOrEmpty(stdout) + " " + valueOrEmpty(stderr)).trim();
        }

        private static String valueOrEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    public static final class Options {
        private final String runtimeCommand;
        private final String image;
        private final String containerUser;
        private final String workMount;
        private final String runnerCommand;
        private final String taskSpecFile;
        private final String eventFile;
        private final int eventBatchLimitBytes;
        private final int eventBatchLimitCount;
        private final int eventLineLimitBytes;
        private final int pidsLimit;
        private final double cpus;
        private final String securityProfile;
        private final Duration commandTimeout;
        private final LinuxSupervisorOptions supervisor;

        private Options(Builder builder) {
            this.runtimeCommand = requireText(builder.runtimeCommand, "runtimeCommand");
            this.image = requireText(builder.image, "image");
            this.containerUser = requireText(builder.containerUser, "containerUser");
            this.workMount = requireText(builder.workMount, "workMount");
            SandboxManifestFactory.requireExecutionRoot(this.workMount, false);
            this.runnerCommand = requireText(builder.runnerCommand, "runnerCommand");
            this.taskSpecFile = requireText(builder.taskSpecFile, "taskSpecFile");
            this.eventFile = requireText(builder.eventFile, "eventFile");
            if (!SandboxExecutionManifest.SPEC_FILE.equals(taskSpecFile)
                    || !SandboxExecutionManifest.EVENT_FILE.equals(eventFile)) {
                throw new IllegalArgumentException("runner v2 requires fixed attempt-local control filenames");
            }
            if (builder.pidsLimit <= 0) {
                throw new IllegalArgumentException("pidsLimit must be positive");
            }
            if (builder.eventLineLimitBytes <= 0 || builder.eventBatchLimitBytes <= builder.eventLineLimitBytes
                    || builder.eventBatchLimitCount <= 0) {
                throw new IllegalArgumentException("event limits must be positive and batch bytes must exceed line bytes");
            }
            this.eventBatchLimitBytes = builder.eventBatchLimitBytes;
            this.eventBatchLimitCount = builder.eventBatchLimitCount;
            this.eventLineLimitBytes = builder.eventLineLimitBytes;
            if (builder.cpus <= 0) {
                throw new IllegalArgumentException("cpus must be positive");
            }
            if (builder.commandTimeout == null || builder.commandTimeout.isZero() || builder.commandTimeout.isNegative()) {
                throw new IllegalArgumentException("commandTimeout must be positive");
            }
            this.pidsLimit = builder.pidsLimit;
            this.cpus = builder.cpus;
            this.securityProfile = StringUtils.hasText(builder.securityProfile) ? builder.securityProfile : "docker-default";
            this.commandTimeout = builder.commandTimeout;
            this.supervisor = builder.supervisor;
        }

        static Options fromProperties(SandboxProperties properties) {
            SandboxProperties.LinuxContainer linux = properties.getLinuxContainer();
            return builder()
                    .runtimeCommand(linux.getRuntimeCommand())
                    .image(linux.getImage())
                    .containerUser(linux.getContainerUser())
                    .workMount(linux.getWorkMount())
                    .runnerCommand(linux.getRunnerCommand())
                    .taskSpecFile(linux.getTaskSpecFile())
                    .eventFile(linux.getEventFile())
                    .eventBatchLimitBytes(properties.getEventBatchLimitBytes())
                    .eventBatchLimitCount(properties.getEventBatchLimitCount())
                    .eventLineLimitBytes(properties.getEventLineLimitBytes())
                    .pidsLimit(linux.getPidsLimit())
                    .cpus(linux.getCpus())
                    .securityProfile(properties.getSecurityProfile())
                    .commandTimeout(linux.getCommandTimeout())
                    .supervisor(linux.getSupervisor() == null ? null
                            : LinuxSupervisorOptions.fromProperties(linux.getSupervisor()))
                    .build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public String runtimeCommand() {
            return runtimeCommand;
        }

        public String image() {
            return image;
        }

        public String containerUser() {
            return containerUser;
        }

        public String workMount() {
            return workMount;
        }

        public String runnerCommand() {
            return runnerCommand;
        }

        public String taskSpecFile() {
            return taskSpecFile;
        }

        public String eventFile() {
            return eventFile;
        }

        public int eventBatchLimitBytes() {
            return eventBatchLimitBytes;
        }

        public int eventBatchLimitCount() {
            return eventBatchLimitCount;
        }

        public int eventLineLimitBytes() {
            return eventLineLimitBytes;
        }

        public int pidsLimit() {
            return pidsLimit;
        }

        public double cpus() {
            return cpus;
        }

        public String securityProfile() {
            return securityProfile;
        }

        public Duration commandTimeout() {
            return commandTimeout;
        }

        public LinuxSupervisorOptions supervisor() {
            return supervisor;
        }

        public static final class Builder {
            private String runtimeCommand = "docker";
            private String image = "cpp-judge-runner:latest";
            private String containerUser = "65532:65532";
            private String workMount = "/work";
            private String runnerCommand = "/opt/judge-runner/run-task";
            private String taskSpecFile = SandboxExecutionManifest.SPEC_FILE;
            private String eventFile = SandboxExecutionManifest.EVENT_FILE;
            private int eventBatchLimitBytes = SandboxProperties.DEFAULT_EVENT_BATCH_LIMIT_BYTES;
            private int eventBatchLimitCount = SandboxProperties.DEFAULT_EVENT_BATCH_LIMIT_COUNT;
            private int eventLineLimitBytes = SandboxProperties.DEFAULT_EVENT_LINE_LIMIT_BYTES;
            private int pidsLimit = 64;
            private double cpus = 1.0;
            private String securityProfile = "docker-default";
            private Duration commandTimeout = Duration.ofSeconds(30);
            private LinuxSupervisorOptions supervisor;

            public Builder supervisor(LinuxSupervisorOptions supervisor) {
                this.supervisor = supervisor;
                return this;
            }

            public Builder runtimeCommand(String runtimeCommand) {
                this.runtimeCommand = runtimeCommand;
                return this;
            }

            public Builder image(String image) {
                this.image = image;
                return this;
            }

            public Builder containerUser(String containerUser) {
                this.containerUser = containerUser;
                return this;
            }

            public Builder workMount(String workMount) {
                this.workMount = workMount;
                return this;
            }

            public Builder runnerCommand(String runnerCommand) {
                this.runnerCommand = runnerCommand;
                return this;
            }

            public Builder taskSpecFile(String taskSpecFile) {
                this.taskSpecFile = taskSpecFile;
                return this;
            }

            public Builder eventFile(String eventFile) {
                this.eventFile = eventFile;
                return this;
            }

            public Builder pidsLimit(int pidsLimit) {
                this.pidsLimit = pidsLimit;
                return this;
            }

            public Builder eventBatchLimitBytes(int eventBatchLimitBytes) {
                this.eventBatchLimitBytes = eventBatchLimitBytes;
                return this;
            }

            public Builder eventBatchLimitCount(int eventBatchLimitCount) {
                this.eventBatchLimitCount = eventBatchLimitCount;
                return this;
            }

            public Builder eventLineLimitBytes(int eventLineLimitBytes) {
                this.eventLineLimitBytes = eventLineLimitBytes;
                return this;
            }

            public Builder cpus(double cpus) {
                this.cpus = cpus;
                return this;
            }

            public Builder securityProfile(String securityProfile) {
                this.securityProfile = securityProfile;
                return this;
            }

            public Builder commandTimeout(Duration commandTimeout) {
                this.commandTimeout = commandTimeout;
                return this;
            }

            public Options build() {
                return new Options(this);
            }
        }
    }

    private record RuntimeInfo(String os, String version) {
        static RuntimeInfo parse(String output) {
            String[] parts = firstLine(output).split("\\|", -1);
            return new RuntimeInfo(
                    normalize(parts.length > 0 ? parts[0] : ""),
                    parts.length > 1 ? parts[1].trim() : "unknown"
            );
        }
    }

    private record ImageInfo(String os, String architecture) {
        static ImageInfo parse(String output) {
            String[] parts = firstLine(output).split("\\|", -1);
            return new ImageInfo(
                    normalize(parts.length > 0 ? parts[0] : ""),
                    parts.length > 1 ? parts[1].trim() : "unknown"
            );
        }
    }

    private record ProbeInfo(
            String uid,
            String network,
            String cgroup,
            String seccomp,
            String apparmor
    ) {
        static ProbeInfo parse(String output) {
            Map<String, String> values = new LinkedHashMap<>();
            output.lines()
                    .map(String::trim)
                    .filter(line -> line.contains("="))
                    .forEach(line -> {
                        int index = line.indexOf('=');
                        values.put(line.substring(0, index), line.substring(index + 1));
                    });
            return new ProbeInfo(
                    values.getOrDefault("PROBE_UID", "0"),
                    values.getOrDefault("PROBE_NETWORK", "missing"),
                    values.getOrDefault("PROBE_CGROUP", "missing"),
                    values.getOrDefault("PROBE_SECCOMP", "0"),
                    values.getOrDefault("PROBE_APPARMOR", "")
            );
        }

        boolean networkDisabled() {
            return "disabled".equals(network);
        }

        boolean nonRoot() {
            return StringUtils.hasText(uid) && !"0".equals(uid.trim());
        }

        boolean resourceLimits() {
            return "present".equals(cgroup);
        }

        boolean securityProfileActive() {
            return !"0".equals(seccomp.trim())
                    || (StringUtils.hasText(apparmor) && !apparmor.toLowerCase(Locale.ROOT).contains("unconfined"));
        }
    }

    private record RunState(Path workDir, String eventFile, AtomicLong cursor,
                            AtomicReference<BasicFileAttributes> fileAttributes, AtomicReference<byte[]> pendingLine) {
        RunState(Path workDir, String eventFile) {
            this(workDir, eventFile, new AtomicLong(0), new AtomicReference<>(), new AtomicReference<>(new byte[0]));
        }
    }

    private static final class ProcessCommandExecutor implements CommandExecutor {
        @Override
        public CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Runtime command requires a positive timeout");
            }
            long started = System.nanoTime();
            long budgetNanos = timeout.toNanos();
            Process process = new ProcessBuilder(command).start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            boolean completed = false;
            try {
                process.getOutputStream().close();
                long remaining = budgetNanos - (System.nanoTime() - started);
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                    throw new TimeoutException();
                }
                String output = awaitOutput(stdout, started, budgetNanos);
                String error = awaitOutput(stderr, started, budgetNanos);
                completed = true;
                return new CommandResult(process.exitValue(), output, error);
            } catch (TimeoutException deadline) {
                throw new IOException("Runtime command deadline exceeded; completion is not confirmed", deadline);
            } catch (ExecutionException failure) {
                throw new IOException("Runtime command output could not be read", failure.getCause());
            } finally {
                if (!completed) {
                    stdout.cancel(true);
                    stderr.cancel(true);
                    process.destroyForcibly();
                }
            }
        }

        private static String awaitOutput(CompletableFuture<String> output, long started, long budgetNanos)
                throws InterruptedException, ExecutionException, TimeoutException {
            long remaining = budgetNanos - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            return output.get(remaining, TimeUnit.NANOSECONDS);
        }

        private static CompletableFuture<String> readAsync(InputStream inputStream) {
            return CompletableFuture.supplyAsync(() -> {
                try (InputStream stream = inputStream) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
