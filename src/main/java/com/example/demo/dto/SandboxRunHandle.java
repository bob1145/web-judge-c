package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SandboxRunHandle(
        String judgeId,
        String runId,
        String provider,
        Instant startedAt,
        String eventCursor,
        int protocolVersion,
        String attemptId
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String judgeId;
        private String runId;
        private String provider;
        private Instant startedAt = Instant.now();
        private String eventCursor;
        private int protocolVersion;
        private String attemptId;

        public Builder judgeId(String judgeId) {
            this.judgeId = judgeId;
            return this;
        }

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder eventCursor(String eventCursor) {
            this.eventCursor = eventCursor;
            return this;
        }

        public Builder protocolVersion(int protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }

        public Builder attemptId(String attemptId) {
            this.attemptId = attemptId;
            return this;
        }

        public SandboxRunHandle build() {
            return new SandboxRunHandle(judgeId, runId, provider, startedAt, eventCursor, protocolVersion, attemptId);
        }
    }
}
