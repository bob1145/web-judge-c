package com.example.demo.service;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.model.AuthStatus;
import com.example.demo.model.UserSession;
import com.example.demo.model.UserAccount;
import com.example.demo.security.SessionAuditId;
import com.example.demo.security.WebSocketSessionGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 校验码服务
 * 负责校验码验证、会话管理等认证相关功能
 */
@Service
@Slf4j
public class AccessCodeService {
    
    private final AuthConfiguration authConfig;
    private final UserAccountService userAccountService;
    private final Clock clock;
    private final Object attemptCapacityLock = new Object();
    private final Object globalAttemptLock = new Object();
    private AttemptRecord globalAttempts;
    private int globalInFlight;
    @Autowired
    private ObjectProvider<WebSocketSessionGuard> webSocketSessionGuard;

    public AccessCodeService(AuthConfiguration authConfig) {
        this(authConfig, null);
    }

    @Autowired
    public AccessCodeService(AuthConfiguration authConfig, UserAccountService userAccountService) {
        this(authConfig, userAccountService, Clock.systemDefaultZone());
    }

    public AccessCodeService(AuthConfiguration authConfig, UserAccountService userAccountService, Clock clock) {
        this.authConfig = authConfig;
        this.userAccountService = userAccountService;
        this.clock = clock;
        this.globalAttempts = new AttemptRecord(0, clock.instant());
    }
    
    /**
     * 存储活跃会话的内存映射
     * Key: sessionId, Value: UserSession
     */
    private final Map<String, UserSession> activeSessions = new ConcurrentHashMap<>();
    
    /**
     * 存储IP地址的失败次数与最后尝试时间
     * Key: IP地址, Value: 尝试记录
     */
    private final Map<String, AttemptRecord> attempts = new ConcurrentHashMap<>();
    
    /**
     * 验证校验码
     * 
     * @param code 用户输入的校验码
     * @param ipAddress 用户IP地址
     * @return 验证结果状态
     */
    public AuthStatus validateAccessCode(String code, String ipAddress) {
        log.debug("验证校验码，IP: {}", ipAddress);
        String sourceKey = ipAddress == null ? "<unknown>" : ipAddress;
        synchronized (attemptCapacityLock) {
            if (!attempts.containsKey(sourceKey) && attempts.size() >= authConfig.getMaxAttemptStates()) {
                return AuthStatus.TOO_MANY_ATTEMPTS;
            }
            attempts.putIfAbsent(sourceKey, new AttemptRecord(0, clock.instant()));
        }
        AtomicReference<AuthStatus> result = new AtomicReference<>();
        attempts.computeIfPresent(sourceKey, (storedKey, storedRecord) -> {
            AttemptRecord record = resetExpired(storedRecord, Duration.ofHours(authConfig.getAttemptResetHours()));
            if (record.failures() >= authConfig.getMaxAttempts()) {
                log.warn("IP {} 超过最大尝试次数限制", ipAddress);
                result.set(AuthStatus.TOO_MANY_ATTEMPTS);
                return record;
            }
            synchronized (globalAttemptLock) {
                globalAttempts = resetExpired(globalAttempts, authConfig.getGlobalAccessCodeAttemptWindow());
                if ((long) globalAttempts.failures() + globalInFlight >= authConfig.getMaxGlobalAccessCodeAttempts()) {
                    result.set(AuthStatus.TOO_MANY_ATTEMPTS);
                    return record;
                }
                globalInFlight++;
            }
            try {
                if (code == null || code.trim().isEmpty()) {
                    result.set(AuthStatus.EMPTY_CODE);
                    return recordFailedAttempt(record);
                }
                boolean isValid = authConfig.isUsableAccessCode() && authConfig.getAccessCode().trim().equals(code.trim());
                if (isValid) {
                    log.info("校验码验证成功，IP: {}", ipAddress);
                    result.set(AuthStatus.SUCCESS);
                    return new AttemptRecord(0, clock.instant());
                }
                log.warn("校验码验证失败，IP: {}", ipAddress);
                result.set(AuthStatus.INVALID_CODE);
                return recordFailedAttempt(record);
            } finally {
                synchronized (globalAttemptLock) {
                    globalInFlight--;
                    if (result.get() == AuthStatus.INVALID_CODE || result.get() == AuthStatus.EMPTY_CODE) {
                        globalAttempts = recordFailedAttempt(resetExpired(globalAttempts, authConfig.getGlobalAccessCodeAttemptWindow()));
                    }
                }
            }
        });
        return result.get() == null ? AuthStatus.TOO_MANY_ATTEMPTS : result.get();
    }
    
    /**
     * 创建用户会话
     * 
     * @param rememberMe 是否记住用户30天
     * @param ipAddress 用户IP地址
     * @param userAgent 用户代理信息
     * @return 创建的会话对象
     */
    public UserSession createSession(boolean rememberMe, String ipAddress, String userAgent) {
        return createSession(rememberMe, ipAddress, userAgent, null, false);
    }

    public UserSession createSession(boolean rememberMe, String ipAddress, String userAgent, String userId, boolean admin) {
        return createSession(rememberMe, ipAddress, userAgent, userId, admin, null);
    }

    public UserSession createSession(boolean rememberMe, String ipAddress, String userAgent, UserAccount account) {
        if (userAccountService == null || account == null || account.getUsername() == null
                || account.getUsername().isBlank()) {
            throw new IllegalStateException("Account session validation is unavailable");
        }
        return createSession(rememberMe, ipAddress, userAgent, account.getUserId(), account.isAdmin(), account.getUsername());
    }

