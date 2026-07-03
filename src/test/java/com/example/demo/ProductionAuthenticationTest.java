package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.config.ExecutionProperties;
import com.example.demo.config.ProductionSecurityStartupValidator;
import com.example.demo.config.SandboxConfiguration;
import com.example.demo.config.SandboxProperties;
import com.example.demo.config.WebSocketConfig;
import com.example.demo.dto.AuthRequest;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.UserAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductionAuthenticationTest {

    private static final String TEST_USER_AGENT = "ProductionAuthenticationTest";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthConfiguration authConfiguration;

    @Autowired
    private ExecutionProperties executionProperties;

    @Autowired
    private AccessCodeService accessCodeService;

    @Autowired
    private UserAccountService userAccountService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private String originalProfile;
    private boolean originalAccountAuthEnabled;
    private List<AuthConfiguration.AccountProperties> originalAccounts;
    private int originalMaxAttempts;

    @BeforeEach
    void captureAuthProperties() {
        originalProfile = executionProperties.getProfile();
        originalAccountAuthEnabled = authConfiguration.isAccountAuthEnabled();
        originalAccounts = new ArrayList<>(authConfiguration.getAccounts());
        originalMaxAttempts = authConfiguration.getMaxAttempts();
    }

    @AfterEach
    void restoreAuthProperties() {
        executionProperties.setProfile(originalProfile);
        authConfiguration.setAccountAuthEnabled(originalAccountAuthEnabled);
        authConfiguration.setAccounts(originalAccounts);
        authConfiguration.setMaxAttempts(originalMaxAttempts);
        userAccountService.clearRateLimits();
    }

    @Test
    void productionRejectsSharedAccessCodeOnlyLogin() throws Exception {
        executionProperties.setProfile("linux-prod");
        configureAccount("access-code-user", "access-code-login", "CorrectPassword-47", false);

        AuthRequest request = new AuthRequest();
        request.setAccessCode(authConfiguration.getAccessCode());

        MvcResult result = mockMvc.perform(post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("message").asText()).contains("account credentials");
    }

    @Test
    void productionAccountLoginCreatesSessionWithStableUserIdentityAndNoPlaintextPassword() throws Exception {
        executionProperties.setProfile("worker-prod");
        String userId = "prod-user-" + UUID.randomUUID();
        configureAccount(userId, "prod-login-" + UUID.randomUUID(), "CorrectPassword-48", true);

        AuthRequest request = new AuthRequest();
        request.setUsername(authConfiguration.getAccounts().get(0).getUsername());
        request.setPassword("CorrectPassword-48");

        MvcResult result = mockMvc.perform(post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.path("success").asBoolean()).isTrue();
        String sessionId = json.path("sessionId").asText();
        UserSession session = accessCodeService.getSession(sessionId);
        assertThat(session.getUserId()).isEqualTo(userId);
        assertThat(session.isAdmin()).isTrue();
        assertThat(objectMapper.writeValueAsString(session)).doesNotContain("CorrectPassword-48");
    }

    @Test
    void productionStartupRejectsMissingPlaintextOrSampleAdminPasswordAndAcceptsBcryptHash() {
        assertThatThrownBy(() -> productionValidator(new AuthConfiguration()).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account authentication is required");

        AuthConfiguration plaintext = authWithAccount("plain-user", "plain-admin", "admin", true);
        assertThatThrownBy(() -> productionValidator(plaintext).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BCrypt");

        AuthConfiguration samplePassword = authWithAccount(
                "sample-user",
                "sample-admin",
                passwordEncoder.encode("admin"),
                true
        );
        assertThatThrownBy(() -> productionValidator(samplePassword).validateNow())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sample admin password");

        AuthConfiguration valid = authWithAccount(
                "valid-user",
                "valid-admin",
                passwordEncoder.encode("CorrectPassword-49"),
                true
        );
        assertThatCode(() -> productionValidator(valid).validateNow()).doesNotThrowAnyException();
    }

    @Test
    void productionAccountFailuresAreRateLimited() throws Exception {
        executionProperties.setProfile("linux-prod");
        authConfiguration.setMaxAttempts(2);
        configureAccount("rate-user", "rate-login-" + UUID.randomUUID(), "CorrectPassword-50", false);

        AuthRequest bad = new AuthRequest();
        bad.setUsername(authConfiguration.getAccounts().get(0).getUsername());
        bad.setPassword("wrong-password");
        mockMvc.perform(post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isUnauthorized());

        AuthRequest correct = new AuthRequest();
        correct.setUsername(bad.getUsername());
        correct.setPassword("CorrectPassword-50");
        MvcResult locked = mockMvc.perform(post("/auth/verify")
                        .header("User-Agent", TEST_USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correct)))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        JsonNode json = objectMapper.readTree(locked.getResponse().getContentAsString());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("message").asText()).contains("Too many");
    }

    private void configureAccount(String userId, String username, String password, boolean admin) {
        authConfiguration.setAccountAuthEnabled(true);
        authConfiguration.setAccounts(List.of(account(userId, username, passwordEncoder.encode(password), admin)));
    }

    private AuthConfiguration authWithAccount(String userId, String username, String passwordHash, boolean admin) {
        AuthConfiguration auth = new AuthConfiguration();
        auth.setAccessCode("changed-secret");
        auth.setDefaultAccessCode("123");
        auth.setAccountAuthEnabled(true);
        auth.setAccounts(List.of(account(userId, username, passwordHash, admin)));
        return auth;
    }

    private AuthConfiguration.AccountProperties account(String userId, String username, String passwordHash, boolean admin) {
        AuthConfiguration.AccountProperties account = new AuthConfiguration.AccountProperties();
        account.setUserId(userId);
        account.setUsername(username);
        account.setPasswordHash(passwordHash);
        account.setAdmin(admin);
        return account;
    }

    private ProductionSecurityStartupValidator productionValidator(AuthConfiguration auth) {
        ExecutionProperties execution = new ExecutionProperties();
        execution.setProfile("linux-prod");
        execution.setRequireSandbox(true);

        SandboxConfiguration sandbox = new SandboxConfiguration();
        sandbox.setEnabled(true);

        SandboxProperties sandboxProperties = new SandboxProperties();
        sandboxProperties.setProvider(SandboxProperties.Provider.LINUX_CONTAINER);
        sandboxProperties.setIsolation(SandboxProperties.Isolation.CONTAINER);
        sandboxProperties.setSecurityProfile("seccomp:judge-linux");
        sandboxProperties.setCapabilityProbeRequired(true);
        sandboxProperties.setCapabilityProbePassed(true);

        return new ProductionSecurityStartupValidator(
                auth,
                execution,
                sandbox,
                sandboxProperties,
                new WebSocketConfig("https://safe.example.com")
        );
    }
}
