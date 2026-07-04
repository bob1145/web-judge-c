package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JudgeControllerPolicyTest {

    private static final String TEST_USER_AGENT = "JudgeControllerPolicyTest";

    private static final String FIXED_GENERATOR = """
            #include <iostream>

            int main() {
                std::cout << 7 << std::endl;
                return 0;
            }
            """;

    private static final String ECHO_SOLUTION = """
            #include <iostream>

            int main() {
                int value = 0;
                std::cin >> value;
                std::cout << value << std::endl;
                return 0;
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessCodeService accessCodeService;

    @Autowired
    private ExecutionProperties executionProperties;

    private String sessionId;
    private String originalProfile;
    private int originalMaxCasesPerTask;
    private int originalLargeModeThreshold;
    private long originalMaxOutputBytesPerCase;

    @BeforeEach
    void setUp() {
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", TEST_USER_AGENT);
        sessionId = session.getSessionId();
        originalProfile = executionProperties.getProfile();
        originalMaxCasesPerTask = executionProperties.getMaxCasesPerTask();
        originalLargeModeThreshold = executionProperties.getLargeModeThreshold();
        originalMaxOutputBytesPerCase = executionProperties.getMaxOutputBytesPerCase();
    }

    @AfterEach
    void restoreExecutionProperties() {
        executionProperties.setProfile(originalProfile);
        executionProperties.setMaxCasesPerTask(originalMaxCasesPerTask);
        executionProperties.setLargeModeThreshold(originalLargeModeThreshold);
        executionProperties.setMaxOutputBytesPerCase(originalMaxOutputBytesPerCase);
    }

    @Test
    void jsonCreateResponseIncludesPolicyMetadata() throws Exception {
        MvcResult result = postJudge(request(3), MediaType.APPLICATION_JSON)
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("judgeId").asText()).satisfies(value -> UUID.fromString(value));
        assertThat(json.path("mode").asText()).isEqualTo(executionProperties.getProfile());
        assertThat(json.path("requestedCases").asInt()).isEqualTo(3);
        assertThat(json.path("maxCasesPerTask").asInt()).isEqualTo(executionProperties.getMaxCasesPerTask());
        assertThat(json.path("maxOutputBytesPerCase").asLong()).isEqualTo(executionProperties.getMaxOutputBytesPerCase());
        assertThat(json.path("highVolume").asBoolean()).isFalse();
        assertThat(json.path("status").asText()).isEqualTo("CREATED");
    }

    @Test
    void highVolumeCreateResponseIsDerivedFromBackendPolicy() throws Exception {
        executionProperties.setProfile("local-large");
        executionProperties.setMaxCasesPerTask(100_000);
        executionProperties.setLargeModeThreshold(5_000);
        executionProperties.setMaxOutputBytesPerCase(967_772_160L);

        MvcResult result = postJudge(request(100_000), MediaType.APPLICATION_JSON)
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("requestedCases").asInt()).isEqualTo(100_000);
        assertThat(json.path("maxCasesPerTask").asInt()).isGreaterThanOrEqualTo(100_000);
        assertThat(json.path("maxOutputBytesPerCase").asLong()).isEqualTo(967_772_160L);
        assertThat(json.path("highVolume").asBoolean()).isTrue();
        assertThat(json.path("mode").asText()).isEqualTo("local-large");
    }

    @Test
    void invalidCreateRequestReturnsStablePolicyErrorWithoutSensitiveDetails() throws Exception {
        executionProperties.setProfile("trusted-local");
        executionProperties.setMaxCasesPerTask(5);

        MvcResult result = postJudge(request(6), MediaType.APPLICATION_JSON)
                .andExpect(status().isBadRequest())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("code").asText()).isEqualTo("JUDGE_POLICY_REJECTED");
        assertThat(json.path("message").asText()).contains("submitted 6");
        assertThat(json.path("submitted").asInt()).isEqualTo(6);
        assertThat(json.path("max").asInt()).isEqualTo(5);
        assertThat(json.path("profile").asText()).isEqualTo("trusted-local");

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("PolicyValidationException")
                .doesNotContain("java.")
                .doesNotContain("at com.")
                .doesNotContain("C:\\")
                .doesNotContain("/tmp")
                .doesNotContain("g++");
    }

    @Test
    void plainTextCreateResponseRemainsAvailableForCurrentFrontendMigration() throws Exception {
        MvcResult result = postJudgeWithoutAccept(request(2))
                .andExpect(status().isOk())
                .andReturn();

        String judgeId = result.getResponse().getContentAsString();
        assertThat(judgeId).isNotBlank();
        UUID.fromString(judgeId);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_PLAIN_VALUE);
    }

    @Test
    void createdTaskKeepsPolicySnapshotWhenConfigurationChangesBeforeStart() throws Exception {
        executionProperties.setProfile("snapshot-test");
        executionProperties.setMaxCasesPerTask(2);
        executionProperties.setLargeModeThreshold(2);

        MvcResult createResult = postJudge(request(2), MediaType.APPLICATION_JSON)
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String judgeId = createJson.path("judgeId").asText();
        assertThat(createJson.path("maxCasesPerTask").asInt()).isEqualTo(2);
        assertThat(createJson.path("highVolume").asBoolean()).isTrue();

        executionProperties.setProfile("changed-after-create");
        executionProperties.setMaxCasesPerTask(1);

        mockMvc.perform(post("/judge/start/{judgeId}", judgeId)
                        .header("X-Session-ID", sessionId)
                        .header("User-Agent", TEST_USER_AGENT))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions postJudge(Map<String, Object> request, MediaType accept)
            throws Exception {
        return mockMvc.perform(post("/judge")
                .header("X-Session-ID", sessionId)
                .header("User-Agent", TEST_USER_AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(accept)
                .content(objectMapper.writeValueAsString(request)));
    }

    private org.springframework.test.web.servlet.ResultActions postJudgeWithoutAccept(Map<String, Object> request)
            throws Exception {
        return mockMvc.perform(post("/judge")
                .header("X-Session-ID", sessionId)
                .header("User-Agent", TEST_USER_AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private Map<String, Object> request(int testCases) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userCode", ECHO_SOLUTION);
        request.put("generatorCode", FIXED_GENERATOR);
        request.put("bruteForceCode", ECHO_SOLUTION);
        request.put("timeLimit", 2_000);
        request.put("memoryLimit", 268_435_456L);
        request.put("precision", 0.0);
        request.put("testCases", testCases);
        request.put("useSpecialJudge", false);
        request.put("specialJudgeCode", "");
        return request;
    }
}
