package com.example.demo;

import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JudgeBaselineTest {

    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(30);
    private static final String TEST_USER_AGENT = "JudgeBaselineTest";

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

    private static final String WRONG_SOLUTION = """
            #include <iostream>

            int main() {
                int value = 0;
                std::cin >> value;
                std::cout << (value + 1) << std::endl;
                return 0;
            }
            """;

    private static final String RUNTIME_ERROR_SOLUTION = """
            int main() {
                return 1;
            }
            """;

    private static final String INVALID_CPP = """
            int main( {
                return 0;
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessCodeService accessCodeService;

    private String sessionId;

    @BeforeEach
    void createAuthenticatedSession() {
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", TEST_USER_AGENT);
        sessionId = session.getSessionId();
    }

    @Test
    void threeCaseAcFlowIsCreatedStartedAndPolledThroughHttp() throws Exception {
        String judgeId = createJudgeTask(request(3, ECHO_SOLUTION, FIXED_GENERATOR, ECHO_SOLUTION));

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText()).isEqualTo("AC");
        assertThat(statusJson.path("message").asText()).isEqualTo("全部通过！");
        assertThat(statusJson.path("progress").asInt()).isEqualTo(100);

        JsonNode results = statusJson.path("results");
        assertThat(results.isArray()).isTrue();
        assertThat(results).hasSize(3);
        for (int i = 0; i < results.size(); i++) {
            JsonNode result = results.get(i);
            assertThat(result.path("caseNumber").asInt()).isEqualTo(i + 1);
            assertThat(result.path("status").asText()).isEqualTo("AC");
            assertThat(result.has("timeUsed")).isTrue();
            assertThat(result.path("timeUsed").asLong()).isGreaterThanOrEqualTo(0);
            assertThat(result.has("memoryUsed")).isTrue();
            assertThat(result.path("memoryUsed").asLong()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void wrongAnswerFlowPreservesFailureDetailsAndSingleCaseDownload() throws Exception {
        String judgeId = createJudgeTask(request(1, WRONG_SOLUTION, FIXED_GENERATOR, ECHO_SOLUTION));

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText()).isEqualTo("WA");
        assertThat(statusJson.path("message").asText()).isEqualTo("WA on Test Case #1");
        JsonNode result = statusJson.path("results").get(0);
        assertThat(result.path("caseNumber").asInt()).isEqualTo(1);
        assertThat(result.path("status").asText()).isEqualTo("WA");
        assertThat(result.path("timeUsed").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(result.path("memoryUsed").asLong()).isGreaterThanOrEqualTo(0);

        MvcResult detailsResult = mockMvc.perform(get("/details/{judgeId}/{caseNumber}", judgeId, 1)
                        .header("X-Session-ID", sessionId)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detailsJson = objectMapper.readTree(detailsResult.getResponse().getContentAsString());
        assertThat(detailsJson.path("input").asText().trim()).isEqualTo("7");
        assertThat(detailsJson.path("userOutput").asText().trim()).isEqualTo("8");
        assertThat(detailsJson.path("correctOutput").asText().trim()).isEqualTo("7");

        mockMvc.perform(get("/download/{judgeId}/{caseNumber}", judgeId, 1)
                        .header("X-Session-ID", sessionId)
                        .header("User-Agent", TEST_USER_AGENT))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"1.in\""))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("7")));
    }

    @Test
    void compilationErrorIsAvailableThroughStatusPolling() throws Exception {
        String judgeId = createJudgeTask(request(1, INVALID_CPP, FIXED_GENERATOR, ECHO_SOLUTION));

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText()).isEqualTo("COMPILATION_ERROR");
        assertThat(statusJson.path("message").asText()).contains("Compilation failed for user.cpp");
        assertThat(statusJson.path("progress").asInt()).isEqualTo(100);
        assertThat(statusJson.path("results").isNull()).isTrue();
    }

    @Test
    void runtimeErrorIsRecordedAsSingleCaseRe() throws Exception {
        String judgeId = createJudgeTask(request(1, RUNTIME_ERROR_SOLUTION, FIXED_GENERATOR, ECHO_SOLUTION));

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText()).isEqualTo("RE");
        assertThat(statusJson.path("message").asText()).isEqualTo("RE on Test Case #1");
        assertThat(statusJson.path("progress").asInt()).isEqualTo(100);

        JsonNode results = statusJson.path("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("caseNumber").asInt()).isEqualTo(1);
        assertThat(results.get(0).path("status").asText()).isEqualTo("RE");
        assertThat(results.get(0).path("timeUsed").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(results.get(0).path("memoryUsed").asLong()).isGreaterThanOrEqualTo(0);
    }

    private String createJudgeTask(Map<String, Object> request) throws Exception {
        MvcResult result = mockMvc.perform(post("/judge")
                        .header("X-Session-ID", sessionId)
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        String responseBody = result.getResponse().getContentAsString();
        String judgeId;
        if (responseBody.trim().startsWith("{")) {
            JsonNode responseJson = objectMapper.readTree(responseBody);
            judgeId = responseJson.path("judgeId").asText();
            assertThat(responseJson.path("status").asText()).isEqualTo("CREATED");
            assertThat(responseJson.path("requestedCases").asInt()).isEqualTo((Integer) request.get("testCases"));
        } else {
            judgeId = responseBody;
        }
        assertThat(judgeId).isNotBlank();
        UUID.fromString(judgeId);
        return judgeId;
    }

    private void startJudgeTask(String judgeId) throws Exception {
        mockMvc.perform(post("/judge/start/{judgeId}", judgeId)
                        .header("X-Session-ID", sessionId)
                        .header("User-Agent", TEST_USER_AGENT))
                .andExpect(status().isOk())
                .andExpect(content().string("Judge task started"));
    }

    private JsonNode awaitTerminalStatus(String judgeId) throws Exception {
        long deadline = System.nanoTime() + STATUS_TIMEOUT.toNanos();
        AssertionError lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                MvcResult result = mockMvc.perform(get("/judge/status/{judgeId}", judgeId)
                                .header("X-Session-ID", sessionId)
                                .header("User-Agent", TEST_USER_AGENT)
                                .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andReturn();
                JsonNode statusJson = objectMapper.readTree(result.getResponse().getContentAsString());
                if (statusJson.path("progress").asInt() == 100) {
                    return statusJson;
                }
            } catch (AssertionError error) {
                lastFailure = error;
            }
            Thread.sleep(100);
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AssertionError("Timed out waiting for terminal judge status for " + judgeId);
    }

    private Map<String, Object> request(int testCases, String userCode, String generatorCode, String bruteForceCode) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userCode", userCode);
        request.put("generatorCode", generatorCode);
        request.put("bruteForceCode", bruteForceCode);
        request.put("timeLimit", 2_000);
        request.put("memoryLimit", 268_435_456L);
        request.put("precision", 0.0);
        request.put("testCases", testCases);
        request.put("useSpecialJudge", false);
        request.put("specialJudgeCode", "");
        return request;
    }
}
