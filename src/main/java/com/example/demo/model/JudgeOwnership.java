package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeOwnership {

    private String userId;
    private String sessionId;

    public static JudgeOwnership owner(String userId, String sessionId) {
        return JudgeOwnership.builder()
                .userId(normalize(userId, "anonymous"))
                .sessionId(normalize(sessionId, null))
                .build();
    }

    public static JudgeOwnership anonymous() {
        return owner("anonymous", null);
    }

    public boolean isOwnedBy(String candidateUserId) {
        String normalized = normalize(candidateUserId, null);
        return normalized != null && normalized.equals(userId);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
