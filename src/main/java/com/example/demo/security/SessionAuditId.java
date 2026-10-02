package com.example.demo.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SessionAuditId {

    private SessionAuditId() {
    }

    public static String from(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "session-unknown";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return "session-sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Session audit digest unavailable", exception);
        }
    }
}
