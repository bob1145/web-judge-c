package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeProgress;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import com.example.demo.security.SessionCredentialResolver;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.JudgeScheduler;
import com.example.demo.service.JudgeService;
import com.example.demo.support.CsrfTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "judge.auth.access-code=task02-test-only-access-material",
        "judge.execution.created-task-scan-interval=250ms"
})
class CreatedTaskCancellationHttpTest {
    @Autowired
    private AccessCodeService accessCodeService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FileTaskStore taskStore;
    @Autowired
    private ExecutionProperties executionProperties;
    @Autowired
    private JudgeScheduler judgeScheduler;
    @Autowired
    private JudgeService judgeService;
    @LocalServerPort
    private int serverPort;

    @ParameterizedTest
    @ValueSource(strings = {"start", "cancel", "scan"})
    void expiredCreatedTaskCannotExecuteAndReleasesPendingQuotaOverRealHttp(String entry) throws Exception {
        int originalQueuedLimit = executionProperties.getMaxQueuedTasksPerUser();
        executionProperties.setMaxQueuedTasksPerUser(1);
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", "TASK-18-TTL");
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
            String program = "#include <iostream>\nint main() { std::cout << 7 << std::endl; return 0; }\n";
            String body = objectMapper.writeValueAsString(Map.of(
                    "userCode", program, "generatorCode", program, "bruteForceCode", program,
                    "testCases", 1, "timeLimit", 2_000, "memoryLimit", 268_435_456L,
                    "precision", 0.0, "useSpecialJudge", false, "specialJudgeCode", ""));
            HttpRequest createRequest = HttpRequest.newBuilder(base.resolve("judge"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> created = client.send(createRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(200);
            String judgeId = objectMapper.readTree(created.body()).path("judgeId").asText();
            assertThat(judgeId).isNotBlank();
            HttpResponse<String> held = client.send(createRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(held.statusCode()).isEqualTo(429);
            JudgeTask aged = taskStore.find(judgeId).orElseThrow();
            aged.setCreatedAt(Instant.now().minus(Duration.ofMinutes(16)));
            objectMapper.writeValue(taskStore.taskDirectory(judgeId).resolve("metadata.json").toFile(), aged);

            if ("scan".equals(entry)) {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (taskStore.find(judgeId).orElseThrow().getStatus().name().equals("CREATED")
                        && System.nanoTime() < deadline) {
                    Thread.sleep(25);
                }
                assertThat(taskStore.find(judgeId).orElseThrow().getStatus().name()).isEqualTo("STALE");
            } else {
                HttpResponse<String> expired = client.send(HttpRequest.newBuilder(base.resolve("judge/" + entry + "/" + judgeId))
                        .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                        .header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(expired.statusCode()).isEqualTo("start".equals(entry) ? 409 : 200);
                JsonNode expiredResponse = objectMapper.readTree(expired.body());
                assertThat(expiredResponse.path("status").asText()).isEqualTo("STALE");
                if ("cancel".equals(entry)) {
                    assertThat(expiredResponse.path("accepted").asBoolean()).isFalse();
                }
            }
            HttpResponse<String> status = client.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            JsonNode snapshot = objectMapper.readTree(status.body());
            assertThat(snapshot.path("status").asText()).isEqualTo("STALE");
            assertThat(snapshot.path("summary").path("totalCases").asInt()).isEqualTo(1);
            assertThat(snapshot.path("summary").path("completedCases").asInt()).isZero();
            var cold = new FileTaskStore(objectMapper, taskStore.storageBase());
            JudgeProgress observed = objectMapper.treeToValue(snapshot, JudgeProgress.class);
            assertThat(observed).usingRecursiveComparison().isEqualTo(cold.findSummary(judgeId).orElseThrow());
            assertThat(taskStore.find(judgeId).orElseThrow().getStartedAt()).isNull();
            assertThat(taskStore.find(judgeId).orElseThrow().getSandboxRunHandle()).isNull();
            Map<?, ?> pending = (Map<?, ?>) ReflectionTestUtils.getField(judgeService, "pendingJudgeTasks");
            assertThat(pending.containsKey(judgeId)).isFalse();
            assertThat(judgeScheduler.snapshot().runningCount()).isZero();
            assertThat(judgeScheduler.snapshot().queuedCount()).isZero();
            HttpResponse<String> lateStart = client.send(HttpRequest.newBuilder(base.resolve("judge/start/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(lateStart.statusCode()).isEqualTo(409);
            HttpResponse<String> replacement = client.send(createRequest, HttpResponse.BodyHandlers.ofString());
            assertThat(replacement.statusCode()).isEqualTo(200);
            String replacementId = objectMapper.readTree(replacement.body()).path("judgeId").asText();
            assertThat(replacementId).isNotBlank().isNotEqualTo(judgeId);
            HttpResponse<String> cleanup = client.send(HttpRequest.newBuilder(base.resolve("judge/cancel/" + replacementId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(cleanup.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(cleanup.body()).path("accepted").asBoolean()).isTrue();
            System.out.printf("TASK-18 TTL real HTTP: entry=%s status=STALE completed=0 pendingReleased=true quotaReused=true%n", entry);
        } finally {
            executionProperties.setMaxQueuedTasksPerUser(originalQueuedLimit);
            accessCodeService.invalidateSession(session.getSessionId());
        }
    }

    @Test
    void cancellationRetryAfterRealSummaryIoFailureCommitsOneFinalWithoutPartialTerminalMetadata() throws Exception {
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", "TASK-18-CANCEL-RETRY");
        Path summaryObstacle = null;
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
            String program = "#include <iostream>\nint main() { std::cout << 7 << std::endl; return 0; }\n";
            String body = objectMapper.writeValueAsString(Map.of(
                    "userCode", program, "generatorCode", program, "bruteForceCode", program,
                    "testCases", 1, "timeLimit", 2_000, "memoryLimit", 268_435_456L,
                    "precision", 0.0, "useSpecialJudge", false, "specialJudgeCode", ""));
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(base.resolve("judge"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(200);
            String judgeId = objectMapper.readTree(created.body()).path("judgeId").asText();
            assertThat(judgeId).isNotBlank();
            summaryObstacle = taskStore.taskDirectory(judgeId).resolve("summary.json");
            byte[] before = Files.readAllBytes(taskStore.taskDirectory(judgeId).resolve("metadata.json"));
            Files.createDirectory(summaryObstacle);
            HttpRequest cancel = HttpRequest.newBuilder(base.resolve("judge/cancel/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build();

            HttpResponse<String> failed = client.send(cancel, HttpResponse.BodyHandlers.ofString());

            assertThat(failed.statusCode()).isEqualTo(500);
            assertThat(taskStore.find(judgeId).orElseThrow().getStatus().name()).isEqualTo("CREATED");
            assertThat(Files.readAllBytes(taskStore.taskDirectory(judgeId).resolve("metadata.json"))).isEqualTo(before);
            assertThat(taskStore.taskDirectory(judgeId).resolve("final.json")).doesNotExist();
            Map<?, ?> pending = (Map<?, ?>) ReflectionTestUtils.getField(judgeService, "pendingJudgeTasks");
            assertThat(pending.containsKey(judgeId)).isTrue();
            Files.delete(summaryObstacle);
            summaryObstacle = null;
            byte[] metadata = Files.readAllBytes(taskStore.taskDirectory(judgeId).resolve("metadata.json"));

            HttpResponse<String> retried = client.send(cancel, HttpResponse.BodyHandlers.ofString());

            assertThat(retried.statusCode()).isEqualTo(200);
            JsonNode cancellation = objectMapper.readTree(retried.body());
            assertThat(cancellation.path("accepted").asBoolean()).isTrue();
            assertThat(cancellation.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(cancellation.path("running").asBoolean()).isFalse();
            assertThat(cancellation.path("queued").asBoolean()).isFalse();
            assertThat(pending.containsKey(judgeId)).isFalse();
            assertThat(Files.readAllBytes(taskStore.taskDirectory(judgeId).resolve("metadata.json"))).isNotEqualTo(metadata);
            var committed = taskStore.findSummary(judgeId).orElseThrow();
            assertThat(committed.getStatus()).isEqualTo("CANCELLED");
            assertThat(committed.getSummary().getCompletedCases()).isZero();
            assertThat(committed.getRevision()).isEqualTo(1L);
            assertThat(committed.getFinalizedAt()).isNotNull();
            byte[] finalBytes = Files.readAllBytes(taskStore.taskDirectory(judgeId).resolve("final.json"));
            HttpResponse<String> repeated = client.send(cancel, HttpResponse.BodyHandlers.ofString());
            assertThat(repeated.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(repeated.body()).path("accepted").asBoolean()).isFalse();
            assertThat(Files.readAllBytes(taskStore.taskDirectory(judgeId).resolve("final.json"))).isEqualTo(finalBytes);
            assertThat(taskStore.find(judgeId).orElseThrow().getStartedAt()).isNull();
            assertThat(taskStore.find(judgeId).orElseThrow().getSandboxRunHandle()).isNull();
            assertThat(judgeScheduler.snapshot().runningCount()).isZero();
            assertThat(judgeScheduler.snapshot().queuedCount()).isZero();
            HttpResponse<String> lateStart = client.send(HttpRequest.newBuilder(base.resolve("judge/start/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(lateStart.statusCode()).isEqualTo(409);
            assertThat(objectMapper.readTree(lateStart.body()).path("status").asText()).isEqualTo("CANCELLED");
            System.out.println("TASK-17 real HTTP cancel retry: first=500/CREATED retry=200 accepted=true "
                    + "repeat=200/false status=CANCELLED pendingReleased=true started=false finalRevision=1 lateStart=409");
        } finally {
            if (summaryObstacle != null) {
                Files.delete(summaryObstacle);
            }
            accessCodeService.invalidateSession(session.getSessionId());
        }
    }

    @Test
    void ownerCanCancelCreatedTaskWithoutStartingIt() throws Exception {
        long originalDailyCases = executionProperties.getMaxDailyCasesPerUser();
        executionProperties.setMaxDailyCasesPerUser(1);
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", "TASK-18-CREATED");
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
            String program = "#include <iostream>\nint main() { std::cout << 7 << std::endl; return 0; }\n";
            String body = objectMapper.writeValueAsString(Map.of(
                    "userCode", program, "generatorCode", program, "bruteForceCode", program,
                    "testCases", 1, "timeLimit", 2_000, "memoryLimit", 268_435_456L,
                    "precision", 0.0, "useSpecialJudge", false, "specialJudgeCode", ""));
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(base.resolve("judge"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(200);
            String judgeId = objectMapper.readTree(created.body()).path("judgeId").asText();
            assertThat(judgeId).isNotBlank();
            HttpResponse<String> cancelled = client.send(HttpRequest.newBuilder(base.resolve("judge/cancel/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(cancelled.statusCode()).isEqualTo(200);
            JsonNode cancellation = objectMapper.readTree(cancelled.body());
            assertThat(cancellation.path("accepted").asBoolean()).isTrue();
            assertThat(cancellation.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(cancellation.path("running").asBoolean()).isFalse();
            assertThat(cancellation.path("queued").asBoolean()).isFalse();
            HttpResponse<String> status = client.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(status.statusCode()).isEqualTo(200);
            JsonNode snapshot = objectMapper.readTree(status.body());
            assertThat(snapshot.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(snapshot.path("summary").path("totalCases").asInt()).isEqualTo(1);
            assertThat(snapshot.path("summary").path("completedCases").asInt()).isZero();
            HttpResponse<String> lateStart = client.send(HttpRequest.newBuilder(base.resolve("judge/start/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(lateStart.statusCode()).isEqualTo(409);
            JsonNode rejectedStart = objectMapper.readTree(lateStart.body());
            assertThat(rejectedStart.path("code").asText()).isEqualTo("JUDGE_TASK_FINISHED");
            assertThat(rejectedStart.path("status").asText()).isEqualTo("CANCELLED");
            HttpResponse<String> repeated = client.send(HttpRequest.newBuilder(base.resolve("judge/cancel/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(repeated.statusCode()).isEqualTo(200);
            JsonNode repeatedCancellation = objectMapper.readTree(repeated.body());
            assertThat(repeatedCancellation.path("accepted").asBoolean()).isFalse();
            assertThat(repeatedCancellation.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(taskStore.deleteTaskDirectory(judgeId)).isTrue();
            assertThat(taskStore.taskDirectory(judgeId)).doesNotExist();
            HttpResponse<String> deletedStatus = client.send(HttpRequest.newBuilder(base.resolve("judge/status/" + judgeId))
                    .timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(deletedStatus.statusCode()).isEqualTo(404);
            HttpResponse<String> quotaRejected = client.send(HttpRequest.newBuilder(base.resolve("judge"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .header("Accept", "application/json").header("X-CSRF-TOKEN", token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(quotaRejected.statusCode()).isEqualTo(429);
            JsonNode quotaFailure = objectMapper.readTree(quotaRejected.body());
            assertThat(quotaFailure.path("code").asText()).isEqualTo("JUDGE_QUOTA_EXCEEDED");
            assertThat(quotaFailure.path("message").asText()).contains("daily case quota", "used=1");
        } finally {
            executionProperties.setMaxDailyCasesPerUser(originalDailyCases);
            accessCodeService.invalidateSession(session.getSessionId());
        }
    }
}
