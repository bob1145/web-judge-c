package com.example.demo;

import com.example.demo.config.WorkerProperties;
import com.example.demo.config.SandboxProperties;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.TaskCleanupService;
import com.example.demo.service.sandbox.RemoteWorkerRunner;
import com.example.demo.service.sandbox.TestTlsRemoteWorkerRunner;
import com.example.demo.support.WorkerTlsCertificates;
import com.example.demo.support.RunnerProtocolFixtures;
import java.nio.file.Files;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertificateExpiredException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteWorkerTlsTest {

    private static final String BEARER = "task08-test-only-bearer";
    private static final String SIGNING = "task08-test-only-hmac-material";
    @TempDir
    static Path certificateDirectory;
    @TempDir
    Path taskDirectory;
    private static WorkerTlsCertificates.Material valid;
    private static WorkerTlsCertificates.Material expired;
    private static WorkerTlsCertificates.Material wrongHostname;

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final List<HttpServer> servers = new ArrayList<>();

    @BeforeAll
    static void generateEphemeralCertificates() throws Exception {
        WorkerTlsCertificates authority = WorkerTlsCertificates.create(certificateDirectory);
        valid = authority.issue("valid", "dns:localhost,ip:127.0.0.1", false);
        expired = authority.issue("expired", "dns:localhost,ip:127.0.0.1", true);
        wrongHostname = authority.issue("wrong-hostname", "dns:wrong.invalid", false);
        valid.certificate().checkValidity();
        wrongHostname.certificate().checkValidity();
        assertThatThrownBy(() -> expired.certificate().checkValidity()).isInstanceOf(CertificateExpiredException.class);
        assertThat(Boolean.getBoolean("jdk.internal.httpclient.disableHostnameVerification")).isFalse();
    }

    @AfterEach
    void stopLocalServers() {
        servers.forEach(server -> server.stop(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bearer", "hmac", "both"})
    void trustedTlsServerAcceptsExistingBearerAndHmacProtocolIncludingBody(String mode) throws Exception {
        HttpsServer server = server(valid);
        AtomicInteger accepted = new AtomicInteger();
        server.createContext("/api/v1/capabilities", exchange -> {
            String body = readBody(exchange);
            if (!authenticated(exchange, body, mode)) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            accepted.incrementAndGet();
            respond(exchange, 200, mapper.writeValueAsString(capabilities()));
        });
        server.createContext("/api/v1/runs", exchange -> {
            String body = readBody(exchange);
            if (!authenticated(exchange, body, mode) || !body.contains("tls-judge")) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            accepted.incrementAndGet();
            respond(exchange, 202, mapper.writeValueAsString(SandboxRunHandle.builder()
                    .judgeId("tls-judge").runId("tls-run").provider("remote-worker").eventCursor("0")
                    .protocolVersion(2).attemptId(mapper.readTree(body).path("manifest").path("attemptId").asText()).build()));
        });
        server.start();
        WorkerProperties properties = properties(server);
        credentials(properties, mode);
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(properties, mapper, valid.clientContext());
        assertThat(runner.probe().productionSafe()).isTrue();
        for (String source : List.of("generator.cpp", "user.cpp", "oracle.cpp")) {
            Files.writeString(taskDirectory.resolve(source), "int main(){return 0;}");
        }
        SandboxTaskSpec spec = SandboxTaskSpec.builder().judgeId("tls-judge").userId("tls-user")
                .workDir(taskDirectory).sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, taskDirectory.resolve("generator.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.USER, taskDirectory.resolve("user.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, taskDirectory.resolve("oracle.cpp"))
                .testCases(1).caseTimeLimit(Duration.ofSeconds(1)).maxTaskRuntime(Duration.ofSeconds(10))
                .memoryLimitBytes(1024 * 1024).maxOutputBytesPerCase(4096).build();
        assertThat(runner.start(spec).runId()).isEqualTo("tls-run");
        assertThat(accepted.get()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bearer", "hmac"})
    void incorrectAuthenticationFailsWithoutAnonymousFallback(String mode) throws Exception {
        HttpsServer server = server(valid);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/api/v1/capabilities", exchange -> {
            requests.incrementAndGet();
            boolean validAuthentication = authenticated(exchange, readBody(exchange), mode);
            respond(exchange, validAuthentication ? 200 : 401, validAuthentication ? mapper.writeValueAsString(capabilities()) : "unauthorized");
        });
        server.start();
        WorkerProperties properties = properties(server);
        credentials(properties, mode);
        if ("bearer".equals(mode)) {
            properties.setAuthToken("wrong-test-only-bearer");
        } else {
            properties.setSigningSecret("wrong-test-only-signing-material");
        }
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(properties, mapper, valid.clientContext());
        SandboxCapabilities response = runner.probe();
        assertThat(response.productionSafe()).isFalse();
        assertThat(response.skipReason()).contains("status 401").doesNotContain(BEARER, SIGNING);
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "wrong-hostname", "untrusted"})
    void invalidServerIdentityFailsBeforeAuthenticationHeadersReachHttpHandler(String reason) throws Exception {
        WorkerTlsCertificates.Material material = switch (reason) {
            case "expired" -> expired;
            case "wrong-hostname" -> wrongHostname;
            default -> valid;
        };
        HttpsServer server = server(material);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/api/v1/capabilities", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, mapper.writeValueAsString(capabilities()));
        });
        server.start();
        WorkerProperties properties = properties(server);
        properties.setAuthToken(BEARER);
        RemoteWorkerRunner runner = "untrusted".equals(reason)
                ? new RemoteWorkerRunner(properties, mapper)
                : new TestTlsRemoteWorkerRunner(properties, mapper, material.clientContext());
        SandboxCapabilities response = runner.probe();
        assertThat(response.productionSafe()).isFalse();
        assertThat(response.skipReason()).contains("worker unavailable")
                .contains("wrong-hostname".equals(reason) ? "subject alternative" : "PKIX")
                .doesNotContain(BEARER);
        assertThat(requests.get()).isZero();
    }

    @ParameterizedTest
    @MethodSource("redirectTargets")
    void redirectsNeverSendCredentialsToAnotherEndpointOrDowngradeToHttp(int statusCode, boolean targetTls) throws Exception {
        AtomicInteger targetRequests = new AtomicInteger();
        HttpServer target = targetTls ? server(valid) : HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        if (!targetTls) {
            servers.add(target);
        }
        target.createContext("/stolen", exchange -> {
            targetRequests.incrementAndGet();
            respond(exchange, 200, mapper.writeValueAsString(capabilities()));
        });
        target.start();
        HttpsServer source = server(valid);
        AtomicInteger sourceRequests = new AtomicInteger();
        source.createContext("/api/v1/capabilities", exchange -> {
            if (!authenticated(exchange, readBody(exchange), "both")) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            sourceRequests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", (targetTls ? "https" : "http")
                    + "://127.0.0.1:" + target.getAddress().getPort() + "/stolen");
            respond(exchange, statusCode, "redirect");
        });
        source.start();
        WorkerProperties properties = properties(source);
        credentials(properties, "both");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(properties, mapper, valid.clientContext());
        SandboxCapabilities response = runner.probe();
        assertThat(response.productionSafe()).isFalse();
        assertThat(response.skipReason()).contains("status " + statusCode).doesNotContain(BEARER, SIGNING);
        assertThat(sourceRequests.get()).isEqualTo(1);
        assertThat(targetRequests.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"headers", "body"})
    void authenticatedHttpsPollIsBoundedByTheCallersRemainingBudget(String phase) throws Exception {
        HttpsServer server = server(valid);
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.CountDownLatch pending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/v1/runs/deadline-run/events", exchange -> {
            if (!authenticated(exchange, readBody(exchange), "bearer") || !exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            int request = requests.incrementAndGet();
            boolean delayedBody = request > 1 && phase.equals("body");
            if (delayedBody) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('[');
                exchange.getResponseBody().flush();
            }
            if (request > 1) {
                pending.countDown();
                try {
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                if (delayedBody) {
                    exchange.getResponseBody().write(']');
                } else {
                    respond(exchange, 200, "[]");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        WorkerProperties properties = properties(server);
        properties.setRequestTimeout(Duration.ofSeconds(2));
        credentials(properties, "bearer");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(properties, mapper, valid.clientContext());
        SandboxRunHandle handle = SandboxRunHandle.builder().judgeId("deadline-judge").runId("deadline-run")
                .provider("remote-worker").eventCursor("0").protocolVersion(2)
                .attemptId(java.util.UUID.randomUUID().toString()).build();
        assertThat(runner.pollEvents(handle)).isEmpty();
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> runner.pollEvents(handle, Duration.ofMillis(100)))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(java.net.http.HttpTimeoutException.class);
            assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(1).toNanos());
            assertThat(pending.getCount()).isZero();
            assertThat(requests).hasValue(2);
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @CsvSource({"bytes,1023,fixed", "bytes,1024,fixed", "bytes,1025,fixed",
            "bytes,1023,chunked", "bytes,1024,chunked", "bytes,1025,chunked",
            "event,511,fixed", "event,512,fixed", "event,513,fixed",
            "event,511,chunked", "event,512,chunked", "event,513,chunked",
            "count,1,fixed", "count,2,fixed", "count,3,fixed",
            "count,1,chunked", "count,2,chunked", "count,3,chunked",
            "utf8,0,fixed", "utf8,0,chunked"})
    void authenticatedHttpsEventReadsHonorSharedByteCountAndUtf8Boundaries(String boundary, int value, String transfer)
            throws Exception {
        SandboxProperties limits = new SandboxProperties();
        limits.setEventBatchLimitBytes(1024);
        limits.setEventBatchLimitCount(2);
        limits.setEventLineLimitBytes(512);
        var event = mapper.createObjectNode().put("judgeId", "bounded-task").put("type", "RUN_STARTED")
                .put("occurredAt", "2026-10-08T00:00:00Z").put("protocolVersion", 2)
                .put("attemptId", "12345678-1234-4234-8234-123456789abc").put("sequence", 1).put("message", "");
        String payload;
        if (boundary.equals("bytes")) {
            payload = "[]" + " ".repeat(value - 2);
        } else if (boundary.equals("event")) {
            int padding = value - mapper.writeValueAsBytes(event).length;
            event.put("message", "汉".repeat(padding / 3) + "x".repeat(padding % 3));
            payload = "[" + mapper.writeValueAsString(event) + "]";
        } else if (boundary.equals("count")) {
            var events = mapper.createArrayNode();
            for (int sequence = 1; sequence <= value; sequence++) {
                events.add(event.deepCopy().put("sequence", sequence));
            }
            payload = mapper.writeValueAsString(events);
        } else {
            event.put("message", "x");
            payload = "[" + mapper.writeValueAsString(event) + "]";
        }
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        if (boundary.equals("utf8")) {
            body[payload.indexOf("\"message\":\"x\"") + "\"message\":\"".length()] = (byte) 0xff;
        }
        HttpsServer server = server(valid);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/api/v1/runs/bounded-run/events", exchange -> {
            if (!authenticated(exchange, readBody(exchange), "bearer") || !exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            requests.incrementAndGet();
            try {
                exchange.sendResponseHeaders(200, transfer.equals("chunked") ? 0 : body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        WorkerProperties properties = properties(server);
        credentials(properties, "bearer");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(limits, properties, mapper, valid.clientContext());
        SandboxRunHandle handle = SandboxRunHandle.builder().judgeId("bounded-task").runId("bounded-run")
                .provider("remote-worker").eventCursor("0").protocolVersion(2)
                .attemptId("12345678-1234-4234-8234-123456789abc").build();
        int limit = switch (boundary) {
            case "bytes" -> limits.getEventBatchLimitBytes();
            case "event" -> limits.getEventLineLimitBytes();
            case "count" -> limits.getEventBatchLimitCount();
            default -> -1;
        };
        if (boundary.equals("utf8") || value > limit) {
            assertThatThrownBy(() -> runner.pollEvents(handle)).isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("worker event poll failed").hasCauseInstanceOf(IOException.class);
        } else {
            List<com.example.demo.dto.SandboxTaskEvent> events = runner.pollEvents(handle);
            assertThat(events).hasSize(boundary.equals("bytes") ? 0 : boundary.equals("count") ? value : 1);
            assertThat(events).allMatch(received -> received.protocolVersion() == 2
                    && received.judgeId().equals(handle.judgeId()) && received.attemptId().equals(handle.attemptId()));
            if (boundary.equals("event")) {
                assertThat(events.get(0).message()).isEqualTo(event.path("message").asText());
            }
        }
        assertThat(requests).hasValue(1);
        assertThat(handle.eventCursor()).isEqualTo("0");
    }

    @Test
    void oversizedChunkedHttpsResponseFailsBeforeItsBodyCompletes() throws Exception {
        SandboxProperties limits = new SandboxProperties();
        limits.setEventBatchLimitBytes(1024);
        limits.setEventBatchLimitCount(2);
        limits.setEventLineLimitBytes(512);
        HttpsServer server = server(valid);
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.CountDownLatch pending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/v1/runs/bounded-run/events", exchange -> {
            if (!authenticated(exchange, readBody(exchange), "bearer")) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            if (requests.incrementAndGet() == 1) {
                respond(exchange, 200, "[]");
                return;
            }
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(("[" + " ".repeat(1024)).getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                pending.countDown();
                release.await(7, TimeUnit.SECONDS);
                exchange.getResponseBody().write(']');
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        WorkerProperties properties = properties(server);
        properties.setRequestTimeout(Duration.ofSeconds(2));
        credentials(properties, "bearer");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(limits, properties, mapper, valid.clientContext());
        SandboxRunHandle handle = SandboxRunHandle.builder().judgeId("bounded-task").runId("bounded-run")
                .provider("remote-worker").eventCursor("0").protocolVersion(2)
                .attemptId("12345678-1234-4234-8234-123456789abc").build();
        assertThat(runner.pollEvents(handle)).isEmpty();
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> runner.pollEvents(handle)).isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Worker response exceeds event batch byte limit");
            assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(1).toNanos());
            assertThat(pending.getCount()).isZero();
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(requests).hasValue(2);
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"headers", "body"})
    void startupHttpsPhasesUseOneDeadlineAndLateCreateAckDoesNotFabricateAHandle(String phase) throws Exception {
        HttpsServer server = server(valid);
        AtomicInteger capabilityRequests = new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong postReceived = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/v1/capabilities", exchange -> {
            if (!authenticated(exchange, readBody(exchange), "bearer")) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            if (capabilityRequests.incrementAndGet() > 1) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, 200, mapper.writeValueAsString(capabilities()));
        });
        server.createContext("/api/v1/runs", exchange -> {
            String body = readBody(exchange);
            if (!authenticated(exchange, body, "bearer")) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            postReceived.set(System.nanoTime());
            var manifest = mapper.readTree(body).path("manifest");
            String acknowledgement = mapper.writeValueAsString(SandboxRunHandle.builder()
                    .judgeId(manifest.path("judgeId").asText()).attemptId(manifest.path("attemptId").asText())
                    .runId("late-real-ack").provider("remote-worker").protocolVersion(2).eventCursor("0").build());
            boolean delayedBody = phase.equals("body");
            if (delayedBody) {
                exchange.sendResponseHeaders(202, 0);
                exchange.getResponseBody().write(acknowledgement.substring(0, acknowledgement.length() - 1)
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            }
            try {
                release.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            try {
                if (delayedBody) {
                    exchange.getResponseBody().write('}');
                } else {
                    respond(exchange, 202, acknowledgement);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        WorkerProperties properties = properties(server);
        credentials(properties, "bearer");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(properties, mapper, valid.clientContext());
        assertThat(runner.probe().protocol()).isNotNull();
        for (String source : List.of("generator.cpp", "user.cpp", "oracle.cpp")) {
            Files.writeString(taskDirectory.resolve(source), "int main(){return 0;}");
        }
        SandboxTaskSpec spec = SandboxTaskSpec.builder().judgeId("deadline-create").userId("tls-user")
                .workDir(taskDirectory).sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, taskDirectory.resolve("generator.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.USER, taskDirectory.resolve("user.cpp"))
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, taskDirectory.resolve("oracle.cpp"))
                .testCases(1).caseTimeLimit(Duration.ofSeconds(1)).maxTaskRuntime(Duration.ofSeconds(10))
                .memoryLimitBytes(1024 * 1024).maxOutputBytesPerCase(4096).build();
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> runner.start(spec, Duration.ofMillis(400)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("startup outcome is unconfirmed")
                    .hasRootCauseInstanceOf(java.net.http.HttpTimeoutException.class);
            long finished = System.nanoTime();
            assertThat(postReceived.get()).isNotZero();
            assertThat(finished - postReceived.get()).isLessThan(Duration.ofMillis(350).toNanos());
            assertThat(finished - started).isLessThan(Duration.ofMillis(650).toNanos());
            assertThat(capabilityRequests).hasValue(2);
            assertThat((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(runner, "localEvents")).isEmpty();
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @CsvSource({"cancel,headers", "cleanup,headers", "cancel,body", "cleanup,body"})
    void authenticatedHttpsCancellationAndCleanupUseTheCallersRemainingBudget(String operation, String phase) throws Exception {
        HttpsServer server = server(valid);
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.CountDownLatch pending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/v1/runs/deadline-run/cancel", exchange -> {
            if (!authenticated(exchange, readBody(exchange), "bearer") || !exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            int request = requests.incrementAndGet();
            boolean delayedBody = request > 1 && phase.equals("body");
            if (delayedBody) {
                exchange.sendResponseHeaders(202, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
            }
            if (request > 1) {
                pending.countDown();
                try {
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                if (delayedBody) {
                    exchange.getResponseBody().write('}');
                } else {
                    respond(exchange, 202, "{}");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        WorkerProperties properties = properties(server);
        properties.setRequestTimeout(Duration.ofSeconds(2));
        credentials(properties, "bearer");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(properties, mapper, valid.clientContext());
        SandboxRunHandle handle = SandboxRunHandle.builder().judgeId("deadline-judge").runId("deadline-run")
                .provider("remote-worker").eventCursor("0").protocolVersion(2)
                .attemptId(java.util.UUID.randomUUID().toString()).build();
        runner.cancel(handle);
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> {
                if (operation.equals("cancel")) {
                    runner.cancel(handle, Duration.ofMillis(100));
                } else {
                    runner.cleanupResidual(handle, Duration.ofMillis(100));
                }
            }).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(java.net.http.HttpTimeoutException.class);
            assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(1).toNanos());
            assertThat(pending.getCount()).isZero();
            assertThat(requests).hasValue(2);
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"startup", "expired"})
    void persistedHandleCleanupUsesApprovedBudgetAndPreservesUnconfirmedState(String operation) throws Exception {
        HttpsServer server = server(valid);
        AtomicInteger requests = new AtomicInteger();
        java.util.concurrent.CountDownLatch pending = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/v1/runs/cleanup-budget-run/cancel", exchange -> {
            if (!authenticated(exchange, readBody(exchange), "bearer") || !exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 401, "unauthorized");
                return;
            }
            if (requests.incrementAndGet() > 1) {
                pending.countDown();
                try {
                    release.await(6, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                respond(exchange, 202, "{}");
            } finally {
                exchange.close();
            }
        });
        server.start();
        WorkerProperties properties = properties(server);
        credentials(properties, "bearer");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(properties, mapper, valid.clientContext());
        String judgeId = "persisted-cleanup-budget";
        SandboxRunHandle handle = SandboxRunHandle.builder().judgeId(judgeId).runId("cleanup-budget-run")
                .provider("remote-worker").eventCursor("0").protocolVersion(2)
                .attemptId(java.util.UUID.randomUUID().toString()).build();
        runner.cancel(handle);
        FileTaskStore store = new FileTaskStore(mapper, taskDirectory.resolve("storage"));
        Instant now = Instant.now();
        JudgeStatus status = operation.equals("startup") ? JudgeStatus.RUNNING : JudgeStatus.COMPLETED;
        store.create(JudgeTask.builder().judgeId(judgeId).status(status).requestedCases(1)
                .mode("worker-prod").workDir(store.taskDirectory(judgeId).toString())
                .createdAt(now.minusSeconds(60)).startedAt(now.minusSeconds(30))
                .finishedAt(status.isTerminal() ? now.minusSeconds(1) : null).sandboxRunHandle(handle).build());
        Path workDir = store.taskDirectory(judgeId);
        Path canary = workDir.resolve("case-data.txt");
        Files.writeString(canary, "retained case data");
        byte[] metadataBefore = Files.readAllBytes(workDir.resolve("metadata.json"));
        byte[] eventsBefore = Files.readAllBytes(workDir.resolve("events.jsonl"));
        ExecutionProperties cleanupProperties = new ExecutionProperties();
        cleanupProperties.setCompletedRetention(Duration.ZERO);
        TaskCleanupService cleanupService = new TaskCleanupService(store, cleanupProperties, Optional.of(runner));

        long started = System.nanoTime();
        try {
            if (operation.equals("startup")) {
                assertThatThrownBy(cleanupService::reconcileAndCleanupOnStartup)
                        .isInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage("Residual sandbox cleanup failed for judgeIds: " + judgeId);
            } else {
                TaskCleanupService.CleanupReport report = cleanupService.cleanupExpiredTasks(now);
                assertThat(report.deletedJudgeIds()).isEmpty();
                assertThat(report.failedJudgeIds()).containsExactly(judgeId);
            }
            assertThat(System.nanoTime() - started).isLessThan(Duration.ofSeconds(3).toNanos());
            assertThat(pending.getCount()).isZero();
            assertThat(requests).hasValue(2);
            assertThat(store.find(judgeId).orElseThrow().getStatus()).isEqualTo(status);
            assertThat(store.find(judgeId).orElseThrow().getSandboxRunHandle()).isEqualTo(handle);
            assertThat(Files.readAllBytes(workDir.resolve("metadata.json"))).isEqualTo(metadataBefore);
            assertThat(Files.readAllBytes(workDir.resolve("events.jsonl"))).isEqualTo(eventsBefore);
            assertThat(canary).hasContent("retained case data");
        } finally {
            release.countDown();
        }
    }

    private static Stream<Arguments> redirectTargets() {
        return Stream.of(301, 302, 303, 307, 308)
                .flatMap(statusCode -> Stream.of(Arguments.of(statusCode, false), Arguments.of(statusCode, true)));
    }

    private HttpsServer server(WorkerTlsCertificates.Material material) throws IOException {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(material.serverContext()));
        servers.add(server);
        return server;
    }

    private WorkerProperties properties(HttpServer server) {
        WorkerProperties properties = new WorkerProperties();
        properties.setEndpoint("https://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setRequestTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private void credentials(WorkerProperties properties, String mode) {
        properties.setAuthToken("hmac".equals(mode) ? "" : BEARER);
        properties.setSigningSecret("bearer".equals(mode) ? "" : SIGNING);
    }

    private boolean authenticated(HttpExchange exchange, String body, String mode) {
        if (exchange.getRequestHeaders().getFirst("X-Judge-Worker-Mtls") != null) {
            return false;
        }
        if (!"hmac".equals(mode) && !("Bearer " + BEARER).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            return false;
        }
        if (!"bearer".equals(mode)) {
            String input = exchange.getRequestMethod() + "\n" + exchange.getRequestURI() + "\n" + body;
            if (!"HMAC-SHA256".equals(exchange.getRequestHeaders().getFirst("X-Judge-Worker-Signature-Alg"))
                    || !signature(input).equals(exchange.getRequestHeaders().getFirst("X-Judge-Worker-Signature"))) {
                return false;
            }
        }
        return true;
    }

    private String signature(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGNING.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Test HMAC calculation failed", exception);
        }
    }

    private SandboxCapabilities capabilities() {
        return SandboxCapabilities.builder().provider("remote-worker").isolation("remote")
                .productionSafe(true).networkDisabled(true).nonRoot(true).resourceLimits(true)
                .protocol(RunnerProtocolFixtures.workerProtocol()).build();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
