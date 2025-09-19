package com.example.demo.interceptor;

import com.example.demo.service.AccessCodeService;
import com.example.demo.service.SessionManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 认证拦截器
 * 拦截所有请求，验证用户是否已认证
 * 未认证用户将被重定向到认证页面
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthenticationInterceptor implements HandlerInterceptor {
    
    private final AccessCodeService accessCodeService;
    private final SessionManagementService sessionManagementService;
    
    private static final String SESSION_COOKIE_NAME = "JUDGE_SESSION";
    
    /**
     * 不需要认证的路径白名单
     */
    private static final Set<String> WHITELIST_PATHS = new HashSet<>(Arrays.asList(
            "/auth",           // 认证页面
            "/auth/verify",    // 认证验证接口
            "/auth/check",     // 认证状态检查接口
            "/error",          // 错误页面
            "/favicon.ico",    // 网站图标
            "/static",         // 静态资源
            "/css",            // CSS文件
            "/js",             // JavaScript文件
            "/images",         // 图片资源
            "/examples.js"     // 示例代码文件
    ));
    
    /**
     * 不需要认证的路径前缀
     */
    private static final Set<String> WHITELIST_PREFIXES = new HashSet<>(Arrays.asList(
            "/static/",
            "/css/",
            "/js/",
            "/images/",
            "/webjars/",
            "/actuator/"  // Spring Boot Actuator端点（如果启用）
    ));
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) 
            throws Exception {
        
        String requestPath = request.getRequestURI();
        String method = request.getMethod();
        
        log.debug("拦截请求: {} {}", method, requestPath);
        
        // 检查是否在白名单中
        if (isWhitelistedPath(requestPath)) {
            log.debug("路径在白名单中，允许访问: {}", requestPath);
            return true;
        }
        
        // 获取会话ID
        String sessionId = getSessionIdFromRequest(request);
        
        if (sessionId == null) {
            log.debug("未找到会话ID，重定向到认证页面");
            redirectToAuth(request, response);
            return false;
        }
        
        // 验证会话有效性
        if (!accessCodeService.isSessionValid(sessionId)) {
            log.debug("会话无效: {}, 重定向到认证页面", sessionId);
            redirectToAuth(request, response);
            return false;
        }
        
        // 可选：进行额外的安全检查（IP和User-Agent匹配）
        String clientIp = getClientIpAddress(request);
        String userAgent = request.getHeader("User-Agent");
        
        if (!sessionManagementService.validateSessionWithSecurity(sessionId, clientIp, userAgent)) {
            log.warn("会话安全验证失败: {}, IP: {}", sessionId, clientIp);
            // 这里只记录警告，不强制拒绝访问，因为某些网络环境下IP可能会变化
        }
        
        log.debug("认证通过，允许访问: {}", requestPath);
        return true;
    }
    
    /**
     * 检查路径是否在白名单中
     */
    private boolean isWhitelistedPath(String path) {
        // 精确匹配
        if (WHITELIST_PATHS.contains(path)) {
            return true;
        }
        
        // 前缀匹配
        for (String prefix : WHITELIST_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 从请求中获取会话ID
     */
    private String getSessionIdFromRequest(HttpServletRequest request) {
        // 首先尝试从Cookie中获取
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                    String sessionId = cookie.getValue();
                    if (sessionId != null && !sessionId.trim().isEmpty()) {
                        return sessionId.trim();
                    }
                }
            }
        }
        
        // 然后尝试从Header中获取（支持API调用）
        String sessionId = request.getHeader("X-Session-ID");
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            return sessionId.trim();
        }
        
        // 最后尝试从请求参数中获取
        sessionId = request.getParameter("sessionId");
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            return sessionId.trim();
        }
        
        return null;
    }
    
    /**
     * 重定向到认证页面
     */
    private void redirectToAuth(HttpServletRequest request, HttpServletResponse response) 
            throws Exception {
        
        String requestPath = request.getRequestURI();
        
        // 判断是否为AJAX请求
        if (isAjaxRequest(request)) {
            // AJAX请求返回JSON响应
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"message\":\"未认证\",\"redirectUrl\":\"/auth\"}");
            log.debug("AJAX请求未认证，返回401状态码");
        } else {
            // 普通请求重定向到认证页面
            String redirectUrl = "/auth";
            
            // 保存原始请求路径，认证成功后可以重定向回来
            if (!"/".equals(requestPath) && !requestPath.startsWith("/auth")) {
                redirectUrl += "?redirect=" + java.net.URLEncoder.encode(requestPath, "UTF-8");
            }
            
            response.sendRedirect(redirectUrl);
            log.debug("重定向到认证页面: {}", redirectUrl);
        }
    }
    
    /**
     * 判断是否为AJAX请求
     */
    private boolean isAjaxRequest(HttpServletRequest request) {
        String xRequestedWith = request.getHeader("X-Requested-With");
        String contentType = request.getContentType();
        String accept = request.getHeader("Accept");
        
        return "XMLHttpRequest".equals(xRequestedWith) ||
               (contentType != null && contentType.contains("application/json")) ||
               (accept != null && accept.contains("application/json"));
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