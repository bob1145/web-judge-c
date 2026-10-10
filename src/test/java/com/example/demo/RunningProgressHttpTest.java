package com.example.demo;

import com.example.demo.model.UserSession;
import com.example.demo.model.JudgeStatus;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.security.SessionCredentialResolver;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.JudgeService;
import com.example.demo.service.ProgressPublisher;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.TaskStore;
import com.example.demo.support.CsrfTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "judge.auth.access-code=task02-test-only-access-material",
        "judge.execution.max-concurrent-tasks=2",
        "judge.execution.max-running-tasks-per-user=1",
        "judge.execution.max-concurrent-cases-per-task=1",
        "judge.execution.progress-cache-max-entries=1",
        "judge.execution.large-mode-threshold=20",
        "judge.execution.max-failure-samples=3",
        "judge.execution.max-slow-samples=2"
})
@Import(WebSocketSendHttpTest.IsolatedTaskStore.class)
class RunningProgressHttpTest {
    @Autowired
    private AccessCodeService accessCodeService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ExecutionProperties executionProperties;
    @Autowired
    private JudgeScheduler judgeScheduler;
    @Autowired
    private JudgeService judgeService;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ProgressPublisher progressPublisher;
    @LocalServerPort
    private int serverPort;

    @TempDir
    Path nativeGateDirectory;

