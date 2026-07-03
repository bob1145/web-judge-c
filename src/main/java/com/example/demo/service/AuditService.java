package com.example.demo.service;

import com.example.demo.model.UserSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

@Service
@Slf4j
public class AuditService {

    private static final int MAX_EVENTS = 500;
    private static final String REDACTED = "[REDACTED]";

    private final ArrayDeque<AuditEvent> events = new ArrayDeque<>();

    public void record(String type, UserSession session, String judgeId, String provider, Map<String, ?> details) {
        String userId = session == null ? "anonymous" : stableUserId(session);
        record(type, userId, judgeId, provider, details);
    }

    public void record(String type, String userId, String judgeId, String provider, Map<String, ?> details) {
        AuditEvent event = new AuditEvent(
                blankToUnknown(type),
                blankToUnknown(userId),
                blankToUnknown(judgeId),
                blankToUnknown(provider),
                Instant.now(),
                sanitizeDetails(details)
        );
        synchronized (events) {
            events.addLast(event);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
        }
        log.info("audit type={} userId={} judgeId={} provider={} details={}",
                event.type(), event.userId(), event.judgeId(), event.provider(), event.details());
    }

    public List<AuditEvent> recentEvents() {
        return recentEvents(MAX_EVENTS);
    }

    public List<AuditEvent> recentEvents(int limit) {
        int safeLimit = Math.max(0, limit);
        synchronized (events) {
            List<AuditEvent> snapshot = new ArrayList<>(events);
            int fromIndex = Math.max(0, snapshot.size() - safeLimit);
            return List.copyOf(snapshot.subList(fromIndex, snapshot.size()));
        }
    }

    public void clear() {
        synchronized (events) {
            events.clear();
        }
    }

    private Map<String, Object> sanitizeDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : details.entrySet()) {
            String key = entry.getKey() == null ? "unknown" : entry.getKey();
            sanitized.put(key, sanitizeValue(key, entry.getValue()));
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = entry.getKey() == null ? "unknown" : entry.getKey().toString();
                sanitized.put(childKey, sanitizeValue(childKey, entry.getValue()));
            }
            return Collections.unmodifiableMap(sanitized);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : iterable) {
                sanitized.add(sanitizeValue(key, item));
                if (sanitized.size() >= 20) {
                    sanitized.add("...");
                    break;
                }
            }
            return List.copyOf(sanitized);
        }
        if (value instanceof CharSequence text) {
            return sanitizeText(text.toString());
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return sanitizeText(value.toString());
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("source")
                || normalized.endsWith("code");
    }

    private String sanitizeText(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("password")
                || lower.contains("secret")
                || lower.contains("token")
                || lower.contains("credential")) {
            return REDACTED;
        }
        if (value.length() <= 256) {
            return value;
        }
        return value.substring(0, 256) + "...";
    }

    private String stableUserId(UserSession session) {
        if (session.getUserId() != null && !session.getUserId().isBlank()) {
            return session.getUserId().trim();
        }
        return session.getSessionId();
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    public record AuditEvent(
            String type,
            String userId,
            String judgeId,
            String provider,
            Instant timestamp,
            Map<String, Object> details
    ) {
    }
}
