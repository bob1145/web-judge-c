package com.example.demo.service;

import com.example.demo.config.SandboxConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class SandboxProcessRunner implements ProcessRunner {

    private final SandboxConfiguration sandboxConfiguration;
    private final SandboxService sandboxService;
    private final ProcessRunner directRunner;

    public SandboxProcessRunner(
            SandboxConfiguration sandboxConfiguration,
            SandboxService sandboxService,
            @Qualifier("directProcessRunner") ProcessRunner directRunner
    ) {
        this.sandboxConfiguration = sandboxConfiguration;
        this.sandboxService = sandboxService;
        this.directRunner = directRunner;
    }

    @Override
    public ProcessResult run(Request request) throws IOException, InterruptedException {
        if (!isSandboxAvailable()) {
            if (request.requireSandbox()) {
                return ProcessResult.failure(
                        ProcessResult.Status.SANDBOX_UNAVAILABLE,
                        "Sandbox is required but unavailable for profile " + request.profile()
                );
            }
            return directRunner.run(request);
        }

        SandboxService.SandboxResult result = sandboxService.executeInSandbox(
                request.command().toArray(String[]::new),
                request.workingDirectory(),
                request.inputFile(),
                request.outputFile(),
                request.timeout().toMillis(),
                request.memoryLimitBytes()
        );
        if (result.securityViolation()) {
            return new ProcessResult(
                    ProcessResult.Status.SECURITY_VIOLATION,
                    result.output(),
                    result.violationReason(),
                    result.executionTime(),
                    0,
                    result.exitCode()
            );
        }
        ProcessResult.Status status = result.exitCode() == 0
                ? ProcessResult.Status.SUCCESS
                : ProcessResult.Status.RUNTIME_ERROR;
        return new ProcessResult(status, result.output(), result.error(), result.executionTime(), 0, result.exitCode());
    }

    private boolean isSandboxAvailable() {
        return sandboxConfiguration.isEnabled()
                && sandboxService != null
                && sandboxService.isSandboxAvailable();
    }
}
