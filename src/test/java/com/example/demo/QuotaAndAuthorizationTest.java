package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.FileTaskStore;
import com.example.demo.service.JudgeService;
import com.example.demo.service.QuotaService;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.TaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "judge.auth.access-code=task02-test-only-access-material")
@AutoConfigureMockMvc
@Import(WebSocketSendHttpTest.IsolatedTaskStore.class)
class QuotaAndAuthorizationTest {

    private static final String TEST_USER_AGENT = "QuotaAndAuthorizationTest";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessCodeService accessCodeService;

    @Autowired
    private ExecutionProperties executionProperties;

    @Autowired
    private TaskStore taskStore;

    @Autowired
    private JudgeService judgeService;

    private long originalMaxDailyCasesPerUser;
    private long originalMaxDailyRuntimeMillisPerUser;
    private int originalMaxRunningTasksPerUser;
    private int originalMaxQueuedTasksPerUser;

    @BeforeEach
    void captureQuotaProperties() {
        originalMaxDailyCasesPerUser = executionProperties.getMaxDailyCasesPerUser();
        originalMaxDailyRuntimeMillisPerUser = executionProperties.getMaxDailyRuntimeMillisPerUser();
        originalMaxRunningTasksPerUser = executionProperties.getMaxRunningTasksPerUser();
        originalMaxQueuedTasksPerUser = executionProperties.getMaxQueuedTasksPerUser();
    }

    @AfterEach
    void restoreQuotaProperties() {
        executionProperties.setMaxDailyCasesPerUser(originalMaxDailyCasesPerUser);
        executionProperties.setMaxDailyRuntimeMillisPerUser(originalMaxDailyRuntimeMillisPerUser);
        executionProperties.setMaxRunningTasksPerUser(originalMaxRunningTasksPerUser);
        executionProperties.setMaxQueuedTasksPerUser(originalMaxQueuedTasksPerUser);
    }

    @Test
    void userExceedingDailyCaseQuotaReceivesTooManyRequestsWithoutStackTrace() throws Exception {
        executionProperties.setMaxDailyCasesPerUser(1);
        String userSession = session(uniqueUser("quota-user"), false);

        createJudge(userSession, 1).andExpect(status().isOk());
        MvcResult rejected = createJudge(userSession, 1)
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(json.path("code").asText()).isEqualTo("JUDGE_QUOTA_EXCEEDED");
        assertThat(json.path("message").asText()).contains("daily case quota");
        assertThat(rejected.getResponse().getContentAsString())
                .doesNotContain("QuotaExceededException")
                .doesNotContain("java.")
                .doesNotContain("at com.");
    }

    @Test
    void userExceedingDailyRuntimeBudgetReceivesTooManyRequests() throws Exception {
        executionProperties.setMaxDailyRuntimeMillisPerUser(1_000);
        String userSession = session(uniqueUser("runtime-quota-user"), false);

        MvcResult rejected = createJudge(userSession, 1)
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(json.path("code").asText()).isEqualTo("JUDGE_QUOTA_EXCEEDED");
        assertThat(json.path("message").asText()).contains("daily runtime quota");
    }

