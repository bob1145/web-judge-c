package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "judge.sandbox.production")
public class SandboxProperties {

    public static final int DEFAULT_EVENT_BATCH_LIMIT_BYTES = 256 * 1024;
    public static final int DEFAULT_EVENT_BATCH_LIMIT_COUNT = 256;
    public static final int DEFAULT_EVENT_LINE_LIMIT_BYTES = 128 * 1024;

    private Provider provider = Provider.DIRECT;
    private Isolation isolation = Isolation.PROCESS;
    private String securityProfile = "";
    private boolean capabilityProbeRequired = false;
    private boolean capabilityProbePassed = false;
    private int eventBatchLimitBytes = DEFAULT_EVENT_BATCH_LIMIT_BYTES;
    private int eventBatchLimitCount = DEFAULT_EVENT_BATCH_LIMIT_COUNT;
    private int eventLineLimitBytes = DEFAULT_EVENT_LINE_LIMIT_BYTES;
    private Worker worker = new Worker();
    private LinuxContainer linuxContainer = new LinuxContainer();
    private WindowsContainer windowsContainer = new WindowsContainer();

    public enum Provider {
        DIRECT,
        WINDOWS_CONTAINER,
        LINUX_CONTAINER,
        REMOTE_WORKER
    }

    public enum Isolation {
        PROCESS,
        HYPER_V,
        CONTAINER,
        REMOTE
    }

    @Data
    public static class Worker {
        private String endpoint = "";
        private String authToken = "";
        private String signingSecret = "";
        private String mtlsCertificate = "";

        public boolean hasEndpoint() {
            return StringUtils.hasText(endpoint);
        }

        public boolean hasAuthenticatedChannel() {
            return WorkerProperties.supportsAuthentication(authToken, signingSecret, mtlsCertificate);
        }
    }

    @Data
    public static class LinuxContainer {
        private LinuxSupervisor supervisor;
        private String runtimeCommand = "docker";
        private String image = "cpp-judge-runner:latest";
        private String containerUser = "65532:65532";
        private String workMount = "/work";
        private String runnerCommand = "/opt/judge-runner/run-task";
        private String taskSpecFile = "task-spec.json";
        private String eventFile = "runner-events.jsonl";
        private int pidsLimit = 64;
        private double cpus = 1.0;
        private Duration commandTimeout = Duration.ofSeconds(30);
    }

    @Data
    public static class LinuxSupervisor {
        private String hostRuntimeCommand;
        private String hostRuntimeEndpoint;
        private String supervisorRuntimeCommand;
        private String supervisorRuntimeEndpoint;
        private String supervisorImageId;
        private String executorImageId;
        private String workMount;
        private Integer configWriterUid;
        private Integer configWriterGid;
        private Integer supervisorUid;
        private Integer supervisorGid;
        private Integer holderUid;
        private Integer holderGid;
        private Integer workloadUid;
        private Integer workloadGid;
        private ResourceLimits supervisorLimits;
        private ResourceLimits holderLimits;
        private Duration holderLifetime;
        private Integer stagePidsLimit;
        private Double stageCpus;
        private Long inputLimitBytes;
        private Long artifactLimitBytes;
        private Long volumeLimitBytes;
        private Long controlOutputLimitBytes;
        private Duration commandTimeout;
        private Duration stageCommandTimeout;

        @Data
        public static class ResourceLimits {
            private Long memoryBytes;
            private Integer pidsLimit;
            private Double cpus;
        }
    }

    @Data
    public static class WindowsContainer {
        private String runtimeCommand = "docker";
        private String image = "cpp-judge-runner-windows:latest";
        private String containerUser = "ContainerUser";
        private String workMount = "C:\\work";
        private String runnerCommand = "C:\\judge-runner\\run-task.exe";
        private String probeCommand = "C:\\judge-runner\\probe.cmd";
        private String taskSpecFile = "task-spec.json";
        private String eventFile = "runner-events.jsonl";
        private double cpus = 1.0;
        private long probeMemoryBytes = 268435456L;
        private Duration commandTimeout = Duration.ofSeconds(30);
    }
}
