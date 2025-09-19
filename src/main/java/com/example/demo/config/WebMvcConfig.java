package com.example.demo.config;

import com.example.demo.interceptor.AuthenticationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类
 * 用于注册拦截器、配置静态资源等
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    
    private final AuthenticationInterceptor authenticationInterceptor;
    
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/**")  // 拦截所有路径
                .excludePathPatterns(
                        "/auth",           // 认证页面
                        "/auth/**",        // 认证相关接口
                        "/error",          // 错误页面
                        "/favicon.ico",    // 网站图标
                        "/static/**",      // 静态资源
                        "/css/**",         // CSS文件
                        "/js/**",          // JavaScript文件
                        "/images/**",      // 图片资源
                        "/examples.js",    // 示例代码文件
                        "/webjars/**",     // WebJars资源
                        "/actuator/**"     // Spring Boot Actuator端点
                );
    }
}