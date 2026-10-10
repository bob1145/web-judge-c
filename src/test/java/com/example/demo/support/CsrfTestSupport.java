package com.example.demo.support;

import com.example.demo.security.SessionCredentialResolver;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

public final class CsrfTestSupport {
    private static final Pattern TOKEN_META = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");

    private CsrfTestSupport() {
    }

    public static String token(HttpClient client, URI page) throws Exception {
        return token(client, HttpRequest.newBuilder(page));
    }

    public static String token(HttpClient client, HttpRequest.Builder page) throws Exception {
        HttpResponse<String> response = client.send(page
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        Matcher matcher = TOKEN_META.matcher(response.body());
        assertThat(matcher.find()).as("rendered CSRF token").isTrue();
        return matcher.group(1);
    }

    public static MockHttpServletRequestBuilder withCsrf(MockMvc mockMvc,
                                                       MockHttpServletRequestBuilder builder) throws Exception {
        MockHttpServletRequest request = builder.buildRequest(mockMvc.getDispatcherServlet().getServletContext());
        MockHttpServletRequestBuilder page = get("/auth");
        if (request.getCookies() != null) {
            page.cookie(request.getCookies());
        }
        if (request.getSession(false) != null) {
            page.session((MockHttpSession) request.getSession(false));
        }
        request.getHeaders(SessionCredentialResolver.HEADER_NAME).asIterator()
                .forEachRemaining(value -> page.header(SessionCredentialResolver.HEADER_NAME, value));
        request.getHeaders("User-Agent").asIterator()
                .forEachRemaining(value -> page.header("User-Agent", value));
        MvcResult rendered = mockMvc.perform(page).andReturn();
        CsrfToken token = (CsrfToken) rendered.getRequest().getAttribute(CsrfToken.class.getName());
        assertThat(token).isNotNull();
        String value = token.getToken();
        return builder.session((MockHttpSession) rendered.getRequest().getSession(false))
                .header(token.getHeaderName(), value);
    }
}
