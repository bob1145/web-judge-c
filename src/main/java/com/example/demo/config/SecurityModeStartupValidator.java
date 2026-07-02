package com.example.demo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
@RequiredArgsConstructor
@Slf4j
public class SecurityModeStartupValidator implements SmartInitializingSingleton {

    private static final String TRUSTED_LOCAL = "trusted-local";
    private static final String INTRANET_LARGE = "intranet-large";
    private static final String PUBLIC_DISABLED = "public-disabled";

    private final AuthConfiguration authConfiguration;
    private final ExecutionProperties executionProperties;
    private final SandboxConfiguration sandboxConfiguration;
    private final WebSocketConfig webSocketConfig;

    @Override
    public void afterSingletonsInstantiated() {
        validateNow();
    }

    public void validateNow() {
        String profile = normalizedProfile();
        List<String> risks = currentRisks();

        if (TRUSTED_LOCAL.equals(profile)) {
            risks.forEach(risk -> log.warn("HIGH RISK trusted-local configuration: {}", risk));
            return;
        }

        if (INTRANET_LARGE.equals(profile)) {
            if (!risks.isEmpty()) {
                throw new IllegalStateException("Insecure intranet-large configuration: " + String.join("; ", risks));
            }
            return;
        }

        if (executionProperties.isRequireSandbox() && !sandboxConfiguration.isEnabled()) {
            throw new IllegalStateException("Insecure " + profile + " configuration: sandbox is disabled while required");
        }
    }

    public void assertJudgeCreationAllowed() {
        if (PUBLIC_DISABLED.equals(normalizedProfile())) {
            throw new PublicJudgeDisabledException("Judge creation is disabled in public-disabled profile");
        }
    }

    private List<String> currentRisks() {
        List<String> risks = new ArrayList<>();
        if (usesDefaultAccessCode()) {
            risks.add("default access code");
        }
        if (webSocketConfig.hasWildcardOrigin()) {
            risks.add("wildcard WebSocket origin");
        }
        if (!sandboxConfiguration.isEnabled()) {
            risks.add("sandbox is disabled");
        }
        if (executionProperties.isRequireSandbox() && !sandboxConfiguration.isEnabled()) {
            risks.add("sandbox is disabled while required");
        }
        return risks;
    }

    private boolean usesDefaultAccessCode() {
        return Objects.equals(
                valueOrEmpty(authConfiguration.getAccessCode()),
                valueOrEmpty(authConfiguration.getDefaultAccessCode())
        );
    }

    private String normalizedProfile() {
        return valueOrEmpty(executionProperties.getProfile()).toLowerCase(Locale.ROOT);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static class PublicJudgeDisabledException extends IllegalStateException {
        public PublicJudgeDisabledException(String message) {
            super(message);
        }
    }
}
