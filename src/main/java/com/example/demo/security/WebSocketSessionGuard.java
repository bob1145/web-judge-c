package com.example.demo.security;

import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.QuotaService;
import com.example.demo.service.SessionManagementService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompEncoder;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.sockjs.frame.SockJsFrame;
import org.springframework.web.socket.sockjs.transport.session.AbstractSockJsSession;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class WebSocketSessionGuard extends OncePerRequestFilter implements ExecutorChannelInterceptor {
    private static final String PROGRESS_PREFIX = "/topic/progress/";
    private static final int MAX_CONNECTIONS = 128;
    private static final int MAX_OWNER_CONNECTIONS = 2;
    private static final int MAX_SUBSCRIPTIONS = 8;
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    private static final int MAX_PENDING_PROGRESS = 32;
    private static final int MAX_PENDING_PROGRESS_BYTES = 2 * 1024 * 1024;
    public static final String PROGRESS_NOTICE_HEADER = "progressNoticeId";
    public static final String PROGRESS_DEADLINE_HEADER = "progressDeadlineNanos";
    public static final String PROGRESS_SUBSCRIPTIONS_HEADER = "progressReservedSubscriptions";
    public static final String PROGRESS_CANCELLED_HEADER = "progressNotificationCancelled";
    public static final Duration PROGRESS_SEND_BUDGET = Duration.ofSeconds(5);
    private static final Pattern FRAME_MESSAGE_ID = Pattern.compile("^message-id:([^\\r\\n]+)", Pattern.MULTILINE);
    private final AccessCodeService accessCodeService;
    private final QuotaService quotaService;
    private final SessionManagementService sessionManagementService;
    private final ClientAddressResolver clientAddressResolver;
    private final Clock clock;
    private final Map<String, ConnectionState> connections = new HashMap<>();

    @Autowired
    public WebSocketSessionGuard(AccessCodeService accessCodeService, QuotaService quotaService,
                                SessionManagementService sessionManagementService, ClientAddressResolver clientAddressResolver) {
        this(accessCodeService, quotaService, sessionManagementService, clientAddressResolver, Clock.systemUTC());
    }

    public WebSocketSessionGuard(AccessCodeService accessCodeService, QuotaService quotaService,
                                SessionManagementService sessionManagementService, ClientAddressResolver clientAddressResolver,
                                Clock clock) {
        this.accessCodeService = accessCodeService;
        this.quotaService = quotaService;
        this.sessionManagementService = sessionManagementService;
        this.clientAddressResolver = clientAddressResolver;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !("/ws".equals(path) || path.startsWith("/ws/")) || CorsUtils.isPreFlightRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UserSession session = accessCodeService.getSession(SessionCredentialResolver.resolve(request));
        try {
            if (session == null || !sessionManagementService.validateSessionWithSecurity(
                    session.getSessionId(), clientAddressResolver.resolve(request), request.getHeader("User-Agent"))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
                return;
            }
        } catch (ResponseStatusException exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid forwarded client address");
            return;
        }
        Principal principal = new SessionPrincipal(session.getSessionId());
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public Principal getUserPrincipal() {
                return principal;
            }

            @Override
            public String getRemoteUser() {
                return principal.getName();
            }
        }, response);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        if (type != SimpMessageType.CONNECT && type != SimpMessageType.SUBSCRIBE
                && type != SimpMessageType.UNSUBSCRIBE) {
            return message;
        }
        Principal principal = SimpMessageHeaderAccessor.getUser(message.getHeaders());
        UserSession session = activeSession(principal);
        if (session == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (type == SimpMessageType.SUBSCRIBE) {
            String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
            if (destination == null || !destination.startsWith(PROGRESS_PREFIX)
                    || !quotaService.canAccessTask(destination.substring(PROGRESS_PREFIX.length()), session)) {
                throw new AccessDeniedException("Subscription not allowed");
            }
        }
        String connectionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        synchronized (connections) {
            ConnectionState connection = connections.get(connectionId);
            if (connection == null || connection.closing || !connection.sessionId.equals(session.getSessionId())
                    || isIdle(connection)) {
                throw new AccessDeniedException("Connection is not active");
            }
            if (type == SimpMessageType.SUBSCRIBE || type == SimpMessageType.UNSUBSCRIBE) {
                String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders());
                if (subscriptionId == null || subscriptionId.isBlank()) {
                    throw new AccessDeniedException("Subscription ID required");
                }
                if (type == SimpMessageType.SUBSCRIBE) {
                    if (connection.subscriptions.contains(subscriptionId)
                            || connection.subscriptions.size() >= MAX_SUBSCRIPTIONS) {
                        throw new AccessDeniedException("Subscription limit reached or ID already used");
                    }
                    connection.subscriptions.add(subscriptionId);
                } else if (!connection.subscriptions.contains(subscriptionId)
                        || !connection.pendingUnsubscriptions.add(subscriptionId)) {
                    throw new AccessDeniedException("Subscription not found");
                }
            }
            connection.lastActivity = clock.instant();
        }
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception exception) {
        if (!(handler instanceof SimpleBrokerMessageHandler) || exception != null
                || SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.UNSUBSCRIBE) {
            return;
        }
        synchronized (connections) {
            ConnectionState connection = connections.get(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
            String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders());
            if (connection != null && connection.pendingUnsubscriptions.remove(subscriptionId)) {
                connection.subscriptions.remove(subscriptionId);
            }
        }
    }

    public boolean registerConnection(WebSocketSession socket) {
        UserSession session = activeSession(socket.getPrincipal());
        if (session == null) {
            return false;
        }
        String ownerId = quotaService.ownershipFor(session).getUserId();
        synchronized (connections) {
            if (connections.containsKey(socket.getId()) || connections.size() >= MAX_CONNECTIONS
                    || connections.values().stream().filter(connection -> connection.ownerId.equals(ownerId)).count()
                    >= MAX_OWNER_CONNECTIONS) {
                return false;
            }
            connections.put(socket.getId(), new ConnectionState(socket, session.getSessionId(), ownerId, clock.instant()));
        }
        if (activeSession(socket.getPrincipal()) == null) {
            revokeSessionConnections(session.getSessionId());
            return false;
        }
        return true;
    }

    public void unregisterConnection(String connectionId) {
        synchronized (connections) {
            connections.remove(connectionId);
        }
    }

    public int getConnectionCount() {
        synchronized (connections) {
            return connections.size();
        }
    }

    public ChannelInterceptor progressBrokerInterceptor() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (!isProgressMessage(message)) {
                    return message;
                }
                SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.wrap(message);
                if (!(headers.getHeader(PROGRESS_NOTICE_HEADER) instanceof String)) {
                    headers.setHeader(PROGRESS_NOTICE_HEADER, UUID.randomUUID().toString());
                }
                if (!(headers.getHeader(PROGRESS_DEADLINE_HEADER) instanceof Long)) {
                    headers.setHeader(PROGRESS_DEADLINE_HEADER, System.nanoTime() + PROGRESS_SEND_BUDGET.toNanos());
                }
                return MessageBuilder.createMessage(message.getPayload(), headers.getMessageHeaders());
            }
        };
    }

    public ChannelInterceptor progressOutboundInterceptor() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (!isProgressMessage(message)) {
                    return message;
                }
                String connectionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
                String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders());
                Object noticeId = message.getHeaders().get(PROGRESS_NOTICE_HEADER);
                String deliveryId = null;
                synchronized (connections) {
                    ConnectionState connection = connections.get(connectionId);
                    if (connection != null && !connection.closing) {
                        for (var pending : connection.pendingProgress.entrySet()) {
                            if (pending.getValue().noticeId().equals(noticeId)
                                    && pending.getValue().subscriptionId().equals(subscriptionId)) {
                                deliveryId = pending.getKey();
                                break;
                            }
                        }
                    }
                }
                if (deliveryId == null) {
                    return null;
                }
                StompHeaderAccessor headers = StompHeaderAccessor.wrap(message);
                headers.updateStompCommandAsServerMessage();
                headers.setMessageId(deliveryId);
                return MessageBuilder.createMessage(message.getPayload(), headers.getMessageHeaders());
            }
        };
    }

    public MultiValueMap<String, String> reserveProgress(Message<?> message, MultiValueMap<String, String> subscribers) {
        if (!isProgressMessage(message)) {
            return subscribers;
        }
        MultiValueMap<String, String> admitted = new LinkedMultiValueMap<>();
        List<ConnectionState> rejected = new java.util.ArrayList<>();
        Object noticeId = message.getHeaders().get(PROGRESS_NOTICE_HEADER);
        Object cancelled = message.getHeaders().get(PROGRESS_CANCELLED_HEADER);
        Long deadline = (Long) message.getHeaders().get(PROGRESS_DEADLINE_HEADER);
        for (var subscriber : subscribers.entrySet()) {
            ConnectionState connection;
            synchronized (connections) {
                connection = connections.get(subscriber.getKey());
            }
            if (connection == null) {
                continue;
            }
            if (!(noticeId instanceof String) || deadline == null || deadline - System.nanoTime() <= 0 || isCancelled(cancelled)
                    || !isConnectionActive(connection.socket) || !(message.getPayload() instanceof byte[] body)
                    || body.length > MAX_PENDING_PROGRESS_BYTES) {
                rejected.add(connection);
                continue;
            }
            for (String subscriptionId : subscriber.getValue()) {
                synchronized (connections) {
                    if (connections.get(subscriber.getKey()) == connection && !connection.closing
                            && connection.pendingProgress.values().stream().anyMatch(pending ->
                            pending.noticeId().equals(noticeId) && pending.subscriptionId().equals(subscriptionId))) {
                        admitted.add(subscriber.getKey(), subscriptionId);
                        continue;
                    }
                }
                String deliveryId = UUID.randomUUID().toString();
                SimpMessageHeaderAccessor target = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
                target.setSessionId(subscriber.getKey());
                target.setSubscriptionId(subscriptionId);
                target.copyHeadersIfAbsent(message.getHeaders());
                StompHeaderAccessor headers = StompHeaderAccessor.wrap(MessageBuilder.createMessage(body, target.getMessageHeaders()));
                headers.updateStompCommandAsServerMessage();
                headers.setMessageId(deliveryId);
                byte[] encoded = new StompEncoder().encode(headers.getMessageHeaders(), body);
                int bytes = encoded.length + 16;
                if (connection.socket instanceof AbstractSockJsSession sockJs) {
                    bytes = SockJsFrame.messageFrame(sockJs.getSockJsServiceConfig().getMessageCodec(),
                            new String(encoded, StandardCharsets.UTF_8)).getContent().getBytes(StandardCharsets.UTF_8).length + 16;
                }
                synchronized (connections) {
                    if (connections.get(subscriber.getKey()) != connection || connection.closing
                            || connection.pendingProgress.size() >= MAX_PENDING_PROGRESS
                            || bytes > MAX_PENDING_PROGRESS_BYTES - connection.pendingBytes) {
                        rejected.add(connection);
                        continue;
                    }
                    connection.pendingProgress.put(deliveryId, new PendingProgress((String) noticeId, subscriptionId, bytes, deadline,
                            cancelled instanceof AtomicBoolean cancellation ? cancellation : null));
                    connection.pendingBytes += bytes;
                    admitted.add(subscriber.getKey(), subscriptionId);
                }
            }
        }
        rejected.forEach(this::closeConnection);
        return admitted;
    }

    public int getPendingProgressCount(String connectionId) {
        synchronized (connections) {
            ConnectionState connection = connections.get(connectionId);
            return connection == null ? 0 : connection.pendingProgress.size();
        }
    }

    public long getPendingProgressBytes(String connectionId) {
        synchronized (connections) {
            ConnectionState connection = connections.get(connectionId);
            return connection == null ? 0 : connection.pendingBytes;
        }
    }

    public boolean canWriteProgress(String connectionId, String... frames) {
        ConnectionState candidate;
        synchronized (connections) {
            candidate = connections.get(connectionId);
        }
        if (candidate == null || !isConnectionActive(candidate.socket)) {
            return false;
        }
        synchronized (connections) {
            ConnectionState connection = connections.get(connectionId);
            if (connection != candidate || connection.closing) {
                return false;
            }
            for (String frame : frames) {
                String deliveryId = progressMessageId(frame);
                PendingProgress pending = connection.pendingProgress.get(deliveryId);
                if (pending != null && (pending.deadlineNanos() - System.nanoTime() <= 0 || isCancelled(pending.cancelled()))) {
                    return false;
                }
            }
            return true;
        }
    }

    public void progressWritten(String connectionId, String... frames) {
        synchronized (connections) {
            ConnectionState connection = connections.get(connectionId);
            if (connection != null) {
                for (String frame : frames) {
                    PendingProgress pending = connection.pendingProgress.remove(progressMessageId(frame));
                    if (pending != null) {
                        connection.pendingBytes -= pending.bytes();
                    }
                }
            }
        }
    }

    private boolean isProgressMessage(Message<?> message) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        return SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE
                && destination != null && destination.startsWith(PROGRESS_PREFIX);
    }

    private String progressMessageId(String frame) {
        if (!frame.startsWith("MESSAGE\n") && !frame.startsWith("MESSAGE\r\n")) {
            return null;
        }
        int headerEnd = frame.indexOf("\n\n");
        if (headerEnd < 0) {
            headerEnd = frame.indexOf("\r\n\r\n");
        }
        if (headerEnd < 0) {
            return null;
        }
        Matcher messageId = FRAME_MESSAGE_ID.matcher(frame).region(0, headerEnd);
        return messageId.find() ? messageId.group(1) : null;
    }

    public boolean isConnectionActive(WebSocketSession socket) {
        if (!isSessionActive(socket.getPrincipal())) {
            return false;
        }
        synchronized (connections) {
            ConnectionState connection = connections.get(socket.getId());
            return connection != null && !connection.closing && !isIdle(connection);
        }
    }

    public void revokeSessionConnections(String sessionId) {
        snapshotConnections().stream().filter(connection -> connection.sessionId.equals(sessionId))
                .forEach(this::closeConnection);
    }

    public void closeInactiveConnections() {
        for (ConnectionState connection : snapshotConnections()) {
            if (!connection.socket.isOpen()) {
                unregisterConnection(connection.socket.getId());
            } else if (!isConnectionActive(connection.socket) || hasExpiredProgress(connection)) {
                closeConnection(connection);
            }
        }
    }

    private boolean hasExpiredProgress(ConnectionState connection) {
        synchronized (connections) {
            long now = System.nanoTime();
            return connection.pendingProgress.values().stream().anyMatch(pending ->
                    pending.deadlineNanos() - now <= 0 || isCancelled(pending.cancelled()));
        }
    }

    private boolean isCancelled(Object cancellation) {
        return cancellation instanceof AtomicBoolean cancelled && cancelled.get();
    }

    private List<ConnectionState> snapshotConnections() {
        synchronized (connections) {
            return List.copyOf(connections.values());
        }
    }

    private boolean isIdle(ConnectionState connection) {
        return Duration.between(connection.lastActivity, clock.instant()).compareTo(IDLE_TIMEOUT) >= 0;
    }

    private void closeConnection(ConnectionState connection) {
        synchronized (connections) {
            if (connections.get(connection.socket.getId()) != connection || connection.closing) {
                return;
            }
            connection.closing = true;
        }
        try {
            connection.socket.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException exception) {
            synchronized (connections) {
                connection.closing = false;
            }
            logger.warn("Failed to close revoked WebSocket connection", exception);
        } finally {
            if (!connection.socket.isOpen()) {
                unregisterConnection(connection.socket.getId());
            }
        }
    }

    public boolean isSessionActive(Principal principal) {
        return activeSession(principal) != null;
    }

    private UserSession activeSession(Principal principal) {
        return principal instanceof SessionPrincipal identity ? accessCodeService.getSession(identity.sessionId) : null;
    }

    private static final class ConnectionState {
        private final WebSocketSession socket;
        private final String sessionId;
        private final String ownerId;
        private final Set<String> subscriptions = new HashSet<>();
        private final Set<String> pendingUnsubscriptions = new HashSet<>();
        private final Map<String, PendingProgress> pendingProgress = new HashMap<>();
        private long pendingBytes;
        private Instant lastActivity;
        private boolean closing;

        private ConnectionState(WebSocketSession socket, String sessionId, String ownerId, Instant lastActivity) {
            this.socket = socket;
            this.sessionId = sessionId;
            this.ownerId = ownerId;
            this.lastActivity = lastActivity;
        }
    }

    private record PendingProgress(String noticeId, String subscriptionId, int bytes, long deadlineNanos, AtomicBoolean cancelled) {
    }

    private static final class SessionPrincipal implements Principal {
        private final String sessionId;
        private final String auditId;

        private SessionPrincipal(String sessionId) {
            this.sessionId = sessionId;
            this.auditId = SessionAuditId.from(sessionId);
        }

        @Override
        public String getName() {
            return auditId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SessionPrincipal identity && sessionId.equals(identity.sessionId);
        }

        @Override
        public int hashCode() {
            return sessionId.hashCode();
        }

        @Override
        public String toString() {
            return auditId;
        }
    }
}
