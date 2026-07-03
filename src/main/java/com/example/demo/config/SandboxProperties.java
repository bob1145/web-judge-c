package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "judge.sandbox.production")
public class SandboxProperties {

    private Provider provider = Provider.DIRECT;
    private Isolation isolation = Isolation.PROCESS;
    private String securityProfile = "";
    private boolean capabilityProbeRequired = false;
    private boolean capabilityProbePassed = false;
    private Worker worker = new Worker();
    private LinuxContainer linuxContainer = new LinuxContainer();

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
        private String mtlsCertificate = "";

        public boolean hasEndpoint() {
            return StringUtils.hasText(endpoint);
        }

        public boolean hasAuthenticatedChannel() {
            return StringUtils.hasText(authToken) || StringUtils.hasText(mtlsCertificate);
        }
    }

    @Data
    public static class LinuxContainer {
        private String runtimeCommand = "docker";
        private String image = "cpp-judge-runner:latest";
        private String containerUser = "65532:65532";
        private String workMount = "/work";
        private String runnerCommand = "/opt/judge-runner/run-task";
        private String taskSpecFile = "sandbox-task.json";
        private String eventFile = "events.jsonl";
        private int pidsLimit = 64;
        private double cpus = 1.0;
        private Duration commandTimeout = Duration.ofSeconds(30);
    }
}
