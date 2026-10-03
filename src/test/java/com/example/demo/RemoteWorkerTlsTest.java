package com.example.demo;

import com.example.demo.config.WorkerProperties;
import com.example.demo.dto.SandboxCapabilities;
import com.example.demo.dto.SandboxRunHandle;
import com.example.demo.dto.SandboxTaskSpec;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
