package com.example.demo.service;

import com.example.demo.dto.SessionInfo;
import com.example.demo.model.UserSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 会话管理服务
 * 负责会话的高级管理功能，如定期清理、会话信息查询等
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionManagementService {
    
    private final AccessCodeService accessCodeService;
    
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
        
        // 检查IP地址匹配
        if (session.getIpAddress() != null && !session.getIpAddress().equals(ipAddress)) {
            log.warn("会话 {} 的IP地址不匹配，原IP: {}, 当前IP: {}", 
                    sessionId, session.getIpAddress(), ipAddress);
            // 注意：在某些网络环境下IP可能会变化，这里只记录警告，不强制验证失败
        }
        
        // 检查User-Agent匹配
        if (session.getUserAgent() != null && !session.getUserAgent().equals(userAgent)) {
            log.warn("会话 {} 的User-Agent不匹配", sessionId);
            // 同样只记录警告，不强制验证失败
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
                sessionId, additionalHours, newExpiresAt);
        
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
     * 每小时执行一次
     */
    @Scheduled(fixedRate = 3600000) // 1小时 = 3600000毫秒
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