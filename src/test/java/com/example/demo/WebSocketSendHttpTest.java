package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.model.UserSession;
import com.example.demo.security.SessionAuditId;
import com.example.demo.security.SessionCredentialResolver;
import com.example.demo.security.WebSocketSessionGuard;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.SessionManagementService;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.support.CsrfTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.Lifecycle;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompDecoder;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.RestTemplateXhrTransport;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "judge.auth.access-code=task06-test-only-access-material",
        "app.websocket.allowed-origins=http://127.0.0.1:*"
})
@Import(WebSocketSendHttpTest.IsolatedTaskStore.class)
class WebSocketSendHttpTest {
    private static final String USER_AGENT = "TASK-06-SEND";
    private static final String READY = "server-ready";
    private static final String AFTER = "server-after-rejected-send";
    private static final Map<String, Object> FORGED = Map.of("status", "AC", "progress", 100);

    @Autowired
    private AccessCodeService accessCodeService;
    @Autowired
    private AuthConfiguration authConfiguration;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FileTaskStore taskStore;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    @Autowired
    private SimpUserRegistry simpUserRegistry;
    @Autowired
    private WebSocketSessionGuard sessionGuard;
    @Autowired
    private SessionManagementService sessionManagementService;
    @Autowired
    private ProgressPublisher progressPublisher;
    @Autowired
    @Qualifier("clientOutboundChannel")
    private ExecutorSubscribableChannel clientOutboundChannel;
    @Autowired
    @Qualifier("brokerChannel")
    private ExecutorSubscribableChannel brokerChannel;
    @Autowired
    private ObjectProvider<SimpleBrokerMessageHandler> brokerProvider;
    @Autowired
    private ExecutionProperties executionProperties;
    @LocalServerPort
    private int serverPort;

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedTaskStore {
        @Bean
        @Primary
        FileTaskStore webSocketTaskStore(ObjectMapper mapper) throws IOException {
            return new FileTaskStore(mapper, Files.createTempDirectory("task06-websocket-"));
        }
    }

