package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.ProductionSecurityStartupValidator;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SandboxProperties;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.config.WorkerProperties;
import com.example.demo.dto.AuthRequest;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.service.sandbox.SandboxRunner;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.UserAccountService;
import com.example.demo.security.SessionCredentialResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static com.example.demo.support.CsrfTestSupport.withCsrf;
import static com.example.demo.support.CsrfTestSupport.token;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "judge.auth.access-code=task02-test-only-access-material")
@AutoConfigureMockMvc
class ProductionAuthenticationTest {

    private static final String TEST_USER_AGENT = "ProductionAuthenticationTest";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthConfiguration authConfiguration;

    @Autowired
    private ExecutionProperties executionProperties;

    @Autowired
    private AccessCodeService accessCodeService;

    @Autowired
    private UserAccountService userAccountService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private String originalProfile;
    private boolean originalAccountAuthEnabled;
    private List<AuthConfiguration.AccountProperties> originalAccounts;
    private int originalMaxAttempts;
    private AuthConfiguration.SessionEnvironmentPolicy originalEnvironmentPolicy;
    @LocalServerPort
    private int serverPort;

    @BeforeEach
    void captureAuthProperties() {
        originalProfile = executionProperties.getProfile();
        originalAccountAuthEnabled = authConfiguration.isAccountAuthEnabled();
        originalAccounts = new ArrayList<>(authConfiguration.getAccounts());
        originalMaxAttempts = authConfiguration.getMaxAttempts();
        originalEnvironmentPolicy = authConfiguration.getSessionEnvironmentPolicy();
    }

    @AfterEach
    void restoreAuthProperties() {
        executionProperties.setProfile(originalProfile);
        authConfiguration.setAccountAuthEnabled(originalAccountAuthEnabled);
        authConfiguration.setAccounts(originalAccounts);
        authConfiguration.setMaxAttempts(originalMaxAttempts);
        authConfiguration.setSessionEnvironmentPolicy(originalEnvironmentPolicy);
        userAccountService.clearRateLimits();
    }

