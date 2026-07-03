package com.example.demo.model;

import com.example.demo.config.AuthConfiguration;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAccount {

    private String userId;
    private String username;
    private String passwordHash;
    private boolean admin;
    private boolean enabled;

    public static UserAccount from(AuthConfiguration.AccountProperties properties) {
        return UserAccount.builder()
                .userId(normalize(properties.getUserId(), properties.getUsername()))
                .username(normalize(properties.getUsername(), null))
                .passwordHash(normalize(properties.getPasswordHash(), null))
                .admin(properties.isAdmin())
                .enabled(properties.isEnabled())
                .build();
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
