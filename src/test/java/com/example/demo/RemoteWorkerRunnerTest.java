package com.example.demo;

import com.example.demo.config.WorkerProperties;
import com.example.demo.config.SandboxProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.support.RunnerProtocolFixtures;
import com.example.demo.dto.SandboxComparison;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskEvent;
import com.example.demo.dto.SandboxTaskSpec;
import com.example.demo.service.sandbox.RemoteWorkerRunner;
import com.example.demo.service.sandbox.TestTlsRemoteWorkerRunner;
import com.example.demo.support.WorkerTlsCertificates;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.CsvSource;
import com.example.demo.dto.RunnerProtocolCapabilities;
import java.util.Set;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteWorkerRunnerTest {

    @TempDir
    static Path certificateDirectory;
    private static WorkerTlsCertificates.Material tlsMaterial;

    @BeforeAll
    static void createCertificates() throws Exception {
        tlsMaterial = WorkerTlsCertificates.create(certificateDirectory)
                .issue("valid", "dns:localhost,ip:127.0.0.1", false);
    }

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
                .protocol(RunnerProtocolFixtures.workerProtocol())
                .build();
        workerServer.respondJson("GET", "/api/v1/capabilities", 200, workerCapabilities);
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper, tlsMaterial.clientContext());

        SandboxCapabilities capabilities = runner.probe();

        assertThat(capabilities.productionSafe()).isTrue();
        assertThat(capabilities.provider()).isEqualTo("remote-worker");
        assertThat(workerServer.lastRequest("GET", "/api/v1/capabilities").authorization())
                .isEqualTo("Bearer worker-secret");
    }

    @Test
    void startSubmitsFullSandboxTaskSpecAndReceivesWorkerRunHandle() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondJson("GET", "/api/v1/capabilities", 200,
                SandboxCapabilities.builder().provider("remote-worker")
                        .protocol(RunnerProtocolFixtures.workerProtocol()).build());
        workerServer.handle("POST", "/api/v1/runs", exchange -> {
            JsonNode manifest = objectMapper.readTree(workerServer.lastRequest("POST", "/api/v1/runs").body()).get("manifest");
            SandboxRunHandle workerHandle = SandboxRunHandle.builder()
                    .judgeId("judge-remote").runId("worker-run-1").provider("remote-worker")
                    .eventCursor("cursor-0").protocolVersion(2).attemptId(manifest.get("attemptId").asText()).build();
            send(exchange, 202, objectMapper.writeValueAsString(workerHandle));
        });
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper, tlsMaterial.clientContext());
        SandboxTaskSpec spec = validSpec();

        SandboxRunHandle handle = runner.start(spec);

        JsonNode body = objectMapper.readTree(workerServer.lastRequest("POST", "/api/v1/runs").body());
        JsonNode submitted = body.get("manifest");
        assertThat(handle.runId()).isEqualTo("worker-run-1");
        assertThat(handle.provider()).isEqualTo("remote-worker");
        assertThat(submitted.path("judgeId").asText()).isEqualTo("judge-remote");
        assertThat(submitted.path("sourcePaths").path("USER").asText()).isEqualTo("user.cpp");
        assertThat(submitted.path("protocolVersion").asInt()).isEqualTo(2);
        assertThat(submitted.path("attemptId").asText()).isEqualTo(handle.attemptId());
        assertThat(submitted.path("comparison").path("precision").asText()).isEqualTo("1.0000000000000000000000001E-9");
        assertThat(submitted.path("caseTimeLimitMillis").asLong()).isEqualTo(1000);
        assertThat(body.toString()).doesNotContain(spec.workDir(), "workDir", "userId", "profile");
        assertThat(new String(java.util.Base64.getDecoder().decode(body.path("sourceContentsBase64")
                .path("user.cpp").asText()), StandardCharsets.UTF_8)).isEqualTo("int main(){return 0;}");
        assertThat(workerServer.lastRequest("POST", "/api/v1/runs").authorization())
                .isEqualTo("Bearer worker-secret");
    }

    @Test
    void pollEventsMapsWorkerEventsAndUsesHandleCursor() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        SandboxTaskEvent event = SandboxTaskEvent.of("judge-remote", SandboxTaskEvent.Type.COMPLETED, "completed");
        workerServer.respondJson("GET", "/api/v1/runs/worker-run-2/events?cursor=cursor-0", 200, List.of(event));
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper, tlsMaterial.clientContext());
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"401|denied", "503|down", "200|{", "200|null", "200|[null]", "200|[{}]",
            "200|[{\"type\":null}]", "200|[] []", "200|[{\"type\":\"RUN_STARTED\",\"type\":\"RUN_FINISHED\"}]"})
    void pollingFailureDoesNotFabricateRunnerEvents(int status, String body) throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondText("GET", "/api/v1/runs/worker-run-failed/events?cursor=cursor-0", status, body);
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()),
                objectMapper, tlsMaterial.clientContext());
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId("judge-remote").runId("worker-run-failed").provider("remote-worker")
                .eventCursor("cursor-0").protocolVersion(2).attemptId("attempt-failed").build();

        assertThatThrownBy(() -> runner.pollEvents(handle))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("worker event poll failed");
        assertThat(workerServer.requests("GET", "/api/v1/runs/worker-run-failed/events?cursor=cursor-0"))
                .hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"0,2,512", "-1,2,512", "1024,0,512", "1024,-1,512", "1024,2,0", "1024,2,-1",
            "512,2,512", "512,2,513"})
    void workerEventBudgetRejectsInvalidConfigurationWithoutStartingTransport(int batchBytes, int batchCount, int lineBytes) {
        assertThatThrownBy(() -> RemoteWorkerRunner.Options.builder().endpoint("https://127.0.0.1:1")
                .authToken("test-only-token").eventBatchLimitBytes(batchBytes).eventBatchLimitCount(batchCount)
                .eventLineLimitBytes(lineBytes).build()).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("event limits must be positive and batch bytes must exceed line bytes");
    }

    @Test
    void workerEventBudgetDefaultsReuseTheApprovedSharedConfiguration() {
        var options = RemoteWorkerRunner.Options.builder().endpoint("https://127.0.0.1:1")
                .authToken("test-only-token").build();
        assertThat(options.eventBatchLimitBytes()).isEqualTo(SandboxProperties.DEFAULT_EVENT_BATCH_LIMIT_BYTES);
        assertThat(options.eventBatchLimitCount()).isEqualTo(SandboxProperties.DEFAULT_EVENT_BATCH_LIMIT_COUNT);
        assertThat(options.eventLineLimitBytes()).isEqualTo(SandboxProperties.DEFAULT_EVENT_LINE_LIMIT_BYTES);
    }

    @Test
    void interruptedPollPreservesCancellationSignalInsteadOfReturningAnEvent() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondJson("GET", "/api/v1/runs/worker-run-interrupted/events?cursor=0", 200, List.of());
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()),
                objectMapper, tlsMaterial.clientContext());
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId("judge-remote").runId("worker-run-interrupted").provider("remote-worker")
                .eventCursor("0").protocolVersion(2).attemptId("attempt-interrupted").build();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> runner.pollEvents(handle))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"linux-container"})
    void cancelRejectsForeignOrMissingProviderBeforeExternalActions(String provider) throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondText("POST", "/api/v1/runs/foreign-run/cancel", 202, "");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()),
                objectMapper, tlsMaterial.clientContext());
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId("foreign-judge").runId("foreign-run").provider(provider).build();

        assertThatThrownBy(() -> runner.cancel(handle))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handle provider");
        assertThat(workerServer.requests("POST", "/api/v1/runs/foreign-run/cancel")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"linux-container", "windows-hyperv"})
    void pollRejectsForeignOrMissingProviderBeforeExternalActions(String provider) throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondJson("GET", "/api/v1/runs/foreign-run/events?cursor=0", 200, List.of());
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()),
                objectMapper, tlsMaterial.clientContext());
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId("foreign-judge").runId("foreign-run").provider(provider).eventCursor("0").build();

        assertThatThrownBy(() -> runner.pollEvents(handle))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handle provider");
        assertThat(workerServer.requests("GET", "/api/v1/runs/foreign-run/events?cursor=0")).isEmpty();
    }

    @Test
    void pollRejectsMissingHandleBeforeExternalActions() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()),
                objectMapper, tlsMaterial.clientContext());

        assertThatThrownBy(() -> runner.pollEvents(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("handle provider");
    }

    @Test
    void cancelSendsAuthenticatedRequestAndTreatsRepeatedCancelAsIdempotent() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        AtomicInteger cancelCount = new AtomicInteger();
        workerServer.handle("POST", "/api/v1/runs/worker-run-3/cancel", exchange -> {
            int count = cancelCount.incrementAndGet();
            send(exchange, count == 1 ? 202 : 404, "");
        });
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper, tlsMaterial.clientContext());
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

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 204, 404, 409})
    void cleanupCannotTreatCancelAcknowledgementAsConfirmedDomainStop(int cancelStatus) throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondText("POST", "/api/v1/runs/worker-cleanup/cancel", cancelStatus, "");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()),
                objectMapper, tlsMaterial.clientContext());
        SandboxRunHandle handle = SandboxRunHandle.builder()
                .judgeId("judge-remote-cleanup")
                .runId("worker-cleanup")
                .provider("remote-worker")
                .protocolVersion(2)
                .attemptId("worker-cleanup-attempt")
                .build();

        assertThatThrownBy(() -> runner.cleanupResidual(handle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stop is not confirmed");
        assertThat(workerServer.requests("POST", "/api/v1/runs/worker-cleanup/cancel"))
                .singleElement().satisfies(request ->
                        assertThat(request.authorization()).isEqualTo("Bearer worker-secret"));
    }

    @Test
    void unavailableWorkerProducesSandboxUnavailableCapabilityAndEventInsteadOfThrowing() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondText("GET", "/api/v1/capabilities", 503, "down");
        workerServer.respondText("POST", "/api/v1/runs", 503, "down");
        workerServer.respondText("GET", "/api/v1/runs/unavailable/events?cursor=0", 503, "down");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper, tlsMaterial.clientContext());

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

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void unavailableEventsRemainOwnedByExactAttemptAcrossPollingOrders(boolean differentJudge,
                                                                     boolean reverseOrder) throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondText("GET", "/api/v1/capabilities", 503, "down");
        workerServer.respondText("GET", "/api/v1/runs/unavailable/events?cursor=0", 503, "down");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()),
                objectMapper, tlsMaterial.clientContext());
        SandboxRunHandle first = runner.start(validSpec());
        SandboxRunHandle second = runner.start(validSpec(differentJudge ? "judge-remote-other" : "judge-remote"));
        assertThat(first.attemptId()).isNotEqualTo(second.attemptId());
        List<SandboxRunHandle> handles = reverseOrder ? List.of(second, first) : List.of(first, second);
        for (SandboxRunHandle original : handles) {
            SandboxRunHandle restored = objectMapper.readValue(objectMapper.writeValueAsBytes(original), SandboxRunHandle.class);
            assertThat(restored).isEqualTo(original);
            assertThat(runner.pollEvents(restored)).singleElement().satisfies(event -> {
                assertThat(event.judgeId()).isEqualTo(restored.judgeId());
                assertThat(event.attemptId()).isEqualTo(restored.attemptId());
                assertThat(event.protocolVersion()).isEqualTo(restored.protocolVersion());
                assertThat(event.sequence()).isEqualTo(1L);
                assertThat(event.type()).isEqualTo(SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE);
            });
            assertThat(runner.pollEvents(restored)).isEmpty();
        }
        assertThat(workerServer.requests("GET", "/api/v1/runs/unavailable/events?cursor=0")).isEmpty();
    }

    @Test
    void cleanupOfUnavailableAttemptDiscardsOnlyItsPendingLocalEvent() throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondText("GET", "/api/v1/capabilities", 503, "down");
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()),
                objectMapper, tlsMaterial.clientContext());
        SandboxRunHandle first = runner.start(validSpec());
        SandboxRunHandle second = runner.start(validSpec());
        runner.cleanupResidual(first);
        runner.cleanupResidual(first, Duration.ofMillis(100));
        Object pending = org.springframework.test.util.ReflectionTestUtils.getField(runner, "localEvents");
        assertThat(pending).isInstanceOf(java.util.Map.class);
        assertThat(((java.util.Map<?, ?>) pending)).hasSize(1);
        assertThat(((java.util.Map<?, ?>) pending).containsKey(second)).isTrue();
        assertThat(runner.pollEvents(second)).singleElement().satisfies(event ->
                assertThat(event.attemptId()).isEqualTo(second.attemptId()));
        assertThat(((java.util.Map<?, ?>) pending)).isEmpty();
        assertThat(runner.pollEvents(first)).isEmpty();
        assertThat(runner.pollEvents(second)).isEmpty();
        runner.cleanupResidual(second, Duration.ofMillis(100));
        runner.cleanupResidual(second);
        assertThat(workerServer.requests("POST", "/api/v1/runs")).isEmpty();
        assertThat(workerServer.requests("POST", "/api/v1/runs/unavailable/cancel")).isEmpty();
        assertThat(workerServer.requests("GET", "/api/v1/runs/unavailable/events?cursor=0")).isEmpty();
    }

    @Test
    void runnerRejectsRemoteWorkerWithoutAuthenticatedChannel() {
        WorkerProperties properties = new WorkerProperties();
        properties.setEndpoint("https://127.0.0.1:65535");

        assertThatThrownBy(() -> new RemoteWorkerRunner(properties, objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authenticated channel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "version", "comparison", "limits", "sources", "fractional-version", "string-version"})
    void incompatibleWorkerNeverReceivesRunOrSources(String mode) throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        var supported = RunnerProtocolFixtures.workerProtocol();
        var features = new java.util.HashSet<>(supported.features());
        if (mode.equals("limits")) {
            features.remove("explicit-limits");
        } else if (mode.equals("sources")) {
            features.remove(RunnerProtocolCapabilities.INLINE_SOURCES);
        }
        var protocol = new RunnerProtocolCapabilities(mode.equals("version") ? Set.of(1) : Set.of(2),
                mode.equals("comparison") ? Set.of(SandboxComparison.Mode.LEGACY_TEXT) : supported.comparisonModes(), features);
        var response = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.valueToTree(SandboxCapabilities.builder()
                .provider("remote-worker").protocol(mode.equals("legacy") ? null : protocol).build());
        if (mode.equals("fractional-version")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) response.get("protocol")).putArray("versions").add(2.5);
        } else if (mode.equals("string-version")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) response.get("protocol")).putArray("versions").add("2");
        }
        workerServer.respondJson("GET", "/api/v1/capabilities", 200, response);
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper, tlsMaterial.clientContext());
        SandboxTaskSpec spec = validSpec();

        SandboxRunHandle handle = runner.start(spec);

        assertThat(handle.runId()).isEqualTo("unavailable");
        assertThat(runner.pollEvents(handle)).singleElement().satisfies(event ->
                assertThat(event.type()).isEqualTo(SandboxTaskEvent.Type.SANDBOX_UNAVAILABLE));
        assertThat(workerServer.requests("POST", "/api/v1/runs")).isEmpty();
        assertThat(Path.of(spec.workDir()).resolve("control")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"protocolVersion", "attemptId", "judgeId", "provider", "runId", "fractional-version", "string-version", "reserved-runId"})
    void mismatchedWorkerAcknowledgementIsNotAccepted(String field) throws Exception {
        workerServer = FakeWorkerServer.start(objectMapper);
        workerServer.respondJson("GET", "/api/v1/capabilities", 200, SandboxCapabilities.builder()
                .provider("remote-worker").protocol(RunnerProtocolFixtures.workerProtocol()).build());
        workerServer.handle("POST", "/api/v1/runs", exchange -> {
            JsonNode manifest = objectMapper.readTree(workerServer.lastRequest("POST", "/api/v1/runs").body()).get("manifest");
            var response = objectMapper.createObjectNode().put("judgeId", manifest.path("judgeId").asText())
                    .put("attemptId", manifest.path("attemptId").asText()).put("protocolVersion", 2)
                    .put("runId", "fixture-run").put("provider", "remote-worker").put("eventCursor", "0");
            if (field.equals("reserved-runId")) {
                response.put("runId", "unavailable");
            } else if (field.equals("fractional-version")) {
                response.put("protocolVersion", 2.5);
            } else if (field.equals("string-version")) {
                response.put("protocolVersion", "2");
            } else if (field.equals("protocolVersion")) {
                response.put(field, 1);
            } else {
                response.put(field, "");
            }
            send(exchange, 202, objectMapper.writeValueAsString(response));
        });
        RemoteWorkerRunner runner = new TestTlsRemoteWorkerRunner(workerProperties(workerServer.baseUri()), objectMapper, tlsMaterial.clientContext());
        assertThatThrownBy(() -> runner.start(validSpec())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup outcome is unconfirmed")
                .hasRootCauseMessage("worker returned an incompatible run acknowledgement");
        assertThat((Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(runner, "localEvents")).isEmpty();
        assertThat(workerServer.requests("POST", "/api/v1/runs")).hasSize(1);
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
        return validSpec("judge-remote");
    }

    private SandboxTaskSpec validSpec(String judgeId) throws IOException {
        Path storageBase = tempDir.resolve("storage").toAbsolutePath().normalize();
        Path workDir = storageBase.resolve(judgeId);
        Files.createDirectories(workDir);
        Path generator = workDir.resolve("generator.cpp");
        Path user = workDir.resolve("user.cpp");
        Path oracle = workDir.resolve("oracle.cpp");
        Files.writeString(generator, "int main(){return 0;}");
        Files.writeString(user, "int main(){return 0;}");
        Files.writeString(oracle, "int main(){return 0;}");
        return SandboxTaskSpec.builder()
                .judgeId(judgeId)
                .userId("user-remote")
                .profile("worker-prod")
                .storageBase(storageBase)
                .workDir(workDir)
                .sourcePath(SandboxTaskSpec.SourceRole.GENERATOR, generator)
                .sourcePath(SandboxTaskSpec.SourceRole.USER, user)
                .sourcePath(SandboxTaskSpec.SourceRole.ORACLE, oracle)
                .comparison(SandboxComparison.decimal("1.0000000000000000000000001E-9"))
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
            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tlsMaterial.serverContext()));
            FakeWorkerServer fake = new FakeWorkerServer(server, objectMapper);
            server.start();
            return fake;
        }

        URI baseUri() {
            return URI.create("https://127.0.0.1:" + server.getAddress().getPort());
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
