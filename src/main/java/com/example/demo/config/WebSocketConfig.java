package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.websocket.allowed-origins:https://yourdomain.com,http://localhost:*}")
    private String allowedOrigins;

    public WebSocketConfig() {
    }

    public WebSocketConfig(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(getAllowedOriginPatterns())
                .withSockJS();
    }

    public String[] getAllowedOriginPatterns() {
        return parseAllowedOriginPatterns(allowedOrigins);
    }

    public boolean hasWildcardOrigin() {
        return Arrays.stream(getAllowedOriginPatterns())
                .anyMatch("*"::equals);
    }

    public boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        return Arrays.stream(getAllowedOriginPatterns())
                .anyMatch(pattern -> PatternMatchUtils.simpleMatch(pattern, origin));
    }

    public static String[] parseAllowedOriginPatterns(String configuredOrigins) {
        if (configuredOrigins == null || configuredOrigins.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }
}
