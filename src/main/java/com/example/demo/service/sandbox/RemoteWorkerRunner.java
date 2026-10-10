package com.example.demo.service.sandbox;

import com.example.demo.config.SandboxProperties;
import com.example.demo.config.WorkerProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxExecutionManifest;
import com.example.demo.dto.RunnerProtocolCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "judge.sandbox.production.provider", havingValue = "remote-worker")
public class RemoteWorkerRunner implements SandboxRunner {

    private static final String PROVIDER = "remote-worker";
    private static final String UNAVAILABLE_RUN_ID = "unavailable";

    private final Options options;
    private final ObjectMapper objectMapper;
    private final ObjectReader eventReader;
    private final HttpClient httpClient;
    private final Map<SandboxRunHandle, List<SandboxTaskEvent>> localEvents = new ConcurrentHashMap<>();

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
        this.eventReader = SandboxTaskEvent.jsonReader(this.objectMapper)
                .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
        return probe(options.requestTimeout());
    }

    private SandboxCapabilities probe(Duration timeout) {
        try {
            HttpResponse<byte[]> response = send("GET", "/api/v1/capabilities", "", timeout);
            if (!isSuccess(response.statusCode())) {
                return unavailableCapabilities("worker unavailable: status " + response.statusCode());
            }
            SandboxCapabilities capabilities = objectMapper.readValue(response.body(), SandboxCapabilities.class);
            if (capabilities.protocol() == null) {
                return unavailableCapabilities("worker does not declare a runner protocol");
            }
            capabilities.protocol().requireVersion2();
            return capabilities;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return unavailableCapabilities("worker unavailable: " + e.getMessage());
        }
    }

    @Override
    public SandboxRunHandle start(SandboxTaskSpec spec) {
        return start(spec, spec.maxTaskRuntime());
    }

    @Override
    public SandboxRunHandle start(SandboxTaskSpec spec, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Worker startup requires a positive timeout");
        }
        long started = System.nanoTime();
        long budgetNanos = timeout.toNanos();
        boolean createRequested = false;
        try {
            SandboxManifestFactory.Prepared prepared = SandboxManifestFactory.prepare(spec);
            SandboxExecutionManifest manifest = prepared.manifest();
            SandboxCapabilities capabilities = probe(remainingStartupBudget(started, budgetNanos));
            if (capabilities.protocol() == null) {
                return unavailableHandle(spec.judgeId(), "worker unavailable: does not support the requested runner protocol");
            }
            capabilities.protocol().require(manifest);
            if (!capabilities.protocol().features().contains(RunnerProtocolCapabilities.INLINE_SOURCES)) {
                return unavailableHandle(spec.judgeId(), "worker does not support inline task sources");
            }
            String body = objectMapper.writeValueAsString(new StartRequest(manifest, prepared.sourceContentsBase64()));
            Duration remaining = remainingStartupBudget(started, budgetNanos);
            createRequested = true;
            HttpResponse<byte[]> response = send("POST", "/api/v1/runs", body, remaining);
            if (!isSuccess(response.statusCode())) {
                throw new IllegalStateException("worker returned an unsuccessful start acknowledgement");
            }
            var acknowledgement = objectMapper.readTree(response.body());
            var version = acknowledgement.path("protocolVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt()) {
                throw new IllegalStateException("worker returned an incompatible run acknowledgement");
            }
            SandboxRunHandle handle = objectMapper.treeToValue(acknowledgement, SandboxRunHandle.class);
            if (handle.protocolVersion() != manifest.protocolVersion()
                    || !manifest.attemptId().equals(handle.attemptId())
                    || !manifest.judgeId().equals(handle.judgeId())
                    || !PROVIDER.equals(handle.provider()) || !StringUtils.hasText(handle.runId())
                    || UNAVAILABLE_RUN_ID.equals(handle.runId())) {
                throw new IllegalStateException("worker returned an incompatible run acknowledgement");
            }
            return handle;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (createRequested) {
                throw new IllegalStateException("Worker startup outcome is unconfirmed; trusted recovery is required", e);
            }
            return unavailableHandle(spec.judgeId(), "worker unavailable: " + e.getMessage());
        }
    }

    private Duration remainingStartupBudget(long started, long budgetNanos) throws IOException {
        long remaining = budgetNanos - (System.nanoTime() - started);
        if (remaining <= 0) {
            throw new IOException("Worker startup deadline exceeded");
        }
        return Duration.ofNanos(remaining);
    }

    @Override
    public List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle) {
        return pollEvents(handle, options.requestTimeout());
    }

    @Override
    public List<SandboxTaskEvent> pollEvents(SandboxRunHandle handle, Duration timeout) {
        if (handle == null || !PROVIDER.equals(handle.provider())) {
            throw new IllegalArgumentException("Sandbox run handle provider does not match " + PROVIDER);
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Worker event poll requires a positive timeout");
        }
        List<SandboxTaskEvent> local = localEvents.remove(handle);
        if (local != null) {
            return local;
        }
        if (UNAVAILABLE_RUN_ID.equals(handle.runId())) {
            return List.of();
        }
        String path = "/api/v1/runs/" + encode(handle.runId()) + "/events?cursor=" + encode(handle.eventCursor());
        try {
            HttpResponse<byte[]> response = send("GET", path, "", timeout);
            if (!isSuccess(response.statusCode())) {
                throw new IllegalStateException("worker event poll failed: status " + response.statusCode());
            }
            List<SandboxTaskEvent> events = new ArrayList<>();
            try (JsonParser parser = eventReader.getFactory().createParser(response.body())) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("worker event poll failed: invalid event batch");
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (events.size() >= options.eventBatchLimitCount()) {
                        throw new IOException("Worker response exceeds event batch count limit");
                    }
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        throw new IllegalStateException("worker event poll failed: invalid event batch");
                    }
                    long started = parser.getTokenLocation().getByteOffset();
                    SandboxTaskEvent event = eventReader.readValue(parser);
                    if (parser.getCurrentLocation().getByteOffset() - started > options.eventLineLimitBytes()) {
                        throw new IOException("Worker response exceeds event byte limit");
                    }
                    if (event == null || event.type() == null) {
                        throw new IllegalStateException("worker event poll failed: invalid event batch");
                    }
                    events.add(event);
                }
                if (parser.nextToken() != null) {
                    throw new IllegalStateException("worker event poll failed: trailing event payload");
                }
            }
            return events;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker event poll interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("worker event poll failed", e);
        }
    }

    @Override
    public void cancel(SandboxRunHandle handle) {
        cancel(handle, options.requestTimeout());
    }

    @Override
    public void cancel(SandboxRunHandle handle, Duration timeout) {
        if (handle == null || !PROVIDER.equals(handle.provider())) {
            throw new IllegalArgumentException("Sandbox run handle provider does not match " + PROVIDER);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Worker cancellation requires a positive timeout");
        }
        if (UNAVAILABLE_RUN_ID.equals(handle.runId())) {
            localEvents.remove(handle);
            return;
        }
        try {
            HttpResponse<byte[]> response = send(
                    "POST",
                    "/api/v1/runs/" + encode(handle.runId()) + "/cancel",
                    "",
                    timeout
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

    @Override
    public void cleanupResidual(SandboxRunHandle handle) {
        cleanupResidual(handle, options.requestTimeout());
    }

    @Override
    public void cleanupResidual(SandboxRunHandle handle, Duration timeout) {
        cancel(handle, timeout);
        if (UNAVAILABLE_RUN_ID.equals(handle.runId())) {
            return;
        }
        throw new IllegalStateException("Sandbox execution domain stop is not confirmed");
    }

    private record StartRequest(SandboxExecutionManifest manifest, Map<String, String> sourceContentsBase64) {
    }

    private HttpResponse<byte[]> send(String method, String pathAndQuery, String body)
            throws IOException, InterruptedException {
        return send(method, pathAndQuery, body, options.requestTimeout());
    }

    private HttpResponse<byte[]> send(String method, String pathAndQuery, String body, Duration timeout)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Worker request interrupted");
        }
        long started = System.nanoTime();
        Duration requestBudget = timeout.compareTo(options.requestTimeout()) < 0 ? timeout : options.requestTimeout();
        String requestBody = body == null ? "" : body;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(pathAndQuery))
                .timeout(requestBudget)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        addAuthenticationHeaders(builder, method, pathAndQuery, requestBody);
        HttpRequest.BodyPublisher publisher = requestBody.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8);
        HttpRequest request = builder.method(method, publisher).build();
        var response = httpClient.sendAsync(request, ignored -> boundedResponseBody());
        try {
            long remaining = requestBudget.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) {
                throw new HttpTimeoutException("Worker HTTP request deadline exceeded");
            }
            HttpResponse<byte[]> received = response.get(remaining, TimeUnit.NANOSECONDS);
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(received.body()));
            return received;
        } catch (TimeoutException exception) {
            throw new HttpTimeoutException("Worker HTTP request deadline exceeded");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof IOException failure) {
                throw failure;
            }
            throw new IOException("Worker HTTP request failed", exception.getCause());
        } finally {
            if (!response.isDone()) {
                response.cancel(true);
            }
        }
    }

    private HttpResponse.BodySubscriber<byte[]> boundedResponseBody() {
        var delegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long received;
            private boolean failed;

            @Override
            public CompletionStage<byte[]> getBody() {
                return delegate.getBody();
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                delegate.onSubscribe(subscription);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                if (failed) {
                    return;
                }
                for (ByteBuffer buffer : buffers) {
                    if (buffer.remaining() > options.eventBatchLimitBytes() - received) {
                        failed = true;
                        delegate.onError(new IOException("Worker response exceeds event batch byte limit"));
                        subscription.cancel();
                        return;
                    }
                    received += buffer.remaining();
                }
                delegate.onNext(buffers);
            }

            @Override
            public void onError(Throwable failure) {
                if (!failed) {
                    delegate.onError(failure);
                }
            }

            @Override
            public void onComplete() {
                if (!failed) {
                    delegate.onComplete();
                }
            }
        };
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
        String attemptId = UUID.randomUUID().toString();
        SandboxTaskEvent event = new SandboxTaskEvent(judgeId, SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE,
                Instant.now(), null, null, reason, null, null, SandboxExecutionManifest.VERSION, attemptId, 1L);
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId(judgeId)
                .runId(UNAVAILABLE_RUN_ID)
                .provider(PROVIDER)
                .startedAt(Instant.now())
                .eventCursor("0")
                .protocolVersion(SandboxExecutionManifest.VERSION)
                .attemptId(attemptId)
                .build();
        localEvents.put(handle, List.of(event));
        return handle;
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
        private final int eventBatchLimitBytes;
        private final int eventBatchLimitCount;
        private final int eventLineLimitBytes;

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
            if (builder.eventLineLimitBytes <= 0 || builder.eventBatchLimitBytes <= builder.eventLineLimitBytes
                    || builder.eventBatchLimitCount <= 0) {
                throw new IllegalArgumentException("event limits must be positive and batch bytes must exceed line bytes");
            }
            this.eventBatchLimitBytes = builder.eventBatchLimitBytes;
            this.eventBatchLimitCount = builder.eventBatchLimitCount;
            this.eventLineLimitBytes = builder.eventLineLimitBytes;
        }

        static Options from(SandboxProperties sandboxProperties, WorkerProperties workerProperties) {
            workerProperties = workerProperties.withFallback(sandboxProperties.getWorker());
            return builder()
                    .endpoint(workerProperties.getEndpoint())
                    .authToken(workerProperties.getAuthToken())
                    .signingSecret(workerProperties.getSigningSecret())
                    .mtlsCertificate(workerProperties.getMtlsCertificate())
                    .connectTimeout(workerProperties.getConnectTimeout())
                    .requestTimeout(workerProperties.getRequestTimeout())
                    .eventBatchLimitBytes(sandboxProperties.getEventBatchLimitBytes())
                    .eventBatchLimitCount(sandboxProperties.getEventBatchLimitCount())
                    .eventLineLimitBytes(sandboxProperties.getEventLineLimitBytes())
                    .build();
        }

        static Options from(WorkerProperties workerProperties) {
            return from(new SandboxProperties(), workerProperties);
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

        public int eventBatchLimitBytes() {
            return eventBatchLimitBytes;
        }

        public int eventBatchLimitCount() {
            return eventBatchLimitCount;
        }

        public int eventLineLimitBytes() {
            return eventLineLimitBytes;
        }

        public static final class Builder {
            private String endpoint = "";
            private String authToken = "";
            private String signingSecret = "";
            private String mtlsCertificate = "";
            private Duration connectTimeout = Duration.ofSeconds(5);
            private Duration requestTimeout = Duration.ofSeconds(30);
            private int eventBatchLimitBytes = SandboxProperties.DEFAULT_EVENT_BATCH_LIMIT_BYTES;
            private int eventBatchLimitCount = SandboxProperties.DEFAULT_EVENT_BATCH_LIMIT_COUNT;
            private int eventLineLimitBytes = SandboxProperties.DEFAULT_EVENT_LINE_LIMIT_BYTES;

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

            public Builder eventBatchLimitBytes(int eventBatchLimitBytes) {
                this.eventBatchLimitBytes = eventBatchLimitBytes;
                return this;
            }

            public Builder eventBatchLimitCount(int eventBatchLimitCount) {
                this.eventBatchLimitCount = eventBatchLimitCount;
                return this;
            }

            public Builder eventLineLimitBytes(int eventLineLimitBytes) {
                this.eventLineLimitBytes = eventLineLimitBytes;
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
