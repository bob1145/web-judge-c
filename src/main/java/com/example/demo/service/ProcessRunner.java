package com.example.demo.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface ProcessRunner {

    ProcessResult run(Request request) throws IOException, InterruptedException;

    record Request(
            List<String> command,
            Path workingDirectory,
            Path inputFile,
            Path outputFile,
            Duration timeout,
            Duration killGrace,
            long memoryLimitBytes,
            long maxOutputBytes,
            long maxErrorBytes,
            String profile,
            boolean requireSandbox
    ) {

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private List<String> command;
            private Path workingDirectory;
            private Path inputFile;
            private Path outputFile;
            private Duration timeout = Duration.ofSeconds(2);
            private Duration killGrace = Duration.ofSeconds(2);
            private long memoryLimitBytes = 256L * 1024 * 1024;
            private long maxOutputBytes = 1024L * 1024;
            private long maxErrorBytes = 1024L * 1024;
            private String profile = "trusted-local";
            private boolean requireSandbox;

            public Builder command(List<String> command) {
                this.command = command == null ? null : List.copyOf(command);
                return this;
            }

            public Builder workingDirectory(Path workingDirectory) {
                this.workingDirectory = workingDirectory;
                return this;
            }

            public Builder inputFile(Path inputFile) {
                this.inputFile = inputFile;
                return this;
            }

            public Builder outputFile(Path outputFile) {
                this.outputFile = outputFile;
                return this;
            }

            public Builder timeout(Duration timeout) {
                this.timeout = timeout;
                return this;
            }

            public Builder killGrace(Duration killGrace) {
                this.killGrace = killGrace;
                return this;
            }

            public Builder memoryLimitBytes(long memoryLimitBytes) {
                this.memoryLimitBytes = memoryLimitBytes;
                return this;
            }

            public Builder maxOutputBytes(long maxOutputBytes) {
                this.maxOutputBytes = maxOutputBytes;
                return this;
            }

            public Builder maxErrorBytes(long maxErrorBytes) {
                this.maxErrorBytes = maxErrorBytes;
                return this;
            }

            public Builder profile(String profile) {
                this.profile = profile;
                return this;
            }

            public Builder requireSandbox(boolean requireSandbox) {
                this.requireSandbox = requireSandbox;
                return this;
            }

            public Request build() {
                return new Request(
                        command,
                        workingDirectory,
                        inputFile,
                        outputFile,
                        timeout,
                        killGrace,
                        memoryLimitBytes,
                        maxOutputBytes,
                        maxErrorBytes,
                        profile,
                        requireSandbox
                );
            }
        }
    }
}
