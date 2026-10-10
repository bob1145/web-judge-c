package com.example.demo.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import java.util.Objects;

public final class SessionBoundCsrfTokenRepository implements CsrfTokenRepository {
    private static final String SESSION_BINDING = SessionBoundCsrfTokenRepository.class.getName() + ".credential";
    private final HttpSessionCsrfTokenRepository delegate = new HttpSessionCsrfTokenRepository();

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        delegate.saveToken(token, request, response);
        HttpSession session = request.getSession(false);
        if (session != null) {
            if (token == null) {
                session.removeAttribute(SESSION_BINDING);
            } else {
                session.setAttribute(SESSION_BINDING, SessionCredentialResolver.resolve(request));
            }
        }
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        CsrfToken token = delegate.loadToken(request);
        HttpSession session = request.getSession(false);
        if (token != null && !Objects.equals(session.getAttribute(SESSION_BINDING),
                SessionCredentialResolver.resolve(request))) {
            saveToken(null, request, null);
            return null;
        }
        return token;
    }
}
