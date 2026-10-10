package com.example.demo;

import com.example.demo.config.ExecutionProperties;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.JudgeService;
import com.example.demo.service.TaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "judge.auth.access-code=task02-test-only-access-material")
@AutoConfigureMockMvc
@Import(WebSocketSendHttpTest.IsolatedTaskStore.class)
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

    @Autowired
    private ExecutionProperties executionProperties;

    @Autowired
    private JudgeService judgeService;

    @Autowired
    private TaskStore taskStore;

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
        String message = statusJson.path("message").asText();
        assertThat(message)
                .contains("Compilation failed for user.cpp")
                .contains("user.cpp")
                .doesNotContain("RUNTIME_ERROR")
                .doesNotContain("online-judge")
                .doesNotContain("AppData")
                .doesNotContain("\\judge-")
                .doesNotContain("/judge-");
        assertThat(statusJson.path("progress").asInt()).isEqualTo(100);
        assertThat(statusJson.path("results").isNull()).isTrue();
    }

    @Test
    void runtimeErrorIsRecordedAsSingleCaseRe() throws Exception {
        String judgeId = createJudgeTask(request(1, RUNTIME_ERROR_SOLUTION, FIXED_GENERATOR, ECHO_SOLUTION));

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText())
                .as("terminal status=%.80s message=%.1024s progress=%s results=%.2048s",
                        statusJson.path("status").asText(),
                        statusJson.path("message").asText(),
                        statusJson.path("progress").asInt(),
                        statusJson.path("results").toString())
                .isEqualTo("RE");
        assertThat(statusJson.path("message").asText()).isEqualTo("RE on Test Case #1");
        assertThat(statusJson.path("progress").asInt()).isEqualTo(100);

        JsonNode results = statusJson.path("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("caseNumber").asInt()).isEqualTo(1);
        assertThat(results.get(0).path("status").asText()).isEqualTo("RE");
        assertThat(results.get(0).path("timeUsed").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(results.get(0).path("memoryUsed").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void generatorCaptureIoFailureIsSystemErrorInsteadOfOutputLimitExceeded() throws Exception {
        String judgeId = createJudgeTask(request(1, ECHO_SOLUTION, FIXED_GENERATOR, ECHO_SOLUTION));
        Files.createDirectory(taskStore.taskDirectory(judgeId).resolve("1.in"));

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText()).isEqualTo("System Error");
        assertThat(taskStore.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.SYSTEM_ERROR);
        assertThat(statusJson.path("summary").path("completedCases").asInt()).isEqualTo(1);
        assertThat(statusJson.path("summary").path("systemError").asInt()).isEqualTo(1);
        assertThat(statusJson.path("summary").path("outputLimitExceeded").asInt()).isZero();
        assertThat(statusJson.path("summary").path("ac").asInt()).isZero();
        assertThat(statusJson.path("results").get(0).path("status").asText()).isEqualTo("System Error");
    }

    @Test
    void generatorOutputLimitIsReportedAsOutputLimitExceededInsteadOfSystemError() throws Exception {
        long outputBytes = Math.addExact(executionProperties.getMaxOutputBytesPerCase(), 1);
        String judgeId = createJudgeTask(request(1, ECHO_SOLUTION, sizedGenerator(outputBytes), ECHO_SOLUTION));

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");
        assertThat(statusJson.path("message").asText()).isEqualTo("OUTPUT_LIMIT_EXCEEDED on Test Case #1");
        assertThat(statusJson.path("summary").path("outputLimitExceeded").asInt()).isEqualTo(1);
        assertThat(statusJson.path("summary").path("systemError").asInt()).isZero();
        assertThat(statusJson.path("results").get(0).path("status").asText()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");

        MvcResult detailsResult = mockMvc.perform(get("/details/{judgeId}/{caseNumber}", judgeId, 1)
                        .header("X-Session-ID", sessionId)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode detailsJson = objectMapper.readTree(detailsResult.getResponse().getContentAsString());
        long previewBytes = Math.min(executionProperties.getMaxDetailPreviewBytes(),
                executionProperties.getMaxOutputBytesPerCase());
        assertThat(detailsJson.path("input").asText()).hasSize(Math.toIntExact(previewBytes));
        assertThat(detailsJson.path("userOutput").asText()).isEmpty();
        assertThat(detailsJson.path("correctOutput").asText()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void judgeProgramOutputLimitIsReportedAsOutputLimitExceededInsteadOfSystemError(boolean specialJudge) throws Exception {
        long outputBytes = Math.addExact(executionProperties.getMaxOutputBytesPerCase(), 1);
        Map<String, Object> taskRequest = request(1, ECHO_SOLUTION, FIXED_GENERATOR, ECHO_SOLUTION);
        taskRequest.put("useSpecialJudge", specialJudge);
        taskRequest.put(specialJudge ? "specialJudgeCode" : "bruteForceCode", sizedGenerator(outputBytes));
        String judgeId = createJudgeTask(taskRequest);

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");
        assertThat(statusJson.path("summary").path("completedCases").asInt()).isEqualTo(1);
        assertThat(statusJson.path("summary").path("outputLimitExceeded").asInt()).isEqualTo(1);
        assertThat(statusJson.path("summary").path("systemError").asInt()).isZero();
        assertThat(statusJson.path("summary").path("ac").asInt()).isZero();
        assertThat(statusJson.path("results").get(0).path("status").asText()).isEqualTo("OUTPUT_LIMIT_EXCEEDED");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void generatorOutputAtOrBelowConfiguredLimitIsAccepted(long limitOffset) throws Exception {
        long outputBytes = Math.addExact(executionProperties.getMaxOutputBytesPerCase(), limitOffset);
        String judgeId = createJudgeTask(request(1, ECHO_SOLUTION, sizedGenerator(outputBytes), ECHO_SOLUTION));

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText()).isEqualTo("AC");
        assertThat(statusJson.path("summary").path("ac").asInt()).isEqualTo(1);
        assertThat(statusJson.path("summary").path("outputLimitExceeded").asInt()).isZero();
        assertThat(statusJson.path("summary").path("systemError").asInt()).isZero();
        assertThat(statusJson.path("results").get(0).path("status").asText()).isEqualTo("AC");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ordinaryComparisonVectors")
    void ordinaryComparisonFollowsApprovedIntegerAndLiteralRules(String vectorId, String userOutput,
                                                                 String expectedOutput, String precision,
                                                                 boolean expectedMatch, String expectedError) {
        if (expectedError != null) {
            assertThat(expectedError).isEqualTo("System Error");
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(judgeService, "outputsMatch",
                    userOutput, expectedOutput, precision)).as(vectorId).isInstanceOf(IllegalArgumentException.class);
            return;
        }
        Boolean actual = ReflectionTestUtils.invokeMethod(judgeService, "outputsMatch", userOutput, expectedOutput,
                precision);

        assertThat(actual).as(vectorId).isEqualTo(expectedMatch);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ordinaryComparisonRegressionCases")
    void confirmedFalseAcCasesProduceRealWrongAnswerResults(String vectorId, String userOutput,
                                                           String expectedOutput, String precision) throws Exception {
        String userCode = "#include <iostream>\nint main() { std::cout << \"%s\\n\"; }\n".formatted(userOutput);
        String oracleCode = "#include <iostream>\nint main() { std::cout << \"%s\\n\"; }\n".formatted(expectedOutput);
        Map<String, Object> body = request(1, userCode, FIXED_GENERATOR, oracleCode);
        body.put("precision", precision);
        String judgeId = createJudgeTask(body);

        startJudgeTask(judgeId);

        JsonNode statusJson = awaitTerminalStatus(judgeId);
        assertThat(statusJson.path("status").asText()).as(vectorId).isEqualTo("WA");
        assertThat(statusJson.path("results").get(0).path("status").asText()).isEqualTo("WA");
        assertThat(statusJson.path("summary").path("completedCases").asInt()).isEqualTo(1);
        assertThat(statusJson.path("summary").path("wa").asInt()).isEqualTo(1);
        assertThat(statusJson.path("summary").path("ac").asInt()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-user", "invalid-oracle", "long-token", "large-exponent"})
    void realComparisonInputErrorsAreSystemErrorsRatherThanAcceptedOrWrongAnswer(String shape) throws Exception {
        String invalidUtf8 = "#include <cstdio>\nint main(){std::putchar(255);}\n";
        String userCode = switch (shape) {
            case "invalid-user" -> invalidUtf8;
            case "long-token" -> "#include <iostream>\n#include <string>\nint main(){std::cout << std::string(4097, 'x');}\n";
            case "large-exponent" -> "#include <iostream>\nint main(){std::cout << \"1e1001\";}\n";
            default -> ECHO_SOLUTION;
        };
        String oracleCode = shape.equals("invalid-oracle") ? invalidUtf8 : ECHO_SOLUTION;
        String judgeId = createJudgeTask(request(1, userCode, FIXED_GENERATOR, oracleCode));
        startJudgeTask(judgeId);
        JsonNode snapshot = awaitTerminalStatus(judgeId);
        assertThat(snapshot.path("status").asText()).isEqualTo("System Error");
        assertThat(taskStore.find(judgeId).orElseThrow().getStatus()).isEqualTo(JudgeStatus.SYSTEM_ERROR);
        assertThat(snapshot.path("results").get(0).path("status").asText()).isEqualTo("System Error");
        assertThat(snapshot.path("summary").path("completedCases").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("systemError").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("ac").asInt()).isZero();
        assertThat(snapshot.path("summary").path("wa").asInt()).isZero();
    }

    @Test
    void aRealNulOutputCannotBeIgnoredAsWhitespaceAndReportedAccepted() throws Exception {
        String userCode = "#include <iostream>\nint main() { std::cout << char(0) << 7 << std::endl; }\n";
        String judgeId = createJudgeTask(request(1, userCode, FIXED_GENERATOR, ECHO_SOLUTION));
        startJudgeTask(judgeId);

        JsonNode snapshot = awaitTerminalStatus(judgeId);
        assertThat(snapshot.path("status").asText()).isEqualTo("WA");
        assertThat(snapshot.path("results").get(0).path("status").asText()).isEqualTo("WA");
        assertThat(snapshot.path("summary").path("completedCases").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("wa").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("ac").asInt()).isZero();
        MvcResult details = mockMvc.perform(get("/details/{judgeId}/{caseNumber}", judgeId, 1)
                        .header("X-Session-ID", sessionId)
                        .header("User-Agent", TEST_USER_AGENT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode caseDetails = objectMapper.readTree(details.getResponse().getContentAsString());
        assertThat(caseDetails.path("userOutput").asText()).isEqualTo("\u00007\n");
        assertThat(caseDetails.path("correctOutput").asText()).isEqualTo("7\n");
    }

    static List<Arguments> ordinaryComparisonVectors() throws IOException {
        List<Arguments> arguments = new ArrayList<>();
        for (JsonNode vector : loadComparisonVectors()) {
            arguments.add(Arguments.of(vector.path("id").asText(), vector.path("userOutput").asText(),
                    vector.path("expectedOutput").asText(), vector.path("precision").asText(),
                    vector.path("matches").asBoolean(), vector.path("error").asText(null)));
        }
        return arguments;
    }

    static List<Arguments> ordinaryComparisonRegressionCases() throws IOException {
        List<Arguments> arguments = new ArrayList<>();
        for (JsonNode vector : loadComparisonVectors()) {
            if (List.of("large-adjacent-zero", "nan-versus-number", "decimal-large-adjacent-zero",
                    "finite-overflow-zero", "finite-underflow-zero").contains(vector.path("id").asText())) {
                arguments.add(Arguments.of(vector.path("id").asText(), vector.path("userOutput").asText(),
                        vector.path("expectedOutput").asText(), vector.path("precision").asText()));
            }
        }
        return arguments;
    }

    private static JsonNode loadComparisonVectors() throws IOException {
        try (InputStream vectors = JudgeBaselineTest.class
                .getResourceAsStream("/comparison/ordinary-output-vectors.json")) {
            if (vectors == null) {
                throw new IOException("Ordinary comparison vectors are missing");
            }
            return new ObjectMapper().readTree(vectors);
        }
    }

    private String sizedGenerator(long outputBytes) {
        assertThat(outputBytes).isGreaterThanOrEqualTo(2);
        return """
                #include <algorithm>
                #include <iostream>
                #include <string>

                int main() {
                    std::ios::sync_with_stdio(false);
                    std::cout << "7\\n";
                    const std::string block(4096, ' ');
                    long long remaining = %dLL - 2;
                    while (remaining > 0) {
                        const auto chunk = std::min<long long>(remaining, block.size());
                        std::cout.write(block.data(), chunk);
                        remaining -= chunk;
                    }
                    return 0;
                }
                """.formatted(outputBytes);
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
