package com.example.demo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionRunbookDocumentationTest {

    @Test
    void productionRunbookCoversProfilesValidationRollbackAndSmokeCommands() throws Exception {
        String runbook = readRequired("docs/production-sandbox-runbook.md");

        assertThat(runbook)
                .contains("trusted-local")
                .contains("windows-prod")
                .contains("linux-prod")
                .contains("worker-prod")
                .contains("scripts/smoke/windows-capability-smoke.ps1")
                .contains("scripts/smoke/linux-capability-smoke.sh")
                .contains("scripts/smoke/high-volume-smoke.ps1")
                .contains("scripts/smoke/high-volume-smoke.sh")
                .contains("rollback")
                .contains("default access code")
                .contains("wildcard origin")
                .contains("network disabled")
                .contains("resource limits")
                .contains("judge.execution.max-output-bytes-per-case")
                .contains("JUDGE_EXECUTION_MAX_OUTPUT_BYTES_PER_CASE")
                .contains("path isolation")
                .contains("process-tree kill");
    }

    @Test
    void securityBoundaryStatesWhatIsAndIsNotProductionSafe() throws Exception {
        String boundary = readRequired("docs/security-boundary.md");

        assertThat(boundary)
                .contains("Windows Hyper-V")
                .contains("Job Object-only is not a production boundary")
                .contains("Linux direct process execution is not production safe")
                .contains("cgroup")
                .contains("seccomp")
                .contains("AppArmor")
                .contains("non-root")
                .contains("network none")
                .contains("remote worker")
                .contains("authenticated")
                .contains("WSL smoke is not production release evidence");
    }

    @Test
    void capabilitySmokeScriptsExposeDryRunAndPlatformChecks() throws Exception {
        String windowsSmoke = readRequired("scripts/smoke/windows-capability-smoke.ps1");
        String linuxSmoke = readRequired("scripts/smoke/linux-capability-smoke.sh");

        assertThat(windowsSmoke)
                .contains("SupportsShouldProcess")
                .contains("WindowsHyperVContainerRunnerTest")
                .contains("--isolation hyperv")
                .contains("--network none")
                .contains("Job Object")
                .contains("default access code")
                .contains("wildcard origin");
        assertThat(linuxSmoke)
                .contains("set -euo pipefail")
                .contains("LinuxContainerRunnerTest")
                .contains("--network none")
                .contains("--pids-limit")
                .contains("--read-only")
                .contains("seccomp")
                .contains("AppArmor")
                .contains("default access code")
                .contains("wildcard origin");
    }

    @Test
    void readmeWarnsProductionRequiresStrongSandboxAndAuth() throws Exception {
        String readme = readRequired("README.md");

        assertThat(readme)
                .contains("Production deployment requires strong sandbox and strong authentication")
                .contains("docs/production-sandbox-runbook.md")
                .contains("docs/security-boundary.md");
    }

    private String readRequired(String path) throws Exception {
        Path file = Path.of(path);
        assertThat(file).exists();
        return Files.readString(file);
    }
}
