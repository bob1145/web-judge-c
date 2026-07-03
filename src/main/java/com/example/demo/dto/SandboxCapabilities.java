package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SandboxCapabilities(
        String provider,
        String isolation,
        boolean productionSafe,
        boolean networkDisabled,
        boolean nonRoot,
        boolean resourceLimits,
        String securityProfile,
        String details,
        String skipReason
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String provider;
        private String isolation;
        private boolean productionSafe;
        private boolean networkDisabled;
        private boolean nonRoot;
        private boolean resourceLimits;
        private String securityProfile;
        private String details;
        private String skipReason;

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder isolation(String isolation) {
            this.isolation = isolation;
            return this;
        }

        public Builder productionSafe(boolean productionSafe) {
            this.productionSafe = productionSafe;
            return this;
        }

        public Builder networkDisabled(boolean networkDisabled) {
            this.networkDisabled = networkDisabled;
            return this;
        }

        public Builder nonRoot(boolean nonRoot) {
            this.nonRoot = nonRoot;
            return this;
        }

        public Builder resourceLimits(boolean resourceLimits) {
            this.resourceLimits = resourceLimits;
            return this;
        }

        public Builder securityProfile(String securityProfile) {
            this.securityProfile = securityProfile;
            return this;
        }

        public Builder details(String details) {
            this.details = details;
            return this;
        }

        public Builder skipReason(String skipReason) {
            this.skipReason = skipReason;
            return this;
        }

        public SandboxCapabilities build() {
            return new SandboxCapabilities(
                    provider,
                    isolation,
                    productionSafe,
                    networkDisabled,
                    nonRoot,
                    resourceLimits,
                    securityProfile,
                    details,
                    skipReason
            );
        }
    }
}
