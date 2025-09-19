package com.example.demo.service;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.model.AuthStatus;
import com.example.demo.model.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 校验码服务
 * 负责校验码验证、会话管理等认证相关功能
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccessCodeService {
    
    private final AuthConfiguration authConfig;
    
    /**
     * 存储活跃会话的内存映射
     * Key: sessionId, Value: UserSession
     */
    private final Map<String, UserSession> activeSessions = new ConcurrentHashMap<>();
    
    /**
     * 存储IP地址的尝试次数
     * Key: IP地址, Value: 尝试次数
     */
    private final Map<String, Integer> attemptCounts = new ConcurrentHashMap<>();
    
    /**
     * 存储IP地址的最后尝试时间
     * Key: IP地址, Value: 最后尝试时间
     */
    private final Map<String, LocalDateTime> lastAttemptTimes = new ConcurrentHashMap<>();
    
    /**
     * 验证校验码
     * 
     * @param code 用户输入的校验码
     * @param ipAddress 用户IP地址
     * @return 验证结果状态
     */
    public AuthStatus validateAccessCode(String code, String ipAddress) {
        log.debug("验证校验码，IP: {}", ipAddress);
        
        // 检查输入是否为空
        if (code == null || code.trim().isEmpty()) {
            log.warn("校验码为空，IP: {}", ipAddress);
            return AuthStatus.EMPTY_CODE;
        }
        
        // 检查尝试次数限制
        if (isExceedMaxAttempts(ipAddress)) {
            log.warn("IP {} 超过最大尝试次数限制", ipAddress);
            return AuthStatus.TOO_MANY_ATTEMPTS;
        }
        
        // 验证校验码
        boolean isValid = authConfig.getAccessCode().equals(code.trim());
        
        if (isValid) {
            log.info("校验码验证成功，IP: {}", ipAddress);
            clearAttemptRecord(ipAddress);
            return AuthStatus.SUCCESS;
        } else {
            log.warn("校验码验证失败，IP: {}", ipAddress);
            recordFailedAttempt(ipAddress);
            return AuthStatus.INVALID_CODE;
        }
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
        String sessionId = generateSessionId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = rememberMe ? 
            now.plus(authConfig.getSessionTimeout()) : 
            now.plus(authConfig.getNormalSessionTimeout());
        
        UserSession session = UserSession.builder()
                .sessionId(sessionId)
                .createdAt(now)
                .expiresAt(expiresAt)
                .rememberMe(rememberMe)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build();
        
        activeSessions.put(sessionId, session);
        
        log.info("创建会话成功，sessionId: {}, rememberMe: {}, IP: {}", 
                sessionId, rememberMe, ipAddress);
        
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
            log.debug("会话不存在: {}", sessionId);
            return false;
        }
        
        if (session.isExpired()) {
            log.debug("会话已过期: {}", sessionId);
            // 清理过期会话
            activeSessions.remove(sessionId);
            return false;
        }
        
        return true;
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
                log.info("会话已失效: {}", sessionId);
            }
        }
    }
    
    /**
     * 清理所有过期会话
     * 这个方法可以定期调用来清理内存中的过期会话
     */
    public void cleanupExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        activeSessions.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().getExpiresAt().isBefore(now);
            if (expired) {
                log.debug("清理过期会话: {}", entry.getKey());
            }
            return expired;
        });
    }
    
    /**
     * 获取活跃会话数量
     * 
     * @return 活跃会话数量
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }
    
    /**
     * 检查IP是否超过最大尝试次数
     */
    private boolean isExceedMaxAttempts(String ipAddress) {
        Integer attempts = attemptCounts.get(ipAddress);
        if (attempts == null) {
            return false;
        }
        
        // 检查是否需要重置计数
        LocalDateTime lastAttempt = lastAttemptTimes.get(ipAddress);
        if (lastAttempt != null && lastAttempt.isBefore(LocalDateTime.now().minusHours(authConfig.getAttemptResetHours()))) {
            clearAttemptRecord(ipAddress);
            return false;
        }
        
        return attempts >= authConfig.getMaxAttempts();
    }
    
    /**
     * 记录失败尝试
     */
    private void recordFailedAttempt(String ipAddress) {
        attemptCounts.merge(ipAddress, 1, Integer::sum);
        lastAttemptTimes.put(ipAddress, LocalDateTime.now());
    }
    
    /**
     * 清除尝试记录
     */
    private void clearAttemptRecord(String ipAddress) {
        attemptCounts.remove(ipAddress);
        lastAttemptTimes.remove(ipAddress);
    }
    
    /**
     * 生成唯一的会话ID
     */
    private String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}