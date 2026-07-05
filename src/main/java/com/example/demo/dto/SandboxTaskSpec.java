package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SandboxTaskSpec(
        String judgeId,
        String userId,
        String profile,
        String workDir,
        Map<SourceRole, String> sourcePaths,
        int testCases,
        Duration caseTimeLimit,
        Duration maxTaskRuntime,
        long memoryLimitBytes,
        long maxOutputBytesPerCase,
        boolean stopOnFirstNonAc,
        Retention retention
) {

    public SandboxTaskSpec {
        requireText(judgeId, "judgeId");
        requireText(userId, "userId");
        requireText(profile, "profile");
        requireText(workDir, "workDir");
        if (testCases <= 0) {
            throw new IllegalArgumentException("testCases must be positive");
        }
        requirePositiveDuration(caseTimeLimit, "caseTimeLimit");
        requirePositiveDuration(maxTaskRuntime, "maxTaskRuntime");
        if (memoryLimitBytes <= 0) {
            throw new IllegalArgumentException("memoryLimitBytes must be positive");
        }
        if (maxOutputBytesPerCase <= 0) {
            throw new IllegalArgumentException("maxOutputBytesPerCase must be positive");
        }
        sourcePaths = sourcePaths == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sourcePaths));
        retention = retention == null
                ? Retention.defaultRetention()
                : retention;
    }

    public enum SourceRole {
        GENERATOR,
        USER,
        ORACLE,
        SPECIAL_JUDGE
    }

    public record Retention(
            Duration completed,
            Duration failed,
            Duration cancelled
    ) {
        public Retention {
            requirePositiveDuration(completed, "retention.completed");
            requirePositiveDuration(failed, "retention.failed");
            requirePositiveDuration(cancelled, "retention.cancelled");
        }

        public static Retention defaultRetention() {
            return new Retention(Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(24));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String judgeId;
        private String userId;
        private String profile = "trusted-local";
        private Path storageBase;
        private Path workDir;
        private final Map<SourceRole, Path> sourcePaths = new LinkedHashMap<>();
        private int testCases;
        private Duration caseTimeLimit;
        private Duration maxTaskRuntime = Duration.ofMinutes(30);
        private long memoryLimitBytes;
        private long maxOutputBytesPerCase;
        private boolean stopOnFirstNonAc;
        private Retention retention = Retention.defaultRetention();

        public Builder judgeId(String judgeId) {
            this.judgeId = judgeId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder profile(String profile) {
            this.profile = profile;
            return this;
        }

        public Builder storageBase(Path storageBase) {
            this.storageBase = storageBase;
            return this;
        }

        public Builder workDir(Path workDir) {
            this.workDir = workDir;
            return this;
        }

        public Builder sourcePath(SourceRole role, Path sourcePath) {
            if (role == null) {
                throw new IllegalArgumentException("source role is required");
            }
            if (sourcePath == null) {
                sourcePaths.remove(role);
            } else {
                sourcePaths.put(role, sourcePath);
            }
            return this;
        }

        public Builder testCases(int testCases) {
            this.testCases = testCases;
            return this;
        }

        public Builder caseTimeLimit(Duration caseTimeLimit) {
            this.caseTimeLimit = caseTimeLimit;
            return this;
        }

        public Builder maxTaskRuntime(Duration maxTaskRuntime) {
            this.maxTaskRuntime = maxTaskRuntime;
            return this;
        }

        public Builder memoryLimitBytes(long memoryLimitBytes) {
            this.memoryLimitBytes = memoryLimitBytes;
            return this;
        }

        public Builder maxOutputBytesPerCase(long maxOutputBytesPerCase) {
            this.maxOutputBytesPerCase = maxOutputBytesPerCase;
            return this;
        }

        public Builder stopOnFirstNonAc(boolean stopOnFirstNonAc) {
            this.stopOnFirstNonAc = stopOnFirstNonAc;
            return this;
        }

        public Builder retention(Retention retention) {
            this.retention = retention;
            return this;
        }

        public SandboxTaskSpec build() {
            String normalizedWorkDir = normalizeRequiredPath(workDir, storageBase, "workDir");
            Map<SourceRole, String> normalizedSources = new LinkedHashMap<>();
            for (Map.Entry<SourceRole, Path> source : sourcePaths.entrySet()) {
                normalizedSources.put(
                        source.getKey(),
                        normalizeRequiredPath(source.getValue(), storageBase, "sourcePaths." + source.getKey())
                );
            }
            return new SandboxTaskSpec(
                    judgeId,
                    userId,
                    profile,
                    normalizedWorkDir,
                    normalizedSources,
                    testCases,
                    caseTimeLimit,
                    maxTaskRuntime,
                    memoryLimitBytes,
                    maxOutputBytesPerCase,
                    stopOnFirstNonAc,
                    retention
            );
        }
    }

    private static String normalizeRequiredPath(Path path, Path storageBase, String fieldName) {
        if (path == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(fieldName + " must be absolute");
        }
        Path normalized = path.normalize();
        if (storageBase != null) {
            Path normalizedBase = storageBase.toAbsolutePath().normalize();
            if (!normalized.startsWith(normalizedBase)) {
                throw new IllegalArgumentException(fieldName + " must stay inside storage base");
            }
        }
        return normalized.toString();
    }

    private static void requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static void requirePositiveDuration(Duration value, String fieldName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
