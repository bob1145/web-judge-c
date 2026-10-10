package com.example.demo.config;

import com.example.demo.security.WebSocketSessionGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SubscriptionRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.messaging.web.csrf.XorCsrfChannelInterceptor;
import org.springframework.security.messaging.web.socket.server.CsrfTokenHandshakeInterceptor;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketSessionDecorator;
import org.springframework.web.socket.sockjs.frame.SockJsFrame;
import org.springframework.web.socket.sockjs.transport.handler.XhrPollingTransportHandler;
import org.springframework.web.socket.sockjs.transport.handler.XhrStreamingTransportHandler;
import org.springframework.web.socket.sockjs.transport.handler.EventSourceTransportHandler;
import org.springframework.web.socket.sockjs.transport.SockJsServiceConfig;
import org.springframework.web.socket.sockjs.transport.SockJsSession;
import org.springframework.web.socket.sockjs.transport.TransportType;
import org.springframework.web.socket.sockjs.transport.session.AbstractHttpSockJsSession;
import org.springframework.web.socket.sockjs.transport.session.PollingSockJsSession;
import org.springframework.web.socket.sockjs.transport.session.StreamingSockJsSession;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketSessionGuard sessionGuard;

    @Value("${app.websocket.allowed-origins:https://yourdomain.com,http://localhost:*}")
    private String allowedOrigins;

    public WebSocketConfig() {
        this.sessionGuard = null;
    }

    public WebSocketConfig(String allowedOrigins) {
        this.sessionGuard = null;
        this.allowedOrigins = allowedOrigins;
    }

    @Autowired
    public WebSocketConfig(WebSocketSessionGuard sessionGuard) {
        this.sessionGuard = sessionGuard;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setPreservePublishOrder(true);
        config.setApplicationDestinationPrefixes("/app");
        if (sessionGuard != null) {
            config.configureBrokerChannel().interceptors(sessionGuard.progressBrokerInterceptor());
        }
    }

    @Bean
    public SmartInitializingSingleton boundedProgressSubscriptions(ObjectProvider<SimpleBrokerMessageHandler> brokerProvider) {
        return () -> {
            if (sessionGuard == null) {
                return;
            }
            SimpleBrokerMessageHandler broker = brokerProvider.getIfAvailable();
            if (broker == null) {
                return;
            }
            SubscriptionRegistry delegate = broker.getSubscriptionRegistry();
            broker.setSubscriptionRegistry(new SubscriptionRegistry() {
                @Override
                public void registerSubscription(Message<?> message) {
                    delegate.registerSubscription(message);
                }

                @Override
                public void unregisterSubscription(Message<?> message) {
                    delegate.unregisterSubscription(message);
                }

                @Override
                public void unregisterAllSubscriptions(String sessionId) {
                    delegate.unregisterAllSubscriptions(sessionId);
                }

                @Override
                public MultiValueMap<String, String> findSubscriptions(Message<?> message) {
                    Object reserved = message.getHeaders().get(WebSocketSessionGuard.PROGRESS_SUBSCRIPTIONS_HEADER);
                    if (reserved instanceof MultiValueMap<?, ?>) {
                        @SuppressWarnings("unchecked")
                        MultiValueMap<String, String> subscribers = (MultiValueMap<String, String>) reserved;
                        return sessionGuard.reserveProgress(message, subscribers);
                    }
                    return sessionGuard.reserveProgress(message, delegate.findSubscriptions(message));
                }
            });
        };
    }

    @Bean
    public MessageChannel progressNotificationChannel(
            @Qualifier("brokerChannel") MessageChannel brokerChannel,
            ObjectProvider<SimpleBrokerMessageHandler> brokerProvider,
            @Qualifier("clientOutboundChannelExecutor") Executor executor
    ) {
        return (message, timeout) -> {
            Object budget = message.getHeaders().get(WebSocketSessionGuard.PROGRESS_DEADLINE_HEADER);
            if (!(budget instanceof Long deadline) || deadline - System.nanoTime() <= 0) {
                throw new MessageDeliveryException(message, "Progress notification budget exhausted");
            }
            MultiValueMap<String, String> subscribers = brokerProvider.getObject().getSubscriptionRegistry()
                    .findSubscriptions(message);
            if (subscribers.isEmpty()) {
                return true;
            }
            Message<?> reserved = MessageBuilder.fromMessage(message)
                    .setHeader(WebSocketSessionGuard.PROGRESS_SUBSCRIPTIONS_HEADER, subscribers).build();
            if (deadline - System.nanoTime() <= 0) {
                throw new MessageDeliveryException(message, "Progress notification budget exhausted before dispatch");
            }
            FutureTask<Boolean> sending = new FutureTask<>(() -> {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                    throw new MessageDeliveryException(message, "Progress notification budget exhausted in queue");
                }
                return brokerChannel.send(reserved, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            });
            executor.execute(sending);
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new TimeoutException("Progress notification budget exhausted after dispatch");
                }
                return sending.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                sending.cancel(true);
                Thread.currentThread().interrupt();
                throw new MessageDeliveryException(message, "Progress notification interrupted", interrupted);
            } catch (TimeoutException timeoutFailure) {
                sending.cancel(true);
                throw new MessageDeliveryException(message, "Progress notification budget exhausted", timeoutFailure);
            } catch (ExecutionException failure) {
                throw new MessageDeliveryException(message, "Progress notification failed", failure.getCause());
            } finally {
                if (sending.isCancelled() && executor instanceof ThreadPoolTaskExecutor pool) {
                    pool.getThreadPoolExecutor().remove(sending);
                }
            }
        };
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        if (sessionGuard != null) {
            registration.interceptors(sessionGuard.progressOutboundInterceptor());
        }
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (SimpMessageType.MESSAGE == SimpMessageHeaderAccessor.getMessageType(message.getHeaders())) {
                    throw new AccessDeniedException("Client SEND is not allowed");
                }
                return message;
            }
        });
        if (sessionGuard != null) {
            registration.interceptors(sessionGuard);
        }
        registration.interceptors(new XorCsrfChannelInterceptor());
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        if (sessionGuard == null) {
            return;
        }
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                if (!sessionGuard.registerConnection(session)) {
                    session.close(CloseStatus.POLICY_VIOLATION);
                    return;
                }
                try {
                    super.afterConnectionEstablished(new WebSocketSessionDecorator(session) {
                        @Override
                        public void sendMessage(WebSocketMessage<?> message) throws IOException {
                            if (!sessionGuard.isConnectionActive(this)) {
                                close(CloseStatus.POLICY_VIOLATION);
                                return;
                            }
                            String frame = message instanceof TextMessage text ? text.getPayload()
                                    : message instanceof BinaryMessage binary
                                    ? StandardCharsets.UTF_8.decode(binary.getPayload().asReadOnlyBuffer()).toString() : "";
                            if (!sessionGuard.canWriteProgress(getId(), frame)) {
                                close(CloseStatus.POLICY_VIOLATION);
                                return;
                            }
                            super.sendMessage(message);
                            if (!(session instanceof AbstractHttpSockJsSession)) {
                                sessionGuard.progressWritten(getId(), frame);
                            }
                        }
                    });
                } catch (Exception exception) {
                    try {
                        session.close(CloseStatus.SERVER_ERROR);
                    } finally {
                        if (!session.isOpen()) {
                            sessionGuard.unregisterConnection(session.getId());
                        }
                    }
                    throw exception;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                try {
                    super.afterConnectionClosed(session, closeStatus);
                } finally {
                    sessionGuard.unregisterConnection(session.getId());
                }
            }
        });
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        var sockJs = registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(getAllowedOriginPatterns())
                .addInterceptors(new CsrfTokenHandshakeInterceptor())
                .withSockJS();
        if (sessionGuard != null) {
            sockJs.setTransportHandlerOverrides(new XhrPollingTransportHandler() {
                @Override
                public PollingSockJsSession createSession(String sessionId, WebSocketHandler handler, Map<String, Object> attributes) {
                    return new PollingSockJsSession(sessionId, getServiceConfig(), handler, attributes) {
                        @Override
                        protected void writeFrameInternal(SockJsFrame frame) throws IOException {
                            String[] messages = frameMessages(this, frame);
                            if (messages.length > 0 && !sessionGuard.canWriteProgress(getId(), messages)) {
                                close(CloseStatus.POLICY_VIOLATION);
                                return;
                            }
                            super.writeFrameInternal(frame);
                            sessionGuard.progressWritten(getId(), messages);
                        }
                    };
                }
            }, new XhrStreamingTransportHandler() {
                @Override
                public boolean checkSessionType(SockJsSession session) {
                    return session instanceof ProgressStreamingSockJsSession streaming
                            && streaming.transportType == TransportType.XHR_STREAMING;
                }

                @Override
                public StreamingSockJsSession createSession(String sessionId, WebSocketHandler handler, Map<String, Object> attributes) {
                    return new ProgressStreamingSockJsSession(sessionId, getServiceConfig(), handler, attributes,
                            TransportType.XHR_STREAMING, ("h".repeat(2048) + "\n").getBytes(StandardCharsets.US_ASCII));
                }
            }, new EventSourceTransportHandler() {
                @Override
                public boolean checkSessionType(SockJsSession session) {
                    return session instanceof ProgressStreamingSockJsSession streaming
                            && streaming.transportType == TransportType.EVENT_SOURCE;
                }

                @Override
                public StreamingSockJsSession createSession(String sessionId, WebSocketHandler handler, Map<String, Object> attributes) {
                    return new ProgressStreamingSockJsSession(sessionId, getServiceConfig(), handler, attributes,
                            TransportType.EVENT_SOURCE, new byte[]{13, 10});
                }
            });
        }
    }

    private String[] frameMessages(AbstractHttpSockJsSession session, SockJsFrame frame) throws IOException {
        return frame.getContent().startsWith("a[")
                ? session.getSockJsServiceConfig().getMessageCodec().decode(frame.getContent().substring(1)) : new String[0];
    }

    private final class ProgressStreamingSockJsSession extends StreamingSockJsSession {
        private final TransportType transportType;
        private final byte[] prelude;

        private ProgressStreamingSockJsSession(String sessionId, SockJsServiceConfig config, WebSocketHandler handler,
                                              Map<String, Object> attributes, TransportType transportType, byte[] prelude) {
            super(sessionId, config, handler, attributes);
            this.transportType = transportType;
            this.prelude = prelude;
        }

        @Override
        protected byte[] getPrelude(ServerHttpRequest request) {
            return prelude;
        }

        @Override
        protected void writeFrameInternal(SockJsFrame frame) throws IOException {
            String[] messages = frameMessages(this, frame);
            if (messages.length > 0 && !sessionGuard.canWriteProgress(getId(), messages)) {
                close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            super.writeFrameInternal(frame);
            sessionGuard.progressWritten(getId(), messages);
        }
    }

    public String[] getAllowedOriginPatterns() {
        return parseAllowedOriginPatterns(allowedOrigins);
    }

    public boolean hasWildcardOrigin() {
        return Arrays.stream(getAllowedOriginPatterns())
                .anyMatch("*"::equals);
    }

    public boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        return Arrays.stream(getAllowedOriginPatterns())
                .anyMatch(pattern -> PatternMatchUtils.simpleMatch(pattern, origin));
    }

    public static String[] parseAllowedOriginPatterns(String configuredOrigins) {
        if (configuredOrigins == null || configuredOrigins.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }
}
