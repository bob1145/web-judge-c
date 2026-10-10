package com.example.demo.controller;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.AuthRequest;
import com.example.demo.dto.AuthResponse;
import com.example.demo.dto.LogoutRequest;
import com.example.demo.dto.SessionInfo;
import com.example.demo.model.AuthStatus;
import com.example.demo.model.UserAccount;
import com.example.demo.model.UserSession;
import com.example.demo.security.SessionCredentialResolver;
import com.example.demo.security.SessionAuditId;
import com.example.demo.security.ClientAddressResolver;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.SessionManagementService;
import com.example.demo.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

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
    private final UserAccountService userAccountService;
    private final ExecutionProperties executionProperties;
    private final CsrfTokenRepository csrfTokenRepository;
    private final ClientAddressResolver clientAddressResolver;
    
    private static final String SESSION_COOKIE_NAME = SessionCredentialResolver.COOKIE_NAME;
    private static final Pattern NESTED_PERCENT_ESCAPE = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final IpAddressMatcher LOOPBACK_IPV4 = new IpAddressMatcher("127.0.0.0/8");
    private static final IpAddressMatcher LOOPBACK_IPV6 = new IpAddressMatcher("::1/128");
    private static final int COOKIE_MAX_AGE = 30 * 24 * 60 * 60; // 30天

    @ExceptionHandler(AccessCodeService.SessionCapacityExceededException.class)
    @ResponseBody
    public ResponseEntity<AuthResponse> sessionCapacityExceeded(AccessCodeService.SessionCapacityExceededException exception) {
        return ResponseEntity.status(503).body(AuthResponse.failure(exception.getMessage()));
    }
    
    /**
     * 显示认证页面
     */
    @GetMapping("/auth")
    public String showAuthPage(Model model, HttpServletRequest request,
                              @RequestParam(value = "redirect", required = false) String redirectUrl) {
        String targetUrl = safeRedirect(redirectUrl);
        // 检查是否已经认证
        String sessionId = getSessionIdFromRequest(request);
        if (sessionId != null && isRequestSessionValid(sessionId, request)) {
            log.debug("用户已认证，重定向到目标页面");
            return "redirect:" + targetUrl;
        }
        
        model.addAttribute("title", "身份验证");
        model.addAttribute("redirectUrl", targetUrl);
        model.addAttribute("accountAuthRequired", userAccountService.requiresAccountAuthentication());
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
        if (userAccountService.requiresAccountAuthentication()) {
            UserAccountService.LoginResult login = userAccountService.authenticate(
                    request.getUsername(),
                    request.getPassword(),
                    ipAddress
            );
            if (!login.success()) {
                int statusCode = login.status() == UserAccountService.Status.TOO_MANY_ATTEMPTS ? 429 : 401;
                return ResponseEntity.status(statusCode).body(AuthResponse.failure(login.message()));
            }

            UserAccount account = login.account();
            UserSession session = accessCodeService.createSession(
                    request.isRememberMe(),
                    ipAddress,
                    userAgent,
                    account
            );
            setSessionCookie(httpResponse, httpRequest, session.getSessionId(), request.isRememberMe());
            rotateCsrfToken(httpRequest, httpResponse);

            String redirectUrl = safeRedirect(httpRequest.getParameter("redirect"));

            return ResponseEntity.ok(AuthResponse.success(session.getSessionId(), redirectUrl));
        }

        AuthStatus status = accessCodeService.validateAccessCode(request.getAccessCode(), ipAddress);
        
        if (status == AuthStatus.SUCCESS) {
            // 创建会话
            UserSession session = accessCodeService.createSession(
                    request.isRememberMe(), ipAddress, userAgent);
            
            // 设置会话Cookie
            setSessionCookie(httpResponse, httpRequest, session.getSessionId(), request.isRememberMe());
            rotateCsrfToken(httpRequest, httpResponse);
            
            // 获取重定向URL
            String redirectUrl = safeRedirect(httpRequest.getParameter("redirect"));
            
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
            log.info("用户登出，会话已失效: {}", SessionAuditId.from(sessionId));
        }
        
        // 清除Cookie
        clearSessionCookie(response, request);
        rotateCsrfToken(request, response);
        
        return ResponseEntity.ok(AuthResponse.success(null, "/auth"));
    }
    
    /**
     * 获取当前会话信息
     */
    @GetMapping("/auth/session")
    @ResponseBody
    public ResponseEntity<SessionInfo> getSessionInfo(HttpServletRequest request) {
        String sessionId = getSessionIdFromRequest(request);
        
        if (sessionId == null || !isRequestSessionValid(sessionId, request)) {
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
        
        if (sessionId != null && isRequestSessionValid(sessionId, request)) {
            return ResponseEntity.ok(AuthResponse.success(sessionId, null));
        } else {
            return ResponseEntity.ok(AuthResponse.failure("未认证"));
        }
    }
    
    /**
     * 从请求中获取会话ID
     */
    private String getSessionIdFromRequest(HttpServletRequest request) {
        return SessionCredentialResolver.resolve(request);
    }

    private boolean isRequestSessionValid(String sessionId, HttpServletRequest request) {
        return sessionManagementService.validateSessionWithSecurity(
                sessionId, getClientIpAddress(request), request.getHeader("User-Agent"));
    }

    private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        csrfTokenRepository.saveToken(null, request, response);
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
    }
    
    /**
     * 设置会话Cookie
     */
    private void setSessionCookie(HttpServletResponse response, HttpServletRequest request, String sessionId,
                                  boolean rememberMe) {
        Cookie cookie = sessionCookie(request, sessionId);
        
        if (rememberMe) {
            cookie.setMaxAge(COOKIE_MAX_AGE); // 30天
        } else {
            cookie.setMaxAge(-1); // 会话Cookie，浏览器关闭时删除
        }
        
        response.addCookie(cookie);
        log.debug("设置会话Cookie，auditId: {}, rememberMe: {}", SessionAuditId.from(sessionId), rememberMe);
    }
    
    /**
     * 清除会话Cookie
     */
    private void clearSessionCookie(HttpServletResponse response, HttpServletRequest request) {
        Cookie cookie = sessionCookie(request, "");
        cookie.setMaxAge(0); // 立即过期
        
        response.addCookie(cookie);
        log.debug("清除会话Cookie");
    }

    private Cookie sessionCookie(HttpServletRequest request, String value) {
        Cookie cookie = new Cookie(SESSION_COOKIE_NAME, value);
        String profile = executionProperties.getProfile();
        boolean trustedLocal = profile != null && "trusted-local".equalsIgnoreCase(profile.trim());
        cookie.setSecure(request.isSecure() || !trustedLocal
                || !(LOOPBACK_IPV4.matches(request) || LOOPBACK_IPV6.matches(request)));
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setPath("/");
        return cookie;
    }
    
    /**
     * 获取客户端真实IP地址
     */
    private static String safeRedirect(String redirect) {
        if (redirect == null || !redirect.startsWith("/") || redirect.startsWith("//")) {
            return "/";
        }
        try {
            URI target = URI.create(redirect);
            String ascii = target.toASCIIString();
            byte[] decodedBytes = new byte[ascii.length()];
            int byteCount = 0;
            for (int offset = 0; offset < ascii.length(); offset++) {
                char character = ascii.charAt(offset);
                if (character == '%') {
                    decodedBytes[byteCount++] = (byte) Integer.parseInt(ascii.substring(offset + 1, offset + 3), 16);
                    offset += 2;
                } else {
                    decodedBytes[byteCount++] = (byte) character;
                }
            }
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(decodedBytes, 0, byteCount)).toString();
            String path = target.getPath();
            if (target.isAbsolute() || target.getRawAuthority() != null || path == null || path.contains("//")
                    || NESTED_PERCENT_ESCAPE.matcher(path).find()
                    || decoded.chars().anyMatch(character -> character == '\\' || Character.isISOControl(character))) {
                return "/";
            }
            URI normalized = new URI(null, null, path, null, null).normalize();
            String normalizedPath = normalized.getPath();
            if (!normalizedPath.startsWith("/") || normalizedPath.startsWith("/../") || normalizedPath.equals("/..")
                    || normalizedPath.equals("/auth") || normalizedPath.equals("/auth/")) {
                return "/";
            }
            String query = target.getRawQuery() == null ? "" : "?" + target.getRawQuery();
            String fragment = target.getRawFragment() == null ? "" : "#" + target.getRawFragment();
            return URI.create(normalized.toASCIIString() + query + fragment).toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException | CharacterCodingException exception) {
            return "/";
        }
    }

    private String getClientIpAddress(HttpServletRequest request) {
        return clientAddressResolver.resolve(request);
    }
}
