package com.example.demo;

import com.example.demo.security.SessionAuditId;
import com.example.demo.security.SessionCredentialResolver;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCredentialResolverTest {

    @Test
    void absentAndQueryOnlyRequestsHaveNoCredential() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(SessionCredentialResolver.resolve(request)).isNull();
        request.setParameter("sessionId", "query-only-value");
        request.setCookies(new Cookie("unrelated", "unrelated-value"));
        assertThat(SessionCredentialResolver.resolve(request)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cookie", "header", "both"})
    void normalizesSupportedCredentialsAndIgnoresQuery(String mode) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (!"header".equals(mode)) {
            request.setCookies(new Cookie("JUDGE_SESSION", "  normalized-value  "));
        }
        if (!"cookie".equals(mode)) {
            request.addHeader("X-Session-ID", " normalized-value ");
        }
        request.setParameter("sessionId", "different-query-value");
        assertThat(SessionCredentialResolver.resolve(request)).isEqualTo("normalized-value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"different", "", "   ", "first,second"})
    void badHeaderCannotFallBackToValidCookie(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("JUDGE_SESSION", "valid-cookie"));
        request.addHeader("X-Session-ID", header);
        assertThat(SessionCredentialResolver.resolve(request)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"different", "", "   "})
    void badCookieCannotFallBackToValidHeader(String cookie) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("JUDGE_SESSION", cookie));
        request.addHeader("X-Session-ID", "valid-header");
        assertThat(SessionCredentialResolver.resolve(request)).isNull();
    }

    @Test
    void repeatedMatchingValuesAreAccepted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("JUDGE_SESSION", "same"), new Cookie("JUDGE_SESSION", " same "));
        request.addHeader("X-Session-ID", "same");
        request.addHeader("X-Session-ID", "same");
        assertThat(SessionCredentialResolver.resolve(request)).isEqualTo("same");
    }

    @Test
    void conflictingRepeatedCookiesOrHeadersAreRejected() {
        MockHttpServletRequest cookies = new MockHttpServletRequest();
        cookies.setCookies(new Cookie("JUDGE_SESSION", "first"), new Cookie("JUDGE_SESSION", "second"));
        assertThat(SessionCredentialResolver.resolve(cookies)).isNull();
        MockHttpServletRequest headers = new MockHttpServletRequest();
        headers.addHeader("X-Session-ID", "first");
        headers.addHeader("X-Session-ID", "second");
        assertThat(SessionCredentialResolver.resolve(headers)).isNull();
    }

    @Test
    void auditFingerprintIsStableDistinctAndHasNoRawCredential() {
        assertThat(SessionAuditId.from("abc"))
                .isEqualTo("session-sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                .isEqualTo(SessionAuditId.from("abc"))
                .isNotEqualTo(SessionAuditId.from("different"));
        assertThat(SessionAuditId.from(null)).isEqualTo("session-unknown");
        assertThat(SessionAuditId.from(" ")).isEqualTo("session-unknown");
    }
}
