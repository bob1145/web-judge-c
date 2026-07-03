package com.example.demo;

import com.example.demo.config.WorkerProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.sandbox.RemoteWorkerRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteWorkerRunnerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private FakeWorkerServer workerServer;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        if (workerServer != null) {
            workerServer.stop();
        }
    }

    @Test
    void probeReadsWorkerCapabilitiesAndSendsAuthenticationHeaders() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        SandboxCapabilities workerCapabilities = SandboxCapabilities.builder()
                .provider("remote-worker")
                .isolation("remote")
                .productionSafe(true)
                .networkDisabled(true)
                .nonRoot(true)
                .resourceLimits(true)
                .securityProfile("linux-container")
                .details("worker=linux-1")
                .build();
        workerServer.respondJson("GET", "/api/v1/capabilities", 200, workerCapabilities);
        RemoteWorkerRunner runner = new RemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper);

        SandboxCapabilities capabilities = runner.probe();

        assertThat(capabilities.productionSafe()).isTrue();
        assertThat(capabilities.provider()).isEqualTo("remote-worker");
        assertThat(workerServer.lastRequest("GET", "/api/v1/capabilities").authorization())
                .isEqualTo("Bearer worker-secret");
    }

    @Test
    void startSubmitsFullSandboxTaskSpecAndReceivesWorkerRunHandle() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        SandboxRunHandle workerHandle = SandboxRunHandle.builder()
                .judgeId("judge-remote")
                .runId("worker-run-1")
                .provider("remote-worker")
                .eventCursor("cursor-0")
                .build();
        workerServer.respondJson("POST", "/api/v1/runs", 202, workerHandle);
        RemoteWorkerRunner runner = new RemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper);
        SandboxTaskSpec spec = validSpec();

        SandboxRunHandle handle = runner.start(spec);

        JsonNode submitted = objectMapper.readTree(workerServer.lastRequest("POST", "/api/v1/runs").body());
        assertThat(handle.runId()).isEqualTo("worker-run-1");
        assertThat(handle.provider()).isEqualTo("remote-worker");
        assertThat(submitted.path("judgeId").asText()).isEqualTo("judge-remote");
        assertThat(submitted.path("userId").asText()).isEqualTo("user-remote");
        assertThat(submitted.path("profile").asText()).isEqualTo("worker-prod");
        assertThat(submitted.path("sourcePaths").path("USER").asText()).endsWith("user.cpp");
        assertThat(workerServer.lastRequest("POST", "/api/v1/runs").authorization())
                .isEqualTo("Bearer worker-secret");
    }

    @Test
    void pollEventsMapsWorkerEventsAndUsesHandleCursor() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        SandboxTaskEvent event = SandboxTaskEvent.of("judge-remote", SandboxTaskEvent.Type.COMPLETED, "completed");
        workerServer.respondJson("GET", "/api/v1/runs/worker-run-2/events?cursor=cursor-0", 200, List.of(event));
        RemoteWorkerRunner runner = new RemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper);
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId("judge-remote")
                .runId("worker-run-2")
                .provider("remote-worker")
                .eventCursor("cursor-0")
                .build();

        List<SandboxTaskEvent> events = runner.pollEvents(handle);

        assertThat(events).singleElement().satisfies(received -> {
            assertThat(received.judgeId()).isEqualTo("judge-remote");
            assertThat(received.type()).isEqualTo(SandboxTaskEvent.Type.COMPLETED);
        });
        assertThat(workerServer.lastRequest("GET", "/api/v1/runs/worker-run-2/events?cursor=cursor-0").authorization())
                .isEqualTo("Bearer worker-secret");
    }

    @Test
    void cancelSendsAuthenticatedRequestAndTreatsRepeatedCancelAsIdempotent() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        AtomicInteger cancelCount = new AtomicInteger();
        workerServer.handle("POST", "/api/v1/runs/worker-run-3/cancel", exchange -> {
            int count = cancelCount.incrementAndGet();
            send(exchange, count == 1 ? 202 : 404, "");
        });
        RemoteWorkerRunner runner = new RemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper);
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId("judge-remote")
                .runId("worker-run-3")
                .provider("remote-worker")
                .eventCursor("cursor-0")
                .build();

        runner.cancel(handle);
        runner.cancel(handle);

        assertThat(cancelCount).hasValue(2);
        assertThat(workerServer.requests("POST", "/api/v1/runs/worker-run-3/cancel"))
                .allSatisfy(request -> assertThat(request.authorization()).isEqualTo("Bearer worker-secret"));
    }

    @Test
    void unavailableWorkerProducesSandboxUnavailableCapabilityAndEventInsteadOfThrowing() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondText("GET", "/api/v1/capabilities", 503, "down");
        workerServer.respondText("POST", "/api/v1/runs", 503, "down");
        workerServer.respondText("GET", "/api/v1/runs/unavailable/events?cursor=0", 503, "down");
        RemoteWorkerRunner runner = new RemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper);

        SandboxCapabilities capabilities = runner.probe();
        SandboxRunHandle handle = runner.start(validSpec());
        List<SandboxTaskEvent> events = runner.pollEvents(handle);

        assertThat(capabilities.productionSafe()).isFalse();
        assertThat(capabilities.skipReason()).contains("worker unavailable");
        assertThat(handle.runId()).isEqualTo("unavailable");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE);
            assertThat(event.message()).contains("worker unavailable");
        });
    }

    @Test
    void runnerRejectsRemoteWorkerWithoutAuthenticatedChannel() {
        WorkerProperties properties = new WorkerProperties();
        properties.setEndpoint("http://127.0.0.1:65535");

        assertThatThrownBy(() -> new RemoteWorkerRunner(properties, objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authenticated channel");
    }

    private WorkerProperties workerProperties(URI endpoint) {
        WorkerProperties properties = new WorkerProperties();
        properties.setEndpoint(endpoint.toString());
        properties.setAuthToken("worker-secret");
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setRequestTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private SandboxTaskSpec validSpec() throws IOException {
        Path storageBase = tempDir.resolve("storage").toAbsolutePath().normalize();
        Path workDir = storageBase.resolve("judge-remote");
        Files.createDirectories(workDir);
        Path generator = workDir.resolve("generator.cpp");
        Path user = workDir.resolve("user.cpp");
        Path oracle = workDir.resolve("oracle.cpp");
        Files.writeString(generator, "int main(){return 0;}");
        Files.writeString(user, "int main(){return 0;}");
        Files.writeString(oracle, "int main(){return 0;}");
        return SandboxTaskSpec.builder()
                .judgeId("judge-remote")
                .userId("user-remote")
                .profile("worker-prod")
                .storageBase(storageBase)
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, generator)
                .sourcePath(SandboxTaskSpec.SourceRole.USER, user)
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, oracle)
                .testCases(2)
                .caseTimeLimit(Duration.ofSeconds(1))
                .maxTaskRuntime(Duration.ofSeconds(10))
                .memoryLimitBytes(128L * 1024 * 1024)
                .maxOutputBytesPerCase(4096)
                .build();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    private static final class FakeWorkerServer {
        private final HttpServer server;
        private final ObjectMapper objectMapper;
        private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        private FakeWorkerServer(HttpServer server, ObjectMapper objectMapper) {
            this.server = server;
            this.objectMapper = objectMapper;
        }

        static FakeWorkerServer start(ObjectMapper objectMapper) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            FakeWorkerServer fake = new FakeWorkerServer(server, objectMapper);
            server.start();
            return fake;
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        void stop() {
            server.stop(0);
        }

        void respondJson(String method, String pathAndQuery, int status, Object body) throws IOException {
            respondText(method, pathAndQuery, status, objectMapper.writeValueAsString(body));
        }

        void respondText(String method, String pathAndQuery, int status, String body) {
            handle(method, pathAndQuery, exchange -> send(exchange, status, body));
        }

        void handle(String method, String pathAndQuery, Handler handler) {
            String path = pathAndQuery.split("\\?", 2)[0];
            server.createContext(path, exchange -> {
                CapturedRequest request = capture(exchange);
                requests.add(request);
                if (!method.equals(exchange.getRequestMethod()) || !pathAndQuery.equals(request.pathAndQuery())) {
                    send(exchange, 404, "unexpected route: " + request.pathAndQuery());
                    return;
                }
                handler.handle(exchange);
            });
        }

        CapturedRequest lastRequest(String method, String pathAndQuery) {
            return requests(method, pathAndQuery).stream()
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new AssertionError("No request " + method + " " + pathAndQuery + " in " + requests));
        }

        List<CapturedRequest> requests(String method, String pathAndQuery) {
            return requests.stream()
                    .filter(request -> request.method().equals(method))
                    .filter(request -> request.pathAndQuery().equals(pathAndQuery))
                    .toList();
        }

        private CapturedRequest capture(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String query = exchange.getRequestURI().getRawQuery();
            String pathAndQuery = exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query);
            return new CapturedRequest(
                    exchange.getRequestMethod(),
                    pathAndQuery,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    body
            );
        }
    }

    private record CapturedRequest(String method, String pathAndQuery, String authorization, String body) {
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
