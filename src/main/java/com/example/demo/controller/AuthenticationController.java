package com.example.demo.controller;

import com.example.demo.dto.AuthRequest;
import com.example.demo.dto.AuthResponse;
import com.example.demo.dto.LogoutRequest;
import com.example.demo.dto.SessionInfo;
import com.example.demo.model.AuthStatus;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.SessionManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 认证控制器
 * 处理用户认证相关的请求，包括登录、登出、会话验证等
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class AuthenticationController {
    
    private final AccessCodeService accessCodeService;
    private final SessionManagementService sessionManagementService;
    
    private static final String SESSION_COOKIE_NAME = "JUDGE_SESSION";
    private static final int COOKIE_MAX_AGE = 30 * 24 * 60 * 60; // 30天
    
    /**
     * 显示认证页面
     */
    @GetMapping("/auth")
    public String showAuthPage(Model model, HttpServletRequest request,
                              @RequestParam(value = "redirect", required = false) String redirectUrl) {
        // 检查是否已经认证
        String sessionId = getSessionIdFromRequest(request);
        if (sessionId != null && accessCodeService.isSessionValid(sessionId)) {
            log.debug("用户已认证，重定向到目标页面");
            String targetUrl = (redirectUrl != null && !redirectUrl.isEmpty()) ? redirectUrl : "/";
            return "redirect:" + targetUrl;
        }
        
        model.addAttribute("title", "身份验证");
        model.addAttribute("redirectUrl", redirectUrl);
        return "auth";
    }
    
    /**
     * 处理认证请求
     */
    @PostMapping("/auth/verify")
    @ResponseBody
    public ResponseEntity<AuthResponse> verifyAccessCode(
            @RequestBody AuthRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        
        String ipAddress = getClientIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        
        log.info("收到认证请求，IP: {}, rememberMe: {}", ipAddress, request.isRememberMe());
        
        // 验证校验码
        AuthStatus status = accessCodeService.validateAccessCode(request.getAccessCode(), ipAddress);
        
        if (status == AuthStatus.SUCCESS) {
            // 创建会话
            UserSession session = accessCodeService.createSession(
                    request.isRememberMe(), ipAddress, userAgent);
            
            // 设置会话Cookie
            setSessionCookie(httpResponse, session.getSessionId(), request.isRememberMe());
            
            // 获取重定向URL
            String redirectUrl = httpRequest.getParameter("redirect");
            if (redirectUrl == null || redirectUrl.isEmpty()) {
                redirectUrl = "/";
            }
            
            return ResponseEntity.ok(AuthResponse.success(session.getSessionId(), redirectUrl));
        } else {
            return ResponseEntity.ok(AuthResponse.failure(status.getMessage()));
        }
    }
    
    /**
     * 处理登出请求
     */
    @PostMapping("/auth/logout")
    @ResponseBody
    public ResponseEntity<AuthResponse> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        
        String sessionId = getSessionIdFromRequest(request);
        
        if (sessionId != null) {
            // 使会话失效
            accessCodeService.invalidateSession(sessionId);
            log.info("用户登出，会话已失效: {}", sessionId);
        }
        
        // 清除Cookie
        clearSessionCookie(response);
        
        return ResponseEntity.ok(AuthResponse.success(null, "/auth"));
    }
    
    /**
     * 获取当前会话信息
     */
    @GetMapping("/auth/session")
    @ResponseBody
    public ResponseEntity<SessionInfo> getSessionInfo(HttpServletRequest request) {
        String sessionId = getSessionIdFromRequest(request);
        
        if (sessionId == null) {
            return ResponseEntity.ok(SessionInfo.invalid());
        }
        
        SessionInfo sessionInfo = sessionManagementService.getSessionInfo(sessionId);
        return ResponseEntity.ok(sessionInfo);
    }
    
    /**
     * 检查认证状态
     */
    @GetMapping("/auth/check")
    @ResponseBody
    public ResponseEntity<AuthResponse> checkAuthStatus(HttpServletRequest request) {
        String sessionId = getSessionIdFromRequest(request);
        
        if (sessionId != null && accessCodeService.isSessionValid(sessionId)) {
            return ResponseEntity.ok(AuthResponse.success(sessionId, null));
        } else {
            return ResponseEntity.ok(AuthResponse.failure("未认证"));
        }
    }
    
    /**
     * 从请求中获取会话ID
     */
    private String getSessionIdFromRequest(HttpServletRequest request) {
        // 首先尝试从Cookie中获取
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        
        // 然后尝试从Header中获取
        String sessionId = request.getHeader("X-Session-ID");
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            return sessionId.trim();
        }
        
        return null;
    }
    
    /**
     * 设置会话Cookie
     */
    private void setSessionCookie(HttpServletResponse response, String sessionId, boolean rememberMe) {
        Cookie cookie = new Cookie(SESSION_COOKIE_NAME, sessionId);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // 建议true（需要HTTPS）
        cookie.setPath("/");
        
        if (rememberMe) {
            cookie.setMaxAge(COOKIE_MAX_AGE); // 30天
        } else {
            cookie.setMaxAge(-1); // 会话Cookie，浏览器关闭时删除
        }
        
        response.addCookie(cookie);
        log.debug("设置会话Cookie: {}, rememberMe: {}", sessionId, rememberMe);
    }
    
    /**
     * 清除会话Cookie
     */
    private void clearSessionCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(SESSION_COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(0); // 立即过期
        
        response.addCookie(cookie);
        log.debug("清除会话Cookie");
    }
    
    /**
     * 获取客户端真实IP地址
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp;
        }
        
        return request.getRemoteAddr();
    }
}