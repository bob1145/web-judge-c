package com.example.demo.service.sandbox;

import com.example.demo.config.SandboxProperties;
import com.example.demo.config.WorkerProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "judge.sandbox.production.provider", havingValue = "remote-worker")
public class RemoteWorkerRunner implements SandboxRunner {

    private static final String PROVIDER = "remote-worker";
    private static final String UNAVAILABLE_RUN_ID = "unavailable";

    private final Options options;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, List<SandboxTaskEvent>> localEvents = new ConcurrentHashMap<>();

    @Autowired
    public RemoteWorkerRunner(
            SandboxProperties sandboxProperties,
            WorkerProperties workerProperties,
            ObjectMapper objectMapper
    ) {
        this(Options.from(sandboxProperties, workerProperties), objectMapper);
    }

    public RemoteWorkerRunner(WorkerProperties workerProperties, ObjectMapper objectMapper) {
        this(Options.from(workerProperties), objectMapper);
    }

    public RemoteWorkerRunner(Options options, ObjectMapper objectMapper) {
        this(options, objectMapper, null);
    }

    RemoteWorkerRunner(Options options, ObjectMapper objectMapper, SSLContext sslContext) {
        this.options = options == null ? Options.builder().build() : options;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(this.options.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.httpClient = builder.build();
    }

    @Override
    public SandboxCapabilities probe() {
        try {
            HttpResponse<String> response = send("GET", "/api/v1/capabilities", "");
            if (!isSuccess(response.statusCode())) {
                return unavailableCapabilities("worker unavailable: status " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), SandboxCapabilities.class);
        } catch (Exception e) {
            return unavailableCapabilities("worker unavailable: " + e.getMessage());
        }
    }

    @Override
    public SandboxRunHandle start(SandboxTaskSpec spec) {
        try {
            String body = objectMapper.writeValueAsString(spec);
            HttpResponse<String> response = send("POST", "/api/v1/runs", body);
            if (!isSuccess(response.statusCode())) {
                return unavailableHandle(spec.judgeId(), "worker unavailable: status " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), SandboxRunHandle.class);
        } catch (Exception e) {
            return unavailableHandle(spec.judgeId(), "worker unavailable: " + e.getMessage());
        }
    }

    @Override
    public List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle) {
        List<SandboxTaskEvent> local = localEvents.remove(handle.runId());
        if (local != null) {
            return local;
        }
        String path = "/api/v1/runs/" + encode(handle.runId()) + "/events?cursor=" + encode(handle.eventCursor());
        try {
            HttpResponse<String> response = send("GET", path, "");
            if (!isSuccess(response.statusCode())) {
                return List.of(unavailableEvent(handle.judgeId(), "worker unavailable: status " + response.statusCode()));
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of(unavailableEvent(handle.judgeId(), "worker unavailable: " + e.getMessage()));
        }
    }

    @Override
    public void cancel(SandboxRunHandle handle) {
        if (UNAVAILABLE_RUN_ID.equals(handle.runId())) {
            return;
        }
        try {
            HttpResponse<String> response = send(
                    "POST",
                    "/api/v1/runs/" + encode(handle.runId()) + "/cancel",
                    ""
            );
            if (isSuccess(response.statusCode()) || response.statusCode() == 404 || response.statusCode() == 409) {
                return;
            }
            throw new IllegalStateException("worker cancel failed: status " + response.statusCode());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("worker cancel failed: " + e.getMessage(), e);
        }
    }

    private HttpResponse<String> send(String method, String pathAndQuery, String body)
            throws IOException, InterruptedException {
        String requestBody = body == null ? "" : body;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(pathAndQuery))
                .timeout(options.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        addAuthenticationHeaders(builder, method, pathAndQuery, requestBody);
        HttpRequest.BodyPublisher publisher = requestBody.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8);
        HttpRequest request = builder.method(method, publisher).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void addAuthenticationHeaders(
            HttpRequest.Builder builder,
            String method,
            String pathAndQuery,
            String body
    ) {
        if (StringUtils.hasText(options.authToken())) {
            builder.header("Authorization", "Bearer " + options.authToken());
        }
        if (StringUtils.hasText(options.signingSecret())) {
            builder.header("X-Judge-Worker-Signature-Alg", "HMAC-SHA256");
            builder.header("X-Judge-Worker-Signature", signature(method, pathAndQuery, body));
        }
    }

    private String signature(String method, String pathAndQuery, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(options.signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((method + "\n" + pathAndQuery + "\n" + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("worker request signing failed", e);
        }
    }

    private URI uri(String pathAndQuery) {
        String base = options.endpoint().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + pathAndQuery);
    }

    private SandboxCapabilities unavailableCapabilities(String reason) {
        return SandboxCapabilities.builder()
                .provider(PROVIDER)
                .isolation("remote")
                .productionSafe(false)
                .networkDisabled(false)
                .nonRoot(false)
                .resourceLimits(false)
                .securityProfile("remote-worker")
                .details("endpoint=" + options.endpoint())
                .skipReason(reason)
                .build();
    }

    private SandboxRunHandle unavailableHandle(String judgeId, String reason) {
        SandboxTaskEvent event = unavailableEvent(judgeId, reason);
        localEvents.put(UNAVAILABLE_RUN_ID, List.of(event));
        return SandboxRunHandle.builder()
                .judgeId(judgeId)
                .runId(UNAVAILABLE_RUN_ID)
                .provider(PROVIDER)
                .startedAt(Instant.now())
                .eventCursor("0")
                .build();
    }

    private SandboxTaskEvent unavailableEvent(String judgeId, String reason) {
        return SandboxTaskEvent.of(judgeId, SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE, reason);
    }

    private boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public static final class Options {
        private final URI endpoint;
        private final String authToken;
        private final String signingSecret;
        private final String mtlsCertificate;
        private final Duration connectTimeout;
        private final Duration requestTimeout;

        private Options(Builder builder) {
            WorkerProperties settings = new WorkerProperties();
            settings.setEndpoint(builder.endpoint);
            settings.setAuthToken(builder.authToken);
            settings.setSigningSecret(builder.signingSecret);
            settings.setMtlsCertificate(builder.mtlsCertificate);
            settings.setConnectTimeout(builder.connectTimeout);
            settings.setRequestTimeout(builder.requestTimeout);
            this.endpoint = settings.validateAndGetEndpoint();
            this.authToken = valueOrEmpty(builder.authToken);
            this.signingSecret = valueOrEmpty(builder.signingSecret);
            this.mtlsCertificate = valueOrEmpty(builder.mtlsCertificate);
            this.connectTimeout = builder.connectTimeout;
            this.requestTimeout = builder.requestTimeout;
        }

        static Options from(SandboxProperties sandboxProperties, WorkerProperties workerProperties) {
            return from(workerProperties.withFallback(sandboxProperties.getWorker()));
        }

        static Options from(WorkerProperties workerProperties) {
            return builder()
                    .endpoint(workerProperties.getEndpoint())
                    .authToken(workerProperties.getAuthToken())
                    .signingSecret(workerProperties.getSigningSecret())
                    .mtlsCertificate(workerProperties.getMtlsCertificate())
                    .connectTimeout(workerProperties.getConnectTimeout())
                    .requestTimeout(workerProperties.getRequestTimeout())
                    .build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public URI endpoint() {
            return endpoint;
        }

        public String authToken() {
            return authToken;
        }

        public String signingSecret() {
            return signingSecret;
        }

        public String mtlsCertificate() {
            return mtlsCertificate;
        }

        public Duration connectTimeout() {
            return connectTimeout;
        }

        public Duration requestTimeout() {
            return requestTimeout;
        }

        public static final class Builder {
            private String endpoint = "";
            private String authToken = "";
            private String signingSecret = "";
            private String mtlsCertificate = "";
            private Duration connectTimeout = Duration.ofSeconds(5);
            private Duration requestTimeout = Duration.ofSeconds(30);

            public Builder endpoint(String endpoint) {
                this.endpoint = endpoint;
                return this;
            }

            public Builder authToken(String authToken) {
                this.authToken = authToken;
                return this;
            }

            public Builder signingSecret(String signingSecret) {
                this.signingSecret = signingSecret;
                return this;
            }

            public Builder mtlsCertificate(String mtlsCertificate) {
                this.mtlsCertificate = mtlsCertificate;
                return this;
            }

            public Builder connectTimeout(Duration connectTimeout) {
                this.connectTimeout = connectTimeout;
                return this;
            }

            public Builder requestTimeout(Duration requestTimeout) {
                this.requestTimeout = requestTimeout;
                return this;
            }

            public Options build() {
                return new Options(this);
            }
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
