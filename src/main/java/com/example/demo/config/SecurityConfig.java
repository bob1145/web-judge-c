package com.example.demo.config;

import com.example.demo.security.SessionBoundCsrfTokenRepository;
import com.example.demo.security.SessionCredentialResolver;
import com.example.demo.security.WebSocketSessionGuard;
import com.example.demo.service.AccessCodeService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Spring Security 基础配置
 * 保留既有会话认证，并校验状态变更请求的CSRF凭证
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new SessionBoundCsrfTokenRepository();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, CsrfTokenRepository csrfTokenRepository,
                                         AccessCodeService accessCodeService,
                                         WebSocketSessionGuard sessionGuard) throws Exception {
        http
            .addFilterAfter(sessionGuard, SecurityContextHolderAwareRequestFilter.class)
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                    .ignoringRequestMatchers(new AntPathRequestMatcher("/ws/*/*/xhr", "POST"),
                            new AntPathRequestMatcher("/ws/*/*/xhr_streaming", "POST"),
                            new AntPathRequestMatcher("/ws/*/*/xhr_send", "POST"))
                    .requireCsrfProtectionMatcher(request -> CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                            && !isCookieFreeAuthenticatedHeaderRequest(request, accessCodeService)))
            .authorizeHttpRequests(authz -> authz
                .anyRequest().permitAll()
            );
        
        return http.build();
    }

    @Bean
    public FilterRegistrationBean<WebSocketSessionGuard> webSocketSessionGuardRegistration(
            WebSocketSessionGuard sessionGuard) {
        FilterRegistrationBean<WebSocketSessionGuard> registration = new FilterRegistrationBean<>(sessionGuard);
        registration.setEnabled(false);
        return registration;
    }

    private boolean isCookieFreeAuthenticatedHeaderRequest(HttpServletRequest request,
                                                          AccessCodeService accessCodeService) {
        if (request.getCookies() != null && request.getCookies().length > 0) {
            return false;
        }
        String sessionId = SessionCredentialResolver.resolve(request);
        return sessionId != null && accessCodeService.isSessionValid(sessionId);
    }
}
