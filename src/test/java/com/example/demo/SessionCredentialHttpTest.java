package com.example.demo;

import com.example.demo.controller.AdminController;
import com.example.demo.controller.AuthenticationController;
import com.example.demo.controller.JudgeController;
import com.example.demo.model.JudgeOwnership;
import com.example.demo.model.JudgeStatus;
import com.example.demo.model.JudgeTask;
import com.example.demo.model.UserSession;
import com.example.demo.service.AccessCodeService;
import com.example.demo.service.TaskStore;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "judge.auth.access-code=task02-test-only-access-material")
@AutoConfigureMockMvc
class SessionCredentialHttpTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AccessCodeService accessCodeService;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private JudgeController judgeController;
    @Autowired
    private AdminController adminController;
    @Autowired
    private AuthenticationController authenticationController;

    private UserSession session;
    private UserSession otherSession;
    private String judgeId;

    @BeforeEach
    void createOwnerAndDownload() throws Exception {
        session = accessCodeService.createSession(false, "127.0.0.1", "TASK-02", "http-owner-" + UUID.randomUUID(), true);
        otherSession = accessCodeService.createSession(false, "127.0.0.1", "TASK-02");
        judgeId = "http-credential-" + UUID.randomUUID();
        taskStore.create(JudgeTask.builder()
                .judgeId(judgeId)
                .status(JudgeStatus.COMPLETED)
                .requestedCases(1)
                .mode("trusted-local")
                .ownership(JudgeOwnership.owner(session.getUserId(), session.getSessionId()))
                .workDir(taskStore.taskDirectory(judgeId).toString())
                .createdAt(Instant.now())
                .build());
        Files.writeString(taskStore.taskDirectory(judgeId).resolve("1.in"), "credential-download");
    }

    @AfterEach
    void invalidateSessions() {
        accessCodeService.invalidateSession(session.getSessionId());
        accessCodeService.invalidateSession(otherSession.getSessionId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/admin/queue", "/judge/status/{judgeId}", "/download/{judgeId}/1", "/auth/session"})
    void queryOnlyCannotAuthenticateProtectedEndpoints(String path) throws Exception {
        var result = mockMvc.perform(request(path).param("sessionId", session.getSessionId()));
        if ("/auth/session".equals(path)) {
            result.andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.sessionId").isEmpty());
        } else {
            result.andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cookie", "header", "same"})
    void cookieHeaderAndMatchingCredentialsKeepProtectedEndpointsUsable(String mode) throws Exception {
        for (String path : new String[]{"/", "/admin/queue", "/judge/status/{judgeId}", "/download/{judgeId}/1", "/auth/session"}) {
            MockHttpServletRequestBuilder request = request(path);
            if (!"header".equals(mode)) {
                request.cookie(new Cookie("JUDGE_SESSION", session.getSessionId()));
            }
            if (!"cookie".equals(mode)) {
                request.header("X-Session-ID", session.getSessionId());
            }
            mockMvc.perform(request).andExpect(status().isOk());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/admin/queue", "/judge/status/{judgeId}", "/download/{judgeId}/1", "/auth/session"})
    void conflictingValidCredentialsAreRejected(String path) throws Exception {
        var result = mockMvc.perform(request(path)
                        .cookie(new Cookie("JUDGE_SESSION", session.getSessionId()))
                        .header("X-Session-ID", otherSession.getSessionId()));
        if ("/auth/session".equals(path)) {
            result.andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.sessionId").isEmpty());
        } else {
            result.andExpect(status().isUnauthorized());
        }
    }

    @Test
    void authenticationControllerDoesNotResolveQueryOrConflictingCredentials() throws Exception {
        mockMvc.perform(request("/auth/check").param("sessionId", session.getSessionId()))
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(request("/auth/check")
                        .cookie(new Cookie("JUDGE_SESSION", session.getSessionId()))
                        .header("X-Session-ID", otherSession.getSessionId()))
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get("/auth")
                        .cookie(new Cookie("JUDGE_SESSION", session.getSessionId()))
                        .header("X-Session-ID", otherSession.getSessionId()))
                .andExpect(status().isOk());
    }

    @Test
    void logoutCannotChooseBetweenConflictingCredentials() throws Exception {
        mockMvc.perform(post("/auth/logout").accept(MediaType.APPLICATION_JSON)
                        .cookie(new Cookie("JUDGE_SESSION", session.getSessionId()))
                        .header("X-Session-ID", otherSession.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isEmpty());
        assertThat(accessCodeService.isSessionValid(session.getSessionId())).isTrue();
        assertThat(accessCodeService.isSessionValid(otherSession.getSessionId())).isTrue();
    }

    @Test
    void pageRedirectDoesNotCopyQueryCredentials() throws Exception {
        var response = mockMvc.perform(get("/download/{judgeId}/1", judgeId)
                        .param("sessionId", session.getSessionId()))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        assertThat(response.getRedirectedUrl()).doesNotContain(session.getSessionId()).doesNotContain("sessionId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"query", "conflict"})
    void controllersCannotBypassSharedCredentialRulesWithoutMvcInterceptor(String mode) throws Exception {
        MockMvc controllers = MockMvcBuilders.standaloneSetup(judgeController, adminController, authenticationController).build();
        for (String path : new String[]{"/admin/queue", "/judge/status/{judgeId}", "/download/{judgeId}/1", "/auth/check"}) {
            MockHttpServletRequestBuilder request = request(path);
            if ("query".equals(mode)) {
                request.param("sessionId", session.getSessionId());
            } else {
                request.cookie(new Cookie("JUDGE_SESSION", session.getSessionId()))
                        .header("X-Session-ID", otherSession.getSessionId());
            }
            var result = controllers.perform(request);
            if ("/auth/check".equals(path)) {
                result.andExpect(jsonPath("$.success").value(false));
            } else {
                result.andExpect(status().is(path.startsWith("/admin") ? 403 : 404));
            }
        }
    }

    private MockHttpServletRequestBuilder request(String path) {
        return get(path.replace("{judgeId}", judgeId)).header("User-Agent", "TASK-02").accept(MediaType.APPLICATION_JSON);
    }
}
