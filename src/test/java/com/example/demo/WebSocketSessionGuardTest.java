package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.UserSession;
import com.example.demo.security.ClientAddressResolver;
import com.example.demo.security.WebSocketSessionGuard;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.QuotaService;
import com.example.demo.service.SessionManagementService;
import com.example.demo.service.TaskStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompEncoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketSessionGuardTest {
    private AccessCodeService access;
    private QuotaService quota;
    private WebSocketSessionGuard guard;
    private MutableClock clock;
    private int nextConnectionId;

    @BeforeEach
    void setUp() {
        AuthConfiguration auth = new AuthConfiguration();
        access = new AccessCodeService(auth);
        quota = spy(new QuotaService(new ExecutionProperties(), mock(TaskStore.class)));
        when(quota.canAccessTask(anyString(), any(UserSession.class))).thenReturn(true);
        clock = new MutableClock();
        guard = new WebSocketSessionGuard(access, quota, new SessionManagementService(access, auth),
                new ClientAddressResolver(auth), clock);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("guard", guard);
        ReflectionTestUtils.setField(access, "webSocketSessionGuard", beans.getBeanProvider(WebSocketSessionGuard.class));
    }

    @Test
    void sharedOwnerLimitCountsDistinctLoginSessionsAndReleasesOnlyClosedConnections() throws Exception {
        WebSocketSession first = socket(session("owner"));
        WebSocketSession second = socket(session("owner"));
        WebSocketSession third = socket(session("owner"));
        assertThat(guard.registerConnection(first)).isTrue();
        assertThat(guard.registerConnection(second)).isTrue();
        assertThat(guard.registerConnection(third)).isFalse();
        assertThat(guard.getConnectionCount()).isEqualTo(2);
        guard.unregisterConnection(first.getId());
        guard.unregisterConnection(first.getId());
        assertThat(guard.registerConnection(third)).isTrue();
        assertThat(guard.getConnectionCount()).isEqualTo(2);
    }

    @Test
    void globalLimitIs128AcrossIndependentOwners() throws Exception {
        for (int connectionIndex = 0; connectionIndex < 128; connectionIndex++) {
            assertThat(guard.registerConnection(socket(session("owner-" + connectionIndex)))).isTrue();
        }
        assertThat(guard.registerConnection(socket(session("overflow-owner")))).isFalse();
        assertThat(guard.getConnectionCount()).isEqualTo(128);
    }

    @Test
    void ownerLimitRemainsAtomicUnderConcurrentRegistration() throws Exception {
        List<WebSocketSession> sockets = new ArrayList<>();
        for (int connectionIndex = 0; connectionIndex < 16; connectionIndex++) {
            sockets.add(socket(session("concurrent-owner")));
        }
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var results = sockets.stream().map(socket -> executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return guard.registerConnection(socket);
            })).toList();
            start.countDown();
            int accepted = 0;
            for (var result : results) {
                accepted += result.get(5, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(2);
            assertThat(guard.getConnectionCount()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void subscriptionsAreBoundedAndDuplicateIdsCannotBypassTheLimit() throws Exception {
        WebSocketSession socket = socket(session("subscriber"));
        assertThat(guard.registerConnection(socket)).isTrue();
        for (int subscriptionIndex = 0; subscriptionIndex < 8; subscriptionIndex++) {
            guard.preSend(message(socket, SimpMessageType.SUBSCRIBE, "sub-" + subscriptionIndex), null);
        }
        assertThatThrownBy(() -> guard.preSend(message(socket, SimpMessageType.SUBSCRIBE, "sub-0"), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guard.preSend(message(socket, SimpMessageType.SUBSCRIBE, "overflow"), null))
                .isInstanceOf(AccessDeniedException.class);
        Message<byte[]> unsubscribe = message(socket, SimpMessageType.UNSUBSCRIBE, "sub-0");
        guard.preSend(unsubscribe, null);
        assertThatThrownBy(() -> guard.preSend(message(socket, SimpMessageType.SUBSCRIBE, "replacement"), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guard.preSend(unsubscribe, null)).isInstanceOf(AccessDeniedException.class);
        guard.afterMessageHandled(unsubscribe, null, mock(SimpleBrokerMessageHandler.class), null);
        assertThat(guard.preSend(message(socket, SimpMessageType.SUBSCRIBE, "replacement"), null)).isNotNull();
        assertThatThrownBy(() -> guard.preSend(message(socket, SimpMessageType.SUBSCRIBE, "overflow"), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unauthorizedSubscriptionDoesNotConsumeAValidSlot() throws Exception {
        WebSocketSession socket = socket(session("subscriber"));
        assertThat(guard.registerConnection(socket)).isTrue();
        when(quota.canAccessTask("task", access.getSession(sessionId(socket)))).thenReturn(false);
        assertThatThrownBy(() -> guard.preSend(message(socket, SimpMessageType.SUBSCRIBE, "rejected"), null))
                .isInstanceOf(AccessDeniedException.class);
        when(quota.canAccessTask(anyString(), any(UserSession.class))).thenReturn(true);
        for (int subscriptionIndex = 0; subscriptionIndex < 8; subscriptionIndex++) {
            assertThat(guard.preSend(message(socket, SimpMessageType.SUBSCRIBE, "sub-" + subscriptionIndex), null))
                    .isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "server-push"})
    void transportTrafficDoesNotExtendTheTenMinuteBusinessIdleTimeout(String traffic) throws Exception {
        WebSocketSession socket = socket(session("idle-owner"));
        assertThat(guard.registerConnection(socket)).isTrue();
        clock.advance(Duration.ofMinutes(9));
        if (traffic.equals("heartbeat")) {
            guard.preSend(message(socket, SimpMessageType.HEARTBEAT, null), null);
        } else {
            assertThat(guard.isConnectionActive(socket)).isTrue();
        }
        clock.advance(Duration.ofMinutes(1));
        access.cleanupExpiredSessions();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.getConnectionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBSCRIBE", "UNSUBSCRIBE"})
    void validBusinessActivityRenewsIdleTime(String activity) throws Exception {
        WebSocketSession socket = socket(session("active-owner"));
        assertThat(guard.registerConnection(socket)).isTrue();
        guard.preSend(message(socket, SimpMessageType.SUBSCRIBE, "first"), null);
        clock.advance(Duration.ofMinutes(9));
        guard.preSend(message(socket, SimpMessageType.valueOf(activity), activity.equals("SUBSCRIBE") ? "second" : "first"), null);
        clock.advance(Duration.ofMinutes(1));
        access.cleanupExpiredSessions();
        assertThat(guard.getConnectionCount()).isEqualTo(1);
        assertThat(guard.isConnectionActive(socket)).isTrue();
        clock.advance(Duration.ofMinutes(9));
        access.cleanupExpiredSessions();
        assertThat(guard.getConnectionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "expired"})
    void sessionRevocationClosesIdleConnectionsWithoutMessages(String reason) throws Exception {
        UserSession identity = session("revoked-owner");
        WebSocketSession socket = socket(identity);
        assertThat(guard.registerConnection(socket)).isTrue();
        if (reason.equals("logout")) {
            access.invalidateSession(identity.getSessionId());
        } else {
            identity.setExpiresAt(LocalDateTime.now().minusSeconds(1));
            access.cleanupExpiredSessions();
        }
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.getConnectionCount()).isZero();
        assertThat(access.getSession(identity.getSessionId())).isNull();
    }

    @Test
    void failedCloseDoesNotFalselyReleaseCapacityAndCanBeRetried() throws Exception {
        UserSession identity = session("close-owner");
        WebSocketSession socket = socket(identity);
        assertThat(guard.registerConnection(socket)).isTrue();
        assertThat(guard.registerConnection(socket(session("close-owner")))).isTrue();
        doThrow(new IOException("test close failure")).when(socket).close(CloseStatus.POLICY_VIOLATION);
        access.invalidateSession(identity.getSessionId());
        assertThat(guard.getConnectionCount()).isEqualTo(2);
        assertThat(guard.registerConnection(socket(session("close-owner")))).isFalse();
        doAnswer(invocation -> {
            when(socket.isOpen()).thenReturn(false);
            return null;
        }).when(socket).close(CloseStatus.POLICY_VIOLATION);
        access.cleanupExpiredSessions();
        assertThat(guard.getConnectionCount()).isEqualTo(1);
        assertThat(guard.registerConnection(socket(session("close-owner")))).isTrue();
    }

    @Test
    void progressLimitIncludesMessagesWaitingBeforeTheOutboundChannel() throws Exception {
        WebSocketSession socket = socket(session("queued-owner"));
        assertThat(guard.registerConnection(socket)).isTrue();
        for (int progressIndex = 0; progressIndex < 32; progressIndex++) {
            Message<?> notice = progressNotice("small-frame".getBytes(StandardCharsets.UTF_8), null);
            assertThat(guard.reserveProgress(notice, subscriber(socket))).isEqualTo(subscriber(socket));
            assertThat(guard.getPendingProgressCount(socket.getId())).isEqualTo(progressIndex + 1);
            assertThat(guard.getPendingProgressBytes(socket.getId())).isBetween(1L, 2L * 1024 * 1024);
        }
        doAnswer(invocation -> {
            assertThat(guard.getPendingProgressCount(socket.getId())).isEqualTo(32);
            assertThat(guard.getPendingProgressBytes(socket.getId())).isLessThanOrEqualTo(2L * 1024 * 1024);
            when(socket.isOpen()).thenReturn(false);
            return null;
        }).when(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.reserveProgress(progressNotice(new byte[1], null), subscriber(socket))).isEmpty();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.getPendingProgressCount(socket.getId())).isZero();
        assertThat(guard.getConnectionCount()).isZero();
    }

    @Test
    void combinedEncodedByteBudgetRejectsTheNextFrameWithoutCrossingTwoMiB() throws Exception {
        WebSocketSession socket = socket(session("bytes-owner"));
        assertThat(guard.registerConnection(socket)).isTrue();
        for (int progressIndex = 0; progressIndex < 3; progressIndex++) {
            assertThat(guard.reserveProgress(progressNotice(new byte[512 * 1024], null), subscriber(socket)))
                    .isEqualTo(subscriber(socket));
        }
        assertThat(guard.getPendingProgressCount(socket.getId())).isEqualTo(3);
        assertThat(guard.getPendingProgressBytes(socket.getId())).isBetween(3L * 512 * 1024, 2L * 1024 * 1024);
        doAnswer(invocation -> {
            assertThat(guard.getPendingProgressCount(socket.getId())).isEqualTo(3);
            assertThat(guard.getPendingProgressBytes(socket.getId())).isLessThanOrEqualTo(2L * 1024 * 1024);
            when(socket.isOpen()).thenReturn(false);
            return null;
        }).when(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.reserveProgress(progressNotice(new byte[512 * 1024], null), subscriber(socket))).isEmpty();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.getPendingProgressBytes(socket.getId())).isZero();
    }

    @Test
    void outboundDispatchDoesNotReleaseBytesUntilTheActualFrameIsWritten() throws Exception {
        WebSocketSession socket = socket(session("written-owner"));
        assertThat(guard.registerConnection(socket)).isTrue();
        Message<?> notice = progressNotice("payload\nmessage-id:body-must-not-release-another-frame".getBytes(StandardCharsets.UTF_8), null);
        guard.reserveProgress(notice, subscriber(socket));
        long reservedBytes = guard.getPendingProgressBytes(socket.getId());
        assertThat(guard.reserveProgress(notice, subscriber(socket))).isEqualTo(subscriber(socket));
        assertThat(guard.getPendingProgressBytes(socket.getId())).isEqualTo(reservedBytes);
        assertThat(guard.progressBrokerInterceptor().preSend(notice, null).getHeaders()
                .get(WebSocketSessionGuard.PROGRESS_NOTICE_HEADER))
                .isEqualTo(notice.getHeaders().get(WebSocketSessionGuard.PROGRESS_NOTICE_HEADER));
        Message<?> outbound = outboundProgress(socket, notice);
        assertThat(guard.getPendingProgressCount(socket.getId())).isEqualTo(1);
        guard.afterMessageHandled(outbound, null, mock(SimpleBrokerMessageHandler.class), null);
        assertThat(guard.getPendingProgressCount(socket.getId())).isEqualTo(1);
        String frame = new String(new StompEncoder().encode(outbound.getHeaders(), (byte[]) outbound.getPayload()), StandardCharsets.UTF_8);
        assertThat(guard.canWriteProgress(socket.getId(), frame)).isTrue();
        guard.progressWritten(socket.getId(), frame);
        guard.progressWritten(socket.getId(), frame);
        assertThat(guard.getPendingProgressCount(socket.getId())).isZero();
        assertThat(guard.getPendingProgressBytes(socket.getId())).isZero();
    }

    @Test
    void expiredQueuedFrameCannotBeWrittenOrFalselyReleaseCapacity() throws Exception {
        WebSocketSession socket = socket(session("deadline-owner"));
        assertThat(guard.registerConnection(socket)).isTrue();
        Message<?> notice = progressNotice(new byte[1], System.nanoTime() + Duration.ofSeconds(1).toNanos());
        assertThat(guard.reserveProgress(notice, subscriber(socket))).isEqualTo(subscriber(socket));
        Message<?> outbound = outboundProgress(socket, notice);
        String frame = new String(new StompEncoder().encode(outbound.getHeaders(), (byte[]) outbound.getPayload()), StandardCharsets.UTF_8);
        Thread.sleep(1100);
        assertThat(guard.canWriteProgress(socket.getId(), frame)).isFalse();
        assertThat(guard.getPendingProgressCount(socket.getId())).isEqualTo(1);
        guard.closeInactiveConnections();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.getPendingProgressCount(socket.getId())).isZero();
    }

    @Test
    void cancelledNotificationCannotBeWrittenOrRequeuedAndMaintenanceReclaimsIt() throws Exception {
        WebSocketSession socket = socket(session("cancelled-notice-owner"));
        assertThat(guard.registerConnection(socket)).isTrue();
        AtomicBoolean cancelled = new AtomicBoolean();
        Message<?> notice = MessageBuilder.fromMessage(progressNotice(new byte[1], null))
                .setHeader(WebSocketSessionGuard.PROGRESS_CANCELLED_HEADER, cancelled).build();
        assertThat(guard.reserveProgress(notice, subscriber(socket))).isEqualTo(subscriber(socket));
        Message<?> outbound = outboundProgress(socket, notice);
        String frame = new String(new StompEncoder().encode(outbound.getHeaders(), (byte[]) outbound.getPayload()), StandardCharsets.UTF_8);
        cancelled.set(true);
        assertThat(guard.canWriteProgress(socket.getId(), frame)).isFalse();
        assertThat(guard.getPendingProgressCount(socket.getId())).isEqualTo(1);
        guard.closeInactiveConnections();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.reserveProgress(notice, subscriber(socket))).isEmpty();
        assertThat(guard.getPendingProgressCount(socket.getId())).isZero();
        assertThat(guard.getPendingProgressBytes(socket.getId())).isZero();
    }

    @Test
    void alreadyExpiredProgressIsRejectedBeforeAnyQueueAdmission() throws Exception {
        WebSocketSession socket = socket(session("expired-notice-owner"));
        assertThat(guard.registerConnection(socket)).isTrue();
        assertThat(guard.reserveProgress(progressNotice(new byte[1], System.nanoTime() - 1), subscriber(socket))).isEmpty();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertThat(guard.getPendingProgressCount(socket.getId())).isZero();
    }

    private Message<?> progressNotice(byte[] payload, Long deadline) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination("/topic/progress/task");
        if (deadline != null) {
            headers.setHeader(WebSocketSessionGuard.PROGRESS_DEADLINE_HEADER, deadline);
        }
        return guard.progressBrokerInterceptor().preSend(MessageBuilder.createMessage(payload, headers.getMessageHeaders()), null);
    }

    private MultiValueMap<String, String> subscriber(WebSocketSession socket) {
        MultiValueMap<String, String> subscriptions = new LinkedMultiValueMap<>();
        subscriptions.add(socket.getId(), "progress-subscription");
        return subscriptions;
    }

    private Message<?> outboundProgress(WebSocketSession socket, Message<?> notice) {
        SimpMessageHeaderAccessor target = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        target.setSessionId(socket.getId());
        target.setSubscriptionId("progress-subscription");
        target.copyHeadersIfAbsent(notice.getHeaders());
        Message<?> outbound = guard.progressOutboundInterceptor().preSend(
                MessageBuilder.createMessage(notice.getPayload(), target.getMessageHeaders()), null);
        assertThat(outbound).isNotNull();
        assertThat(StompHeaderAccessor.wrap(outbound).getMessageId()).isNotBlank();
        return outbound;
    }

    private UserSession session(String ownerId) {
        return access.createSession(false, "127.0.0.1", "guard-test", ownerId, false);
    }

    private WebSocketSession socket(UserSession identity) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/info");
        request.setServletPath("/ws/info");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "guard-test");
        request.addHeader("X-Session-ID", identity.getSessionId());
        AtomicReference<Principal> principal = new AtomicReference<>();
        guard.doFilter(request, new MockHttpServletResponse(), (wrapped, response) ->
                principal.set(((HttpServletRequest) wrapped).getUserPrincipal()));
        assertThat(principal.get()).isNotNull();
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("connection-" + nextConnectionId++);
        when(socket.getPrincipal()).thenReturn(principal.get());
        when(socket.getAttributes()).thenReturn(java.util.Map.of("sessionId", identity.getSessionId()));
        AtomicBoolean open = new AtomicBoolean(true);
        when(socket.isOpen()).thenAnswer(invocation -> open.get());
        doAnswer(invocation -> {
            open.set(false);
            return null;
        }).when(socket).close(CloseStatus.POLICY_VIOLATION);
        return socket;
    }

    private String sessionId(WebSocketSession socket) {
        return (String) socket.getAttributes().get("sessionId");
    }

    private Message<byte[]> message(WebSocketSession socket, SimpMessageType type, String subscriptionId) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(type);
        headers.setSessionId(socket.getId());
        headers.setUser(socket.getPrincipal());
        headers.setSubscriptionId(subscriptionId);
        headers.setDestination("/topic/progress/task");
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