    @ParameterizedTest
    @CsvSource({"daily-case,daily case quota", "daily-runtime,daily runtime quota"})
    void cancellationAndPhysicalDeletionDoNotRefundDailyQuotaAfterReload(String quota, String expectedQuota) throws Exception {
        executionProperties.setMaxDailyCasesPerUser(Long.MAX_VALUE);
        executionProperties.setMaxDailyRuntimeMillisPerUser(Long.MAX_VALUE);
        if (quota.equals("daily-case")) {
            executionProperties.setMaxDailyCasesPerUser(1);
        } else {
            executionProperties.setMaxDailyRuntimeMillisPerUser(2_000);
        }
        String userId = uniqueUser("deleted-daily-quota");
        String ownerSession = session(userId, false);
        String judgeId = createJudgeId(ownerSession, 1);
        mockMvc.perform(post("/judge/cancel/{judgeId}", judgeId)
                        .header("X-Session-ID", ownerSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        assertThat(taskStore.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.CANCELLED);
        createJudge(ownerSession, 1).andExpect(status().isTooManyRequests());

        FileTaskStore fileStore = (FileTaskStore) taskStore;
        assertThat(fileStore.deleteTaskDirectory(judgeId)).isTrue();
        assertThat(fileStore.taskDirectory(judgeId)).doesNotExist();
        String anotherSession = session(userId, false);
        MvcResult rejected = createJudge(anotherSession, 1)
                .andExpect(status().isTooManyRequests())
                .andReturn();
        JsonNode response = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(response.path("code").asText()).isEqualTo("JUDGE_QUOTA_EXCEEDED");
        assertThat(response.path("message").asText()).contains(expectedQuota);

        FileTaskStore reloadedStore = new FileTaskStore(objectMapper, fileStore.storageBase());
        assertThat(reloadedStore.find(judgeId)).isEmpty();
        QuotaService reloadedQuota = new QuotaService(executionProperties, reloadedStore);
        UserSession reloadedSession = UserSession.builder().userId(userId).sessionId(anotherSession).build();
        assertThatThrownBy(() -> reloadedQuota.assertCanCreate(reloadedSession, policy(1)))
                .isInstanceOf(QuotaService.QuotaExceededException.class)
                .hasMessageContaining(expectedQuota);
        createJudge(session(uniqueUser("other-deleted-daily-quota"), false), 1)
                .andExpect(status().isOk());
    }

    @Test
    void userExceedingRunningTaskQuotaReceivesTooManyRequests() throws Exception {
        executionProperties.setMaxRunningTasksPerUser(1);
        String userId = uniqueUser("running-quota-user");
        String userSession = session(userId, false);
        createStoredTask("existing-running-quota-" + UUID.randomUUID(), userId, JudgeStatus.RUNNING, 1);

        MvcResult rejected = createJudge(userSession, 1)
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(json.path("code").asText()).isEqualTo("JUDGE_QUOTA_EXCEEDED");
        assertThat(json.path("message").asText()).contains("running task quota");
    }

    @Test
    void userExceedingQueuedTaskQuotaReceivesTooManyRequests() throws Exception {
        executionProperties.setMaxQueuedTasksPerUser(1);
        String userSession = session(uniqueUser("queued-quota-user"), false);

        createJudge(userSession, 1).andExpect(status().isOk());
        MvcResult rejected = createJudge(userSession, 1)
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(json.path("code").asText()).isEqualTo("JUDGE_QUOTA_EXCEEDED");
        assertThat(json.path("message").asText()).contains("queued task quota");
    }

    @ParameterizedTest
    @CsvSource({"queued,false", "queued,true", "daily-case,false", "daily-runtime,false"})
    void concurrentSessionsOfOneOwnerCannotConsumeTheLastCreationQuotaTwice(String quota, boolean admin) throws Exception {
        executionProperties.setMaxDailyCasesPerUser(Long.MAX_VALUE);
        executionProperties.setMaxDailyRuntimeMillisPerUser(Long.MAX_VALUE);
        executionProperties.setMaxQueuedTasksPerUser(Integer.MAX_VALUE);
        String expectedQuota = switch (quota) {
            case "queued" -> {
                executionProperties.setMaxQueuedTasksPerUser(1);
                yield "queued task quota";
            }
            case "daily-case" -> {
                executionProperties.setMaxDailyCasesPerUser(1);
                yield "daily case quota";
            }
            case "daily-runtime" -> {
                executionProperties.setMaxDailyRuntimeMillisPerUser(2_000);
                yield "daily runtime quota";
            }
            default -> throw new IllegalArgumentException(quota);
        };
        String ownerId = uniqueUser("concurrent-create");
        Map<?, ?> pending = (Map<?, ?>) ReflectionTestUtils.getField(judgeService, "pendingJudgeTasks");
        Set<?> pendingBefore = Set.copyOf(pending.keySet());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> attempts = new ArrayList<>();
        List<String> createdIds = new ArrayList<>();
        try {
            for (int attemptIndex = 0; attemptIndex < 8; attemptIndex++) {
                String ownerSession = session(ownerId, admin);
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent creation start timed out");
                    }
                    return createJudge(ownerSession, 1).andReturn();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<MvcResult> attempt : attempts) {
                MvcResult result = attempt.get(10, TimeUnit.SECONDS);
                int statusCode = result.getResponse().getStatus();
                assertThat(statusCode).isIn(200, 429);
                JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
                if (statusCode == 200) {
                    createdIds.add(response.path("judgeId").asText());
                } else {
                    assertThat(response.path("code").asText()).isEqualTo("JUDGE_QUOTA_EXCEEDED");
                    assertThat(response.path("message").asText()).contains(expectedQuota);
                }
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(createdIds).hasSize(1);
        assertThat(taskStore.findAll().stream()
                .filter(task -> task.getOwnership() != null && task.getOwnership().isOwnedBy(ownerId))
                .toList()).singleElement().satisfies(task -> {
                    assertThat(task.getJudgeId()).isEqualTo(createdIds.get(0));
                    assertThat(task.getStatus()).isEqualTo(JudgeStatus.CREATED);
                });
        Set<?> newPending = new HashSet<>(pending.keySet());
        newPending.removeAll(pendingBefore);
        assertThat(newPending).isEqualTo(Set.copyOf(createdIds));
        String otherOwnerSession = session(uniqueUser("independent-create"), false);
        createJudge(otherOwnerSession, 1).andExpect(status().isOk());
    }

    @Test
    void otherUserCannotReadCancelDetailsOrDownloadOwnersTask() throws Exception {
        String ownerSession = session(uniqueUser("owner-user"), false);
        String otherSession = session(uniqueUser("other-user"), false);
        String judgeId = createJudgeId(ownerSession, 2);

        mockMvc.perform(get("/judge/status/{judgeId}", judgeId)
                        .header("X-Session-ID", otherSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/judge/cancel/{judgeId}", judgeId)
                        .header("X-Session-ID", otherSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/details/{judgeId}/{caseNumber}", judgeId, 1)
                        .header("X-Session-ID", otherSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/download/{judgeId}/{caseNumber}", judgeId, 1)
                        .header("X-Session-ID", otherSession)
                        .header("User-Agent", TEST_USER_AGENT))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousUserCannotReadOwnersTaskOrLearnJudgeIdExists() throws Exception {
        String ownerSession = session(uniqueUser("anonymous-owner"), false);
        String judgeId = createJudgeId(ownerSession, 1);

        MvcResult rejected = mockMvc.perform(get("/judge/status/{judgeId}", judgeId)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(rejected.getResponse().getContentAsString()).doesNotContain(judgeId);
    }

    @Test
    void explicitlyAllowedAdminSessionCanInspectAnotherUsersTask() throws Exception {
        String ownerSession = session(uniqueUser("owner-for-admin"), false);
        String adminSession = session(uniqueUser("admin-user"), true);
        String judgeId = createJudgeId(ownerSession, 1);

        mockMvc.perform(get("/judge/status/{judgeId}", judgeId)
                        .header("X-Session-ID", adminSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(post("/judge/cancel/{judgeId}", judgeId)
                        .header("X-Session-ID", adminSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    private String session(String userId, boolean admin) {
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", TEST_USER_AGENT, userId, admin);
        return session.getSessionId();
    }

    private String uniqueUser(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String createJudgeId(String sessionId, int cases) throws Exception {
        MvcResult result = createJudge(sessionId, cases)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("judgeId").asText();
    }

    private org.springframework.test.web.servlet.ResultActions createJudge(String sessionId, int cases) throws Exception {
        return mockMvc.perform(post("/judge")
                .header("X-Session-ID", sessionId)
                .header("User-Agent", TEST_USER_AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request(cases))));
    }

    private JudgeRequest request(int cases) {
        JudgeRequest request = new JudgeRequest();
        request.setUserCode("int main(){return 0;}");
        request.setGeneratorCode("int main(){return 0;}");
        request.setBruteForceCode("int main(){return 0;}");
        request.setTimeLimit(2_000);
        request.setMemoryLimit(268_435_456L);
        request.setTestCases(cases);
        return request;
    }

    private void createStoredTask(String judgeId, String userId, JudgeStatus status, int cases) throws Exception {
        Path workDir = taskStore.taskDirectory(judgeId);
        taskStore.create(JudgeTask.builder()
                .judgeId(judgeId)
                .status(status)
                .requestedCases(cases)
                .mode(executionProperties.getProfile())
                .policy(policy(cases))
                .ownership(JudgeOwnership.owner(userId, "seed-session-" + userId))
                .workDir(workDir.toString())
                .createdAt(Instant.now())
                .build());
    }

    private ResolvedTaskPolicy policy(int cases) {
        return new ResolvedTaskPolicy(
                executionProperties.getProfile(),
                false,
                executionProperties.getMaxCasesPerTask(),
                cases,
                executionProperties.getBatchSize(),
                executionProperties.getMaxConcurrentCasesPerTask(),
                Duration.ofMillis(2_000),
                executionProperties.getMaxTaskRuntime(),
                268_435_456L,
                executionProperties.getMaxOutputBytesPerCase(),
                executionProperties.isRequireSandbox()
        );
    }
}
