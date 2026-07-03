package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SecurityModeStartupValidator;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.service.ProcessResult;
import com.example.demo.service.ProcessRunner;
import com.example.demo.service.SandboxProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ProductionSandboxBaselineTest {

    @TempDir
    Path tempDir;

    @Test
    void currentApplicationDefaultsAreTrustedLocalWithSandboxDisabledAndDefaultAccessCode() throws Exception {
        String applicationYaml = readSource("src", "main", "resources", "application.yml");

        assertThat(applicationYaml)
                .contains("profile: trusted-local")
                .contains("access-code: \"123\"")
                .contains("default-access-code: \"123\"")
                .contains("require-sandbox: false")
                .contains("enabled: false");
        assertThat(applicationYaml)
                .as("default origin is already narrowed; wildcard risk is locked by the trusted-local test below")
                .doesNotContain("allowed-origins: \"*\"");
    }

    @Test
    void trustedLocalModeStillAllowsWildcardOriginWithoutFailingStartup() {
        SecurityModeStartupValidator validator = validator(
                "trusted-local",
                "123",
                "*",
                false,
                false
        );
        WebSocketConfig webSocketConfig = new WebSocketConfig("*");

        assertThatCode(validator::validateNow).doesNotThrowAnyException();
        assertThat(webSocketConfig.hasWildcardOrigin()).isTrue();
        assertThat(webSocketConfig.isOriginAllowed("https://attacker.example")).isTrue();
    }

    @Test
    void windowsOrDisabledSandboxExecutionFallsBackToHostProcessPath() throws Exception {
        String sandboxService = readSource("src", "main", "java", "com", "example", "demo", "service", "SandboxService.java");

        assertThat(sandboxService)
                .contains("boolean isWindows = System.getProperty(\"os.name\").toLowerCase().contains(\"windows\")")
                .contains("if (!sandboxConfig.isEnabled() || isWindows)")
                .contains("return executeDirectly(command, workingDir, inputFile, outputFile, timeLimit)")
                .contains("private SandboxResult executeDirectly")
                .contains("new ProcessBuilder(command)");
    }

    @Test
    void sandboxProcessRunnerFallsBackToDirectRunnerWhenSandboxUnavailableAndSandboxIsNotRequired() throws Exception {
        SandboxConfiguration sandboxConfiguration = new SandboxConfiguration();
        sandboxConfiguration.setEnabled(false);
        AtomicBoolean directRunnerCalled = new AtomicBoolean(false);
        ProcessRunner directRunner = request -> {
            directRunnerCalled.set(true);
            return new ProcessResult(ProcessResult.Status.SUCCESS, "direct", "", 0, 0, 0);
        };
        SandboxProcessRunner runner = new SandboxProcessRunner(sandboxConfiguration, null, directRunner);

        ProcessResult result = runner.run(ProcessRunner.Request.builder()
                .command(List.of("not-executed"))
                .workingDirectory(tempDir)
                .timeout(Duration.ofMillis(10))
                .killGrace(Duration.ofMillis(10))
                .memoryLimitBytes(1024)
                .maxOutputBytes(1024)
                .maxErrorBytes(1024)
                .profile("trusted-local")
                .requireSandbox(false)
                .build());

        assertThat(result.status()).isEqualTo(ProcessResult.Status.SUCCESS);
        assertThat(result.output()).isEqualTo("direct");
        assertThat(directRunnerCalled).isTrue();
    }

    @Test
    void judgeServiceStillOrchestratesCppCompilationAndCaseExecutionInsideWebApplication() throws Exception {
        String judgeService = readSource("src", "main", "java", "com", "example", "demo", "service", "JudgeService.java");

        assertThat(judgeService)
                .contains("CompletableFuture.supplyAsync(() -> compile")
                .contains("private Path compile")
                .contains("\"g++\"")
                .contains("caseBatchRunner.run(")
                .contains("private ProcessResult runProcess")
                .contains(".command(List.of(executable.toAbsolutePath().toString()))")
                .doesNotContain("SandboxRunner");
    }

    private SecurityModeStartupValidator validator(
            String profile,
            String accessCode,
            String allowedOrigins,
            boolean sandboxEnabled,
            boolean requireSandbox
    ) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode(accessCode);
        auth.setDefaultAccessCode("123");

        ExecutionProperties execution = new ExecutionProperties();
        execution.setProfile(profile);
        execution.setRequireSandbox(requireSandbox);

        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(sandboxEnabled);

        return new SecurityModeStartupValidator(auth, execution, sandbox, new WebSocketConfig(allowedOrigins));
    }

    private String readSource(String first, String... more) throws Exception {
        return Files.readString(Path.of(first, more), StandardCharsets.UTF_8);
    }
}
