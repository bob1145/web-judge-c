package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.model.UserSession;
import com.example.demo.security.SessionCredentialResolver;
import com.example.demo.service.AccessCodeService;
import com.example.demo.support.CsrfTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "judge.auth.access-code=task02-test-only-access-material"
})
@Import(WebSocketSendHttpTest.IsolatedTaskStore.class)
class CsrfProtectionHttpTest {
    @Autowired
    private AccessCodeService accessCodeService;
    @Autowired
    private AuthConfiguration authConfiguration;
    @Autowired
    private ObjectMapper objectMapper;
    @LocalServerPort
    private int serverPort;

    private final List<String> sessions = new ArrayList<>();

    @AfterEach
    void cleanSessions() {
        sessions.forEach(accessCodeService::invalidateSession);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/auth/verify", "/auth/logout", "/judge", "/judge/start/not-a-task",
            "/judge/cancel/not-a-task", "/judge/cleanup/not-a-task"})
    void cookieStateChangesRejectMissingAndWrongTokensBeforeControllerEffects(String path) throws Exception {
        UserSession session = session();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (String token : new String[]{"", "wrong-token"}) {
            HttpRequest.Builder request = post(path).header("Cookie", cookie(session.getSessionId()))
                    .header("Origin", "https://untrusted.invalid").header("X-Requested-With", "XMLHttpRequest");
            if (!token.isEmpty()) {
                request.header("X-CSRF-TOKEN", token);
            }
            assertThat(send(client, request).statusCode()).as(path + " token=" + token).isEqualTo(403);
            assertThat(accessCodeService.isSessionValid(session.getSessionId())).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "cookie", "conflicting"})
    void arbitraryOrCookieBearingSessionHeadersDoNotBypassCsrf(String mode) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        UserSession session = session();
        HttpRequest.Builder request = post("/judge/start/not-a-task")
                .header(SessionCredentialResolver.HEADER_NAME, "invalid".equals(mode) ? "invalid" : session.getSessionId());
        if ("cookie".equals(mode)) {
            request.header("Cookie", cookie(session.getSessionId()));
        } else if ("conflicting".equals(mode)) {
            request.header(SessionCredentialResolver.HEADER_NAME, session().getSessionId());
        }
        assertThat(send(client, request).statusCode()).isEqualTo(403);
    }

    @Test
    void validCookieFreeHeaderRequestStillUsesNormalTaskAuthorization() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        assertThat(send(client, post("/judge/start/not-a-task")
                .header(SessionCredentialResolver.HEADER_NAME, session().getSessionId())).statusCode()).isEqualTo(404);
    }

    @Test
    void loginRequiresPreLoginTokenAndRotatesItBeforeCookieLogout() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        HttpClient client = client(cookies);
        assertThat(send(client, post("/auth/verify")).statusCode()).isEqualTo(403);
        String anonymousToken = CsrfTestSupport.token(client, uri("/auth"));
        assertThat(cookies.getCookieStore().getCookies()).anyMatch(value -> "JSESSIONID".equals(value.getName()));
        HttpResponse<String> login = send(client, post("/auth/verify").header("X-CSRF-TOKEN", anonymousToken));
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(login.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        String sessionId = body.path("sessionId").asText();
        sessions.add(sessionId);
        assertThat(accessCodeService.isSessionValid(sessionId)).isTrue();
        assertThat(send(client, post("/auth/logout").header("X-CSRF-TOKEN", anonymousToken)).statusCode()).isEqualTo(403);
        assertThat(accessCodeService.isSessionValid(sessionId)).isTrue();
        String authenticatedToken = CsrfTestSupport.token(client, uri("/"));
        assertThat(send(client, post("/auth/logout").header("X-CSRF-TOKEN", authenticatedToken)).statusCode()).isEqualTo(200);
        assertThat(accessCodeService.isSessionValid(sessionId)).isFalse();
        assertThat(cookies.getCookieStore().getCookies()).noneMatch(value -> SessionCredentialResolver.COOKIE_NAME.equals(value.getName()));
        assertThat(send(client, post("/auth/verify").header("X-CSRF-TOKEN", authenticatedToken)).statusCode()).isEqualTo(403);
    }

    @Test
    void csrfTokenCannotMoveBetweenBrowserOrJudgeSessions() throws Exception {
        UserSession first = session();
        UserSession second = session();
        CookieManager firstCookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        CookieManager secondCookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        installCookie(firstCookies, first.getSessionId());
        installCookie(secondCookies, second.getSessionId());
        HttpClient firstClient = client(firstCookies);
        HttpClient secondClient = client(secondCookies);
        String firstToken = CsrfTestSupport.token(firstClient, uri("/"));
        CsrfTestSupport.token(secondClient, uri("/"));
        assertThat(send(secondClient, post("/auth/logout").header("X-CSRF-TOKEN", firstToken)).statusCode()).isEqualTo(403);
        installCookie(firstCookies, second.getSessionId());
        assertThat(send(firstClient, post("/auth/logout").header("X-CSRF-TOKEN", firstToken)).statusCode()).isEqualTo(403);
        assertThat(accessCodeService.isSessionValid(first.getSessionId())).isTrue();
        assertThat(accessCodeService.isSessionValid(second.getSessionId())).isTrue();
        String replacementToken = CsrfTestSupport.token(firstClient, uri("/"));
        assertThat(send(firstClient, post("/auth/logout").header("X-CSRF-TOKEN", replacementToken)).statusCode()).isEqualTo(200);
        assertThat(accessCodeService.isSessionValid(second.getSessionId())).isFalse();
        assertThat(accessCodeService.isSessionValid(first.getSessionId())).isTrue();
    }

    @Test
    void cookieTaskCreateAndStartUseBoundToken() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        installCookie(cookies, session().getSessionId());
        HttpClient client = client(cookies);
        String token = CsrfTestSupport.token(client, uri("/"));
        String program = "#include <iostream>\nint main() { std::cout << 7 << std::endl; return 0; }\n";
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "userCode", program, "generatorCode", program, "bruteForceCode", program,
                "testCases", 1, "timeLimit", 2_000, "memoryLimit", 268_435_456L,
                "precision", 0.0, "useSpecialJudge", false, "specialJudgeCode", ""));
        HttpResponse<String> created = send(client, post("/judge").header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(created.statusCode()).isEqualTo(200);
        String judgeId = objectMapper.readTree(created.body()).path("judgeId").asText();
        assertThat(judgeId).isNotBlank();
        String operation = "/judge/start/" + judgeId;
        assertThat(send(client, post(operation)).statusCode()).isEqualTo(403);
        HttpResponse<String> accepted = send(client, post(operation).header("X-CSRF-TOKEN", token));
        assertThat(accepted.statusCode()).isEqualTo(200);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode status = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/judge/status/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            status = objectMapper.readTree(response.body());
            if (status.path("progress").asInt() == 100) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).isNotNull();
        assertThat(status.path("progress").asInt()).isEqualTo(100);
        assertThat(status.path("status").asText()).isEqualTo("AC");
        assertThat(status.path("results")).hasSize(1);
        assertThat(status.path("results").get(0).path("status").asText()).isEqualTo("AC");
        assertThat(send(client, post("/judge/cleanup/" + judgeId)).statusCode()).isEqualTo(403);
        assertThat(send(client, post("/judge/cleanup/" + judgeId).header("X-CSRF-TOKEN", token)).statusCode())
                .as("CSRF-authorized acknowledgement, not proof of deletion").isEqualTo(200);
    }

    @Test
    void untrustedCorsPreflightDoesNotAuthorizeCredentialedHeaders() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/judge"))
                .timeout(Duration.ofSeconds(5)).header("Origin", "https://untrusted.invalid")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Session-ID,X-CSRF-TOKEN")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    private UserSession session() {
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", "TASK-04-CSRF");
        sessions.add(session.getSessionId());
        return session;
    }

    private HttpClient client(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private void installCookie(CookieManager cookies, String sessionId) {
        HttpCookie cookie = new HttpCookie(SessionCredentialResolver.COOKIE_NAME, sessionId);
        cookie.setVersion(0);
        cookie.setPath("/");
        cookies.getCookieStore().add(uri("/"), cookie);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + serverPort + path);
    }

    private String cookie(String sessionId) {
        return SessionCredentialResolver.COOKIE_NAME + "=" + sessionId;
    }

    private HttpRequest.Builder post(String path) throws Exception {
        String body = path.equals("/auth/verify")
                ? objectMapper.writeValueAsString(java.util.Map.of("accessCode", authConfiguration.getAccessCode())) : "{}";
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        HttpRequest httpRequest = request.build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (httpRequest.uri().getPath().equals("/auth/verify") && response.statusCode() == 200) {
            String sessionId = objectMapper.readTree(response.body()).path("sessionId").asText("");
            if (!sessionId.isEmpty()) {
                sessions.add(sessionId);
            }
        }
        return response;
    }
}
