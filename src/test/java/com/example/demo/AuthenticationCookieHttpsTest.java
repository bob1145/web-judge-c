package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.AuthRequest;
import com.example.demo.security.SessionCredentialResolver;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.UserAccountService;
import com.example.demo.support.WorkerTlsCertificates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static com.example.demo.support.CsrfTestSupport.token;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "judge.auth.access-code=task02-test-only-access-material", "server.ssl.enabled=true",
        "server.ssl.key-store-type=PKCS12"
})
class AuthenticationCookieHttpsTest {
    @TempDir
    static Path certificateDirectory;
    private static WorkerTlsCertificates.Material certificates;

    @BeforeAll
    static void createCertificates() throws Exception {
        certificates = WorkerTlsCertificates.create(certificateDirectory)
                .issue("cookie-server", "dns:localhost,ip:127.0.0.1", false);
    }

    @DynamicPropertySource
    static void configureTls(DynamicPropertyRegistry registry) {
        registry.add("server.ssl.key-store", () -> certificates.keyStore().toUri().toString());
        registry.add("server.ssl.key-store-password", () -> certificates.storePassword());
    }

    @Autowired
    private AuthConfiguration authConfiguration;
    @Autowired
    private ExecutionProperties executionProperties;
    @Autowired
    private AccessCodeService accessCodeService;
    @Autowired
    private UserAccountService userAccountService;
    @Autowired
    private ObjectMapper objectMapper;
    @LocalServerPort
    private int serverPort;

    private boolean originalAccountAuthEnabled;
    private String originalProfile;
    private List<AuthConfiguration.AccountProperties> originalAccounts;

    @BeforeEach
    void saveConfiguration() {
        originalAccountAuthEnabled = authConfiguration.isAccountAuthEnabled();
        originalProfile = executionProperties.getProfile();
        originalAccounts = new ArrayList<>(authConfiguration.getAccounts());
    }

    @AfterEach
    void restoreConfiguration() {
        authConfiguration.setAccountAuthEnabled(originalAccountAuthEnabled);
        executionProperties.setProfile(originalProfile);
        authConfiguration.setAccounts(originalAccounts);
        userAccountService.clearRateLimits();
    }

    @ParameterizedTest(name = "account={0}, remember={1}")
    @MethodSource("cookieModes")
    void realTlsCookieAuthenticatesIsNotSentOverHttpAndIsCleared(boolean accountMode, boolean rememberMe) throws Exception {
        executionProperties.setProfile(accountMode ? "linux-prod" : "trusted-local");
        authConfiguration.setAccountAuthEnabled(accountMode);
        AuthRequest request = new AuthRequest();
        request.setRememberMe(rememberMe);
        if (accountMode) {
            AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
            account.setUserId("tls-cookie-user-" + UUID.randomUUID());
            account.setUsername("tls-cookie-login-" + UUID.randomUUID());
            account.setPasswordHash(new BCryptPasswordEncoder().encode("CorrectPassword-54"));
            authConfiguration.setAccounts(List.of(account));
            request.setUsername(account.getUsername());
            request.setPassword("CorrectPassword-54");
        } else {
            request.setAccessCode(authConfiguration.getAccessCode());
        }
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        HttpClient client = HttpClient.newBuilder().sslContext(certificates.clientContext()).cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        String baseUrl = "https://127.0.0.1:" + serverPort;
        HttpRequest login = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/verify"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", token(client, URI.create(baseUrl + "/auth")))
                .header("User-Agent", "AuthenticationCookieHttpsTest").header("X-Forwarded-Proto", "http")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request))).build();
        HttpResponse<String> response = client.send(login, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.sslSession()).isPresent();
        assertThat(response.sslSession().orElseThrow().getPeerCertificates()[0]).isEqualTo(certificates.certificate());
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        String sessionId = body.path("sessionId").asText();
        try {
            String header = sessionCookieHeader(response);
            HttpCookie cookie = HttpCookie.parse(header).get(0);
            assertThat(cookie.getSecure()).isTrue();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getPath()).isEqualTo("/");
            assertThat(cookie.getPortlist()).isNull();
            assertThat(header).contains("SameSite=Lax");
            assertThat(cookie.getMaxAge()).isEqualTo(rememberMe ? 30L * 24 * 60 * 60 : -1);
            HttpResponse<String> session = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/auth/session"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(session.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(session.body()).path("valid").asBoolean()).isTrue();
            assertThat(cookies.getCookieStore().getCookies()).anyMatch(stored ->
                    SessionCredentialResolver.COOKIE_NAME.equals(stored.getName()));

            HttpServer plainHttp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            plainHttp.createContext("/capture", exchange -> {
                String incoming = exchange.getRequestHeaders().getFirst("Cookie");
                byte[] bytes = (incoming == null ? "" : incoming).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            plainHttp.start();
            try {
                URI plainUrl = URI.create("http://127.0.0.1:" + plainHttp.getAddress().getPort() + "/capture");
                HttpResponse<String> captured = client.send(HttpRequest.newBuilder(plainUrl)
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(captured.statusCode()).isEqualTo(200);
                assertThat(captured.body()).doesNotContain(SessionCredentialResolver.COOKIE_NAME + "=");
            } finally {
                plainHttp.stop(0);
            }

            HttpResponse<String> logout = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/auth/logout"))
                    .timeout(Duration.ofSeconds(5)).header("X-CSRF-TOKEN", token(client, URI.create(baseUrl + "/")))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(logout.statusCode()).isEqualTo(200);
            String deletionHeader = sessionCookieHeader(logout);
            HttpCookie deleted = HttpCookie.parse(deletionHeader).get(0);
            assertThat(deleted.getSecure()).isTrue();
            assertThat(deleted.isHttpOnly()).isTrue();
            assertThat(deleted.getPath()).isEqualTo(cookie.getPath());
            assertThat(deleted.getDomain()).isEqualTo(cookie.getDomain());
            assertThat(deletionHeader).contains("SameSite=Lax");
            assertThat(deleted.getMaxAge()).isZero();
            assertThat(cookies.getCookieStore().getCookies()).noneMatch(stored ->
                    SessionCredentialResolver.COOKIE_NAME.equals(stored.getName()));
            assertThat(accessCodeService.isSessionValid(sessionId)).isFalse();
        } finally {
            accessCodeService.invalidateSession(sessionId);
        }
    }

    static List<Arguments> cookieModes() {
        return List.of(Arguments.of(false, false), Arguments.of(false, true),
                Arguments.of(true, false), Arguments.of(true, true));
    }

    private String sessionCookieHeader(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(SessionCredentialResolver.COOKIE_NAME + "="))
                .findFirst().orElseThrow();
    }
}
