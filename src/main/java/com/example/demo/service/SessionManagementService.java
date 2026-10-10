package com.example.demo.service;

import com.example.demo.dto.SessionInfo;
import com.example.demo.config.AuthConfiguration;
import com.example.demo.model.UserSession;
import com.example.demo.security.SessionAuditId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 会话管理服务
 * 负责会话的高级管理功能，如定期清理、会话信息查询等
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionManagementService {
    
    private final AccessCodeService accessCodeService;
    private final AuthConfiguration authConfiguration;
    
    /**
     * 获取会话详细信息
     * 
     * @param sessionId 会话ID
     * @return 会话信息DTO
     */
    public SessionInfo getSessionInfo(String sessionId) {
        UserSession session = accessCodeService.getSession(sessionId);
        
        if (session == null) {
            return SessionInfo.invalid();
        }
        
        // 计算剩余时间
        Duration remaining = Duration.between(LocalDateTime.now(), session.getExpiresAt());
        long remainingMinutes = Math.max(0, remaining.toMinutes());
        
        return SessionInfo.builder()
                .valid(true)
                .sessionId(session.getSessionId())
                .createdAt(session.getCreatedAt())
                .expiresAt(session.getExpiresAt())
                .rememberMe(session.isRememberMe())
                .remainingMinutes(remainingMinutes)
                .build();
    }
    
    /**
     * 验证会话并检查IP和User-Agent匹配
     * 
     * @param sessionId 会话ID
     * @param ipAddress 当前请求的IP地址
     * @param userAgent 当前请求的User-Agent
     * @return 会话是否有效且匹配
     */
    public boolean validateSessionWithSecurity(String sessionId, String ipAddress, String userAgent) {
        UserSession session = accessCodeService.getSession(sessionId);
        
        if (session == null) {
            return false;
        }
        
        AuthConfiguration.SessionEnvironmentPolicy policy = session.isAdmin()
                ? AuthConfiguration.SessionEnvironmentPolicy.STRICT
                : authConfiguration.getSessionEnvironmentPolicy();
        if (policy == null) {
            return false;
        }
        boolean sourceChanged = !Objects.equals(session.getIpAddress(), ipAddress);
        boolean agentChanged = !Objects.equals(session.getUserAgent(), userAgent);
        if (sourceChanged || agentChanged) {
            log.warn("会话环境变化，auditId: {}, policy: {}, sourceChanged: {}, agentChanged: {}",
                    SessionAuditId.from(sessionId), policy, sourceChanged, agentChanged);
            if (policy == AuthConfiguration.SessionEnvironmentPolicy.STRICT) {
                accessCodeService.invalidateSession(sessionId);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 延长会话有效期
     * 
     * @param sessionId 会话ID
     * @param additionalHours 延长的小时数
     * @return 是否延长成功
     */
    public boolean extendSession(String sessionId, int additionalHours) {
        UserSession session = accessCodeService.getSession(sessionId);
        
        if (session == null) {
            return false;
        }
        
        LocalDateTime newExpiresAt = session.getExpiresAt().plusHours(additionalHours);
        session.setExpiresAt(newExpiresAt);
        
        log.info("会话 {} 有效期已延长 {} 小时，新过期时间: {}", 
                SessionAuditId.from(sessionId), additionalHours, newExpiresAt);
        
        return true;
    }
    
    /**
     * 获取系统会话统计信息
     * 
     * @return 会话统计信息
     */
    public SessionStatistics getSessionStatistics() {
        int activeCount = accessCodeService.getActiveSessionCount();
        
        return SessionStatistics.builder()
                .activeSessionCount(activeCount)
                .lastCleanupTime(LocalDateTime.now())
                .build();
    }
    
    /**
     * 定期清理过期会话
     * 按配置间隔执行
     */
    @Scheduled(fixedRateString = "${judge.auth.attempt-cleanup-interval-ms:60000}")
    public void scheduledCleanup() {
        log.debug("开始定期清理过期会话");
        int beforeCount = accessCodeService.getActiveSessionCount();
        
        accessCodeService.cleanupExpiredSessions();
        
        int afterCount = accessCodeService.getActiveSessionCount();
        int cleanedCount = beforeCount - afterCount;
        
        if (cleanedCount > 0) {
            log.info("定期清理完成，清理了 {} 个过期会话，当前活跃会话数: {}", 
                    cleanedCount, afterCount);
        }
    }
    
    /**
     * 会话统计信息内部类
     */
    @lombok.Data
    @lombok.Builder
    public static class SessionStatistics {
        private int activeSessionCount;
        private LocalDateTime lastCleanupTime;
    }
}
