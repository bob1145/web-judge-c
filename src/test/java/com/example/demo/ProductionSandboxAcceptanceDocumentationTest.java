package com.example.demo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSandboxAcceptanceDocumentationTest {

    @Test
    void acceptanceDocumentMapsEveryRequirementToEvidence() throws Exception {
        String acceptance = readRequired("docs/production-sandbox-acceptance.md");

        assertThat(acceptance)
                .contains("Release status: BLOCKED until live platform smoke passes on the deployment target")
                .contains("Requirement 1")
                .contains("Requirement 2")
                .contains("Requirement 3")
                .contains("Requirement 4")
                .contains("Requirement 5")
                .contains("Requirement 6")
                .contains("Requirement 7")
                .contains("Requirement 8")
                .contains("Requirement 9")
                .contains("Requirement 10");

        assertThat(acceptance)
                .contains("ProductionSecurityStartupValidatorTest")
                .contains("SandboxRunnerContractTest")
                .contains("JudgeSandboxOrchestrationTest")
                .contains("WindowsHyperVContainerRunnerTest")
                .contains("LinuxContainerRunnerTest")
                .contains("RemoteWorkerRunnerTest")
                .contains("ProductionHighVolumeIntegrationTest")
                .contains("QuotaAndAuthorizationTest")
                .contains("ProductionAuthenticationTest")
                .contains("JudgeFileServiceProductionTest")
                .contains("AuditAndAdminTest")
                .contains("ProductionCleanupTest")
                .contains("ProductionRunbookDocumentationTest");
    }

    @Test
    void acceptanceDocumentListsReleaseCommandsAndKnownBlockers() throws Exception {
        String acceptance = readRequired("docs/production-sandbox-acceptance.md");

        assertThat(acceptance)
                .contains(".\\mvnw.cmd test")
                .contains(".\\mvnw.cmd \"-Dtest=ProductionHighVolumeIntegrationTest\" test")
                .contains("scripts/smoke/windows-capability-smoke.ps1")
                .contains("scripts/smoke/linux-capability-smoke.sh")
                .contains("scripts/smoke/high-volume-smoke.ps1 -Cases 100000")
                .contains("scripts/smoke/high-volume-smoke.sh --cases 100000")
                .contains("default credentials rejected")
                .contains("wildcard origin rejected")
                .contains("direct runner is rejected in production profiles")
                .contains("WSL precheck is not production release evidence")
                .contains("Docker CLI unavailable on this Windows workstation");
    }

    private String readRequired(String path) throws Exception {
        Path file = Path.of(path);
        assertThat(file).exists();
        return Files.readString(file);
    }
}
