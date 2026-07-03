package com.example.demo.service.sandbox;

import com.example.demo.config.SandboxProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public LinuxContainerRunner(SandboxProperties sandboxProperties, ObjectMapper objectMapper) {
        this(Options.fromProperties(sandboxProperties), new ProcessCommandExecutor(), objectMapper);
    }

    public LinuxContainerRunner(Options options, CommandExecutor commandExecutor, ObjectMapper objectMapper) {
        this.options = options == null ? Options.builder().build() : options;
        this.commandExecutor = commandExecutor == null ? new ProcessCommandExecutor() : commandExecutor;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    public static LinuxContainerRunner withDefaults(ObjectMapper objectMapper) {
        return new LinuxContainerRunner(Options.builder().build(), new ProcessCommandExecutor(), objectMapper);
    }

    @Override
    public SandboxCapabilities probe() {
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
            boolean productionSafe = reasons.isEmpty();
            return SandboxCapabilities.builder()
                    .provider(PROVIDER)
                    .isolation("container")
                    .productionSafe(productionSafe)
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
        try {
            Path workDir = Path.of(spec.workDir()).toAbsolutePath().normalize();
            Files.createDirectories(workDir);
            validateMountedSources(spec, workDir);
            Path taskSpecPath = workDir.resolve(options.taskSpecFile());
            objectMapper.writeValue(taskSpecPath.toFile(), spec);

            String containerName = containerName(spec.judgeId());
            CommandResult result = commandExecutor.run(startCommand(spec, workDir, containerName), options.commandTimeout());
            if (!result.succeeded()) {
                throw new IllegalStateException("linux container start failed: " + compact(result.combinedOutput()));
            }
            String containerId = firstLine(result.stdout());
            if (!StringUtils.hasText(containerId)) {
                throw new IllegalStateException("linux container start did not return a container id");
            }
            runs.put(containerId, new RunState(workDir, options.eventFile()));
            return SandboxRunHandle.builder()
                    .judgeId(spec.judgeId())
                    .runId(containerId)
                    .provider(PROVIDER)
                    .startedAt(Instant.now())
                    .eventCursor("0")
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
        RunState state = runs.get(handle.runId());
        if (state == null) {
            return List.of();
        }
        Path eventFile = state.workDir().resolve(state.eventFile());
        if (!Files.exists(eventFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(eventFile, StandardCharsets.UTF_8);
            int cursor = state.cursor().get();
            if (cursor >= lines.size()) {
                return List.of();
            }
            List<SandboxTaskEvent> events = new ArrayList<>();
            for (int i = cursor; i < lines.size(); i++) {
                String line = lines.get(i);
                if (StringUtils.hasText(line)) {
                    events.add(objectMapper.readValue(line, SandboxTaskEvent.class));
                }
            }
            state.cursor().set(lines.size());
            if (events.stream().anyMatch(event -> terminal(event.type()))) {
                cleanupContainer(handle.runId());
            }
            return events;
        } catch (Exception e) {
            return List.of(SandboxTaskEvent.of(
                    handle.judgeId(),
                    SandboxTaskEvent.Type.SYSTEM_ERROR,
                    "Failed to read linux container events: " + e.getMessage()
            ));
        }
    }

    @Override
    public void cancel(SandboxRunHandle handle) {
        cleanupContainer(handle.runId());
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

    private List<String> startCommand(SandboxTaskSpec spec, Path workDir, String containerName) {
        List<String> command = baseRunCommand(false, containerName, spec.memoryLimitBytes());
        command.add("--mount");
        command.add("type=bind,source=" + workDir + ",target=" + options.workMount());
        command.add("--workdir");
        command.add(options.workMount());
        command.add(options.image());
        command.add(options.runnerCommand());
        command.add(options.workMount() + "/" + options.taskSpecFile());
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
        if (!StringUtils.hasText(containerId)) {
            return;
        }
        try {
            commandExecutor.run(
                    List.of(options.runtimeCommand(), "rm", "-f", containerId),
                    options.commandTimeout()
            );
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            runs.remove(containerId);
        }
    }

    private void validateMountedSources(SandboxTaskSpec spec, Path workDir) {
        for (Map.Entry<SandboxTaskSpec.SourceRole, String> source : spec.sourcePaths().entrySet()) {
            Path sourcePath = Path.of(source.getValue()).toAbsolutePath().normalize();
            if (!sourcePath.startsWith(workDir)) {
                throw new IllegalArgumentException("source path " + source.getKey() + " must stay inside mounted workDir");
            }
        }
    }

    private boolean terminal(SandboxTaskEvent.Type type) {
        return type == SandboxTaskEvent.Type.COMPLETED
                || type == SandboxTaskEvent.Type.CANCELLED
                || type == SandboxTaskEvent.Type.BUDGET_EXCEEDED
                || type == SandboxTaskEvent.Type.SECURITY_VIOLATION
                || type == SandboxTaskEvent.Type.SYSTEM_ERROR
                || type == SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE;
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
        private final int pidsLimit;
        private final double cpus;
        private final String securityProfile;
        private final Duration commandTimeout;

        private Options(Builder builder) {
            this.runtimeCommand = requireText(builder.runtimeCommand, "runtimeCommand");
            this.image = requireText(builder.image, "image");
            this.containerUser = requireText(builder.containerUser, "containerUser");
            this.workMount = requireText(builder.workMount, "workMount");
            this.runnerCommand = requireText(builder.runnerCommand, "runnerCommand");
            this.taskSpecFile = requireText(builder.taskSpecFile, "taskSpecFile");
            this.eventFile = requireText(builder.eventFile, "eventFile");
            if (builder.pidsLimit <= 0) {
                throw new IllegalArgumentException("pidsLimit must be positive");
            }
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
                    .pidsLimit(linux.getPidsLimit())
                    .cpus(linux.getCpus())
                    .securityProfile(properties.getSecurityProfile())
                    .commandTimeout(linux.getCommandTimeout())
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

        public static final class Builder {
            private String runtimeCommand = "docker";
            private String image = "cpp-judge-runner:latest";
            private String containerUser = "65532:65532";
            private String workMount = "/work";
            private String runnerCommand = "/opt/judge-runner/run-task";
            private String taskSpecFile = "sandbox-task.json";
            private String eventFile = "events.jsonl";
            private int pidsLimit = 64;
            private double cpus = 1.0;
            private String securityProfile = "docker-default";
            private Duration commandTimeout = Duration.ofSeconds(30);

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

    private record RunState(Path workDir, String eventFile, AtomicInteger cursor) {
        RunState(Path workDir, String eventFile) {
            this(workDir, eventFile, new AtomicInteger(0));
        }
    }

    private static final class ProcessCommandExecutor implements CommandExecutor {
        @Override
        public CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).start();
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(124, stdout.join(), "command timed out after " + timeout);
            }
            return new CommandResult(process.exitValue(), stdout.join(), stderr.join());
        }

        private static CompletableFuture<String> readAsync(InputStream inputStream) {
            return CompletableFuture.supplyAsync(() -> {
                try (InputStream stream = inputStream) {
                    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return e.getMessage();
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
