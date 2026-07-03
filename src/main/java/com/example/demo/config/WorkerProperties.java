package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

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
        return StringUtils.hasText(authToken)
                || StringUtils.hasText(signingSecret)
                || StringUtils.hasText(mtlsCertificate);
    }
}
