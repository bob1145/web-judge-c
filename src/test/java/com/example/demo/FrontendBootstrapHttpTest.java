package com.example.demo;

import com.example.demo.security.SessionCredentialResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "judge.auth.access-code=frontend-test-access-material",
        "judge.auth.account-auth-enabled=false",
        "judge.execution.profile=trusted-local"
})
@AutoConfigureMockMvc
class FrontendBootstrapHttpTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void bootstrapIsPublicNonCacheableAndExposesOnlyUiConfiguration() throws Exception {
        mockMvc.perform(get("/auth/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andExpect(jsonPath("$.csrfHeader").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.accountAuthRequired").value(false))
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.accessCode").doesNotExist());
    }

    @Test
    void mutationWithoutCsrfStillFails() throws Exception {
        mockMvc.perform(post("/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessCode\":\"frontend-test-access-material\",\"rememberMe\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reactCanAuthenticateWithBootstrapTokenAndRefreshAfterRotation() throws Exception {
        var bootstrap = mockMvc.perform(get("/auth/bootstrap")).andExpect(status().isOk()).andReturn();
        JsonNode initial = objectMapper.readTree(bootstrap.getResponse().getContentAsString());
        var session = (MockHttpSession) bootstrap.getRequest().getSession(false);
        var login = mockMvc.perform(post("/auth/verify")
                        .session(session)
                        .header(initial.get("csrfHeader").asText(), initial.get("csrfToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessCode\":\"frontend-test-access-material\",\"rememberMe\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        var cookie = login.getResponse().getCookie(SessionCredentialResolver.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        var refreshed = mockMvc.perform(get("/auth/bootstrap").session(session).cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        JsonNode rotated = objectMapper.readTree(refreshed.getResponse().getContentAsString());
        assertThat(rotated.get("csrfToken").asText()).isNotEqualTo(initial.get("csrfToken").asText());
        mockMvc.perform(get("/auth/session").session(session).cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
    }
}
