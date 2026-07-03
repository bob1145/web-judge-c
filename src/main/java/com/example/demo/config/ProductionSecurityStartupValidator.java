package com.example.demo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductionSecurityStartupValidator implements SmartInitializingSingleton {

    private static final String TRUSTED_LOCAL = "trusted-local";
    private static final String WINDOWS_PROD = "windows-prod";
    private static final String LINUX_PROD = "linux-prod";
    private static final String WORKER_PROD = "worker-prod";

    private final AuthConfiguration authConfiguration;
    private final ExecutionProperties executionProperties;
    private final SandboxConfiguration sandboxConfiguration;
    private final SandboxProperties sandboxProperties;
    private final WebSocketConfig webSocketConfig;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void afterSingletonsInstantiated() {
        validateNow();
    }

    public void validateNow() {
        String profile = normalizedProfile();
        List<String> risks = baseRisks();

        if (TRUSTED_LOCAL.equals(profile)) {
            risks.forEach(risk -> log.warn("HIGH RISK trusted-local configuration: {}", risk));
            return;
        }
        risks.addAll(authenticationRisks());

        switch (profile) {
            case WINDOWS_PROD -> validateWindowsProd(risks);
            case LINUX_PROD -> validateLinuxProd(risks);
            case WORKER_PROD -> validateWorkerProd(risks);
            default -> {
                if (executionProperties.isRequireSandbox() && !sandboxConfiguration.isEnabled()) {
                    throw new IllegalStateException("Insecure " + profile + " configuration: sandbox is disabled while required");
                }
            }
        }

        if (!risks.isEmpty()) {
            throw new IllegalStateException("Insecure " + profile + " configuration: " + String.join("; ", risks));
        }
    }

    private void validateWindowsProd(List<String> risks) {
        requireProvider(SandboxProperties.Provider.WINDOWS_CONTAINER, "windows-container", risks);
        if (sandboxProperties.getIsolation() != SandboxProperties.Isolation.HYPER_V) {
            risks.add("isolation must be hyper-v");
        }
    }

    private void validateLinuxProd(List<String> risks) {
        requireProvider(SandboxProperties.Provider.LINUX_CONTAINER, "linux-container", risks);
        if (sandboxProperties.getIsolation() != SandboxProperties.Isolation.CONTAINER) {
            risks.add("isolation must be container");
        }
        if (!StringUtils.hasText(sandboxProperties.getSecurityProfile())) {
            risks.add("security profile is required");
        }
    }

    private void validateWorkerProd(List<String> risks) {
        requireProvider(SandboxProperties.Provider.REMOTE_WORKER, "remote-worker", risks);
        SandboxProperties.Worker worker = sandboxProperties.getWorker();
        if (worker == null || !worker.hasEndpoint()) {
            risks.add("worker endpoint is required");
        }
        if (worker == null || !worker.hasAuthenticatedChannel()) {
            risks.add("worker authentication is required");
        }
    }

    private List<String> baseRisks() {
        List<String> risks = new ArrayList<>();
        if (usesDefaultAccessCode()) {
            risks.add("default access code");
        }
        if (webSocketConfig.hasWildcardOrigin()) {
            risks.add("wildcard WebSocket origin");
        }
        if (!executionProperties.isRequireSandbox()) {
            risks.add("sandbox.required must be true");
        }
        if (!sandboxConfiguration.isEnabled()) {
            risks.add("sandbox is disabled");
        }
        if (sandboxProperties.getProvider() == SandboxProperties.Provider.DIRECT) {
            risks.add("direct sandbox provider is not allowed");
        }
        if (sandboxProperties.isCapabilityProbeRequired() && !sandboxProperties.isCapabilityProbePassed()) {
            risks.add("sandbox capability probe has not passed");
        }
        return risks;
    }

    private List<String> authenticationRisks() {
        List<String> risks = new ArrayList<>();
        if (!authConfiguration.isAccountAuthEnabled() || authConfiguration.getAccounts().isEmpty()) {
            risks.add("production account authentication is required");
            return risks;
        }
        for (AuthConfiguration.AccountProperties account : authConfiguration.getAccounts()) {
            if (!StringUtils.hasText(account.getUserId()) || !StringUtils.hasText(account.getUsername())) {
                risks.add("account userId and username are required");
            }
            String hash = account.getPasswordHash();
            if (!StringUtils.hasText(hash) || !hash.startsWith("$2")) {
                risks.add("account password hash must use BCrypt");
                continue;
            }
            if (account.isAdmin() && matchesSamplePassword(hash)) {
                risks.add("sample admin password is not allowed");
            }
        }
        return risks;
    }

    private boolean matchesSamplePassword(String hash) {
        return passwordEncoder.matches("admin", hash)
                || passwordEncoder.matches("password", hash)
                || passwordEncoder.matches("123456", hash)
                || passwordEncoder.matches(valueOrEmpty(authConfiguration.getDefaultAccessCode()), hash);
    }

    private void requireProvider(SandboxProperties.Provider expected, String label, List<String> risks) {
        if (sandboxProperties.getProvider() != expected) {
            risks.add("provider must be " + label);
        }
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
}
