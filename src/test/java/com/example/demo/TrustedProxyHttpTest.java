package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.dto.AuthRequest;
import com.example.demo.security.SessionCredentialResolver;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.UserAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static com.example.demo.support.CsrfTestSupport.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "judge.execution.profile=trusted-local",
        "judge.auth.access-code=task03-test-only-proxy-http-material",
        "judge.auth.trusted-proxy-cidrs[0]=127.0.0.1/32",
        "judge.auth.session-environment-policy=STRICT",
        "judge.auth.max-attempts=2",
        "judge.auth.max-source-attempts=2"
})
class TrustedProxyHttpTest {
    private static final String PASSWORD = "task03-test-only-proxy-http-password";
    private static final String AGENT = "TrustedProxyHttpTest";
    private static final String CHAIN = "192.0.2.99, 203.0.113.5, 127.0.0.1";

    @Autowired
    private AuthConfiguration configuration;
    @Autowired
    private AccessCodeService codes;
    @Autowired
    private UserAccountService accounts;
    @Autowired
    private ObjectMapper mapper;
    @LocalServerPort
    private int port;

    private boolean originalMode;
    private List<AuthConfiguration.AccountProperties> originalAccounts;
    private int originalGlobalLimit;
    private final List<String> sessions = new ArrayList<>();
    private String firstUsername;
    private String secondUsername;

    @BeforeEach
    void configureOwnedAccounts() {
        originalMode = configuration.isAccountAuthEnabled();
        originalAccounts = new ArrayList<>(configuration.getAccounts());
        originalGlobalLimit = configuration.getMaxGlobalAccessCodeAttempts();
        firstUsername = "proxy-first-" + UUID.randomUUID();
        secondUsername = "proxy-second-" + UUID.randomUUID();
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        configuration.setAccounts(List.of(account(firstUsername, hash), account(secondUsername, hash)));
        accounts.clearRateLimits();
    }