    @ParameterizedTest
    @ValueSource(strings = {"missing", "zero", "null", "invalid-type", "empty", "blank", "string-null",
            "page-number", "page-string", "exact-number", "exact-string", "limit-exponent", "limit-bytes",
            "overflow-exponent", "underflow-exponent", "overflow-bytes"})
    @Timeout(15)
    void realHttpPrecisionRejectsExplicitNullAndKeepsDefaultZero(String precisionInput) throws Exception {
        String userAgent = "Java-http-client/" + System.getProperty("java.version");
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", userAgent);
        String judgeId = null;
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        HttpCookie cookie = new HttpCookie(SessionCredentialResolver.COOKIE_NAME, session.getSessionId());
        cookie.setVersion(0);
        cookie.setPath("/");
        cookies.getCookieStore().add(base, cookie);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        String token = CsrfTestSupport.token(client, base);
        try {
            var body = objectMapper.createObjectNode();
            body.put("userCode", "int main(){return 0;}");
            body.put("generatorCode", "int main(){return 0;}");
            body.put("bruteForceCode", "int main(){return 0;}");
            body.put("testCases", 1);
            body.put("timeLimit", 1_000);
            body.put("memoryLimit", 268_435_456L);
            if (precisionInput.equals("zero")) {
                body.put("precision", 0);
            } else if (precisionInput.equals("null")) {
                body.putNull("precision");
            } else if (precisionInput.equals("invalid-type")) {
                body.putArray("precision");
            } else if (precisionInput.equals("empty")) {
                body.put("precision", "");
            } else if (precisionInput.equals("blank")) {
                body.put("precision", " \t\r\n ");
            } else if (precisionInput.equals("string-null")) {
                body.put("precision", "null");
            } else if (precisionInput.equals("page-number")) {
                body.put("precision", 1e-9);
            } else if (precisionInput.equals("page-string")) {
                body.put("precision", "1e-9");
            } else if (precisionInput.equals("exact-number")) {
                body.put("precision", new java.math.BigDecimal("1.00000000000000000000000000000000000001"));
            } else if (precisionInput.equals("exact-string")) {
                body.put("precision", "1.00000000000000000000000000000000000001");
            } else if (precisionInput.equals("limit-exponent")) {
                body.put("precision", "1e1000");
            } else if (precisionInput.equals("limit-bytes")) {
                body.put("precision", "0".repeat(4096));
            } else if (precisionInput.equals("overflow-exponent")) {
                body.put("precision", "1e1001");
            } else if (precisionInput.equals("underflow-exponent")) {
                body.put("precision", "1e-1001");
            } else if (precisionInput.equals("overflow-bytes")) {
                body.put("precision", "0".repeat(4097));
            }
            List<String> existingTasks = taskStore.findAll().stream()
                    .map(task -> task.getJudgeId()).sorted().toList();
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(base.resolve("judge"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode response = objectMapper.readTree(created.body());
            judgeId = response.path("judgeId").asText(null);
            if (List.of("null", "invalid-type", "empty", "blank", "string-null", "overflow-exponent",
                    "underflow-exponent", "overflow-bytes").contains(precisionInput)) {
                assertThat(created.statusCode()).as(created.body()).isEqualTo(400);
                assertThat(response.path("errorCode").asText()).isEqualTo("HTTP_ERROR");
                assertThat(response.path("details").asText()).isEqualTo("请求体格式或参数无效");
                assertThat(response.path("path").asText()).isEqualTo("/judge");
                assertThat(created.body()).doesNotContain("int main", "InvalidNullException", "MismatchedInputException");
                assertThat(judgeId).isNull();
                assertThat(taskStore.findAll().stream().map(task -> task.getJudgeId()).sorted().toList())
                        .containsExactlyElementsOf(existingTasks);
            } else {
                assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
                assertThat(judgeId).isNotBlank();
                var task = taskStore.find(judgeId).orElseThrow();
                assertThat(task.getStatus()).isEqualTo(JudgeStatus.CREATED);
                assertThat(task.getStartedAt()).isNull();
                String expectedPrecision = switch (precisionInput) {
                    case "page-number" -> "1.0E-9";
                    case "page-string" -> "1e-9";
                    case "exact-number", "exact-string" -> "1.00000000000000000000000000000000000001";
                    case "limit-exponent" -> "1e1000";
                    default -> "0";
                };
                assertThat(task.getPolicy().comparison().precision()).isEqualTo(expectedPrecision);
                JsonNode persisted = objectMapper.readTree(Files.readString(
                        taskStore.taskDirectory(judgeId).resolve("metadata.json")));
                assertThat(persisted.path("policy").path("comparison").path("precision").asText()).isEqualTo(expectedPrecision);
            }
        } finally {
            try {
                if (judgeId != null) {
                    assertThat(cancel(client, base, judgeId, token).statusCode()).isEqualTo(200);
                }
            } finally {
                accessCodeService.invalidateSession(session.getSessionId());
            }
        }
    }

    @Test
    @Timeout(30)
    void realCppGeneratorWithoutInputReceivesEofAndCompletesOverHttp() throws Exception {
        String userAgent = "Java-http-client/" + System.getProperty("java.version");
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", userAgent);
        try {
            URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            HttpCookie cookie = new HttpCookie(SessionCredentialResolver.COOKIE_NAME, session.getSessionId());
            cookie.setVersion(0);
            cookie.setPath("/");
            cookies.getCookieStore().add(base, cookie);
            HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                    .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            String token = CsrfTestSupport.token(client, base);
            String generator = "#include <iostream>\nint main(){char input;while(std::cin.get(input)){}std::cout<<7<<std::endl;}\n";
            String solution = "#include <iostream>\nint main(){int value;std::cin>>value;std::cout<<value<<std::endl;}\n";
            String body = objectMapper.writeValueAsString(Map.of(
                    "userCode", solution, "generatorCode", generator, "bruteForceCode", solution,
                    "testCases", 1, "timeLimit", 1_000, "memoryLimit", 268_435_456L,
                    "precision", 0.0, "useSpecialJudge", false, "specialJudgeCode", ""));
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(base.resolve("judge"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
            String judgeId = objectMapper.readTree(created.body()).path("judgeId").asText();
            HttpResponse<String> started = client.send(HttpRequest.newBuilder(base.resolve("judge/start/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
            JsonNode terminal = readStatus(client, base, judgeId);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!JudgeStatus.fromProgressStatus(terminal.path("status").asText())
                    .map(JudgeStatus::isTerminal).orElse(false) && System.nanoTime() < deadline) {
                Thread.sleep(50);
                terminal = readStatus(client, base, judgeId);
            }

            assertThat(terminal.path("status").asText()).as(terminal.toString()).isEqualTo("AC");
            assertThat(terminal.path("summary").path("completedCases").asInt()).isEqualTo(1);
            assertThat(terminal.path("summary").path("ac").asInt()).isEqualTo(1);
            assertThat(terminal.path("summary").path("systemError").asInt()).isZero();
            assertThat(terminal.path("results")).hasSize(1);
            assertThat(terminal.path("results").get(0).path("status").asText()).isEqualTo("AC");
            assertThat(Files.readString(taskStore.taskDirectory(judgeId).resolve("1.in"))).isEqualTo("7\n");
            var task = taskStore.find(judgeId).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(JudgeStatus.AC);
            assertThat(task.getFinishedAt()).isNotNull();
        } finally {
            accessCodeService.invalidateSession(session.getSessionId());
        }
    }

    @Test
    @Timeout(30)
    void realCppMemoryLimitIsPreservedOverHttpAndInTaskStore() throws Exception {
        String userAgent = "Java-http-client/" + System.getProperty("java.version");
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", userAgent);
        try {
            URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            HttpCookie cookie = new HttpCookie(SessionCredentialResolver.COOKIE_NAME, session.getSessionId());
            cookie.setVersion(0);
            cookie.setPath("/");
            cookies.getCookieStore().add(base, cookie);
            HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                    .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            String token = CsrfTestSupport.token(client, base);
            String candidate = """
                    #include <chrono>
                    #include <iostream>
                    #include <thread>
                    int main() {
                        const int bytes = 128 * 1024 * 1024;
                        volatile unsigned char* memory = new unsigned char[bytes];
                        for (int index = 0; index < bytes; index += 4096) memory[index] = 1;
                        std::this_thread::sleep_for(std::chrono::seconds(5));
                        std::cout << 7 << std::endl;
                        delete[] memory;
                    }
                    """;
            String solution = "#include <iostream>\nint main(){std::cout<<7<<std::endl;}\n";
            String body = objectMapper.writeValueAsString(Map.of(
                    "userCode", candidate, "generatorCode", solution, "bruteForceCode", solution,
                    "testCases", 1, "timeLimit", 3_000, "memoryLimit", 67_108_864L,
                    "precision", 0.0, "useSpecialJudge", false, "specialJudgeCode", ""));
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(base.resolve("judge"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
            String judgeId = objectMapper.readTree(created.body()).path("judgeId").asText();
            HttpResponse<String> started = client.send(HttpRequest.newBuilder(base.resolve("judge/start/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
            JsonNode terminal = readStatus(client, base, judgeId);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!JudgeStatus.fromProgressStatus(terminal.path("status").asText())
                    .map(JudgeStatus::isTerminal).orElse(false) && System.nanoTime() < deadline) {
                Thread.sleep(50);
                terminal = readStatus(client, base, judgeId);
            }

            assertThat(terminal.path("status").asText()).as(terminal.toString()).isEqualTo("MLE");
            assertThat(terminal.path("summary").path("completedCases").asInt()).isEqualTo(1);
            assertThat(terminal.path("summary").path("mle").asInt()).isEqualTo(1);
            assertThat(terminal.path("summary").path("ac").asInt()).isZero();
            assertThat(terminal.path("summary").path("systemError").asInt()).isZero();
            assertThat(terminal.path("results")).hasSize(1);
            JsonNode result = terminal.path("results").get(0);
            assertThat(result.path("status").asText()).isEqualTo("MLE");
            assertThat(result.path("memoryUsed").asLong()).isGreaterThan(65_536);
            var task = taskStore.find(judgeId).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(JudgeStatus.MLE);
            assertThat(task.getFinishedAt()).isNotNull();
        } finally {
            accessCodeService.invalidateSession(session.getSessionId());
        }
    }

    @Test
    @Timeout(90)
    void realCppTasksShareOwnerPermitsAcrossCookiesAndSkipBlockedQueueHeads() throws Exception {
        String owner = "task21-owner-" + UUID.randomUUID();
        String other = "task21-other-" + UUID.randomUUID();
        String userAgent = "Java-http-client/" + System.getProperty("java.version");
        List<UserSession> sessions = List.of(
                accessCodeService.createSession(false, "127.0.0.1", userAgent, owner, false),
                accessCodeService.createSession(false, "127.0.0.1", userAgent, owner, false),
                accessCodeService.createSession(false, "127.0.0.1", userAgent, other, false));
        URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
        List<String> names = List.of("owner-first", "owner-next", "other-first", "other-next");
        List<Integer> clientIndexes = List.of(0, 1, 2, 2);
        List<HttpClient> clients = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        List<String> judgeIds = new ArrayList<>();
        try {
            for (UserSession session : sessions) {
                CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
                HttpCookie cookie = new HttpCookie(SessionCredentialResolver.COOKIE_NAME, session.getSessionId());
                cookie.setVersion(0);
                cookie.setPath("/");
                cookies.getCookieStore().add(base, cookie);
                HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
                clients.add(client);
                tokens.add(CsrfTestSupport.token(client, base));
            }
            for (int index = 0; index < names.size(); index++) {
                int clientIndex = clientIndexes.get(index);
                String body = objectMapper.writeValueAsString(Map.of(
                        "userCode", holdingSolutionCode(nativeGateDirectory.resolve(names.get(index))),
                        "generatorCode", generatorCode(),
                        "bruteForceCode", "#include <iostream>\nint main(){std::cout<<1<<std::endl;}",
                        "testCases", 1, "timeLimit", 15_000, "memoryLimit", 268_435_456L,
                        "precision", 0.0, "useSpecialJudge", false, "specialJudgeCode", ""));
                HttpResponse<String> created = clients.get(clientIndex).send(HttpRequest.newBuilder(base.resolve("judge"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .header("Accept", "application/json").header("X-CSRF-TOKEN", tokens.get(clientIndex))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
                String judgeId = objectMapper.readTree(created.body()).path("judgeId").asText();
                assertThat(judgeId).isNotBlank();
                judgeIds.add(judgeId);
            }
            for (int index = 0; index < judgeIds.size(); index++) {
                int clientIndex = clientIndexes.get(index);
                HttpResponse<String> started = clients.get(clientIndex).send(
                        HttpRequest.newBuilder(base.resolve("judge/start/" + judgeIds.get(index)))
                                .timeout(Duration.ofSeconds(5)).header("X-CSRF-TOKEN", tokens.get(clientIndex))
                                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
            }
            ProcessHandle ownerFirst = awaitNativeProcess("owner-first");
            ProcessHandle otherFirst = awaitNativeProcess("other-first");
            assertThat(ownerFirst.isAlive()).isTrue();
            assertThat(otherFirst.isAlive()).isTrue();
            Map<?, ?> cachedStates = (Map<?, ?>) ReflectionTestUtils.getField(progressPublisher, "lastSentByJudgeId");
            assertThat(cachedStates).hasSize(1);
            assertThat(cachedStates.containsKey(judgeIds.get(0)) && cachedStates.containsKey(judgeIds.get(2))).isFalse();
            for (int index : List.of(0, 2)) {
                JsonNode executing = readStatus(clients.get(clientIndexes.get(index)), base, judgeIds.get(index));
                assertThat(executing.path("status").asText()).isEqualTo("RUNNING");
                assertThat(executing.path("summary").path("totalCases").asInt()).isEqualTo(1);
                assertThat(executing.path("summary").path("completedCases").asInt()).isZero();
                for (String field : List.of("ac", "wa", "tle", "mle", "re", "systemError", "outputLimitExceeded")) {
                    assertThat(executing.path("summary").path(field).isIntegralNumber()).isTrue();
                    assertThat(executing.path("summary").path(field).asInt()).isZero();
                }
                assertThat(executing.path("results").isNull()).isTrue();
                assertThat(taskStore.find(judgeIds.get(index)).orElseThrow().getStatus().name()).isEqualTo("RUNNING");
            }
            assertThat(nativeGateDirectory.resolve("owner-next.pid")).doesNotExist();
            assertThat(nativeGateDirectory.resolve("other-next.pid")).doesNotExist();
            assertThat(readStatus(clients.get(1), base, judgeIds.get(1)).path("status").asText()).isEqualTo("QUEUED");
            assertThat(judgeScheduler.snapshot().runningCount()).isEqualTo(2);
            assertThat(judgeScheduler.snapshot().queuedCount()).isEqualTo(2);

            Files.writeString(nativeGateDirectory.resolve("other-first.release"), "");
            ProcessHandle otherNext = awaitNativeProcess("other-next");
            assertThat(ownerFirst.isAlive()).isTrue();
            assertThat(otherFirst.isAlive()).isFalse();
            assertThat(otherNext.isAlive()).isTrue();
            assertThat(nativeGateDirectory.resolve("owner-next.pid")).doesNotExist();
            assertThat(judgeScheduler.snapshot().runningCount()).isEqualTo(2);
            assertThat(judgeScheduler.snapshot().queuedCount()).isEqualTo(1);

            Files.writeString(nativeGateDirectory.resolve("owner-first.release"), "");
            ProcessHandle ownerNext = awaitNativeProcess("owner-next");
            assertThat(ownerFirst.isAlive()).isFalse();
            assertThat(ownerNext.isAlive()).isTrue();
            assertThat(otherNext.isAlive()).isTrue();
            assertThat(judgeScheduler.snapshot().runningCount()).isEqualTo(2);
            assertThat(judgeScheduler.snapshot().queuedCount()).isZero();
            Files.writeString(nativeGateDirectory.resolve("owner-next.release"), "");
            Files.writeString(nativeGateDirectory.resolve("other-next.release"), "");
            for (int index = 0; index < judgeIds.size(); index++) {
                awaitAccepted(clients.get(clientIndexes.get(index)), base, judgeIds.get(index));
                List<String> phases = List.of("CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING");
                List<String> observed = new ArrayList<>();
                int previousPhase = -1;
                for (String line : Files.readAllLines(taskStore.taskDirectory(judgeIds.get(index)).resolve("events.jsonl"))) {
                    String status = objectMapper.readTree(line).path("status").asText();
                    observed.add(status);
                    int phase = phases.indexOf(status);
                    if (phase >= 0) {
                        assertThat(phase).as("normal stages must not regress: %s", observed).isGreaterThanOrEqualTo(previousPhase);
                        previousPhase = phase;
                    }
                }
                assertThat(observed).containsSubsequence("CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING", "AC");
                JudgeProgress cold = new FileTaskStore(objectMapper, taskStore.taskDirectory(judgeIds.get(index)).getParent())
                        .findSummary(judgeIds.get(index)).orElseThrow();
                JudgeProgress http = objectMapper.treeToValue(
                        readStatus(clients.get(clientIndexes.get(index)), base, judgeIds.get(index)), JudgeProgress.class);
                assertThat(http).usingRecursiveComparison().isEqualTo(cold);
            }
            long cacheDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!cachedStates.isEmpty() && System.nanoTime() < cacheDeadline) {
                Thread.sleep(10);
            }
            assertThat(cachedStates).isEmpty();
            assertThat(ownerNext.isAlive()).isFalse();
            assertThat(otherNext.isAlive()).isFalse();
            System.out.printf("TASK-21 real CPP: ownerPids=%d/%d otherPids=%d/%d ownerLimit=1 globalPeak=2 blockedHeadSkipped=true ac=4%n",
                    ownerFirst.pid(), ownerNext.pid(), otherFirst.pid(), otherNext.pid());
        } finally {
            for (String name : names) {
                Files.writeString(nativeGateDirectory.resolve(name + ".release"), "");
            }
            try {
                for (int index = 0; index < judgeIds.size(); index++) {
                    int clientIndex = clientIndexes.get(index);
                    assertThat(cancel(clients.get(clientIndex), base, judgeIds.get(index), tokens.get(clientIndex)).statusCode())
                            .isEqualTo(200);
                }
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while ((judgeScheduler.snapshot().runningCount() != 0 || judgeScheduler.snapshot().queuedCount() != 0)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertThat(judgeScheduler.snapshot().runningCount()).isZero();
                assertThat(judgeScheduler.snapshot().queuedCount()).isZero();
            } finally {
                for (UserSession session : sessions) {
                    accessCodeService.invalidateSession(session.getSessionId());
                }
            }
        }
    }

    private ProcessHandle awaitNativeProcess(String name) throws Exception {
        Path pidFile = nativeGateDirectory.resolve(name + ".pid");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(pidFile).exists();
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        return ProcessHandle.of(pid).orElseThrow();
    }

    private void awaitAccepted(HttpClient client, URI base, String judgeId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode progress;
        do {
            progress = readStatus(client, base, judgeId);
            if (progress.path("status").asText().equals("AC")) {
                break;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertThat(progress.path("status").asText()).isEqualTo("AC");
        assertThat(progress.path("summary").path("completedCases").asInt()).isEqualTo(1);
        assertThat(progress.path("summary").path("ac").asInt()).isEqualTo(1);
    }

    private String holdingSolutionCode(Path marker) {
        String path = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                #include <chrono>
                #include <fstream>
                #include <iostream>
                #include <string>
                #include <thread>
                #ifdef _WIN32
                #include <process.h>
                #else
                #include <unistd.h>
                #endif
                int main() {
                    std::string marker = "%s";
                    #ifdef _WIN32
                    int processId = _getpid();
                    #else
                    int processId = getpid();
                    #endif
                    std::ofstream(marker + ".pid") << processId << std::endl;
                    while (!std::ifstream(marker + ".release").good()) {
                        std::this_thread::sleep_for(std::chrono::milliseconds(20));
                    }
                    std::cout << 1 << std::endl;
                }
                """.formatted(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AC", "WA"})
    @Timeout(45)
    void fortyRealCasesPublishBoundedRunningSummaryFromFirstResult(String verdict) throws Exception {
        String generator = generatorCode();
        String solution = """
                #include <chrono>
                #include <iostream>
                #include <thread>
                int main() {
                    int caseNumber = 0;
                    std::cin >> caseNumber;
                    if (caseNumber > 1) std::this_thread::sleep_for(std::chrono::milliseconds(200));
                    std::cout << caseNumber + %d << std::endl;
                }
                """.formatted(verdict.equals("WA") ? 1 : 0);
        String oracle = "#include <iostream>\nint main(){int value=0;std::cin>>value;std::cout<<value<<std::endl;}";
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", "TASK-23-PROGRESS");
        try {
            URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            HttpCookie cookie = new HttpCookie(SessionCredentialResolver.COOKIE_NAME, session.getSessionId());
            cookie.setVersion(0);
            cookie.setPath("/");
            cookies.getCookieStore().add(base, cookie);
            HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                    .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            String token = CsrfTestSupport.token(client, base);
            String body = objectMapper.writeValueAsString(Map.of(
                    "userCode", solution, "generatorCode", generator, "bruteForceCode", oracle,
                    "testCases", 40, "timeLimit", 2_000, "memoryLimit", 268_435_456L,
                    "precision", 0.0, "useSpecialJudge", false, "specialJudgeCode", ""));
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(base.resolve("judge"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(200);
            String judgeId = objectMapper.readTree(created.body()).path("judgeId").asText();
            assertThat(judgeId).isNotBlank();
            HttpResponse<String> started = client.send(HttpRequest.newBuilder(base.resolve("judge/start/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(started.statusCode()).isEqualTo(200);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            JsonNode firstRunning = null;
            JsonNode terminal = null;
            int previousCompleted = 0;
            long previousRevision = 0;
            JsonNode previousRunning = null;
            boolean delayedSnapshotReplayed = false;
            while (System.nanoTime() < deadline) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                        .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode progress = objectMapper.readTree(response.body());
                String status = progress.path("status").asText();
                if (status.equals("RUNNING") && !progress.path("summary").isMissingNode()
                        && !progress.path("summary").isNull()) {
                    JsonNode summary = progress.path("summary");
                    assertThat(progress.path("revision").isIntegralNumber()).isTrue();
                    long revision = progress.path("revision").asLong();
                    assertThat(revision).isGreaterThan(0).isGreaterThanOrEqualTo(previousRevision);
                    if (revision == previousRevision) {
                        assertThat(progress).isEqualTo(previousRunning);
                    }
                    previousRevision = revision;
                    previousRunning = progress;
                    int completed = summary.path("completedCases").asInt();
                    assertThat(completed).isBetween(previousCompleted, 40);
                    assertThat(summary.path("totalCases").asInt()).isEqualTo(40);
                    int classified = 0;
                    for (String field : List.of("ac", "wa", "tle", "mle", "re", "systemError", "outputLimitExceeded")) {
                        assertThat(summary.path(field).isIntegralNumber()).isTrue();
                        classified += summary.path(field).asInt();
                    }
                    assertThat(classified).isEqualTo(completed);
                    assertThat(summary.path(verdict.toLowerCase()).asInt()).isEqualTo(completed);
                    assertThat(summary.path("failureSamples").size()).isLessThanOrEqualTo(3);
                    assertThat(summary.path("slowSamples").size()).isLessThanOrEqualTo(2);
                    assertThat(progress.path("results").isNull()).isTrue();
                    previousCompleted = completed;
                    if (firstRunning == null && completed > 0) firstRunning = progress;
                    if (!delayedSnapshotReplayed && firstRunning != null
                            && completed > firstRunning.path("summary").path("completedCases").asInt()) {
                        JudgeProgress replayed = progressPublisher.publish(judgeId,
                                objectMapper.treeToValue(firstRunning, JudgeProgress.class));
                        assertThat(replayed.getStatus()).isEqualTo("RUNNING");
                        assertThat(replayed.getSummary().getCompletedCases()).isGreaterThanOrEqualTo(completed);
                        assertThat(replayed.getRevision()).isGreaterThanOrEqualTo(revision);
                        HttpResponse<String> retainedResponse = client.send(HttpRequest.newBuilder(
                                        base.resolve("judge/status/" + judgeId))
                                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertThat(retainedResponse.statusCode()).isEqualTo(200);
                        JsonNode retained = objectMapper.readTree(retainedResponse.body());
                        assertThat(retained.path("summary").path("completedCases").asInt())
                                .isGreaterThanOrEqualTo(replayed.getSummary().getCompletedCases());
                        assertThat(retained.path("summary").path(verdict.toLowerCase()).asInt())
                                .isEqualTo(retained.path("summary").path("completedCases").asInt());
                        assertThat(retained.path("revision").asLong()).isGreaterThanOrEqualTo(replayed.getRevision());
                        delayedSnapshotReplayed = true;
                        System.out.printf("TASK-23 stale-running-replay %s: old=%d retained=%d revision=%d%n",
                                verdict, firstRunning.path("summary").path("completedCases").asInt(),
                                replayed.getSummary().getCompletedCases(), replayed.getRevision());
                    }
                }
                if (status.equals(verdict)) {
                    terminal = progress;
                    break;
                }
                assertThat(List.of("CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING"))
                        .as("unexpected progress: %s", progress).contains(status);
                Thread.sleep(20);
            }
            assertThat(firstRunning).as("a real running snapshot is required before terminal").isNotNull();
            assertThat(delayedSnapshotReplayed).as("actual stale snapshot replay must execute while running").isTrue();
            assertThat(firstRunning.path("summary").path("completedCases").asInt()).isEqualTo(1);
            assertThat(firstRunning.path("summary").path(verdict.toLowerCase()).asInt()).isEqualTo(1);
            assertThat(terminal).isNotNull();
            assertThat(terminal.path("revision").isIntegralNumber()).isTrue();
            assertThat(terminal.path("revision").asLong()).isGreaterThan(previousRevision);
            assertThat(terminal.path("finalizedAt").asText()).isNotBlank();
            assertThat(terminal.path("attemptId").isNull()).isTrue();
            JsonNode authoritativeFinal = objectMapper.readTree(taskStore.taskDirectory(judgeId).resolve("final.json").toFile());
            assertThat(authoritativeFinal).isEqualTo(terminal);
            assertThat(terminal.path("summary").path("completedCases").asInt()).isEqualTo(40);
            assertThat(terminal.path("summary").path(verdict.toLowerCase()).asInt()).isEqualTo(40);
            assertThat(terminal.path("results").isNull()).isTrue();
            Map<?, ?> sentStates = (Map<?, ?>) ReflectionTestUtils.getField(progressPublisher, "lastSentByJudgeId");
            assertThat(sentStates).isNotNull();
            assertThat(sentStates.containsKey(judgeId)).isFalse();
            System.out.printf("TASK-23 real %s: completed=40 throttleEntryReleased=true%n", verdict);
        } finally {
            accessCodeService.invalidateSession(session.getSessionId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "BUDGET_EXCEEDED"})
    @Timeout(45)
    void realMixedResultsSurviveStoppingAndColdHttpRead(String stoppedStatus) throws Exception {
        String solution = """
                #include <chrono>
                #include <iostream>
                #include <thread>
                int main() {
                    int caseNumber = 0;
                    std::cin >> caseNumber;
                    if (caseNumber > 2) std::this_thread::sleep_for(std::chrono::milliseconds(500));
                    std::cout << caseNumber + (caseNumber % 2 == 0 ? 1 : 0) << std::endl;
                }
                """;
        String oracle = "#include <iostream>\nint main(){int value=0;std::cin>>value;std::cout<<value<<std::endl;}";
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", "TASK-17-STOPPING");
        Duration originalBudget = executionProperties.getMaxTaskRuntime();
        if (stoppedStatus.equals("BUDGET_EXCEEDED")) {
            executionProperties.setMaxTaskRuntime(Duration.ofSeconds(8));
        }
        try {
            URI base = URI.create("http://127.0.0.1:" + serverPort + "/");
            CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
            HttpCookie cookie = new HttpCookie(SessionCredentialResolver.COOKIE_NAME, session.getSessionId());
            cookie.setVersion(0);
            cookie.setPath("/");
            cookies.getCookieStore().add(base, cookie);
            HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                    .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
            String token = CsrfTestSupport.token(client, base);
            String body = objectMapper.writeValueAsString(Map.of(
                    "userCode", solution, "generatorCode", generatorCode(), "bruteForceCode", oracle,
                    "testCases", 40, "timeLimit", 2_000, "memoryLimit", 268_435_456L,
                    "precision", 0.0, "useSpecialJudge", false, "specialJudgeCode", ""));
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(base.resolve("judge"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(200);
            String judgeId = objectMapper.readTree(created.body()).path("judgeId").asText();
            assertThat(judgeId).isNotBlank();
            HttpResponse<String> started = client.send(HttpRequest.newBuilder(base.resolve("judge/start/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(started.statusCode()).isEqualTo(200);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            JsonNode mixedRunning = null;
            JsonNode terminal = null;
            boolean cancellationSent = false;
            int previousCompleted = 0;
            while (System.nanoTime() < deadline) {
                JsonNode progress = readStatus(client, base, judgeId);
                String status = progress.path("status").asText();
                JsonNode summary = progress.path("summary");
                if (!summary.isMissingNode() && !summary.isNull()) {
                    int completed = summary.path("completedCases").asInt();
                    assertThat(completed).isBetween(previousCompleted, 40);
                    assertThat(summary.path("totalCases").asInt()).isEqualTo(40);
                    int classified = 0;
                    for (String field : List.of("ac", "wa", "tle", "mle", "re", "systemError", "outputLimitExceeded")) {
                        assertThat(summary.path(field).isIntegralNumber()).isTrue();
                        assertThat(summary.path(field).asInt()).isGreaterThanOrEqualTo(0);
                        classified += summary.path(field).asInt();
                    }
                    assertThat(classified).isEqualTo(completed);
                    assertThat(summary.path("failureSamples").size()).isLessThanOrEqualTo(3);
                    assertThat(summary.path("slowSamples").size()).isLessThanOrEqualTo(2);
                    assertThat(progress.path("results").isNull()).isTrue();
                    previousCompleted = completed;
                    if (status.equals("RUNNING") && summary.path("ac").asInt() > 0 && summary.path("wa").asInt() > 0) {
                        mixedRunning = progress;
                        if (stoppedStatus.equals("CANCELLED") && !cancellationSent) {
                            HttpResponse<String> cancelled = cancel(client, base, judgeId, token);
                            assertThat(cancelled.statusCode()).isEqualTo(200);
                            JsonNode response = objectMapper.readTree(cancelled.body());
                            assertThat(response.path("accepted").asBoolean()).isTrue();
                            assertThat(response.path("status").asText()).isEqualTo("RUNNING");
                            assertThat(response.path("running").asBoolean()).isTrue();
                            cancellationSent = true;
                        }
                    }
                }
                if (status.equals(stoppedStatus)) {
                    terminal = progress;
                    break;
                }
                assertThat(List.of("CREATED", "QUEUED", "PENDING", "COMPILING", "RUNNING"))
                        .as("unexpected progress: %s", progress).contains(status);
                Thread.sleep(20);
            }
            assertThat(mixedRunning).as("real AC and WA must precede the stop").isNotNull();
            assertThat(terminal).isNotNull();
            JsonNode summary = terminal.path("summary");
            assertThat(summary.path("completedCases").asInt()).isBetween(2, 39);
            assertThat(summary.path("ac").asInt()).isGreaterThan(0);
            assertThat(summary.path("wa").asInt()).isGreaterThan(0);
            assertThat(summary.path("firstFailedCase").asInt()).isEqualTo(2);
            assertThat(summary.path("failureSamples").size()).isGreaterThan(0);
            assertThat(summary.path("slowSamples").size()).isGreaterThan(0);
            assertThat(summary.path("stoppedReason").asText()).isEqualTo(stoppedStatus.equals("CANCELLED")
                    ? "Cancellation requested" : "Task runtime budget exceeded");
            long exitDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (judgeScheduler.snapshot().runningCount() != 0 && System.nanoTime() < exitDeadline) {
                Thread.sleep(20);
            }
            assertThat(judgeScheduler.snapshot().runningCount()).isZero();
            JsonNode persisted = objectMapper.readTree(objectMapper.writeValueAsString(taskStore.findSummary(judgeId).orElseThrow()));
            assertThat(persisted).isEqualTo(terminal);
            var finishedAt = taskStore.find(judgeId).orElseThrow().getFinishedAt();
            HttpResponse<String> repeated = cancel(client, base, judgeId, token);
            assertThat(repeated.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(repeated.body()).path("accepted").asBoolean()).isFalse();
            assertThat(taskStore.find(judgeId).orElseThrow().getFinishedAt()).isEqualTo(finishedAt);
            assertThat(readStatus(client, base, judgeId)).isEqualTo(terminal);
            Map<?, ?> sentStates = (Map<?, ?>) ReflectionTestUtils.getField(progressPublisher, "lastSentByJudgeId");
            assertThat(sentStates).isNotNull();
            assertThat(sentStates.containsKey(judgeId)).isFalse();
            System.out.printf("TASK-17 real %s: completed=%d ac=%d wa=%d failureSamples=%d slowSamples=%d coldHttpEqual=true%n",
                    stoppedStatus, summary.path("completedCases").asInt(), summary.path("ac").asInt(),
                    summary.path("wa").asInt(), summary.path("failureSamples").size(), summary.path("slowSamples").size());
        } finally {
            executionProperties.setMaxTaskRuntime(originalBudget);
            accessCodeService.invalidateSession(session.getSessionId());
        }
    }

    private JsonNode readStatus(HttpClient client, URI base, String judgeId) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> cancel(HttpClient client, URI base, String judgeId, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve("judge/cancel/" + judgeId))
                .timeout(Duration.ofSeconds(5)).header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String generatorCode() {
        return """
                #include <fstream>
                #include <iostream>
                #include <string>
                int main(int argc, char** argv) {
                    std::string executablePath = argv[0];
                    std::string counter = executablePath.substr(0, executablePath.find_last_of("/\\\\") + 1)
                            + "case-counter.txt";
                    int caseNumber = 0;
                    std::ifstream(counter) >> caseNumber;
                    std::ofstream(counter) << caseNumber + 1;
                    std::cout << caseNumber + 1 << std::endl;
                }
                """;
    }
}
