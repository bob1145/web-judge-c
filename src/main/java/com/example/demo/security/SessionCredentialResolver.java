package com.example.demo.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;

public final class SessionCredentialResolver {

    public static final String COOKIE_NAME = "JUDGE_SESSION";
    public static final String HEADER_NAME = "X-Session-ID";

    private SessionCredentialResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String credential = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    String value = normalized(cookie.getValue());
                    if (value == null || credential != null && !credential.equals(value)) {
                        return null;
                    }
                    credential = value;
                }
            }
        }
        Enumeration<String> headers = request.getHeaders(HEADER_NAME);
        if (headers != null) {
            while (headers.hasMoreElements()) {
                String value = normalized(headers.nextElement());
                if (value == null || credential != null && !credential.equals(value)) {
                    return null;
                }
                credential = value;
            }
        }
        return credential;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
