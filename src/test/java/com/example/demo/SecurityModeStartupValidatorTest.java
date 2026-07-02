package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SecurityModeStartupValidator;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.dto.AuthRequest;
import com.example.demo.model.AuthStatus;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SecurityModeStartupValidatorTest {

    private static final String TEST_USER_AGENT = "SecurityModeStartupValidatorTest";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessCodeService accessCodeService;

    @Autowired
    private ExecutionProperties executionProperties;

    private String originalProfile;

    @AfterEach
    void restoreProfile() {
        if (originalProfile != null) {
            executionProperties.setProfile(originalProfile);
        }
    }

    @Test
    void trustedLocalAllowsInsecureDefaultsButLogsHighRiskWarnings(CapturedOutput output) {
        SecurityModeStartupValidator validator = validator("trusted-local", "123", "*", false, false);

        validator.validateNow();

        assertThat(output.getOut() + output.getErr())
                .contains("HIGH RISK trusted-local configuration")
                .contains("default access code")
                .contains("wildcard WebSocket origin")
                .contains("sandbox is disabled");
    }

    @Test
    void intranetLargeRejectsDefaultAccessCodeWildcardOriginOrDisabledSandbox() {
        assertThatThrownBy(() -> validator("intranet-large", "123", "https://safe.example.com", true, true).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default access code");

        assertThatThrownBy(() -> validator("intranet-large", "changed-secret", "*", true, true).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard WebSocket origin");

        assertThatThrownBy(() -> validator("intranet-large", "changed-secret", "https://safe.example.com", false, true).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sandbox is disabled");
    }

    @Test
    void publicDisabledProfileRejectsJudgeCreation() throws Exception {
        originalProfile = executionProperties.getProfile();
        executionProperties.setProfile("public-disabled");
        UserSession session = accessCodeService.createSession(false, "127.0.0.1", TEST_USER_AGENT);

        mockMvc.perform(post("/judge")
                        .header("X-Session-ID", session.getSessionId())
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(1))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unauthenticatedProtectedEndpointsAreRejected() throws Exception {
        mockMvc.perform(post("/judge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(1))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/judge/start/abc").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/judge/status/abc").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/details/abc/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/download/abc/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void websocketOriginConfigurationAllowsExpectedOriginsAndRejectsOthers() {
        WebSocketConfig config = new WebSocketConfig("https://app.example.com,http://localhost:*,https://*.trusted.test");

        assertThat(config.isOriginAllowed("https://app.example.com")).isTrue();
        assertThat(config.isOriginAllowed("http://localhost:1234")).isTrue();
        assertThat(config.isOriginAllowed("https://team.trusted.test")).isTrue();
        assertThat(config.isOriginAllowed("https://evil.example.com")).isFalse();
    }

    @Test
    void authFailureThresholdLocksOutRepeatedInvalidCodes() {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode("correct");
        auth.setMaxAttempts(2);
        auth.setAttemptResetHours(1);
        AccessCodeService service = new AccessCodeService(auth);

        assertThat(service.validateAccessCode("bad", "10.0.0.8")).isEqualTo(AuthStatus.INVALID_CODE);
        assertThat(service.validateAccessCode("bad", "10.0.0.8")).isEqualTo(AuthStatus.INVALID_CODE);
        assertThat(service.validateAccessCode("correct", "10.0.0.8")).isEqualTo(AuthStatus.TOO_MANY_ATTEMPTS);
    }

    private SecurityModeStartupValidator validator(
            String profile,
            String accessCode,
            String allowedOrigins,
            boolean sandboxEnabled,
            boolean requireSandbox
    ) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode(accessCode);
        auth.setDefaultAccessCode("123");

        ExecutionProperties execution = new ExecutionProperties();
        execution.setProfile(profile);
        execution.setRequireSandbox(requireSandbox);

        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(sandboxEnabled);

        return new SecurityModeStartupValidator(auth, execution, sandbox, new WebSocketConfig(allowedOrigins));
    }

    private Map<String, Object> request(int testCases) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("userCode", "int main(){return 0;}");
        request.put("generatorCode", "int main(){return 0;}");
        request.put("bruteForceCode", "int main(){return 0;}");
        request.put("timeLimit", 2_000);
        request.put("memoryLimit", 268_435_456L);
        request.put("precision", 0.0);
        request.put("testCases", testCases);
        request.put("useSpecialJudge", false);
        request.put("specialJudgeCode", "");
        return request;
    }
}
