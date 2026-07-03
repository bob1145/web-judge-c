package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.dto.JudgeRequest;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.AuditService;
import com.example.demo.service.ResolvedTaskPolicy;
import com.example.demo.service.TaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuditAndAdminTest {

    private static final String TEST_USER_AGENT = "AuditAndAdminTest";

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
    private AuditService auditService;

    private int originalMaxQueuedTasksPerUser;
    private boolean originalRequireSandbox;

    @BeforeEach
    void captureProperties() {
        originalMaxQueuedTasksPerUser = executionProperties.getMaxQueuedTasksPerUser();
        originalRequireSandbox = executionProperties.isRequireSandbox();
        auditService.clear();
    }

    @AfterEach
    void restoreProperties() {
        executionProperties.setMaxQueuedTasksPerUser(originalMaxQueuedTasksPerUser);
        executionProperties.setRequireSandbox(originalRequireSandbox);
        auditService.clear();
    }

    @Test
    void adminQueueSnapshotRequiresAdminAndShowsQueueProviderAndFailureCounts() throws Exception {
        String ownerUserId = uniqueUser("admin-owner");
        String ownerSession = session(ownerUserId, false);
        String adminSession = session(uniqueUser("admin"), true);
        String nonAdminSession = session(uniqueUser("non-admin"), false);
        String runningJudgeId = createStoredTask("running-admin-" + UUID.randomUUID(), ownerUserId, JudgeStatus.RUNNING, 4);
        createStoredTask("failed-admin-" + UUID.randomUUID(), ownerUserId, JudgeStatus.SYSTEM_ERROR, 8);

        mockMvc.perform(get("/admin/queue")
                        .header("X-Session-ID", nonAdminSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());

        MvcResult result = mockMvc.perform(get("/admin/queue")
                        .header("X-Session-ID", adminSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("queuedCount").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(json.path("runningCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(json.path("providerHealth").path("provider").asText()).isNotBlank();
        assertThat(json.path("recentFailureCounts").path("SYSTEM_ERROR").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(json.path("taskResourceSummaries")).isNotEmpty();
        assertThat(json.path("taskResourceSummaries").toString()).contains(runningJudgeId);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("password")
                .doesNotContain("SUPER_SECRET_SOURCE")
                .doesNotContain("top-secret-token");
    }

    @Test
    void auditEventsAreStructuredForTaskOperationsAndSanitized() throws Exception {
        executionProperties.setRequireSandbox(true);
        String ownerUserId = uniqueUser("audit-owner");
        String ownerSession = session(ownerUserId, false);
        String otherSession = session(uniqueUser("audit-other"), false);

        String judgeId = createJudgeId(ownerSession, 1);
        mockMvc.perform(post("/judge/start/{judgeId}", judgeId)
                        .header("X-Session-ID", ownerSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(post("/judge/cancel/{judgeId}", judgeId)
                        .header("X-Session-ID", ownerSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        String downloadJudgeId = createStoredTask("download-audit-" + UUID.randomUUID(), ownerUserId, JudgeStatus.COMPLETED, 1);
        Files.writeString(taskStore.taskDirectory(downloadJudgeId).resolve("1.in"), "download input");
        mockMvc.perform(get("/download/{judgeId}/{caseNumber}", downloadJudgeId, 1)
                        .header("X-Session-ID", ownerSession)
                        .header("User-Agent", TEST_USER_AGENT))
                .andExpect(status().isOk());

        mockMvc.perform(get("/judge/status/{judgeId}", downloadJudgeId)
                        .header("X-Session-ID", otherSession)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        executionProperties.setMaxQueuedTasksPerUser(0);
        createJudge(ownerSession, 1)
                .andExpect(status().isTooManyRequests());

        List<AuditService.AuditEvent> events = auditService.recentEvents();
        assertThat(events).anySatisfy(event -> assertAuditEvent(event, "task.create", ownerUserId, judgeId));
        assertThat(events).anySatisfy(event -> assertAuditEvent(event, "task.start", ownerUserId, judgeId));
        assertThat(events).anySatisfy(event -> assertAuditEvent(event, "task.cancel", ownerUserId, judgeId));
        assertThat(events).anySatisfy(event -> assertAuditEvent(event, "task.download", ownerUserId, downloadJudgeId));
        assertThat(events).anySatisfy(event -> assertAuditEvent(event, "security.denied", uniqueUserPrefix("audit-other"), downloadJudgeId));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("quota.reject");
            assertThat(event.userId()).isEqualTo(ownerUserId);
            assertThat(event.provider()).isEqualTo(executionProperties.getProfile());
            assertThat(event.details()).containsKey("quota");
        });

        String auditJson = objectMapper.writeValueAsString(events);
        assertThat(auditJson)
                .doesNotContain("SUPER_SECRET_SOURCE")
                .doesNotContain("top-secret-token")
                .doesNotContain("CorrectPassword")
                .doesNotContain("password=");
    }

    private void assertAuditEvent(AuditService.AuditEvent event, String type, String userId, String judgeId) {
        assertThat(event.type()).isEqualTo(type);
        assertThat(event.userId()).startsWith(userId);
        assertThat(event.judgeId()).isEqualTo(judgeId);
        assertThat(event.provider()).isEqualTo(executionProperties.getProfile());
        assertThat(event.timestamp()).isNotNull();
        assertThat(event.details()).isNotNull();
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
        request.setUserCode("int main(){ /* SUPER_SECRET_SOURCE top-secret-token password=CorrectPassword */ return 0; }");
        request.setGeneratorCode("int main(){return 0;}");
        request.setBruteForceCode("int main(){return 0;}");
        request.setTimeLimit(2_000);
        request.setMemoryLimit(268_435_456L);
        request.setTestCases(cases);
        return request;
    }

    private String createStoredTask(String judgeId, String userId, JudgeStatus status, int cases) throws Exception {
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
                .message(status.name())
                .build());
        return judgeId;
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

    private String session(String userId, boolean admin) {
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", TEST_USER_AGENT, userId, admin);
        return session.getSessionId();
    }

    private String uniqueUser(String prefix) {
        return uniqueUserPrefix(prefix) + "-" + UUID.randomUUID();
    }

    private String uniqueUserPrefix(String prefix) {
        return prefix + "-user";
    }
}