    @ParameterizedTest
    @CsvSource({"native,topic", "native,queue", "native,app",
            "websocket,topic", "websocket,queue", "websocket,app",
            "xhr,topic", "xhr,queue", "xhr,app",
            "xhr-polling,topic", "xhr-polling,queue", "xhr-polling,app"})
    @Timeout(30)
    void authenticatedClientSendIsRejectedWithoutForgingServerProgress(String transport, String destinationKind)
            throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        String scenario = transport + "-" + destinationKind;
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT, "ws-owner-" + scenario, false);
        UserSession attacker = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT, "ws-other-" + scenario, false);
        WebSocketStompClient ownerClient = stompClient(transport);
        WebSocketStompClient attackerClient = stompClient(transport);
        StompSession observer = null;
        StompSession producer = null;
        try {
            CookieManager ownerCookies = cookies(base, owner);
            CookieManager attackerCookies = cookies(base, attacker);
            HttpClient ownerHttp = httpClient(ownerCookies);
            String ownerToken = CsrfTestSupport.token(ownerHttp, base);
            String attackerToken = CsrfTestSupport.token(httpClient(attackerCookies), base);
            String judgeId = createTask(ownerHttp, base, ownerToken);
            String topic = "/topic/progress/" + judgeId;
            BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
            CompletableFuture<Void> ready = new CompletableFuture<>();
            CompletableFuture<StompHeaders> rejected = new CompletableFuture<>();
            observer = connect(ownerClient, base, ownerCookies, ownerToken, new StompSessionHandlerAdapter() { });
            observer.subscribe(topic, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    JsonNode frame = (JsonNode) payload;
                    if (READY.equals(frame.path("message").asText())) {
                        ready.complete(null);
                    } else {
                        received.add(frame);
                    }
                }
            });
            long readyDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!ready.isDone() && System.nanoTime() < readyDeadline) {
                messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", READY, 1));
                try {
                    ready.get(100, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException pending) {
                    assertThat(observer.isConnected()).isTrue();
                }
            }
            assertThat(ready.isDone()).isTrue();
            producer = connect(attackerClient, base, attackerCookies, attackerToken, new StompSessionHandlerAdapter() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    rejected.complete(headers);
                }

                @Override
                public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                            byte[] payload, Throwable exception) {
                    rejected.completeExceptionally(exception);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    rejected.completeExceptionally(exception);
                }
            });
            String destination = switch (destinationKind) {
                case "topic" -> topic;
                case "queue" -> "/queue/progress/" + judgeId;
                case "app" -> "/app/progress/" + judgeId;
                default -> throw new IllegalArgumentException(destinationKind);
            };

            producer.send(destination, FORGED);

            assertThat(rejected.get(5, TimeUnit.SECONDS).getFirst("message")).isNotBlank();
            messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", AFTER, 2));
            JsonNode delivered = received.poll(5, TimeUnit.SECONDS);
            assertThat(delivered).isNotNull();
            assertThat(delivered.path("message").asText()).isEqualTo(AFTER);
            assertThat(delivered.path("status").asText()).isEqualTo("RUNNING");
            assertThat(received).noneMatch(frame -> "AC".equals(frame.path("status").asText()));
            System.out.printf("TASK-06 real %s/%s: SEND rejected, server progress delivered%n",
                    transport, destinationKind);
        } finally {
            try {
                if (observer != null && observer.isConnected()) {
                    observer.disconnect();
                }
            } finally {
                accessCodeService.invalidateSession(attacker.getSessionId());
                accessCodeService.invalidateSession(owner.getSessionId());
                try {
                    attackerClient.stop();
                } finally {
                    ownerClient.stop();
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"native,owner", "native,same-owner", "native,admin",
            "websocket,owner", "websocket,same-owner", "websocket,admin",
            "xhr,owner", "xhr,same-owner", "xhr,admin",
            "xhr-polling,owner", "xhr-polling,same-owner", "xhr-polling,admin"})
    @Timeout(30)
    void persistedOwnershipAuthorizesOnlyOwnerAndServerSideAdmin(String transport, String role) throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        String userId = "ws-allowed-" + transport + "-" + role;
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT, userId, false);
        UserSession subscriber = role.equals("owner") ? owner : accessCodeService.createSession(false,
                "127.0.0.1", USER_AGENT, role.equals("same-owner") ? userId : userId + "-admin", role.equals("admin"));
        WebSocketStompClient client = stompClient(transport);
        StompSession session = null;
        try {
            CookieManager ownerCookies = cookies(base, owner);
            HttpClient ownerHttp = httpClient(ownerCookies);
            String ownerToken = CsrfTestSupport.token(ownerHttp, base);
            String judgeId = createTask(ownerHttp, base, ownerToken);
            CookieManager subscriberCookies = subscriber == owner ? ownerCookies : cookies(base, subscriber);
            String token = subscriber == owner ? ownerToken : CsrfTestSupport.token(httpClient(subscriberCookies),
                    HttpRequest.newBuilder(base).header(HttpHeaders.USER_AGENT, USER_AGENT));
            CompletableFuture<StompHeaders> connectedHeaders = new CompletableFuture<>();
            session = connect(client, base, subscriberCookies, token, new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession connected, StompHeaders headers) {
                    connectedHeaders.complete(headers);
                }
            });
            assertThat(connectedHeaders.get(1, TimeUnit.SECONDS).getFirst("user-name"))
                    .isEqualTo(SessionAuditId.from(subscriber.getSessionId()))
                    .doesNotContain(subscriber.getSessionId());
            assertServerProgressReceived(session, "/topic/progress/" + judgeId, "allowed-" + role);
            System.out.printf("TASK-06 real %s/%s: persisted ownership allowed, server identity bound%n", transport, role);
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            client.stop();
            accessCodeService.invalidateSession(subscriber.getSessionId());
            accessCodeService.invalidateSession(owner.getSessionId());
        }
    }

    @ParameterizedTest
    @CsvSource({"native,foreign", "native,missing", "native,wildcard", "native,topic-pattern",
            "native,extra-path", "native,queue", "native,app",
            "websocket,foreign", "websocket,missing", "websocket,wildcard", "websocket,topic-pattern",
            "websocket,extra-path", "websocket,queue", "websocket,app",
            "xhr,foreign", "xhr,missing", "xhr,wildcard", "xhr,topic-pattern",
            "xhr,extra-path", "xhr,queue", "xhr,app",
            "xhr-polling,foreign", "xhr-polling,missing", "xhr-polling,wildcard", "xhr-polling,topic-pattern",
            "xhr-polling,extra-path", "xhr-polling,queue", "xhr-polling,app"})
    @Timeout(30)
    void foreignMissingAndNonExactSubscriptionsAreRejected(String transport, String destinationKind) throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        String userId = "ws-denied-" + transport + "-" + destinationKind;
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT, userId, false);
        UserSession subscriber = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT,
                userId + "-other", !destinationKind.equals("foreign"));
        CompletableFuture<CloseStatus> protocolClosed = new CompletableFuture<>();
        WebSocketStompClient client = stompClient(transport, protocolClosed);
        StompSession session = null;
        try {
            CookieManager ownerCookies = cookies(base, owner);
            HttpClient ownerHttp = httpClient(ownerCookies);
            String judgeId = createTask(ownerHttp, base, CsrfTestSupport.token(ownerHttp, base));
            CookieManager subscriberCookies = cookies(base, subscriber);
            String token = CsrfTestSupport.token(httpClient(subscriberCookies),
                    HttpRequest.newBuilder(base).header(HttpHeaders.USER_AGENT, USER_AGENT));
            CompletableFuture<StompHeaders> rejected = new CompletableFuture<>();
            CompletableFuture<JsonNode> unauthorizedProgress = new CompletableFuture<>();
            session = connect(client, base, subscriberCookies, token, rejectionHandler(rejected), !transport.equals("native"));
            String destination = switch (destinationKind) {
                case "foreign" -> "/topic/progress/" + judgeId;
                case "missing" -> "/topic/progress/missing-" + judgeId;
                case "wildcard" -> "/topic/progress/*";
                case "topic-pattern" -> "/topic/**";
                case "extra-path" -> "/topic/progress/" + judgeId + "/extra";
                case "queue" -> "/queue/progress/" + judgeId;
                case "app" -> "/app/progress/" + judgeId;
                default -> throw new IllegalArgumentException(destinationKind);
            };
            session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    unauthorizedProgress.complete((JsonNode) payload);
                    rejected.completeExceptionally(new AssertionError("Unauthorized subscription received data"));
                }
            });
            Object response = CompletableFuture.anyOf(protocolClosed, rejected).get(5, TimeUnit.SECONDS);
            assertProtocolRejection(transport, response);
            assertThat(unauthorizedProgress.isDone()).isFalse();
            System.out.printf("TASK-06 real %s/%s: SUBSCRIBE rejected despite client ADMIN claim, proof=%s%n",
                    transport, destinationKind, response instanceof StompHeaders ? "ERROR" : response);
        } finally {
            client.stop();
            accessCodeService.invalidateSession(subscriber.getSessionId());
            accessCodeService.invalidateSession(owner.getSessionId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ws/info", "ws/websocket", "ws/000/guard-session/xhr",
            "ws/000/guard-session/xhr_streaming"})
    @Timeout(30)
    void handshakeHttpPathsRejectAnonymousQueryExpiredAndConflictingCredentials(String endpoint) throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession valid = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT);
        UserSession expired = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT);
        expired.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
        HttpClient client = httpClient(new CookieManager());
        try {
            for (String credential : List.of("anonymous", "query", "unknown", "expired", "conflict")) {
                URI target = base.resolve(endpoint + (credential.equals("query") ? "?sessionId=" + valid.getSessionId() : ""));
                HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5));
                switch (credential) {
                    case "unknown" -> request.header(SessionCredentialResolver.HEADER_NAME, "unknown-session");
                    case "expired" -> request.header(SessionCredentialResolver.HEADER_NAME, expired.getSessionId());
                    case "conflict" -> request.header(HttpHeaders.COOKIE,
                            SessionCredentialResolver.COOKIE_NAME + "=" + valid.getSessionId())
                            .header(SessionCredentialResolver.HEADER_NAME, "different-session");
                    default -> { }
                }
                HttpResponse<String> response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as("%s / %s", endpoint, credential).isEqualTo(401);
                assertThat(response.body()).doesNotContain(valid.getSessionId(), expired.getSessionId());
            }
            HttpResponse<String> headerAllowed = client.send(HttpRequest.newBuilder(base.resolve("ws/info"))
                    .timeout(Duration.ofSeconds(5)).header(SessionCredentialResolver.HEADER_NAME, valid.getSessionId())
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(headerAllowed.statusCode()).isEqualTo(200);
            System.out.printf("TASK-06 real HTTP %s: invalid credentials rejected, header authentication allowed%n", endpoint);
        } finally {
            accessCodeService.invalidateSession(expired.getSessionId());
            accessCodeService.invalidateSession(valid.getSessionId());
        }
    }

    @Test
    @Timeout(30)
    void sockJsHttpTransportCannotReuseAnotherSessionsConnection() throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT);
        UserSession other = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT);
        HttpClient ownerHttp = httpClient(cookies(base, owner));
        HttpClient otherHttp = httpClient(cookies(base, other));
        try {
            String ownerToken = CsrfTestSupport.token(ownerHttp, base);
            String otherToken = CsrfTestSupport.token(otherHttp, base);
            String path = "ws/000/task06-identity-transport/";
            HttpResponse<String> opened = postTransport(ownerHttp, base, path + "xhr", ownerToken, "");
            assertThat(opened.statusCode()).isEqualTo(200);
            assertThat(opened.body()).isEqualTo("o\n");
            String connect = objectMapper.writeValueAsString(List.of("CONNECT\naccept-version:1.2\nheart-beat:0,0\n"
                    + "X-CSRF-TOKEN:" + ownerToken + "\n\n\u0000"));
            for (String endpoint : List.of("xhr_send", "xhr", "xhr_streaming")) {
                HttpResponse<String> denied = postTransport(otherHttp, base, path + endpoint, otherToken, connect);
                assertThat(denied.statusCode()).as("another Principal at %s", endpoint).isEqualTo(404);
            }
            assertThat(postTransport(ownerHttp, base, path + "xhr_send", ownerToken, connect).statusCode()).isEqualTo(204);
            HttpResponse<String> connected = postTransport(ownerHttp, base, path + "xhr", ownerToken, "");
            assertThat(connected.statusCode()).isEqualTo(200);
            assertThat(connected.body()).contains("CONNECTED", SessionAuditId.from(owner.getSessionId()))
                    .doesNotContain(owner.getSessionId(), other.getSessionId());
            String disconnect = objectMapper.writeValueAsString(List.of("DISCONNECT\n\n\u0000"));
            assertThat(postTransport(ownerHttp, base, path + "xhr_send", ownerToken, disconnect).statusCode()).isEqualTo(204);
            System.out.println("TASK-06 real SockJS HTTP: cross-session read/send rejected, original identity connected");
        } finally {
            accessCodeService.invalidateSession(other.getSessionId());
            accessCodeService.invalidateSession(owner.getSessionId());
        }
    }

    @ParameterizedTest
    @CsvSource({"native,missing", "native,wrong", "native,other-session",
            "websocket,missing", "websocket,wrong", "websocket,other-session",
            "xhr,missing", "xhr,wrong", "xhr,other-session",
            "xhr-polling,missing", "xhr-polling,wrong", "xhr-polling,other-session"})
    @Timeout(30)
    void connectRequiresItsOwnSessionBoundCsrfToken(String transport, String tokenKind) throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT);
        UserSession other = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT);
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketStompClient client = stompClient(transport, closed);
        try {
            CookieManager ownerCookies = cookies(base, owner);
            String ownerToken = CsrfTestSupport.token(httpClient(ownerCookies), base);
            String otherToken = CsrfTestSupport.token(httpClient(cookies(base, other)), base);
            String connectToken = switch (tokenKind) {
                case "missing" -> null;
                case "wrong" -> "not-a-valid-csrf-token";
                case "other-session" -> otherToken;
                default -> throw new IllegalArgumentException(tokenKind);
            };
            CompletableFuture<StompHeaders> rejected = new CompletableFuture<>();
            assertThatThrownBy(() -> connect(client, base, ownerCookies, ownerToken, connectToken, null,
                    rejectionHandler(rejected), !transport.equals("native"))).isInstanceOf(ExecutionException.class);
            Object proof = CompletableFuture.anyOf(closed, rejected).get(5, TimeUnit.SECONDS);
            assertProtocolRejection(transport, proof);
            System.out.printf("TASK-06 real %s/%s: CONNECT CSRF rejected despite valid HTTP token, proof=%s%n",
                    transport, tokenKind, proof instanceof StompHeaders ? "ERROR" : proof);
        } finally {
            client.stop();
            accessCodeService.invalidateSession(other.getSessionId());
            accessCodeService.invalidateSession(owner.getSessionId());
        }
    }

    @ParameterizedTest
    @CsvSource({"native,cookie", "native,header", "websocket,cookie", "websocket,header",
            "xhr,cookie", "xhr,header", "xhr-polling,cookie", "xhr-polling,header"})
    @Timeout(30)
    void connectTokenAllowsCookieAndHeaderIdentityWithoutHttpCsrfHeader(String transport, String credentialMode)
            throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT,
                "ws-csrf-allowed-" + transport + "-" + credentialMode, false);
        boolean headerMode = credentialMode.equals("header");
        CookieManager ownerCookies = headerMode ? new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
                : cookies(base, owner);
        HttpClient ownerHttp = httpClient(ownerCookies);
        WebSocketStompClient client = stompClient(transport);
        StompSession session = null;
        try {
            String credentialHeader = headerMode ? owner.getSessionId() : null;
            String token = headerMode ? CsrfTestSupport.token(ownerHttp, HttpRequest.newBuilder(base)
                    .header(SessionCredentialResolver.HEADER_NAME, credentialHeader)) : CsrfTestSupport.token(ownerHttp, base);
            if (headerMode) {
                assertThat(ownerCookies.getCookieStore().getCookies())
                        .noneMatch(cookie -> SessionCredentialResolver.COOKIE_NAME.equals(cookie.getName()))
                        .anyMatch(cookie -> "JSESSIONID".equals(cookie.getName()));
            }
            String judgeId = createTask(ownerHttp, base, token, credentialHeader);
            session = connect(client, base, ownerCookies, null, token, credentialHeader,
                    new StompSessionHandlerAdapter() { }, !transport.equals("native"));
            assertServerProgressReceived(session, "/topic/progress/" + judgeId, "csrf-allowed-" + credentialMode);
            System.out.printf("TASK-06 real %s/%s: native CONNECT token accepted without HTTP CSRF header%n",
                    transport, credentialMode);
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            client.stop();
            accessCodeService.invalidateSession(owner.getSessionId());
        }
    }

    @Test
    @Timeout(30)
    void sockJsHttpCsrfAdaptationIsNarrowAndStillRejectsUnapprovedOrigin() throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT);
        HttpClient client = httpClient(cookies(base, owner));
        try {
            CsrfTestSupport.token(client, base);
            for (String path : List.of("judge", "auth", "ws/info", "ws/000/csrf-path/other",
                    "ws/000/csrf-path/xhr_send/extra")) {
                HttpResponse<String> denied = client.send(HttpRequest.newBuilder(base.resolve(path))
                        .timeout(Duration.ofSeconds(5)).header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(denied.statusCode()).as("HTTP CSRF remains required at %s", path).isEqualTo(403);
            }
            HttpResponse<String> originDenied = client.send(HttpRequest.newBuilder(base.resolve("ws/000/csrf-origin/xhr"))
                    .timeout(Duration.ofSeconds(5)).header(HttpHeaders.ORIGIN, "http://unapproved.invalid")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(originDenied.statusCode()).isEqualTo(403);
            System.out.println("TASK-06 real HTTP: non-transport POST still requires CSRF; SockJS rejects unapproved Origin");
        } finally {
            accessCodeService.invalidateSession(owner.getSessionId());
        }
    }

    @ParameterizedTest
    @CsvSource({"native,disabled", "native,admin-revoked", "websocket,disabled", "websocket,admin-revoked",
            "xhr,disabled", "xhr,admin-revoked", "xhr-polling,disabled", "xhr-polling,admin-revoked"})
    @Timeout(30)
    void realAccountRevocationRejectsFurtherSubscriptionAndNewHandshake(String transport, String change) throws Exception {
        assertAccountRevocation(transport, change, false);
    }

    @ParameterizedTest
    @CsvSource({"native,disabled", "native,admin-revoked", "native,logout", "native,expired",
            "websocket,disabled", "websocket,admin-revoked", "websocket,logout", "websocket,expired",
            "xhr,disabled", "xhr,admin-revoked", "xhr,logout", "xhr,expired",
            "xhr-polling,disabled", "xhr-polling,admin-revoked", "xhr-polling,logout", "xhr-polling,expired"})
    @Timeout(30)
    void revokedAccountCannotReceiveExistingSubscriptionWithoutAnotherClientFrame(String transport, String change)
            throws Exception {
        assertAccountRevocation(transport, change, true);
    }

    private void assertAccountRevocation(String transport, String change, boolean checkOutgoing) throws Exception {
        assertAccountRevocation(transport, change, checkOutgoing, true);
    }

    @ParameterizedTest
    @CsvSource({"native,disabled", "native,admin-revoked", "native,logout", "native,expired",
            "websocket,disabled", "websocket,admin-revoked", "websocket,logout", "websocket,expired",
            "xhr,disabled", "xhr,admin-revoked", "xhr,logout", "xhr,expired",
            "xhr-polling,disabled", "xhr-polling,admin-revoked", "xhr-polling,logout", "xhr-polling,expired"})
    @Timeout(30)
    void revokedConnectionsCloseWithoutClientFramesOrServerPush(String transport, String change) throws Exception {
        assertAccountRevocation(transport, change, true, false);
    }

    private void assertAccountRevocation(String transport, String change, boolean checkOutgoing, boolean publishAfterRevocation)
            throws Exception {
        boolean originalAccountMode = authConfiguration.isAccountAuthEnabled();
        List<AuthConfiguration.AccountProperties> originalAccounts = authConfiguration.getAccounts();
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        String ownerId = "ws-account-owner-" + UUID.randomUUID();
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT, ownerId, false);
        AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
        account.setUserId(change.equals("admin-revoked") ? ownerId + "-admin" : ownerId);
        account.setUsername("ws-account-login-" + UUID.randomUUID());
        account.setPasswordHash(new BCryptPasswordEncoder().encode("CorrectPassword-56"));
        account.setAdmin(change.equals("admin-revoked"));
        authConfiguration.setAccountAuthEnabled(true);
        authConfiguration.setAccounts(List.of(account));
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        CompletableFuture<URI> xhrSendUri = new CompletableFuture<>();
        CompletableFuture<Integer> transportStatus = new CompletableFuture<>();
        WebSocketStompClient client = stompClient(transport, closed, xhrSendUri, transportStatus);
        String sessionId = null;
        StompSession session = null;
        try {
            CookieManager subscriberCookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            HttpClient subscriberHttp = httpClient(subscriberCookies);
            HttpResponse<String> login = subscriberHttp.send(HttpRequest.newBuilder(base.resolve("auth/verify"))
                    .timeout(Duration.ofSeconds(5)).header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header("X-CSRF-TOKEN", CsrfTestSupport.token(subscriberHttp, base.resolve("auth")))
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                            "username", account.getUsername(), "password", "CorrectPassword-56")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            JsonNode loginBody = objectMapper.readTree(login.body());
            assertThat(loginBody.path("success").asBoolean()).isTrue();
            sessionId = loginBody.path("sessionId").asText();
            assertThat(sessionId).isNotBlank();
            String token = CsrfTestSupport.token(subscriberHttp,
                    HttpRequest.newBuilder(base).header(HttpHeaders.USER_AGENT, USER_AGENT));
            HttpClient ownerHttp = httpClient(cookies(base, owner));
            String ownerToken = CsrfTestSupport.token(ownerHttp, base);
            String topic = "/topic/progress/" + createTask(ownerHttp, base, ownerToken);
            String nextTopic = "/topic/progress/" + createTask(ownerHttp, base, ownerToken);
            CompletableFuture<StompHeaders> rejected = new CompletableFuture<>();
            session = connect(client, base, subscriberCookies, token, rejectionHandler(rejected),
                    !transport.equals("native"));
            BlockingQueue<JsonNode> received = assertServerProgressReceived(session, topic, "account-allowed-" + change);
            assertThat(simpUserRegistry.getUser(SessionAuditId.from(sessionId))).isNotNull();
            if (change.equals("disabled")) {
                account.setEnabled(false);
            } else if (change.equals("admin-revoked")) {
                account.setAdmin(false);
            } else if (change.equals("logout")) {
                HttpResponse<String> logout = subscriberHttp.send(HttpRequest.newBuilder(base.resolve("auth/logout"))
                        .timeout(Duration.ofSeconds(5)).header("X-CSRF-TOKEN", token)
                        .header(HttpHeaders.USER_AGENT, USER_AGENT).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(logout.statusCode()).isEqualTo(200);
                assertThat(objectMapper.readTree(logout.body()).path("success").asBoolean()).isTrue();
            } else if (change.equals("expired")) {
                accessCodeService.getSession(sessionId).setExpiresAt(java.time.LocalDateTime.now().plusSeconds(1));
                Thread.sleep(1100);
            } else {
                throw new IllegalArgumentException("Unknown revocation scenario");
            }
            if (checkOutgoing) {
                String protectedMessage = "must-not-deliver-after-" + change;
                received.clear();
                if (publishAfterRevocation) {
                    messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", protectedMessage, 1));
                } else if (!change.equals("logout")) {
                    sessionManagementService.scheduledCleanup();
                }
                JsonNode first = received.poll(500, TimeUnit.MILLISECONDS);
                assertThat(first == null ? null : first.path("message").asText()).isNotEqualTo(protectedMessage);
                Object proof = CompletableFuture.anyOf(closed, rejected).get(5, TimeUnit.SECONDS);
                assertThat(proof).isInstanceOf(CloseStatus.class);
                CloseStatus close = (CloseStatus) proof;
                if (transport.equals("xhr-polling") && close.getCode() == 3000) {
                    assertThat(close.getReason()).isEqualTo("Go away!");
                } else if (transport.equals("xhr-polling") && close.getCode() == 1006) {
                    assertThat(transportStatus.get(5, TimeUnit.SECONDS)).isEqualTo(401);
                } else {
                    assertThat(close.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
                }
                long closeDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (simpUserRegistry.getUser(SessionAuditId.from(sessionId)) != null
                        && System.nanoTime() < closeDeadline) {
                    Thread.sleep(20);
                }
                assertThat(simpUserRegistry.getUser(SessionAuditId.from(sessionId))).isNull();
                assertThat(received.stream().map(frame -> frame.path("message").asText()))
                        .doesNotContain(protectedMessage);
                if (!publishAfterRevocation) {
                    assertThat(received).isEmpty();
                    assertThat(sessionGuard.getConnectionCount()).isZero();
                }
                System.out.printf("TASK-06 real %s/%s: existing subscription blocked, serverPush=%s, close=%s%n",
                        transport, change, publishAfterRevocation, close);
            } else if (transport.startsWith("xhr")) {
                URI sendUri = xhrSendUri.get(1, TimeUnit.SECONDS);
                String frame = "SUBSCRIBE\nid:revoked\ndestination:" + nextTopic + "\n\n\u0000";
                HttpResponse<String> denied = postTransport(subscriberHttp, base, sendUri.getRawPath(), token,
                        objectMapper.writeValueAsString(List.of(frame)));
                assertThat(denied.statusCode()).isEqualTo(401);
                System.out.printf("TASK-06 real %s/%s: active transport SUBSCRIBE POST rejected with HTTP 401%n",
                        transport, change);
            } else {
                BlockingQueue<JsonNode> unauthorized = new LinkedBlockingQueue<>();
                session.subscribe(nextTopic, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return JsonNode.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        unauthorized.add((JsonNode) payload);
                    }
                });
                Object proof = CompletableFuture.anyOf(closed, rejected).get(5, TimeUnit.SECONDS);
                if (proof instanceof CloseStatus close) {
                    assertThat(close.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
                } else {
                    assertProtocolRejection(transport, proof);
                }
                messagingTemplate.convertAndSend(nextTopic, new JudgeProgress("RUNNING", "must-not-deliver", 1));
                assertThat(unauthorized.poll(200, TimeUnit.MILLISECONDS)).isNull();
                System.out.printf("TASK-06 real %s/%s: next SUBSCRIBE rejected, proof=%s%n",
                        transport, change, proof instanceof StompHeaders ? "ERROR" : proof);
            }
            HttpResponse<String> handshake = subscriberHttp.send(HttpRequest.newBuilder(base.resolve("ws/info"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(handshake.statusCode()).isEqualTo(401);
            assertThat(accessCodeService.getSession(sessionId)).isNull();
        } finally {
            try {
                if (session != null && session.isConnected()) {
                    try {
                        session.disconnect();
                    } catch (MessageDeliveryException deniedDisconnect) {
                        assertThat(deniedDisconnect).hasRootCauseInstanceOf(HttpClientErrorException.Unauthorized.class);
                    }
                }
            } finally {
                client.stop();
                accessCodeService.invalidateSession(sessionId);
                accessCodeService.invalidateSession(owner.getSessionId());
                authConfiguration.setAccounts(originalAccounts);
                authConfiguration.setAccountAuthEnabled(originalAccountMode);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "websocket", "xhr", "xhr-polling"})
    @Timeout(40)
    void ownerConnectionLimitIsSharedAcrossLoginsAndReleasedAfterRealDisconnect(String transport) throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        String ownerId = "connection-owner-" + UUID.randomUUID();
        UserSession firstIdentity = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT, ownerId, false);
        UserSession secondIdentity = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT, ownerId, false);
        CookieManager firstCookies = cookies(base, firstIdentity);
        CookieManager secondCookies = cookies(base, secondIdentity);
        String firstToken = CsrfTestSupport.token(httpClient(firstCookies), base);
        String secondToken = CsrfTestSupport.token(httpClient(secondCookies), base);
        CompletableFuture<CloseStatus> deniedClose = new CompletableFuture<>();
        List<WebSocketStompClient> clients = List.of(stompClient(transport, new CompletableFuture<>()),
                stompClient(transport, new CompletableFuture<>()), stompClient(transport, deniedClose));
        StompSession first = null;
        StompSession second = null;
        StompSession replacement = null;
        try {
            assertConnectionCount(0);
            first = connect(clients.get(0), base, firstCookies, firstToken, new StompSessionHandlerAdapter() { },
                    !transport.equals("native"));
            second = connect(clients.get(1), base, secondCookies, secondToken, new StompSessionHandlerAdapter() { },
                    !transport.equals("native"));
            assertConnectionCount(2);
            assertThatThrownBy(() -> connect(clients.get(2), base, secondCookies, secondToken,
                    new StompSessionHandlerAdapter() { }, !transport.equals("native"))).isInstanceOf(ExecutionException.class);
            assertPolicyClose(transport, deniedClose.get(5, TimeUnit.SECONDS));
            assertConnectionCount(2);
            first.disconnect();
            assertConnectionCount(1);
            replacement = connect(clients.get(0), base, firstCookies, firstToken, new StompSessionHandlerAdapter() { },
                    !transport.equals("native"));
            assertConnectionCount(2);
            String topic = "/topic/progress/" + createTask(httpClient(firstCookies), base, firstToken);
            assertServerProgressReceived(replacement, topic, "capacity-released-" + transport);
            System.out.printf("TASK-06 real %s: two login sessions share owner limit=2; actual disconnect releases slot%n", transport);
        } finally {
            if (first != null && first.isConnected()) {
                first.disconnect();
            }
            if (second != null && second.isConnected()) {
                second.disconnect();
            }
            if (replacement != null && replacement.isConnected()) {
                replacement.disconnect();
            }
            clients.forEach(WebSocketStompClient::stop);
            accessCodeService.invalidateSession(firstIdentity.getSessionId());
            accessCodeService.invalidateSession(secondIdentity.getSessionId());
            assertConnectionCount(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "websocket", "xhr", "xhr-polling"})
    @Timeout(30)
    void subscriptionLimitIncludesDistinctIdsAndUnsubscribeReleasesOneSlot(String transport) throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession identity = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT,
                "subscription-owner-" + UUID.randomUUID(), false);
        CookieManager cookies = cookies(base, identity);
        String token = CsrfTestSupport.token(httpClient(cookies), base);
        String topic = "/topic/progress/" + createTask(httpClient(cookies), base, token);
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        CompletableFuture<StompHeaders> rejected = new CompletableFuture<>();
        WebSocketStompClient client = stompClient(transport, closed);
        StompSession session = null;
        try {
            session = connect(client, base, cookies, token, rejectionHandler(rejected), !transport.equals("native"));
            BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
            StompFrameHandler handler = new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    received.add((JsonNode) payload);
                }
            };
            List<StompSession.Subscription> subscriptions = new java.util.ArrayList<>();
            for (int subscriptionIndex = 0; subscriptionIndex < 8; subscriptionIndex++) {
                subscriptions.add(session.subscribe(topic, handler));
            }
            assertSubscriptionCount(identity, 8);
            messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", "eight-subscriptions", 1));
            for (int subscriptionIndex = 0; subscriptionIndex < 8; subscriptionIndex++) {
                JsonNode delivered = received.poll(5, TimeUnit.SECONDS);
                assertThat(delivered).isNotNull();
                assertThat(delivered.path("message").asText()).isEqualTo("eight-subscriptions");
            }
            subscriptions.get(0).unsubscribe();
            assertSubscriptionCount(identity, 7);
            session.subscribe(topic, handler);
            assertSubscriptionCount(identity, 8);
            session.subscribe(topic, handler);
            Object proof = CompletableFuture.anyOf(closed, rejected).get(5, TimeUnit.SECONDS);
            assertProtocolRejection(transport, proof);
            assertConnectionCount(0);
            assertThat(simpUserRegistry.getUser(SessionAuditId.from(identity.getSessionId()))).isNull();
            System.out.printf("TASK-06 real %s: eight subscriptions allowed, unsubscribe releases slot, ninth rejected%n", transport);
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            client.stop();
            accessCodeService.invalidateSession(identity.getSessionId());
            assertConnectionCount(0);
        }
    }

    private void assertConnectionCount(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (sessionGuard.getConnectionCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(sessionGuard.getConnectionCount()).isEqualTo(expected);
    }

    private void assertSubscriptionCount(UserSession identity, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var user = simpUserRegistry.getUser(SessionAuditId.from(identity.getSessionId()));
            if (user != null && user.getSessions().stream().mapToInt(connection -> connection.getSubscriptions().size()).sum() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        var user = simpUserRegistry.getUser(SessionAuditId.from(identity.getSessionId()));
        assertThat(user).isNotNull();
        assertThat(user.getSessions().stream().mapToInt(connection -> connection.getSubscriptions().size()).sum()).isEqualTo(expected);
    }

    private void assertPolicyClose(String transport, CloseStatus status) {
        if (transport.startsWith("xhr") && status.getCode() == 3000) {
            assertThat(status.getReason()).isEqualTo("Go away!");
        } else {
            assertThat(status.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        }
    }

    @ParameterizedTest
    @CsvSource({"native,count", "websocket,count", "xhr,count", "xhr-polling,count",
            "native,bytes", "websocket,bytes", "xhr,bytes", "xhr-polling,bytes"})
    @Timeout(45)
    void pendingProgressBudgetIncludesThePreservedOrderBacklogAndKeepsRealTerminalRecoverable(String transport, String budget)
            throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT,
                "pending-owner-" + UUID.randomUUID(), false);
        CookieManager ownerCookies = cookies(base, owner);
        HttpClient ownerHttp = httpClient(ownerCookies);
        String token = CsrfTestSupport.token(ownerHttp, base);
        String judgeId = createTask(ownerHttp, base, token);
        JudgeProgress terminal = finishRealTask(ownerHttp, base, token, judgeId);
        String topic = "/topic/progress/" + judgeId;
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketStompClient client = stompClient(transport, closed);
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        BlockingQueue<String> receivedIds = new LinkedBlockingQueue<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean claimed = new AtomicBoolean();
        AtomicReference<String> blockedId = new AtomicReference<>();
        StompSession observer = null;
        ExecutorChannelInterceptor blocker = new ExecutorChannelInterceptor() {
            @Override
            public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
                if (topic.equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))
                        && claimed.compareAndSet(false, true)) {
                    blockedId.set(StompHeaderAccessor.wrap(message).getMessageId());
                    firstEntered.countDown();
                    try {
                        if (!release.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Budget test did not release the outbound handler");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Budget test outbound handler interrupted", interrupted);
                    }
                }
                return message;
            }
        };
        try {
            observer = connect(client, base, ownerCookies, token, new StompSessionHandlerAdapter() { },
                    !transport.equals("native"));
            observer.subscribe(topic, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    received.add((JsonNode) payload);
                    receivedIds.add(headers.getMessageId());
                }
            });
            assertSubscriptionCount(owner, 1);
            JsonNode ready = null;
            long readyDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (ready == null && System.nanoTime() < readyDeadline) {
                assertThat(progressPublisher.publish(judgeId, terminal)).isEqualTo(terminal);
                ready = received.poll(100, TimeUnit.MILLISECONDS);
            }
            assertThat(ready).isNotNull();
            assertThat(objectMapper.treeToValue(ready, JudgeProgress.class)).isEqualTo(terminal);
            String connectionId = simpUserRegistry.getUser(SessionAuditId.from(owner.getSessionId()))
                    .getSessions().iterator().next().getId();
            assertPendingProgress(connectionId, 0);
            received.clear();
            receivedIds.clear();
            clientOutboundChannel.addInterceptor(blocker);
            assertThat(progressPublisher.publish(judgeId, terminal)).isEqualTo(terminal);
            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(blockedId.get()).isNotBlank();
            if (budget.equals("count")) {
                for (int progressIndex = 1; progressIndex < 32; progressIndex++) {
                    assertThat(progressPublisher.publish(judgeId, terminal)).isEqualTo(terminal);
                    assertThat(sessionGuard.getPendingProgressCount(connectionId)).isEqualTo(progressIndex + 1);
                    assertThat(sessionGuard.getPendingProgressBytes(connectionId)).isBetween(1L, 2L * 1024 * 1024);
                }
                assertThat(progressPublisher.publish(judgeId, terminal)).isEqualTo(terminal);
            } else {
                assertThat(sessionGuard.getPendingProgressCount(connectionId)).isEqualTo(1);
                messagingTemplate.convertAndSend(topic, new JudgeProgress("UNKNOWN", "汉\\\"".repeat(700_000), 0));
            }
            assertPolicyClose(transport, closed.get(5, TimeUnit.SECONDS));
            assertConnectionCount(0);
            assertThat(sessionGuard.getPendingProgressCount(connectionId)).isZero();
            assertThat(sessionGuard.getPendingProgressBytes(connectionId)).isZero();
            release.countDown();
            Thread.sleep(100);
            assertThat(receivedIds).doesNotContain(blockedId.get());
            for (JsonNode frame : received) {
                assertThat(objectMapper.treeToValue(frame, JudgeProgress.class)).isEqualTo(terminal);
            }
            assertThat(new FileTaskStore(objectMapper, taskStore.storageBase()).findSummary(judgeId).orElseThrow())
                    .isEqualTo(terminal);
            assertThat(objectMapper.readValue(taskStore.taskDirectory(judgeId).resolve("final.json").toFile(), JudgeProgress.class))
                    .isEqualTo(terminal);
            HttpResponse<String> recovered = ownerHttp.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header(HttpHeaders.USER_AGENT, USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(recovered.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readValue(recovered.body(), JudgeProgress.class)).isEqualTo(terminal);
            System.out.printf("TASK-23 real %s/%s: pending bound closes stalled subscriber; genuine C++ AC and full cold/HTTP DTO unchanged%n",
                    transport, budget);
        } finally {
            release.countDown();
            clientOutboundChannel.removeInterceptor(blocker);
            accessCodeService.invalidateSession(owner.getSessionId());
            client.stop();
            assertConnectionCount(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"native,retry", "websocket,retry", "xhr,retry", "xhr-polling,retry",
            "native,exhausted", "websocket,exhausted", "xhr,exhausted", "xhr-polling,exhausted",
            "native,timeout", "websocket,timeout", "xhr,timeout", "xhr-polling,timeout", "native,queued"})
    @Timeout(45)
    void progressNotificationRetriesAndDeadlineKeepRealTerminalRecoverable(String transport, String failureMode)
            throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT,
                "notice-owner-" + UUID.randomUUID(), false);
        CookieManager ownerCookies = cookies(base, owner);
        HttpClient ownerHttp = httpClient(ownerCookies);
        String token = CsrfTestSupport.token(ownerHttp, base);
        String judgeId = createTask(ownerHttp, base, token);
        JudgeProgress terminal = finishRealTask(ownerHttp, base, token, judgeId);
        String topic = "/topic/progress/" + judgeId;
        CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        WebSocketStompClient client = stompClient(transport, closed, null, null, 10_000);
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger maximumPending = new AtomicInteger();
        AtomicReference<Object> notice = new AtomicReference<>();
        AtomicReference<Object> deadline = new AtomicReference<>();
        AtomicBoolean changedBudget = new AtomicBoolean();
        AtomicReference<String> connectionId = new AtomicReference<>();
        CompletableFuture<JudgeProgress> publication = null;
        ThreadPoolTaskExecutor queuedExecutor = null;
        ChannelInterceptor failure = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (!topic.equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) {
                    return message;
                }
                Object actualNotice = message.getHeaders().get(WebSocketSessionGuard.PROGRESS_NOTICE_HEADER);
                Object actualDeadline = message.getHeaders().get(WebSocketSessionGuard.PROGRESS_DEADLINE_HEADER);
                notice.compareAndSet(null, actualNotice);
                deadline.compareAndSet(null, actualDeadline);
                if (!notice.get().equals(actualNotice) || !deadline.get().equals(actualDeadline)) {
                    changedBudget.set(true);
                }
                int attempt = attempts.incrementAndGet();
                maximumPending.accumulateAndGet(sessionGuard.getPendingProgressCount(connectionId.get()), Math::max);
                if (failureMode.equals("timeout")) {
                    entered.countDown();
                    long fixtureDeadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                    boolean interrupted = false;
                    try {
                        while (release.getCount() != 0) {
                            long remaining = fixtureDeadline - System.nanoTime();
                            if (remaining <= 0) {
                                throw new IllegalStateException("Notification test did not release the broker");
                            }
                            try {
                                release.await(remaining, TimeUnit.NANOSECONDS);
                            } catch (InterruptedException interruption) {
                                interrupted = true;
                            }
                        }
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } else if (attempt <= (failureMode.equals("retry") ? 2 : 3)) {
                    throw new MessageDeliveryException(message, "Injected notification failure before broker dispatch");
                }
                return message;
            }
        };
        try {
            StompSession observer = connect(client, base, ownerCookies, token, new StompSessionHandlerAdapter() { },
                    !transport.equals("native"));
            observer.subscribe(topic, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    received.add((JsonNode) payload);
                }
            });
            assertSubscriptionCount(owner, 1);
            JsonNode ready = null;
            long readyDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (ready == null && System.nanoTime() < readyDeadline) {
                assertThat(progressPublisher.publish(judgeId, terminal)).isEqualTo(terminal);
                ready = received.poll(100, TimeUnit.MILLISECONDS);
            }
            assertThat(ready).isNotNull();
            assertThat(objectMapper.treeToValue(ready, JudgeProgress.class)).isEqualTo(terminal);
            connectionId.set(simpUserRegistry.getUser(SessionAuditId.from(owner.getSessionId()))
                    .getSessions().iterator().next().getId());
            assertPendingProgress(connectionId.get(), 0);
            received.clear();
            brokerChannel.addInterceptor(failure);
            if (failureMode.equals("queued")) {
                queuedExecutor = new ThreadPoolTaskExecutor();
                queuedExecutor.setCorePoolSize(1);
                queuedExecutor.setMaxPoolSize(1);
                queuedExecutor.setQueueCapacity(1);
                queuedExecutor.initialize();
                queuedExecutor.execute(() -> {
                    entered.countDown();
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Notification test did not release its executor");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            }
            ProgressPublisher notifyingPublisher = queuedExecutor == null ? progressPublisher
                    : new ProgressPublisher(messagingTemplate, taskStore, objectMapper, executionProperties,
                    new WebSocketConfig(sessionGuard).progressNotificationChannel(brokerChannel, brokerProvider, queuedExecutor));
            long started = System.nanoTime();
            publication = CompletableFuture.supplyAsync(() -> notifyingPublisher.publish(judgeId, terminal));
            if (failureMode.equals("timeout")) {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(sessionGuard.getPendingProgressCount(connectionId.get())).isEqualTo(1);
            }
            if (queuedExecutor != null) {
                long queuedDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (queuedExecutor.getThreadPoolExecutor().getQueue().isEmpty() && System.nanoTime() < queuedDeadline) {
                    Thread.sleep(10);
                }
                assertThat(queuedExecutor.getThreadPoolExecutor().getQueue()).hasSize(1);
                assertThat(sessionGuard.getPendingProgressCount(connectionId.get())).isEqualTo(1);
                assertThat(sessionGuard.getPendingProgressBytes(connectionId.get())).isBetween(1L, 2L * 1024 * 1024);
            }
            assertThat(publication.get(6, TimeUnit.SECONDS)).isEqualTo(terminal);
            long elapsed = System.nanoTime() - started;
            if (queuedExecutor == null) {
                assertThat(maximumPending.get()).isEqualTo(1);
                assertThat(notice.get()).isInstanceOf(String.class);
                assertThat(deadline.get()).isInstanceOf(Long.class);
            } else {
                assertThat(queuedExecutor.getThreadPoolExecutor().getQueue()).isEmpty();
            }
            assertThat(changedBudget.get()).isFalse();
            if (failureMode.equals("retry")) {
                assertThat(attempts.get()).isEqualTo(3);
                JsonNode delivered = received.poll(5, TimeUnit.SECONDS);
                assertThat(delivered).isNotNull();
                assertThat(objectMapper.treeToValue(delivered, JudgeProgress.class)).isEqualTo(terminal);
                assertPendingProgress(connectionId.get(), 0);
            } else {
                assertThat(attempts.get()).isEqualTo(queuedExecutor != null ? 0 : failureMode.equals("timeout") ? 1 : 3);
                if (failureMode.equals("timeout") || queuedExecutor != null) {
                    assertThat(elapsed).isBetween(Duration.ofSeconds(5).toNanos(), Duration.ofSeconds(6).toNanos());
                }
                sessionGuard.closeInactiveConnections();
                assertPolicyClose(transport, closed.get(5, TimeUnit.SECONDS));
                assertConnectionCount(0);
                release.countDown();
                assertThat(received.poll(200, TimeUnit.MILLISECONDS)).isNull();
                assertThat(sessionGuard.getPendingProgressCount(connectionId.get())).isZero();
                assertThat(sessionGuard.getPendingProgressBytes(connectionId.get())).isZero();
            }
            assertThat(new FileTaskStore(objectMapper, taskStore.storageBase()).findSummary(judgeId).orElseThrow())
                    .isEqualTo(terminal);
            assertThat(objectMapper.readValue(taskStore.taskDirectory(judgeId).resolve("final.json").toFile(), JudgeProgress.class))
                    .isEqualTo(terminal);
            HttpResponse<String> recovered = ownerHttp.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header(HttpHeaders.USER_AGENT, USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(recovered.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readValue(recovered.body(), JudgeProgress.class)).isEqualTo(terminal);
            System.out.printf("TASK-23 real %s/%s: attempts=%d, publicationMs=%d; pre-counted notice and genuine AC cold/HTTP DTO unchanged%n",
                    transport, failureMode, attempts.get(), TimeUnit.NANOSECONDS.toMillis(elapsed));
        } finally {
            release.countDown();
            brokerChannel.removeInterceptor(failure);
            accessCodeService.invalidateSession(owner.getSessionId());
            client.stop();
            assertConnectionCount(0);
            if (queuedExecutor != null) {
                queuedExecutor.shutdown();
                assertThat(queuedExecutor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
            if (publication != null) {
                publication.get(6, TimeUnit.SECONDS);
            }
        }
    }

    private void assertPendingProgress(String connectionId, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (sessionGuard.getPendingProgressCount(connectionId) != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(sessionGuard.getPendingProgressCount(connectionId)).isEqualTo(expected);
    }

    private JudgeProgress finishRealTask(HttpClient client, URI base, String token, String judgeId) throws Exception {
        HttpResponse<String> started = client.send(HttpRequest.newBuilder(base.resolve("judge/start/" + judgeId))
                .timeout(Duration.ofSeconds(5)).header("X-CSRF-TOKEN", token).header(HttpHeaders.USER_AGENT, USER_AGENT)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> polled = client.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header(HttpHeaders.USER_AGENT, USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(polled.statusCode()).isEqualTo(200);
            JudgeProgress progress = objectMapper.readValue(polled.body(), JudgeProgress.class);
            if (progress.getStatus().equals("AC")) {
                assertThat(progress.getSummary().getCompletedCases()).isEqualTo(1);
                assertThat(progress.getSummary().getAc()).isEqualTo(1);
                assertThat(progress.getRevision()).isGreaterThan(0);
                assertThat(progress.getFinalizedAt()).isNotNull();
                return progress;
            }
            assertThat(List.of("CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING")).contains(progress.getStatus());
            Thread.sleep(20);
        }
        throw new IllegalStateException("Real C++ task did not reach AC");
    }

    @Test
    @Timeout(45)
    void eventSourceFlushReleasesProgressAndCannotBeReusedAsXhrStreaming() throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT,
                "eventsource-owner-" + UUID.randomUUID(), false);
        HttpClient client = httpClient(cookies(base, owner));
        String token = CsrfTestSupport.token(client, base);
        String judgeId = createTask(client, base, token);
        JudgeProgress terminal = finishRealTask(client, base, token, judgeId);
        String transportPath = "ws/000/" + UUID.randomUUID().toString().replace("-", "") + "/";
        HttpResponse<java.io.InputStream> response = client.send(HttpRequest.newBuilder(base.resolve(transportPath + "eventsource"))
                .timeout(Duration.ofSeconds(5)).header(HttpHeaders.USER_AGENT, USER_AGENT).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        try (var body = response.body(); var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            assertThat(readSseEvent(reader)).isEqualTo("o");
            String connect = "CONNECT\naccept-version:1.2\nhost:127.0.0.1\nX-CSRF-TOKEN:" + token + "\n\n\u0000";
            HttpResponse<String> connected = postTransport(client, base, "/" + transportPath + "xhr_send", token,
                    objectMapper.writeValueAsString(List.of(connect)));
            assertThat(connected.statusCode()).isEqualTo(204);
            String greeting = readSseEvent(reader);
            assertThat(greeting).startsWith("a[");
            assertThat(objectMapper.readValue(greeting.substring(1), String[].class)[0]).startsWith("CONNECTED\n");
            String subscribe = "SUBSCRIBE\nid:progress\ndestination:/topic/progress/" + judgeId + "\n\n\u0000";
            HttpResponse<String> subscribed = postTransport(client, base, "/" + transportPath + "xhr_send", token,
                    objectMapper.writeValueAsString(List.of(subscribe)));
            assertThat(subscribed.statusCode()).isEqualTo(204);
            assertSubscriptionCount(owner, 1);
            String connectionId = simpUserRegistry.getUser(SessionAuditId.from(owner.getSessionId()))
                    .getSessions().iterator().next().getId();
            for (int progressIndex = 0; progressIndex < 35; progressIndex++) {
                assertThat(progressPublisher.publish(judgeId, terminal)).isEqualTo(terminal);
                String data = readSseEvent(reader);
                assertThat(data).startsWith("a[");
                for (String frame : objectMapper.readValue(data.substring(1), String[].class)) {
                    List<Message<byte[]>> decoded = new StompDecoder().decode(ByteBuffer.wrap(frame.getBytes(StandardCharsets.UTF_8)));
                    assertThat(decoded).hasSize(1);
                    assertThat(StompHeaderAccessor.getCommand(decoded.get(0).getHeaders())).isEqualTo(StompCommand.MESSAGE);
                    assertThat(objectMapper.readValue(decoded.get(0).getPayload(), JudgeProgress.class))
                            .isEqualTo(terminal);
                }
                assertPendingProgress(connectionId, 0);
                assertThat(sessionGuard.getPendingProgressBytes(connectionId)).isZero();
                assertThat(sessionGuard.getConnectionCount()).isEqualTo(1);
            }
            HttpResponse<String> wrongTransport = postTransport(client, base, "/" + transportPath + "xhr_streaming", token, "[]");
            assertThat(wrongTransport.statusCode()).isEqualTo(404);
            assertThat(new FileTaskStore(objectMapper, taskStore.storageBase()).findSummary(judgeId).orElseThrow())
                    .isEqualTo(terminal);
            System.out.println("TASK-23 real EventSource: 35 genuine AC frames flushed, pending count/bytes return to zero; XHR type reuse rejected");
        } finally {
            accessCodeService.invalidateSession(owner.getSessionId());
            assertConnectionCount(0);
        }
    }

    private String readSseEvent(BufferedReader reader) throws Exception {
        CompletableFuture<String> event = CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        return line.substring(6);
                    }
                }
                throw new IllegalStateException("EventSource ended before its expected event");
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
        return event.get(5, TimeUnit.SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "websocket", "xhr", "xhr-polling"})
    @Timeout(90)
    void fortyRealCasesDeliverRunningSummaryAndSameTerminalOverHttpAndStomp(String transport) throws Exception {
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        UserSession owner = accessCodeService.createSession(false, "127.0.0.1", USER_AGENT,
                "ws-summary-owner-" + UUID.randomUUID(), false);
        CookieManager ownerCookies = cookies(base, owner);
        HttpClient ownerHttp = httpClient(ownerCookies);
        WebSocketStompClient client = stompClient(transport);
        StompSession observer = null;
        String judgeId = null;
        String token = CsrfTestSupport.token(ownerHttp, base);
        CountDownLatch releaseFirstProgress = new CountDownLatch(1);
        ExecutorChannelInterceptor delayedProgress = null;
        try {
            String userCode = "#include <iostream>\n#include <chrono>\n#include <thread>\n"
                    + "int main(){std::this_thread::sleep_for(std::chrono::milliseconds(50));std::cout << 1;}";
            judgeId = createTask(ownerHttp, base, token, null, 40, userCode);
            String topic = "/topic/progress/" + judgeId;
            BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
            CompletableFuture<Void> ready = new CompletableFuture<>();
            observer = connect(client, base, ownerCookies, token, new StompSessionHandlerAdapter() { });
            observer.subscribe(topic, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    JsonNode frame = (JsonNode) payload;
                    if (READY.equals(frame.path("message").asText())) {
                        ready.complete(null);
                    } else {
                        frames.add(frame);
                    }
                }
            });
            long readyDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!ready.isDone() && System.nanoTime() < readyDeadline) {
                messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", READY, 0));
                try {
                    ready.get(100, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException pending) {
                    assertThat(observer.isConnected()).isTrue();
                }
            }
            ready.get(1, TimeUnit.SECONDS);
            CountDownLatch firstProgressEntered = new CountDownLatch(1);
            CountDownLatch laterProgressEntered = new CountDownLatch(1);
            AtomicBoolean firstProgressClaimed = new AtomicBoolean();
            delayedProgress = new ExecutorChannelInterceptor() {
                @Override
                public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
                    if (!topic.equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) {
                        return message;
                    }
                    try {
                        Object payload = message.getPayload();
                        JsonNode progress = payload instanceof byte[] bytes ? objectMapper.readTree(bytes)
                                : payload instanceof String text ? objectMapper.readTree(text) : objectMapper.valueToTree(payload);
                        if (progress.path("summary").path("completedCases").asInt() > 0) {
                            if (firstProgressClaimed.compareAndSet(false, true)) {
                                assertThat(progress.path("status").asText()).isEqualTo("RUNNING");
                                firstProgressEntered.countDown();
                                if (!releaseFirstProgress.await(45, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("First real progress was not released");
                                }
                            } else {
                                laterProgressEntered.countDown();
                            }
                        }
                        return message;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Real progress delivery interrupted", interrupted);
                    } catch (IOException malformed) {
                        throw new IllegalStateException("Real progress payload could not be read", malformed);
                    }
                }
            };
            clientOutboundChannel.addInterceptor(delayedProgress);
            HttpResponse<String> started = ownerHttp.send(HttpRequest.newBuilder(base.resolve("judge/start/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("X-CSRF-TOKEN", token).header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
            assertThat(firstProgressEntered.await(15, TimeUnit.SECONDS)).as("first real %s running delivery", transport).isTrue();
            JsonNode finishedWhileFirstWasHeld = null;
            long producerDeadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (finishedWhileFirstWasHeld == null && System.nanoTime() < producerDeadline) {
                HttpResponse<String> status = ownerHttp.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                        .timeout(Duration.ofSeconds(5)).header(HttpHeaders.USER_AGENT, USER_AGENT).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(status.statusCode()).isEqualTo(200);
                JsonNode snapshot = objectMapper.readTree(status.body());
                if (snapshot.path("status").asText().equals("AC")) {
                    finishedWhileFirstWasHeld = snapshot;
                } else {
                    TimeUnit.MILLISECONDS.sleep(50);
                }
            }
            assertThat(finishedWhileFirstWasHeld).as("real CPP producer must finish independently of WS delivery").isNotNull();
            assertThat(finishedWhileFirstWasHeld.path("summary").path("completedCases").asInt()).isEqualTo(40);
            assertThat(laterProgressEntered.await(250, TimeUnit.MILLISECONDS))
                    .as("later %s outbound progress must not overtake the first real running frame", transport).isFalse();
            releaseFirstProgress.countDown();
            int previousCompleted = 0;
            long previousRevision = 0;
            JsonNode previousFrame = null;
            int intermediateSnapshots = 0;
            JsonNode terminal = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (terminal == null && System.nanoTime() < deadline) {
                JsonNode frame = frames.poll(5, TimeUnit.SECONDS);
                assertThat(frame).as("real %s progress before deadline", transport).isNotNull();
                assertThat(frame.path("revision").isIntegralNumber()).isTrue();
                long revision = frame.path("revision").asLong();
                assertThat(revision).isGreaterThan(0).isGreaterThanOrEqualTo(previousRevision);
                if (revision == previousRevision) {
                    assertThat(frame).isEqualTo(previousFrame);
                }
                if (frame.path("status").asText().equals("RUNNING")) {
                    assertThat(frame.path("results").isNull() || frame.path("results").isMissingNode())
                            .as("running frame must not contain full results: %s", frame).isTrue();
                }
                JsonNode summary = frame.path("summary");
                if (summary.isObject()) {
                    int completed = summary.path("completedCases").asInt();
                    assertThat(completed).isBetween(previousCompleted, 40);
                    assertThat(summary.path("totalCases").asInt()).isEqualTo(40);
                    int classified = 0;
                    for (String field : List.of("ac", "wa", "tle", "mle", "re", "systemError", "outputLimitExceeded")) {
                        assertThat(summary.path(field).isIntegralNumber()).isTrue();
                        classified += summary.path(field).asInt();
                    }
                    assertThat(classified).isEqualTo(completed);
                    assertThat(summary.path("ac").asInt()).isEqualTo(completed);
                    previousCompleted = completed;
                    if (frame.path("status").asText().equals("RUNNING") && completed > 0 && completed < 40) {
                        intermediateSnapshots++;
                    }
                }
                if (frame.path("status").asText().equals("AC")) {
                    assertThat(revision).isGreaterThan(previousRevision);
                    terminal = frame;
                } else {
                    assertThat(List.of("CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING"))
                            .as("unexpected real progress: %s", frame).contains(frame.path("status").asText());
                }
                previousRevision = revision;
                previousFrame = frame;
            }
            assertThat(intermediateSnapshots).isGreaterThan(0);
            assertThat(terminal).isNotNull();
            assertThat(terminal.path("summary").path("completedCases").asInt()).isEqualTo(40);
            assertThat(terminal.path("summary").path("ac").asInt()).isEqualTo(40);
            assertThat(terminal.path("revision").asLong()).isGreaterThan(1L);
            assertThat(terminal.path("finalizedAt").isTextual()).isTrue();
            assertThat(terminal.path("finalizedAt").asText()).isNotBlank();
            assertThat(terminal.path("attemptId").isNull()).isTrue();
            assertThat(objectMapper.readTree(taskStore.taskDirectory(judgeId).resolve("final.json").toFile()))
                    .isEqualTo(terminal);
            JudgeProgress cold = new FileTaskStore(objectMapper, taskStore.storageBase()).findSummary(judgeId).orElseThrow();
            assertThat(objectMapper.readTree(objectMapper.writeValueAsBytes(cold))).isEqualTo(terminal);
            HttpResponse<String> polled = ownerHttp.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header(HttpHeaders.USER_AGENT, USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(polled.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(polled.body())).isEqualTo(terminal);
            JsonNode late = frames.poll(200, TimeUnit.MILLISECONDS);
            if (late != null) {
                assertThat(late).isEqualTo(terminal);
            }
            System.out.printf("TASK-23 real %s: 40 AC, firstRunningHeldUntilRealTerminal=true, noOutboundOvertake=true, intermediateSnapshots=%d, HTTP/STOMP terminal equal%n",
                    transport, intermediateSnapshots);
        } finally {
            releaseFirstProgress.countDown();
            if (delayedProgress != null) {
                clientOutboundChannel.removeInterceptor(delayedProgress);
            }
            try {
                if (judgeId != null) {
                    HttpResponse<String> cancelled = ownerHttp.send(
                            HttpRequest.newBuilder(base.resolve("judge/cancel/" + judgeId)).timeout(Duration.ofSeconds(5))
                                    .header("X-CSRF-TOKEN", token).header(HttpHeaders.USER_AGENT, USER_AGENT)
                                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                    assertThat(cancelled.statusCode()).isEqualTo(200);
                }
            } finally {
                try {
                    if (observer != null && observer.isConnected()) {
                        observer.disconnect();
                    }
                } finally {
                    client.stop();
                    accessCodeService.invalidateSession(owner.getSessionId());
                }
            }
        }
    }

    private HttpResponse<String> postTransport(HttpClient client, URI base, String path, String token, String body)
            throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
                .header(HttpHeaders.ORIGIN, base.toString().replaceAll("/$", ""))
                .header("X-CSRF-TOKEN", token).header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private BlockingQueue<JsonNode> assertServerProgressReceived(StompSession session, String topic, String message)
            throws Exception {
        CompletableFuture<JsonNode> delivered = new CompletableFuture<>();
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                JsonNode frame = (JsonNode) payload;
                received.add(frame);
                if (message.equals(frame.path("message").asText())) {
                    delivered.complete(frame);
                }
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!delivered.isDone() && System.nanoTime() < deadline) {
            messagingTemplate.convertAndSend(topic, new JudgeProgress("RUNNING", message, 1));
            try {
                delivered.get(100, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException pending) {
                assertThat(session.isConnected()).isTrue();
            }
        }
        assertThat(delivered.get(1, TimeUnit.SECONDS).path("status").asText()).isEqualTo("RUNNING");
        return received;
    }

    private void assertProtocolRejection(String transport, Object response) {
        if (response instanceof StompHeaders error) {
            assertThat(error.getFirst("message")).isNotBlank();
        } else {
            CloseStatus status = (CloseStatus) response;
            if (transport.equals("xhr-polling") && status.getCode() == 3000) {
                assertThat(status.getReason()).isEqualTo("Go away!");
            } else {
                assertThat(status.getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());
            }
        }
    }

    private StompSessionHandlerAdapter rejectionHandler(CompletableFuture<StompHeaders> rejected) {
        return new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                rejected.complete(headers);
            }

            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                        byte[] payload, Throwable exception) {
                rejected.completeExceptionally(exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                rejected.completeExceptionally(exception);
            }
        };
    }

    private WebSocketStompClient stompClient(String transport) {
        return stompClient(transport, null);
    }

    private WebSocketStompClient stompClient(String transport, CompletableFuture<CloseStatus> closed) {
        return stompClient(transport, closed, null);
    }

    private WebSocketStompClient stompClient(String transport, CompletableFuture<CloseStatus> closed,
                                              CompletableFuture<URI> xhrSendUri) {
        return stompClient(transport, closed, xhrSendUri, null);
    }

    private WebSocketStompClient stompClient(String transport, CompletableFuture<CloseStatus> closed,
                                             CompletableFuture<URI> xhrSendUri,
                                             CompletableFuture<Integer> transportStatus) {
        return stompClient(transport, closed, xhrSendUri, transportStatus, 5_000);
    }

    private WebSocketStompClient stompClient(String transport, CompletableFuture<CloseStatus> closed,
                                             CompletableFuture<URI> xhrSendUri,
                                             CompletableFuture<Integer> transportStatus, int readTimeout) {
        WebSocketClient socketClient;
        if (transport.equals("native")) {
            socketClient = new StandardWebSocketClient();
        } else {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5_000);
            factory.setReadTimeout(readTimeout);
            RestTemplate restTemplate = new RestTemplate(factory);
            if (xhrSendUri != null || transportStatus != null) {
                restTemplate.setInterceptors(List.of((request, body, execution) -> {
                    if (xhrSendUri != null && request.getURI().getPath().endsWith("/xhr_send")) {
                        xhrSendUri.complete(request.getURI());
                    }
                    org.springframework.http.client.ClientHttpResponse response = execution.execute(request, body);
                    if (transportStatus != null && response.getStatusCode().value() == 401) {
                        transportStatus.complete(401);
                    }
                    return response;
                }));
            }
            RestTemplateXhrTransport xhr = new RestTemplateXhrTransport(restTemplate);
            xhr.setXhrStreamingDisabled(transport.equals("xhr-polling"));
            List<Transport> transports = transport.equals("websocket")
                    ? List.of(new WebSocketTransport(new StandardWebSocketClient())) : List.of(xhr);
            SockJsClient sockJs = new SockJsClient(transports);
            sockJs.setInfoReceiver(xhr);
            socketClient = sockJs;
        }
        WebSocketClient delegate = socketClient;
        if (closed != null) {
            UnaryOperator<WebSocketHandler> observed = handler -> new WebSocketHandlerDecorator(handler) {
                @Override
                public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                    closed.complete(status);
                    super.afterConnectionClosed(session, status);
                }
            };
            socketClient = new WebSocketClient() {
                @Override
                public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler, String url,
                                                                    Object... uriVariables) {
                    return delegate.execute(observed.apply(handler), url, uriVariables);
                }

                @Override
                public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler,
                                                                    WebSocketHttpHeaders headers, URI endpoint) {
                    return delegate.execute(observed.apply(handler), headers, endpoint);
                }
            };
        }
        WebSocketStompClient client = new WebSocketStompClient(socketClient) {
            @Override
            public void start() {
                if (closed != null && delegate instanceof Lifecycle lifecycle) {
                    lifecycle.start();
                }
                super.start();
            }

            @Override
            public void stop() {
                try {
                    super.stop();
                } finally {
                    if (closed != null && delegate instanceof Lifecycle lifecycle) {
                        lifecycle.stop();
                    }
                }
            }
        };
        client.setDefaultHeartbeat(new long[]{0, 0});
        client.setMessageConverter(new CompositeMessageConverter(
                List.of(new StringMessageConverter(), new MappingJackson2MessageConverter())));
        return client;
    }

    private StompSession connect(WebSocketStompClient client, URI base, CookieManager cookies, String token,
                                 StompSessionHandlerAdapter handler) throws Exception {
        return connect(client, base, cookies, token, handler, client.getWebSocketClient() instanceof SockJsClient);
    }

    private StompSession connect(WebSocketStompClient client, URI base, CookieManager cookies, String token,
                                 StompSessionHandlerAdapter handler, boolean sockJs) throws Exception {
        return connect(client, base, cookies, token, token, null, handler, sockJs);
    }

    private StompSession connect(WebSocketStompClient client, URI base, CookieManager cookies, String httpToken,
                                 String connectToken, String credentialHeader, StompSessionHandlerAdapter handler,
                                 boolean sockJs) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.COOKIE, String.join("; ", cookies.get(base, Map.of()).get("Cookie")));
        headers.setOrigin(base.toString().replaceAll("/$", ""));
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        if (httpToken != null) {
            headers.set("X-CSRF-TOKEN", httpToken);
        }
        if (credentialHeader != null) {
            headers.set(SessionCredentialResolver.HEADER_NAME, credentialHeader);
        }
        StompHeaders connectHeaders = new StompHeaders();
        if (connectToken != null) {
            connectHeaders.set("X-CSRF-TOKEN", connectToken);
        }
        connectHeaders.setLogin("client-supplied-admin");
        connectHeaders.set("role", "ADMIN");
        client.start();
        URI endpoint = sockJs ? base.resolve("ws")
                : URI.create(base.toString().replaceFirst("^http", "ws")).resolve("ws/websocket");
        return client.connectAsync(endpoint.toString(), headers, connectHeaders, handler)
                .get(5, TimeUnit.SECONDS);
    }

    private CookieManager cookies(URI base, UserSession session) {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        HttpCookie credential = new HttpCookie(SessionCredentialResolver.COOKIE_NAME, session.getSessionId());
        credential.setVersion(0);
        credential.setPath("/");
        cookies.getCookieStore().add(base, credential);
        return cookies;
    }

    private HttpClient httpClient(CookieManager cookies) {
        return HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private String createTask(HttpClient client, URI base, String token) throws Exception {
        return createTask(client, base, token, null);
    }

    private String createTask(HttpClient client, URI base, String token, String credentialHeader) throws Exception {
        String source = "#include <iostream>\nint main(){std::cout << 1;}";
        return createTask(client, base, token, credentialHeader, 1, source);
    }

    private String createTask(HttpClient client, URI base, String token, String credentialHeader, int testCases,
                              String userCode) throws Exception {
        String source = "#include <iostream>\nint main(){std::cout << 1;}";
        String body = objectMapper.writeValueAsString(Map.of(
                "userCode", userCode, "generatorCode", source, "bruteForceCode", source,
                "testCases", testCases, "timeLimit", 2_000, "memoryLimit", 268_435_456L, "precision", 0.0));
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve("judge"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Accept", "application/json").header("User-Agent", USER_AGENT)
                .header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.ofString(body));
        if (credentialHeader != null) {
            request.header(SessionCredentialResolver.HEADER_NAME, credentialHeader);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String judgeId = objectMapper.readTree(response.body()).path("judgeId").asText();
        assertThat(judgeId).isNotBlank();
        return judgeId;
    }
}