    @AfterEach
    void restoreOwnedState() {
        sessions.forEach(codes::invalidateSession);
        configuration.setAccountAuthEnabled(originalMode);
        configuration.setAccounts(originalAccounts);
        configuration.setMaxGlobalAccessCodeAttempts(originalGlobalLimit);
        accounts.clearRateLimits();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loginProtectedHttpAndWebSocketUseTheSameTrustedClientSource(boolean accountMode) throws Exception {
        configuration.setAccountAuthEnabled(accountMode);
        HttpClient client = client();
        String sessionId = successfulLogin(client, credentials(accountMode, firstUsername, true), CHAIN);
        assertThat(codes.getSession(sessionId).getIpAddress()).isEqualTo("203.0.113.5");
        assertSession(client, CHAIN);
        HttpClient socketClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        WebSocket socket = socketClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .header("Cookie", SessionCredentialResolver.COOKIE_NAME + "=" + sessionId)
                .header("Origin", "http://localhost:" + port).header("User-Agent", AGENT)
                .header("X-Forwarded-For", CHAIN)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/websocket"), new WebSocket.Listener() {})
                .get(5, TimeUnit.SECONDS);
        try {
            assertThat(socket.isInputClosed()).isFalse();
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "task03").get(5, TimeUnit.SECONDS);
        } finally {
            socket.abort();
        }
        assertSession(client, CHAIN);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedTrustedSourceCannotUpgradeWebSocketOrInvalidateTheOwnerSession(boolean accountMode) throws Exception {
        configuration.setAccountAuthEnabled(accountMode);
        HttpClient owner = client();
        String sessionId = successfulLogin(owner, credentials(accountMode, firstUsername, true), CHAIN);
        HttpClient socketClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        for (String header : List.of("invalid.example", "1".repeat(4_097))) {
            assertThatThrownBy(() -> {
                WebSocket opened = socketClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                        .header("Cookie", SessionCredentialResolver.COOKIE_NAME + "=" + sessionId)
                        .header("Origin", "http://localhost:" + port).header("User-Agent", AGENT)
                        .header("X-Forwarded-For", header)
                        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/websocket"), new WebSocket.Listener() {})
                        .get(5, TimeUnit.SECONDS);
                opened.abort();
            }).isInstanceOfSatisfying(ExecutionException.class, exception -> {
                assertThat(exception.getCause()).isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        handshake -> assertThat(handshake.getResponse().statusCode()).isEqualTo(400));
            });
            assertSession(owner, CHAIN);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void malformedOrOverBudgetTrustedHeadersFailBeforeCreatingASession(boolean accountMode, String header) throws Exception {
        configuration.setAccountAuthEnabled(accountMode);
        HttpClient owner = client();
        successfulLogin(owner, credentials(accountMode, firstUsername, true), CHAIN);
        HttpResponse<String> response = login(client(), credentials(accountMode, firstUsername, true), header);
        assertThat(response.statusCode()).isEqualTo(400);
        assertNoSession(response);
        if (header.equals("invalid.example")) {
            HttpResponse<String> protectedResponse = owner.send(HttpRequest.newBuilder(base())
                    .timeout(Duration.ofSeconds(5)).header("User-Agent", AGENT)
                    .header("X-Forwarded-For", header).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(protectedResponse.statusCode()).isEqualTo(400);
            assertNoSession(protectedResponse);
        }
        assertSession(owner, CHAIN);
    }

    @Test
    void realAccountFailuresCannotBeBypassedByChangingTheProxiedSource() throws Exception {
        configuration.setAccountAuthEnabled(true);
        for (int index = 1; index <= 3; index++) {
            HttpResponse<String> response = login(client(), credentials(true, firstUsername, index == 3),
                    "203.0.113." + (10 + index) + ",127.0.0.1");
            assertThat(response.statusCode()).isEqualTo(index == 3 ? 429 : 401);
            assertNoSession(response);
        }
    }

    @Test
    void realSourceFailuresCannotBeBypassedByChangingAccounts() throws Exception {
        configuration.setAccountAuthEnabled(true);
        for (int index = 1; index <= 3; index++) {
            String username = index == 2 ? secondUsername : firstUsername;
            HttpResponse<String> response = login(client(), credentials(true, username, index == 3),
                    "203.0.113.20,127.0.0.1");
            assertThat(response.statusCode()).isEqualTo(index == 3 ? 429 : 401);
            assertNoSession(response);
        }
    }

    @Test
    void realAccessCodeGlobalFailuresCannotBeBypassedByChangingSources() throws Exception {
        configuration.setAccountAuthEnabled(false);
        configuration.setMaxGlobalAccessCodeAttempts(2);
        for (int index = 1; index <= 3; index++) {
            HttpResponse<String> response = login(client(), credentials(false, firstUsername, index == 3),
                    "203.0.113." + (40 + index) + ",127.0.0.1");
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = mapper.readTree(response.body());
            assertThat(body.path("success").asBoolean()).isFalse();
            if (index == 3) {
                assertThat(body.path("message").asText()).isEqualTo(com.example.demo.model.AuthStatus.TOO_MANY_ATTEMPTS.getMessage());
            }
            assertNoSession(response);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anActualNonTrustedSocketPeerCannotSelectTheForwardedClientSource(boolean accountMode) throws Exception {
        configuration.setAccountAuthEnabled(accountMode);
        HttpClient anonymous = client();
        String csrf = token(anonymous, base().resolve("auth"));
        String cookie = anonymous.cookieHandler().orElseThrow() instanceof CookieManager manager
                ? manager.getCookieStore().getCookies().stream().map(value -> value.getName() + "=" + value.getValue())
                .reduce((first, second) -> first + "; " + second).orElse("") : "";
        byte[] body = mapper.writeValueAsBytes(credentials(accountMode, firstUsername, true));
        String headers = "POST /auth/verify HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                + "\r\nCookie: " + cookie + "\r\nX-CSRF-TOKEN: " + csrf + "\r\nUser-Agent: " + AGENT
                + "\r\nX-Forwarded-For: invalid.example\r\nX-Real-IP: 203.0.113.77\r\n"
                + "Forwarded: for=198.51.100.77\r\n\r\n";
        String response;
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress("127.0.0.2", 0));
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(response.substring(0, response.indexOf("\r\n"))).contains(" 200 ");
        String prefix = "Set-Cookie: " + SessionCredentialResolver.COOKIE_NAME + "=";
        String sessionId = response.lines().filter(line -> line.regionMatches(true, 0, prefix, 0, prefix.length()))
                .map(line -> line.substring(prefix.length()).split(";", 2)[0]).findFirst().orElseThrow();
        sessions.add(sessionId);
        assertThat(codes.getSession(sessionId).getIpAddress()).isEqualTo("127.0.0.2");
    }

    private String successfulLogin(HttpClient client, AuthRequest request, String header) throws Exception {
        HttpResponse<String> response = login(client, request, header);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        String sessionId = body.path("sessionId").asText();
        assertThat(sessionId).isNotBlank();
        sessions.add(sessionId);
        return sessionId;
    }

    private HttpResponse<String> login(HttpClient client, AuthRequest request, String header) throws Exception {
        return client.send(HttpRequest.newBuilder(base().resolve("auth/verify"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", token(client, base().resolve("auth"))).header("User-Agent", AGENT)
                .header("X-Forwarded-For", header).header("X-Real-IP", "198.51.100.99")
                .header("Forwarded", "for=192.0.2.99;proto=https")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void assertSession(HttpClient client, String header) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(base().resolve("auth/check"))
                .timeout(Duration.ofSeconds(5)).header("User-Agent", AGENT).header("X-Forwarded-For", header)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("success").asBoolean()).isTrue();
    }

    private void assertNoSession(HttpResponse<String> response) throws Exception {
        assertThat(mapper.readTree(response.body()).path("sessionId").asText("")).isEmpty();
        assertThat(response.headers().allValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith(SessionCredentialResolver.COOKIE_NAME + "="));
    }

    private AuthRequest credentials(boolean accountMode, String username, boolean valid) {
        AuthRequest request = new AuthRequest();
        if (accountMode) {
            request.setUsername(username);
            request.setPassword(valid ? PASSWORD : "wrong-password");
        } else {
            request.setAccessCode(valid ? configuration.getAccessCode() : "wrong-code");
        }
        return request;
    }

    private AuthConfiguration.AccountProperties account(String username, String hash) {
        AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
        account.setUserId(username);
        account.setUsername(username);
        account.setPasswordHash(hash);
        return account;
    }

    private HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + port + "/");
    }

    private static Stream<Arguments> invalidHeaders() {
        List<String> headers = List.of("invalid.example", "203.0.113.5,", "1".repeat(4_097),
                "203.0.113.5" + ",127.0.0.1".repeat(16));
        return Stream.of(false, true).flatMap(mode -> headers.stream().map(header -> Arguments.of(mode, header)));
    }
}
