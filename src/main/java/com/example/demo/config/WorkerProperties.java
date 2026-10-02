package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "judge.worker")
public class WorkerProperties {

    private String endpoint = "";
    private String authToken = "";
    private String signingSecret = "";
    private String mtlsCertificate = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(30);

    public boolean hasEndpoint() {
        return StringUtils.hasText(endpoint);
    }

    public boolean hasAuthenticatedChannel() {
        return supportsAuthentication(authToken, signingSecret, mtlsCertificate);
    }

    public static boolean supportsAuthentication(String authToken, String signingSecret, String mtlsCertificate) {
        return !StringUtils.hasText(mtlsCertificate)
                && (StringUtils.hasText(authToken) || StringUtils.hasText(signingSecret));
    }

    public WorkerProperties withFallback(SandboxProperties.Worker fallback) {
        SandboxProperties.Worker nested = fallback == null ? new SandboxProperties.Worker() : fallback;
        WorkerProperties merged = new WorkerProperties();
        merged.setEndpoint(merge(endpoint, nested.getEndpoint(), "endpoint"));
        merged.setAuthToken(merge(authToken, nested.getAuthToken(), "authentication"));
        merged.setSigningSecret(merge(signingSecret, nested.getSigningSecret(), "signing"));
        merged.setMtlsCertificate(merge(mtlsCertificate, nested.getMtlsCertificate(), "mTLS"));
        merged.setConnectTimeout(connectTimeout);
        merged.setRequestTimeout(requestTimeout);
        return merged;
    }

    public URI validateAndGetEndpoint() {
        if (!hasEndpoint()) {
            throw new IllegalArgumentException("worker endpoint is required"
                    + (!hasAuthenticatedChannel() ? "; worker authentication is required" : ""));
        }
        if (StringUtils.hasText(mtlsCertificate)) {
            throw new IllegalArgumentException("worker mTLS is not implemented; certificate configuration is unsupported");
        }
        if (!hasAuthenticatedChannel()) {
            throw new IllegalArgumentException("worker authentication is required: an authenticated channel needs Bearer or HMAC");
        }
        if (authToken != null && authToken.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("worker authentication contains unsupported control characters");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        URI address;
        try {
            address = URI.create(endpoint.trim());
        } catch (IllegalArgumentException exception) {
            throw invalidEndpoint();
        }
        if (!"https".equalsIgnoreCase(address.getScheme()) || address.getHost() == null
                || address.getRawUserInfo() != null || address.getRawQuery() != null || address.getRawFragment() != null
                || address.getPort() != -1 && (address.getPort() < 1 || address.getPort() > 65535)) {
            throw invalidEndpoint();
        }
        return address;
    }

    private static IllegalArgumentException invalidEndpoint() {
        return new IllegalArgumentException("worker endpoint must be an absolute HTTPS URL without credentials, query or fragment");
    }

    private String merge(String primary, String fallback, String field) {
        String preferred = primary == null ? "" : primary.trim();
        String secondary = fallback == null ? "" : fallback.trim();
        if (!preferred.isEmpty() && !secondary.isEmpty() && !preferred.equals(secondary)) {
            throw new IllegalArgumentException("conflicting worker " + field + " configuration");
        }
        return preferred.isEmpty() ? secondary : preferred;
    }
}