    private UserSession createSession(boolean rememberMe, String ipAddress, String userAgent, String userId,
                                      boolean admin, String accountUsername) {
        String sessionId = generateSessionId();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiresAt = rememberMe ? 
            now.plus(authConfig.getSessionTimeout()) : 
            now.plus(authConfig.getNormalSessionTimeout());
        String stableUserId = userId == null || userId.isBlank() ? sessionId : userId.trim();
        
        UserSession session = UserSession.builder()
                .sessionId(sessionId)
                .userId(stableUserId)
                .accountUsername(accountUsername)
                .admin(admin)
                .createdAt(now)
                .expiresAt(expiresAt)
                .rememberMe(rememberMe)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build();
        
        synchronized (activeSessions) {
            if (activeSessions.size() >= authConfig.getMaxActiveSessions()) {
                activeSessions.entrySet().removeIf(entry -> !entry.getValue().getExpiresAt().isAfter(now));
            }
            if (activeSessions.size() >= authConfig.getMaxActiveSessions()) {
                throw new SessionCapacityExceededException();
            }
            activeSessions.put(sessionId, session);
        }
        
        log.info("创建会话成功，auditId: {}, rememberMe: {}, IP: {}",
                SessionAuditId.from(sessionId), rememberMe, ipAddress);
        
        return session;
    }
    
    /**
     * 验证会话是否有效
     * 
     * @param sessionId 会话ID
     * @return 会话是否有效
     */
    public boolean isSessionValid(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return false;
        }
        
        UserSession session = activeSessions.get(sessionId);
        if (session == null) {
            log.debug("会话不存在: {}", SessionAuditId.from(sessionId));
            return false;
        }
        
        if (session.isExpired()) {
            log.debug("会话已过期: {}", SessionAuditId.from(sessionId));
            // 清理过期会话
            invalidateSession(sessionId);
            return false;
        }

        if (session.getAccountUsername() != null && !isCurrentAccountSession(session)) {
            if (activeSessions.remove(sessionId, session)) {
                closeSessionConnections(sessionId);
            }
            log.debug("会话的账户已失效: {}", SessionAuditId.from(sessionId));
            return false;
        }
        
        return true;
    }

    private boolean isCurrentAccountSession(UserSession session) {
        return userAccountService != null && userAccountService.findByUsername(session.getAccountUsername())
                .filter(UserAccount::isEnabled)
                .filter(account -> session.getUserId().equals(account.getUserId()))
                .filter(account -> !session.isAdmin() || account.isAdmin())
                .isPresent();
    }
    
    /**
     * 获取会话信息
     * 
     * @param sessionId 会话ID
     * @return 会话对象，如果不存在或已过期则返回null
     */
    public UserSession getSession(String sessionId) {
        if (!isSessionValid(sessionId)) {
            return null;
        }
        return activeSessions.get(sessionId);
    }
    
    /**
     * 使会话失效
     * 
     * @param sessionId 会话ID
     */
    public void invalidateSession(String sessionId) {
        if (sessionId != null) {
            UserSession removedSession = activeSessions.remove(sessionId);
            if (removedSession != null) {
                log.info("会话已失效: {}", SessionAuditId.from(sessionId));
                closeSessionConnections(sessionId);
            }
        }
    }
    
    /**
     * 清理所有过期会话
     * 这个方法可以定期调用来清理内存中的过期会话
     */
    public void cleanupExpiredSessions() {
        LocalDateTime now = LocalDateTime.now(clock);
        activeSessions.forEach((sessionId, session) -> {
            if (!session.getExpiresAt().isAfter(now) && activeSessions.remove(sessionId, session)) {
                log.debug("清理过期会话: {}", SessionAuditId.from(sessionId));
                closeSessionConnections(sessionId);
            }
        });
        cleanupExpiredAttempts();
        if (userAccountService != null) {
            userAccountService.cleanupExpiredAttempts();
        }
        WebSocketSessionGuard guard = webSocketSessionGuard == null ? null : webSocketSessionGuard.getIfAvailable();
        if (guard != null) {
            guard.closeInactiveConnections();
        }
    }

    private void closeSessionConnections(String sessionId) {
        WebSocketSessionGuard guard = webSocketSessionGuard == null ? null : webSocketSessionGuard.getIfAvailable();
        if (guard != null) {
            guard.revokeSessionConnections(sessionId);
        }
    }
    
    /**
     * 获取活跃会话数量
     * 
     * @return 活跃会话数量
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }
    
    public void cleanupExpiredAttempts() {
        Instant cutoff = clock.instant().minus(authConfig.getAttemptStateTtl());
        attempts.forEach((key, record) -> attempts.computeIfPresent(key,
                (storedKey, current) -> current.lastAttemptTime().isAfter(cutoff) ? current : null));
        synchronized (globalAttemptLock) {
            globalAttempts = resetExpired(globalAttempts, authConfig.getAttemptStateTtl());
        }
    }

    private AttemptRecord resetExpired(AttemptRecord record, Duration window) {
        Instant now = clock.instant();
        return record.lastAttemptTime().isAfter(now.minus(window)) ? record : new AttemptRecord(0, now);
    }

    private AttemptRecord recordFailedAttempt(AttemptRecord previous) {
        return new AttemptRecord(previous.failures() + 1, clock.instant());
    }

    private record AttemptRecord(int failures, Instant lastAttemptTime) {
    }

    public static class SessionCapacityExceededException extends IllegalStateException {
        public SessionCapacityExceededException() {
            super("Authentication session capacity reached; please retry later");
        }
    }
    
    /**
     * 生成唯一的会话ID
     */
    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
