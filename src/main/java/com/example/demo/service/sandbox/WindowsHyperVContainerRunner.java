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
@ConditionalOnProperty(name = "judge.sandbox.production.provider", havingValue = "windows-container")
public class WindowsHyperVContainerRunner implements SandboxRunner {

    private static final String PROVIDER = "windows-container";

    private final Options options;
    private final CommandExecutor commandExecutor;
    private final ObjectMapper objectMapper;
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    public WindowsHyperVContainerRunner(SandboxProperties sandboxProperties, ObjectMapper objectMapper) {
        this(Options.fromProperties(sandboxProperties), new ProcessCommandExecutor(), objectMapper);
    }

    public WindowsHyperVContainerRunner(Options options, CommandExecutor commandExecutor, ObjectMapper objectMapper) {
        this.options = options == null ? Options.builder().build() : options;
        this.commandExecutor = commandExecutor == null ? new ProcessCommandExecutor() : commandExecutor;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
    }

    public static WindowsHyperVContainerRunner withDefaults(ObjectMapper objectMapper) {
        return new WindowsHyperVContainerRunner(Options.builder().build(), new ProcessCommandExecutor(), objectMapper);
    }

    @Override
    public SandboxCapabilities probe() {
        String probeContainerId = null;
        boolean cleanupConfirmed = false;
        try {
            CommandResult runtime = commandExecutor.run(runtimeVersionCommand(), options.commandTimeout());
            if (!runtime.succeeded()) {
                return unavailable("container runtime unavailable: " + compact(runtime.combinedOutput()),
                        "runtime=" + options.runtimeCommand());
            }
            RuntimeInfo runtimeInfo = RuntimeInfo.parse(runtime.stdout());
            if (!"windows".equals(runtimeInfo.os())) {
                return unavailable("container runtime is not Windows: " + runtimeInfo.os(),
                        "runtime=" + options.runtimeCommand() + " serverOs=" + runtimeInfo.os());
            }

            CommandResult image = commandExecutor.run(imageInspectCommand(), options.commandTimeout());
            if (!image.succeeded()) {
                return unavailable("windows runner image unavailable: " + compact(image.combinedOutput()),
                        "runtime=" + options.runtimeCommand() + " image=" + options.image());
            }
            ImageInfo imageInfo = ImageInfo.parse(image.stdout());
            if (!"windows".equals(imageInfo.os())) {
                return unavailable("runner image is not Windows: " + imageInfo.os(),
                        "image=" + options.image() + " imageOs=" + imageInfo.os());
            }

            CommandResult probeStart = commandExecutor.run(probeRunCommand(), options.commandTimeout());
            if (!probeStart.succeeded()) {
                return unavailable("windows hyper-v probe container failed to start: " + compact(probeStart.combinedOutput()),
                        "runtime=" + options.runtimeCommand() + " image=" + options.image());
            }
            probeContainerId = firstLine(probeStart.stdout());
            if (!StringUtils.hasText(probeContainerId)) {
                return unavailable("windows hyper-v probe did not return a container id",
                        "runtime=" + options.runtimeCommand() + " image=" + options.image());
            }

            CommandResult inspect = commandExecutor.run(inspectCommand(probeContainerId), options.commandTimeout());
            if (!inspect.succeeded()) {
                return unavailable("windows hyper-v probe inspect failed: " + compact(inspect.combinedOutput()),
                        "containerId=" + probeContainerId);
            }
            InspectInfo inspectInfo = InspectInfo.parse(inspect.stdout());

            CommandResult logs = commandExecutor.run(logsCommand(probeContainerId), options.commandTimeout());
            ProbeInfo probeInfo = logs.succeeded()
                    ? ProbeInfo.parse(logs.stdout())
                    : ProbeInfo.missing();

            cleanupConfirmed = cleanupContainer(probeContainerId);
            List<String> reasons = new ArrayList<>();
            if (!inspectInfo.hyperV()) {
                reasons.add("hyper-v isolation is required");
            }
            if (!inspectInfo.networkDisabled()) {
                reasons.add("network must be disabled");
            }
            if (!inspectInfo.memoryLimited()) {
                reasons.add("memory limit is required");
            }
            if (!probeInfo.jobObjectProcessTreeEnabled()) {
                reasons.add("job object process-tree control is required");
            }
            if (!probeInfo.outputLimitEnabled()) {
                reasons.add("output limit control is required");
            }
            if (!cleanupConfirmed) {
                reasons.add("container cleanup confirmation is required");
            }
            boolean productionSafe = reasons.isEmpty();
            return SandboxCapabilities.builder()
                    .provider(PROVIDER)
                    .isolation(inspectInfo.isolation())
                    .productionSafe(productionSafe)
                    .networkDisabled(inspectInfo.networkDisabled())
                    .nonRoot(!"ContainerAdministrator".equalsIgnoreCase(options.containerUser()))
                    .resourceLimits(inspectInfo.memoryLimited() && probeInfo.jobObjectProcessTreeEnabled())
                    .securityProfile(options.securityProfile())
                    .details("runtime=" + options.runtimeCommand()
                            + " serverOs=" + runtimeInfo.os()
                            + " version=" + runtimeInfo.version()
                            + " image=" + options.image()
                            + " imageOs=" + imageInfo.os()
                            + " containerId=" + probeContainerId
                            + " cleanup=" + (cleanupConfirmed ? "confirmed" : "failed"))
                    .skipReason(productionSafe ? null : String.join("; ", reasons))
                    .build();
        } catch (IOException e) {
            return unavailable("container runtime unavailable: " + e.getMessage(),
                    "runtime=" + options.runtimeCommand());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("windows hyper-v probe interrupted",
                    "runtime=" + options.runtimeCommand());
        } catch (RuntimeException e) {
            return unavailable("windows hyper-v probe failed: " + e.getMessage(),
                    "runtime=" + options.runtimeCommand() + " image=" + options.image());
        } finally {
            if (StringUtils.hasText(probeContainerId) && !cleanupConfirmed) {
                cleanupContainer(probeContainerId);
            }
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
                throw new IllegalStateException("windows hyper-v container start failed: " + compact(result.combinedOutput()));
            }
            String containerId = firstLine(result.stdout());
            if (!StringUtils.hasText(containerId)) {
                throw new IllegalStateException("windows hyper-v container start did not return a container id");
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
            throw new IllegalStateException("windows hyper-v container start failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("windows hyper-v container start interrupted", e);
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
                    "Failed to read windows container events: " + e.getMessage()
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
                .isolation("hyperv")
                .productionSafe(false)
                .networkDisabled(false)
                .nonRoot(!"ContainerAdministrator".equalsIgnoreCase(options.containerUser()))
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

    private List<String> probeRunCommand() {
        List<String> command = baseRunCommand(containerName("probe"), options.probeMemoryBytes());
        command.add(options.image());
        command.add(options.probeCommand());
        return command;
    }

    private List<String> inspectCommand(String containerId) {
        return List.of(
                options.runtimeCommand(),
                "inspect",
                "--format",
                "{{.HostConfig.Isolation}}|{{.HostConfig.NetworkMode}}|{{.HostConfig.Memory}}|{{.State.ExitCode}}",
                containerId
        );
    }

    private List<String> logsCommand(String containerId) {
        return List.of(options.runtimeCommand(), "logs", containerId);
    }

    private List<String> startCommand(SandboxTaskSpec spec, Path workDir, String containerName) {
        List<String> command = baseRunCommand(containerName, spec.memoryLimitBytes());
        command.add("--env");
        command.add("JUDGE_JOB_OBJECT_REQUIRED=true");
        command.add("--env");
        command.add("JUDGE_MAX_OUTPUT_BYTES_PER_CASE=" + spec.maxOutputBytesPerCase());
        command.add("--mount");
        command.add("type=bind,source=" + workDir + ",target=" + options.workMount());
        command.add("--workdir");
        command.add(options.workMount());
        command.add(options.image());
        command.add(options.runnerCommand());
        command.add(options.workMount() + "\\" + options.taskSpecFile());
        return command;
    }

    private List<String> baseRunCommand(String containerName, long memoryBytes) {
        List<String> command = new ArrayList<>();
        command.add(options.runtimeCommand());
        command.add("run");
        command.add("-d");
        command.add("--name");
        command.add(containerName);
        command.add("--isolation");
        command.add("hyperv");
        command.add("--network");
        command.add("none");
        command.add("--user");
        command.add(options.containerUser());
        command.add("--memory");
        command.add(String.valueOf(memoryBytes));
        command.add("--cpus");
        command.add(formatCpus(options.cpus()));
        return command;
    }

    private boolean cleanupContainer(String containerId) {
        if (!StringUtils.hasText(containerId)) {
            return false;
        }
        try {
            CommandResult result = commandExecutor.run(
                    List.of(options.runtimeCommand(), "rm", "-f", containerId),
                    options.commandTimeout()
            );
            return result.succeeded();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
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
        return "cpp-judge-win-" + safeJudgeId + "-" + UUID.randomUUID().toString().substring(0, 8);
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
        private final String probeCommand;
        private final String taskSpecFile;
        private final String eventFile;
        private final double cpus;
        private final long probeMemoryBytes;
        private final String securityProfile;
        private final Duration commandTimeout;

        private Options(Builder builder) {
            this.runtimeCommand = requireText(builder.runtimeCommand, "runtimeCommand");
            this.image = requireText(builder.image, "image");
            this.containerUser = requireText(builder.containerUser, "containerUser");
            this.workMount = requireText(builder.workMount, "workMount");
            this.runnerCommand = requireText(builder.runnerCommand, "runnerCommand");
            this.probeCommand = requireText(builder.probeCommand, "probeCommand");
            this.taskSpecFile = requireText(builder.taskSpecFile, "taskSpecFile");
            this.eventFile = requireText(builder.eventFile, "eventFile");
            if (builder.cpus <= 0) {
                throw new IllegalArgumentException("cpus must be positive");
            }
            if (builder.probeMemoryBytes <= 0) {
                throw new IllegalArgumentException("probeMemoryBytes must be positive");
            }
            if (builder.commandTimeout == null || builder.commandTimeout.isZero() || builder.commandTimeout.isNegative()) {
                throw new IllegalArgumentException("commandTimeout must be positive");
            }
            this.cpus = builder.cpus;
            this.probeMemoryBytes = builder.probeMemoryBytes;
            this.securityProfile = StringUtils.hasText(builder.securityProfile) ? builder.securityProfile : "hyper-v;job-object";
            this.commandTimeout = builder.commandTimeout;
        }

        static Options fromProperties(SandboxProperties properties) {
            SandboxProperties.WindowsContainer windows = properties.getWindowsContainer();
            return builder()
                    .runtimeCommand(windows.getRuntimeCommand())
                    .image(windows.getImage())
                    .containerUser(windows.getContainerUser())
                    .workMount(windows.getWorkMount())
                    .runnerCommand(windows.getRunnerCommand())
                    .probeCommand(windows.getProbeCommand())
                    .taskSpecFile(windows.getTaskSpecFile())
                    .eventFile(windows.getEventFile())
                    .cpus(windows.getCpus())
                    .probeMemoryBytes(windows.getProbeMemoryBytes())
                    .securityProfile(StringUtils.hasText(properties.getSecurityProfile())
                            ? properties.getSecurityProfile()
                            : "hyper-v;job-object")
                    .commandTimeout(windows.getCommandTimeout())
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

        public String probeCommand() {
            return probeCommand;
        }

        public String taskSpecFile() {
            return taskSpecFile;
        }

        public String eventFile() {
            return eventFile;
        }

        public double cpus() {
            return cpus;
        }

        public long probeMemoryBytes() {
            return probeMemoryBytes;
        }

        public String securityProfile() {
            return securityProfile;
        }

        public Duration commandTimeout() {
            return commandTimeout;
        }

        public static final class Builder {
            private String runtimeCommand = "docker";
            private String image = "cpp-judge-runner-windows:latest";
            private String containerUser = "ContainerUser";
            private String workMount = "C:\\work";
            private String runnerCommand = "C:\\judge-runner\\run-task.exe";
            private String probeCommand = "C:\\judge-runner\\probe.cmd";
            private String taskSpecFile = "sandbox-task.json";
            private String eventFile = "events.jsonl";
            private double cpus = 1.0;
            private long probeMemoryBytes = 268435456L;
            private String securityProfile = "hyper-v;job-object";
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

            public Builder probeCommand(String probeCommand) {
                this.probeCommand = probeCommand;
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

            public Builder cpus(double cpus) {
                this.cpus = cpus;
                return this;
            }

            public Builder probeMemoryBytes(long probeMemoryBytes) {
                this.probeMemoryBytes = probeMemoryBytes;
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

    private record InspectInfo(String isolation, String networkMode, long memoryBytes, int exitCode) {
        static InspectInfo parse(String output) {
            String[] parts = firstLine(output).split("\\|", -1);
            long memory = parseLong(parts.length > 2 ? parts[2] : "0");
            int exitCode = (int) parseLong(parts.length > 3 ? parts[3] : "0");
            return new InspectInfo(
                    normalize(parts.length > 0 ? parts[0] : ""),
                    normalize(parts.length > 1 ? parts[1] : ""),
                    memory,
                    exitCode
            );
        }

        boolean hyperV() {
            return "hyperv".equals(isolation);
        }

        boolean networkDisabled() {
            return "none".equals(networkMode);
        }

        boolean memoryLimited() {
            return memoryBytes > 0;
        }
    }

    private record ProbeInfo(boolean jobObjectEnabled, boolean processTreeKillEnabled, boolean outputLimitEnabled) {
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
                    "enabled".equalsIgnoreCase(values.getOrDefault("PROBE_JOB_OBJECT", "")),
                    "enabled".equalsIgnoreCase(values.getOrDefault("PROBE_PROCESS_TREE_KILL", "")),
                    "enabled".equalsIgnoreCase(values.getOrDefault("PROBE_OUTPUT_LIMIT", ""))
            );
        }

        static ProbeInfo missing() {
            return new ProbeInfo(false, false, false);
        }

        boolean jobObjectProcessTreeEnabled() {
            return jobObjectEnabled && processTreeKillEnabled;
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

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "0" : value.trim());
        } catch (NumberFormatException e) {
            return 0;
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