    @ParameterizedTest
    @CsvSource({
            "false, AUDIT, source, protected", "false, AUDIT, agent, check",
            "false, AUDIT, agent, session", "false, AUDIT, agent, page", "false, AUDIT, agent, ws",
            "false, STRICT, source, protected", "false, STRICT, agent, check",
            "false, STRICT, agent, session", "false, STRICT, agent, page", "false, STRICT, agent, ws",
            "true, AUDIT, source, protected", "true, AUDIT, agent, check",
            "true, AUDIT, agent, session", "true, AUDIT, agent, page", "true, AUDIT, agent, ws"
    })
    void approvedSessionEnvironmentPolicyAppliesToRealHttpAndNativeHandshake(
            boolean admin, AuthConfiguration.SessionEnvironmentPolicy policy, String change, String entry
    ) throws Exception {
        executionProperties.setProfile("trusted-local");
        authConfiguration.setSessionEnvironmentPolicy(policy);
        String username = "environment-user-" + UUID.randomUUID();
        String password = "TestOnly-Environment-Password-61";
        configureAccount("environment-id-" + UUID.randomUUID(), username, password, admin);
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        String csrf = token(client, base.resolve("auth"));
        HttpResponse<String> login = client.send(HttpRequest.newBuilder(base.resolve("auth/verify"))
                .timeout(Duration.ofSeconds(5)).header("User-Agent", TEST_USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(
                        java.util.Map.of("username", username, "password", password)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(login.body()).path("success").asBoolean()).isTrue();
        String sessionId = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> SessionCredentialResolver.COOKIE_NAME.equals(cookie.getName()))
                .findFirst().orElseThrow().getValue();
        boolean shouldRemainValid = !admin && policy == AuthConfiguration.SessionEnvironmentPolicy.AUDIT;
        String currentAgent = "agent".equals(change) ? TEST_USER_AGENT + "-changed" : TEST_USER_AGENT;
        try {
            HttpResponse<String> unchanged = client.send(HttpRequest.newBuilder(base.resolve("auth/check"))
                    .timeout(Duration.ofSeconds(5)).header("User-Agent", TEST_USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(objectMapper.readTree(unchanged.body()).path("success").asBoolean()).isTrue();
            if ("source".equals(change)) {
                try (Socket connection = new Socket()) {
                    connection.bind(new InetSocketAddress("127.0.0.2", 0));
                    connection.connect(new InetSocketAddress("127.0.0.1", serverPort), 5_000);
                    connection.setSoTimeout(5_000);
                    String request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + serverPort
                            + "\r\nCookie: " + SessionCredentialResolver.COOKIE_NAME + "=" + sessionId
                            + "\r\nUser-Agent: " + TEST_USER_AGENT
                            + "\r\nX-Forwarded-For: 127.0.0.1\r\nConnection: close\r\n\r\n";
                    connection.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                    String statusLine = new BufferedReader(new InputStreamReader(
                            connection.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                    assertThat(Integer.parseInt(statusLine.split(" ")[1])).isEqualTo(shouldRemainValid ? 200 : 302);
                }
            } else if ("ws".equals(entry)) {
                var opening = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                        .newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                        .header("Cookie", SessionCredentialResolver.COOKIE_NAME + "=" + sessionId)
                        .header("User-Agent", currentAgent).header("Origin", "http://localhost:" + serverPort)
                        .buildAsync(URI.create("ws://127.0.0.1:" + serverPort + "/ws/websocket"), new WebSocket.Listener() { });
                if (shouldRemainValid) {
                    WebSocket socket = opening.get(5, TimeUnit.SECONDS);
                    try {
                        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS);
                    } finally {
                        socket.abort();
                    }
                } else {
                    assertThatThrownBy(() -> opening.get(5, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class)
                            .satisfies(failure -> assertThat(failure.getCause())
                                    .isInstanceOf(WebSocketHandshakeException.class)
                                    .satisfies(handshake -> assertThat(((WebSocketHandshakeException) handshake)
                                            .getResponse().statusCode()).isEqualTo(401)));
                }
            } else {
                String path = switch (entry) {
                    case "check" -> "auth/check";
                    case "session" -> "auth/session";
                    case "page" -> "auth";
                    default -> throw new IllegalArgumentException(entry);
                };
                HttpResponse<String> changed = client.send(HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofSeconds(5)).header("User-Agent", currentAgent).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(changed.statusCode()).isEqualTo("page".equals(entry) && shouldRemainValid ? 302 : 200);
                if ("check".equals(entry)) {
                    assertThat(objectMapper.readTree(changed.body()).path("success").asBoolean()).isEqualTo(shouldRemainValid);
                } else if ("session".equals(entry)) {
                    assertThat(objectMapper.readTree(changed.body()).path("valid").asBoolean()).isEqualTo(shouldRemainValid);
                }
            }
            HttpResponse<String> checked = client.send(HttpRequest.newBuilder(base.resolve("auth/check"))
                    .timeout(Duration.ofSeconds(5)).header("User-Agent", TEST_USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(objectMapper.readTree(checked.body()).path("success").asBoolean()).isEqualTo(shouldRemainValid);
            assertThat(accessCodeService.isSessionValid(sessionId)).isEqualTo(shouldRemainValid);
        } finally {
            accessCodeService.invalidateSession(sessionId);
        }
    }

    @Test
    void productionRejectsSharedAccessCodeOnlyLogin() throws Exception {
        executionProperties.setProfile("linux-prod");
        configureAccount("access-code-user", "access-code-login", "CorrectPassword-47", false);

        AuthRequest request = new AuthRequest();
        request.setAccessCode(authConfiguration.getAccessCode());

        MvcResult result = mockMvc.perform(withCsrf(mockMvc, post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("message").asText()).contains("account credentials");
    }

    @Test
    void productionAccountLoginCreatesSessionWithStableUserIdentityAndNoPlaintextPassword() throws Exception {
        executionProperties.setProfile("worker-prod");
        String userId = "prod-user-" + UUID.randomUUID();
        configureAccount(userId, "prod-login-" + UUID.randomUUID(), "CorrectPassword-48", true);

        AuthRequest request = new AuthRequest();
        request.setUsername(authConfiguration.getAccounts().get(0).getUsername());
        request.setPassword("CorrectPassword-48");

        MvcResult result = mockMvc.perform(withCsrf(mockMvc, post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("success").asBoolean()).isTrue();
        String sessionId = json.path("sessionId").asText();
        UserSession session = accessCodeService.getSession(sessionId);
        assertThat(session.getUserId()).isEqualTo(userId);
        assertThat(session.isAdmin()).isTrue();
        assertThat(objectMapper.writeValueAsString(session)).doesNotContain("CorrectPassword-48");
    }

    @Test
    void productionStartupRejectsMissingPlaintextOrSampleAdminPasswordAndAcceptsBcryptHash() {
        assertThatThrownBy(() -> productionValidator(new AuthConfiguration()).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account authentication is required");

        AuthConfiguration plaintext = authWithAccount("plain-user", "plain-admin", "admin", true);
        assertThatThrownBy(() -> productionValidator(plaintext).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BCrypt");

        AuthConfiguration samplePassword = authWithAccount(
                "sample-user",
                "sample-admin",
                passwordEncoder.encode("admin"),
                true
        );
        assertThatThrownBy(() -> productionValidator(samplePassword).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sample admin password");

        AuthConfiguration valid = authWithAccount(
                "valid-user",
                "valid-admin",
                passwordEncoder.encode("CorrectPassword-49"),
                true
        );
        assertThatCode(() -> productionValidator(valid).validateNow()).doesNotThrowAnyException();
    }

    @Test
    void productionAccountFailuresAreRateLimited() throws Exception {
        executionProperties.setProfile("linux-prod");
        authConfiguration.setMaxAttempts(2);
        configureAccount("rate-user", "rate-login-" + UUID.randomUUID(), "CorrectPassword-50", false);

        AuthRequest bad = new AuthRequest();
        bad.setUsername(authConfiguration.getAccounts().get(0).getUsername());
        bad.setPassword("wrong-password");
        mockMvc.perform(withCsrf(mockMvc, post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(withCsrf(mockMvc, post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad))))
                .andExpect(status().isUnauthorized());

        AuthRequest correct = new AuthRequest();
        correct.setUsername(bad.getUsername());
        correct.setPassword("CorrectPassword-50");
        MvcResult locked = mockMvc.perform(withCsrf(mockMvc, post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correct))))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json = objectMapper.readTree(locked.getResponse().getContentAsString());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("message").asText()).contains("Too many");
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "removed", "identity-replaced", "admin-revoked"})
    void currentAccountChangesRevokeRealHttpSessionWithoutRevival(String change) throws Exception {
        executionProperties.setProfile("trusted-local");
        String userId = "revoked-user-" + UUID.randomUUID();
        String username = "revoked-login-" + UUID.randomUUID();
        configureAccount(userId, username, "CorrectPassword-54", true);
        AuthConfiguration.AccountProperties account = authConfiguration.getAccounts().get(0);
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
        AuthRequest request = new AuthRequest();
        request.setUsername(username);
        request.setPassword("CorrectPassword-54");
        String sessionId = realHttpLogin(client, base, request);
        try {
            assertHttpSession(client, base, true);
            assertThat(accessCodeService.getSession(sessionId).getAccountUsername()).isEqualTo(username);
            switch (change) {
                case "disabled" -> account.setEnabled(false);
                case "removed" -> authConfiguration.setAccounts(List.of());
                case "identity-replaced" -> account.setUserId(userId + "-replacement");
                case "admin-revoked" -> account.setAdmin(false);
                default -> throw new IllegalArgumentException(change);
            }
            assertHttpSession(client, base, false);
            account.setEnabled(true);
            account.setUserId(userId);
            account.setAdmin(true);
            authConfiguration.setAccounts(List.of(account));
            assertHttpSession(client, base, false);
            assertThat(accessCodeService.getSession(sessionId)).isNull();
            System.out.printf("TASK-06 real HTTP %s: session revoked; restoring account does not revive it%n", change);
        } finally {
            accessCodeService.invalidateSession(sessionId);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activeAccountAndAccessCodeSessionsKeepExistingPrivileges(boolean accountMode) throws Exception {
        executionProperties.setProfile("trusted-local");
        AuthRequest request = new AuthRequest();
        if (accountMode) {
            String username = "active-login-" + UUID.randomUUID();
            configureAccount("active-user-" + UUID.randomUUID(), username, "CorrectPassword-55", false);
            request.setUsername(username);
            request.setPassword("CorrectPassword-55");
        } else {
            authConfiguration.setAccountAuthEnabled(false);
            request.setAccessCode(authConfiguration.getAccessCode());
        }
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
        String sessionId = realHttpLogin(client, base, request);
        try {
            assertHttpSession(client, base, true);
            if (accountMode) {
                authConfiguration.getAccounts().get(0).setAdmin(true);
            } else {
                authConfiguration.setAccounts(List.of());
            }
            assertHttpSession(client, base, true);
            assertThat(accessCodeService.getSession(sessionId).isAdmin()).isFalse();
        } finally {
            accessCodeService.invalidateSession(sessionId);
        }
    }

    private String realHttpLogin(HttpClient client, URI base, AuthRequest request) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve("auth/verify"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("X-CSRF-TOKEN", token(client, base.resolve("auth")))
                .header("User-Agent", TEST_USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("sessionId").asText()).isNotBlank();
        return body.path("sessionId").asText();
    }

    @Test
    void realHttpSourceLimitCannotBeBypassedByAccountOrHeaderRotation() throws Exception {
        int originalSourceLimit = authConfiguration.getMaxSourceAttempts();
        executionProperties.setProfile("trusted-local");
        configureAccount("http-bounds-user", "http-bounds-login-" + UUID.randomUUID(), "CorrectPassword-53", false);
        authConfiguration.setMaxSourceAttempts(2);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        try {
            for (int index = 1; index <= 3; index++) {
                AuthRequest request = new AuthRequest();
                request.setUsername(index == 3 ? authConfiguration.getAccounts().get(0).getUsername()
                        : "http-unknown-" + UUID.randomUUID());
                request.setPassword(index == 3 ? "CorrectPassword-53" : "wrong-password");
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve("auth/verify"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .header("X-CSRF-TOKEN", token(client, base.resolve("auth")))
                        .header("User-Agent", TEST_USER_AGENT)
                        .header("X-Forwarded-For", "203.0.113." + index)
                        .header("X-Real-IP", "198.51.100." + index)
                        .header("Forwarded", "for=192.0.2." + index)
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request))).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(index == 3 ? 429 : 401);
                JsonNode body = objectMapper.readTree(response.body());
                assertThat(body.path("success").asBoolean()).isFalse();
                assertThat(body.path("sessionId").asText("")).isEmpty();
                assertThat(response.headers().allValues("Set-Cookie"))
                        .noneMatch(cookie -> cookie.startsWith(SessionCredentialResolver.COOKIE_NAME + "="));
            }
        } finally {
            authConfiguration.setMaxSourceAttempts(originalSourceLimit);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realHttpSessionSaturationDoesNotPretendLoginSucceeded(boolean accountMode) throws Exception {
        int originalSessionCapacity = authConfiguration.getMaxActiveSessions();
        executionProperties.setProfile("trusted-local");
        AuthRequest request = new AuthRequest();
        if (accountMode) {
            String username = "http-capacity-login-" + UUID.randomUUID();
            configureAccount("http-capacity-user-" + UUID.randomUUID(), username, "CorrectPassword-54", false);
            request.setUsername(username);
            request.setPassword("CorrectPassword-54");
        } else {
            authConfiguration.setAccountAuthEnabled(false);
            request.setAccessCode(authConfiguration.getAccessCode());
        }
        authConfiguration.setMaxActiveSessions(accessCodeService.getActiveSessionCount() + 1);
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        HttpClient first = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
        String sessionId = null;
        try {
            sessionId = realHttpLogin(first, base, request);
            int fullCount = accessCodeService.getActiveSessionCount();
            HttpClient second = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
            HttpResponse<String> response = second.send(HttpRequest.newBuilder(base.resolve("auth/verify"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("X-CSRF-TOKEN", token(second, base.resolve("auth")))
                    .header("User-Agent", TEST_USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            JsonNode body = objectMapper.readTree(response.body());
            assertThat(body.path("success").asBoolean()).isFalse();
            assertThat(body.path("sessionId").asText("")).isEmpty();
            assertThat(response.headers().allValues("Set-Cookie"))
                    .noneMatch(cookie -> cookie.startsWith(SessionCredentialResolver.COOKIE_NAME + "="));
            assertThat(accessCodeService.getActiveSessionCount()).isEqualTo(fullCount);
            assertHttpSession(first, base, true);
        } finally {
            accessCodeService.invalidateSession(sessionId);
            authConfiguration.setMaxActiveSessions(originalSessionCapacity);
        }
    }

    private void assertHttpSession(HttpClient client, URI base, boolean valid) throws Exception {
        for (String endpoint : List.of("auth/session", "auth/check", "")) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve(endpoint))
                    .timeout(Duration.ofSeconds(5)).header("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .header("User-Agent", TEST_USER_AGENT).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s valid=%s", endpoint, valid)
                    .isEqualTo(endpoint.isEmpty() && !valid ? 401 : 200);
            if (!endpoint.isEmpty()) {
                assertThat(objectMapper.readTree(response.body()).path(endpoint.equals("auth/session") ? "valid" : "success")
                        .asBoolean()).as(endpoint).isEqualTo(valid);
            }
        }
    }

    private void configureAccount(String userId, String username, String password, boolean admin) {
        authConfiguration.setAccountAuthEnabled(true);
        authConfiguration.setAccounts(List.of(account(userId, username, passwordEncoder.encode(password), admin)));
    }

    @ParameterizedTest
    @CsvSource({"trusted-local,false,false", "trusted-local,true,true", "linux-prod,false,true"})
    void loginPageRendersOnlyServerSelectedCredentials(String profile, boolean accountEnabled,
                                                      boolean expectedAccountMode) throws Exception {
        executionProperties.setProfile(profile);
        authConfiguration.setAccountAuthEnabled(accountEnabled);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort
                        + "/auth?accountAuthRequired=false&authMode=access-code"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("name=\"_csrf\"", "id=\"remember-me\"");
        if (expectedAccountMode) {
            assertThat(page.body()).contains("id=\"username\"", "id=\"password\"", "请输入用户名和密码以继续使用系统")
                    .doesNotContain("id=\"access-code\"");
        } else {
            assertThat(page.body()).contains("id=\"access-code\"", "请输入访问码以继续使用系统")
                    .doesNotContain("id=\"username\"", "id=\"password\"");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directLoginIgnoresUntrustedForwardingHeadersInBothAuthenticationModes(boolean accountMode) throws Exception {
        executionProperties.setProfile("trusted-local");
        AuthRequest request = new AuthRequest();
        if (accountMode) {
            String username = "source-login-" + UUID.randomUUID();
            configureAccount("source-user-" + UUID.randomUUID(), username, "CorrectPassword-51", false);
            request.setUsername(username);
            request.setPassword("CorrectPassword-51");
        } else {
            authConfiguration.setAccountAuthEnabled(false);
            request.setAccessCode(authConfiguration.getAccessCode());
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/auth/verify"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("X-CSRF-TOKEN", token(client, URI.create("http://127.0.0.1:" + serverPort + "/auth")))
                .header("User-Agent", TEST_USER_AGENT).header("X-Forwarded-For", "203.0.113.20, 198.51.100.21")
                .header("X-Real-IP", "2001:db8::22")
                .header("Forwarded", "for=203.0.113.23;proto=https;host=untrusted.example")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request))).build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        String sessionId = body.path("sessionId").asText();
        try {
            UserSession session = accessCodeService.getSession(sessionId);
            assertThat(session).isNotNull();
            assertThat(session.getIpAddress()).isEqualTo("127.0.0.1");
        } finally {
            accessCodeService.invalidateSession(sessionId);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("redirectCases")
    void redirectsStayLocalAcrossAllThreeLoginBranches(String shape, String redirect, String expected) throws Exception {
        executionProperties.setProfile("trusted-local");
        String query = redirect == null ? "" : "?redirect=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8);
        String baseUrl = "http://127.0.0.1:" + serverPort;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        for (boolean accountMode : new boolean[]{false, true}) {
            AuthRequest request = new AuthRequest();
            if (accountMode) {
                String username = "redirect-login-" + UUID.randomUUID();
                configureAccount("redirect-user-" + UUID.randomUUID(), username, "CorrectPassword-52", false);
                request.setUsername(username);
                request.setPassword("CorrectPassword-52");
            } else {
                authConfiguration.setAccountAuthEnabled(false);
                request.setAccessCode(authConfiguration.getAccessCode());
            }
            HttpRequest login = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/verify" + query))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("X-CSRF-TOKEN", token(client, URI.create(baseUrl + "/auth")))
                    .header("User-Agent", TEST_USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request))).build();
            HttpResponse<String> response = client.send(login, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(shape).isEqualTo(200);
            JsonNode body = objectMapper.readTree(response.body());
            assertThat(body.path("success").asBoolean()).isTrue();
            String sessionId = body.path("sessionId").asText();
            try {
                assertThat(body.path("redirectUrl").asText()).as(shape).isEqualTo(expected);
                if (!accountMode) {
                    HttpRequest authenticatedGet = HttpRequest.newBuilder(URI.create(baseUrl + "/auth" + query))
                            .timeout(Duration.ofSeconds(5)).header("User-Agent", TEST_USER_AGENT)
                            .GET().build();
                    HttpResponse<String> redirected = client.send(authenticatedGet, HttpResponse.BodyHandlers.ofString());
                    assertThat(redirected.statusCode()).isEqualTo(302);
                    URI location = URI.create(redirected.headers().firstValue("Location").orElseThrow());
                    assertThat(URI.create(baseUrl).resolve(location)).as(shape)
                            .isEqualTo(URI.create(baseUrl + expected));
                }
            } finally {
                accessCodeService.invalidateSession(sessionId);
            }
        }
    }

    static List<Arguments> redirectCases() {
        return List.of(
                Arguments.of("default", null, "/"),
                Arguments.of("relative", "history", "/"),
                Arguments.of("local", "/history?filter=active#rows", "/history?filter=active#rows"),
                Arguments.of("normalized", "/judge/../history?filter=active#rows", "/history?filter=active#rows"),
                Arguments.of("absolute", "https://untrusted.example/", "/"),
                Arguments.of("authority", "//untrusted.example/", "/"),
                Arguments.of("triple-slash", "///untrusted.example/", "/"),
                Arguments.of("backslash", "/\\untrusted.example/", "/"),
                Arguments.of("encoded-slash", "/%2funtrusted.example/", "/"),
                Arguments.of("multiple-encoding", "/%252funtrusted.example/", "/"),
                Arguments.of("encoded-backslash", "/%5cuntrusted.example/", "/"),
                Arguments.of("control-path", "/safe%0d%0aLocation:untrusted", "/"),
                Arguments.of("control-query", "/history?filter=%0a", "/"),
                Arguments.of("malformed-percent", "/safe%zz", "/"),
                Arguments.of("malformed-utf8", "/%ff", "/"),
                Arguments.of("login-loop", "/auth", "/"),
                Arguments.of("login-query-loop", "/auth?redirect=/", "/"),
                Arguments.of("dot-loop", "/history/../auth", "/"),
                Arguments.of("root-dot-loop", "/../auth", "/"),
                Arguments.of("encoded-dot-loop", "/history/%2e%2e/auth", "/"),
                Arguments.of("unicode", "/%E4%B8%AD%E6%96%87?label=%E4%B8%AD", "/%E4%B8%AD%E6%96%87?label=%E4%B8%AD"),
                Arguments.of("encoded-query", "/history?q=a%2Bb#result", "/history?q=a%2Bb#result"),
                Arguments.of("literal-percent", "/history%25?q=100%25", "/history%25?q=100%25"),
                Arguments.of("fragment", "/#section", "/#section")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionCookieCases")
    void sessionCookieCreationAndDeletionShareApprovedFlags(String shape, boolean accountMode, boolean rememberMe,
                                                           String profile, String remoteAddress, boolean secure) throws Exception {
        executionProperties.setProfile(profile);
        AuthRequest request = new AuthRequest();
        request.setRememberMe(rememberMe);
        if (accountMode) {
            String username = "cookie-login-" + UUID.randomUUID();
            configureAccount("cookie-user-" + UUID.randomUUID(), username, "CorrectPassword-53", false);
            request.setUsername(username);
            request.setPassword("CorrectPassword-53");
        } else {
            authConfiguration.setAccountAuthEnabled(false);
            request.setAccessCode(authConfiguration.getAccessCode());
        }
        MvcResult login = mockMvc.perform(withCsrf(mockMvc, post("/auth/verify")
                        .with(servletRequest -> { servletRequest.setRemoteAddr(remoteAddress); return servletRequest; })
                        .header("User-Agent", TEST_USER_AGENT).header("X-Forwarded-Proto", secure ? "http" : "https")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request))))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie(SessionCredentialResolver.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        try {
            assertThat(cookie.getSecure()).as(shape).isEqualTo(secure);
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
            assertThat(cookie.getPath()).isEqualTo("/");
            assertThat(cookie.getDomain()).isNull();
            assertThat(cookie.getMaxAge()).isEqualTo(rememberMe ? 30 * 24 * 60 * 60 : -1);
            MvcResult logout = mockMvc.perform(withCsrf(mockMvc, post("/auth/logout")
                            .with(servletRequest -> { servletRequest.setRemoteAddr(remoteAddress); return servletRequest; })
                            .header("User-Agent", TEST_USER_AGENT).cookie(cookie)))
                    .andExpect(status().isOk()).andReturn();
            Cookie deleted = logout.getResponse().getCookie(SessionCredentialResolver.COOKIE_NAME);
            assertThat(deleted).isNotNull();
            assertThat(deleted.getValue()).isEmpty();
            assertThat(deleted.getSecure()).isEqualTo(cookie.getSecure());
            assertThat(deleted.isHttpOnly()).isTrue();
            assertThat(deleted.getAttribute("SameSite")).isEqualTo("Lax");
            assertThat(deleted.getPath()).isEqualTo(cookie.getPath());
            assertThat(deleted.getDomain()).isEqualTo(cookie.getDomain());
            assertThat(deleted.getMaxAge()).isZero();
            assertThat(accessCodeService.isSessionValid(cookie.getValue())).isFalse();
        } finally {
            accessCodeService.invalidateSession(cookie.getValue());
        }
    }

    static List<Arguments> sessionCookieCases() {
        return List.of(
                Arguments.of("local-access-session", false, false, "trusted-local", "127.0.0.1", false),
                Arguments.of("local-access-remember", false, true, "trusted-local", "127.0.0.1", false),
                Arguments.of("local-account-session", true, false, "trusted-local", "127.0.0.1", false),
                Arguments.of("local-account-remember", true, true, "trusted-local", "127.0.0.1", false),
                Arguments.of("production-account-session", true, false, "linux-prod", "127.0.0.1", true),
                Arguments.of("production-account-remember", true, true, "linux-prod", "127.0.0.1", true),
                Arguments.of("local-external", false, false, "trusted-local", "203.0.113.7", true),
                Arguments.of("local-v6-loopback", false, false, "trusted-local", "::1", false),
                Arguments.of("local-v4-loopback-range", false, false, "trusted-local", "127.0.0.2", false)
        );
    }

    private AuthConfiguration authWithAccount(String userId, String username, String passwordHash, boolean admin) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode("changed-secret");
        auth.setDefaultAccessCode("123");
        auth.setAccountAuthEnabled(true);
        auth.setAccounts(List.of(account(userId, username, passwordHash, admin)));
        return auth;
    }

    private AuthConfiguration.AccountProperties account(String userId, String username, String passwordHash, boolean admin) {
        AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
        account.setUserId(userId);
        account.setUsername(username);
        account.setPasswordHash(passwordHash);
        account.setAdmin(admin);
        return account;
    }

    private ProductionSecurityStartupValidator productionValidator(AuthConfiguration auth) {
        ExecutionProperties execution = new ExecutionProperties();
        execution.setProfile("linux-prod");
        execution.setRequireSandbox(true);

        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(true);

        SandboxProperties sandboxProperties = new SandboxProperties();
        sandboxProperties.setProvider(SandboxProperties.Provider.LINUX_CONTAINER);
        sandboxProperties.setIsolation(SandboxProperties.Isolation.CONTAINER);
        sandboxProperties.setSecurityProfile("seccomp:judge-linux");
        sandboxProperties.setCapabilityProbeRequired(true);
        sandboxProperties.setCapabilityProbePassed(true);

        SandboxRunner startupContractRunner = mock(SandboxRunner.class);
        when(startupContractRunner.probe()).thenReturn(SandboxCapabilities.builder()
                .provider("linux-container").isolation("container").productionSafe(true)
                .controlPlaneIsolated(true).networkDisabled(true).nonRoot(true).resourceLimits(true)
                .securityProfile("test-only-authentication-startup-contract").build());
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("startupContractRunner", startupContractRunner);

        return new ProductionSecurityStartupValidator(
                auth,
                execution,
                sandbox,
                sandboxProperties,
                new WebSocketConfig("https://safe.example.com"),
                new WorkerProperties(),
                beanFactory.getBeanProvider(SandboxRunner.class)
        );
    }
}
