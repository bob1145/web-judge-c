package com.example.demo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.controller.AuthenticationController;
import com.example.demo.dto.AuthRequest;
import com.example.demo.interceptor.AuthenticationInterceptor;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.AuditService;
import com.example.demo.service.SessionManagementService;
import com.example.demo.service.UserAccountService;
import com.example.demo.security.SessionBoundCsrfTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCredentialLoggingTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger("com.example.demo");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void captureAllApplicationLogLevels() {
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLogging() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    void bothLoginModesAndAllSessionLifecycleLogLevelsNeverExposeCredentials() throws Exception {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode("task02-logging-test-material");
        ExecutionProperties execution = new ExecutionProperties();
        execution.setProfile("trusted-local");
        UserAccountService accounts = new UserAccountService(auth, execution);
        AccessCodeService access = new AccessCodeService(auth, accounts);
        SessionManagementService management = new SessionManagementService(access, auth);
        AuthenticationController controller = new AuthenticationController(access, management,
                accounts, execution, new SessionBoundCsrfTokenRepository(),
                new com.example.demo.security.ClientAddressResolver(auth));
        AuthenticationInterceptor interceptor = new AuthenticationInterceptor(access, management,
                new com.example.demo.security.ClientAddressResolver(auth));
        List<String> sensitive = new ArrayList<>(List.of(auth.getAccessCode(), "task02-logging-test-password"));
        for (boolean accountMode : new boolean[]{false, true}) {
            auth.setAccountAuthEnabled(accountMode);
            AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
            account.setUserId("logging-user");
            account.setUsername("logging-user");
            account.setPasswordHash(new BCryptPasswordEncoder().encode(sensitive.get(1)));
            auth.setAccounts(List.of(account));
            AuthRequest credentials = new AuthRequest();
            credentials.setAccessCode(auth.getAccessCode());
            credentials.setUsername(account.getUsername());
            credentials.setPassword(sensitive.get(1));
            MockHttpServletRequest request = request("/");
            var login = controller.verifyAccessCode(credentials, request, new MockHttpServletResponse());
            String sessionId = login.getBody().getSessionId();
            assertThat(sessionId).isNotBlank();
            sensitive.add(sessionId);
            management.validateSessionWithSecurity(sessionId, "192.0.2.2", "changed-agent");
            management.extendSession(sessionId, 1);
            new AuditService().record("test.authenticated", access.getSession(sessionId), null, "trusted-local", Map.of());
            request.addHeader("X-Session-ID", sessionId);
            controller.logout(request, new MockHttpServletResponse());
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isFalse();
            UserSession expired = access.createSession(false, "127.0.0.1", "TASK-02");
            sensitive.add(expired.getSessionId());
            expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            assertThat(access.isSessionValid(expired.getSessionId())).isFalse();
            UserSession cleanup = access.createSession(false, "127.0.0.1", "TASK-02");
            sensitive.add(cleanup.getSessionId());
            cleanup.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            access.cleanupExpiredSessions();
        }
        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages.stream().anyMatch(message -> sensitive.stream().anyMatch(message::contains)))
                .as("application logs must not contain raw credentials").isFalse();
        assertThat(appender.list.stream().map(ILoggingEvent::getLevel)).contains(Level.DEBUG, Level.INFO, Level.WARN);
        assertThat(messages.stream().anyMatch(message -> message.matches(".*session-sha256:[0-9a-f]{64}.*")))
                .as("redacted session events remain correlatable").isTrue();
    }

    @Test
    void accessCodeAuditIdentityIsRedactedWithoutChangingOwnershipIdentity() {
        AccessCodeService access = new AccessCodeService(new AuthConfiguration());
        UserSession session = access.createSession(false, "127.0.0.1", "TASK-02");
        AuditService audit = new AuditService();
        audit.record("test.first", session, null, null, Map.of("sessionId", session.getSessionId()));
        audit.record("test.second", session, null, null, Map.of());
        assertThat(audit.recentEvents().stream().anyMatch(event -> event.toString().contains(session.getSessionId())))
                .as("in-memory audit must not retain access-code session tokens").isFalse();
        assertThat(audit.recentEvents().get(0).userId()).isEqualTo(audit.recentEvents().get(1).userId());
        assertThat(session.getUserId()).isEqualTo(session.getSessionId());
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "TASK-02");
        request.addHeader("Accept", "application/json");
        return request;
    }
}
