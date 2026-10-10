package com.example.demo;

import com.example.demo.config.AuthConfiguration;
import com.example.demo.security.ClientAddressResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

class ClientAddressResolverTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "fe80::1%3"})
    void defaultConfigurationKeepsTheSocketSourceAndIgnoresAllForwardingHeaders(String peer) {
        MockHttpServletRequest request = request(peer, "invalid.example, 203.0.113.2");
        request.addHeader("X-Real-IP", "198.51.100.3");
        request.addHeader("Forwarded", "for=192.0.2.4;proto=https");
        assertThat(new ClientAddressResolver(new AuthConfiguration()).resolve(request)).isEqualTo(peer);
    }

    @Test
    void aNonTrustedPeerCannotChangeSourceEvenWithAMalformedOrOversizedHeader() {
        AuthConfiguration configuration = trusted("10.0.0.0/8");
        configuration.setMaxForwardedHeaderBytes(10);
        MockHttpServletRequest request = request("203.0.113.5", "invalid.example,".repeat(100));
        assertThat(new ClientAddressResolver(configuration).resolve(request)).isEqualTo("203.0.113.5");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "10.0.0.0/8|10.0.0.1|203.0.113.5, 10.0.0.2|203.0.113.5",
            "10.0.0.0/8|10.0.0.1|198.51.100.4, 203.0.113.5, 10.0.0.2|203.0.113.5",
            "10.0.0.0/8|10.0.0.1|10.0.0.3, 10.0.0.2|10.0.0.3",
            "2001:db8::/32|2001:db8::1|2001:db9::2, 2001:db8::3|2001:db9:0:0:0:0:0:2",
            "192.0.2.0/24|::ffff:192.0.2.10|::ffff:203.0.113.5|203.0.113.5"
    }, delimiter = '|')
    void trustedHopsAreRemovedFromRightToLeftWithoutTrustingTheForgedLeftPrefix(
            String cidr, String peer, String header, String expected) {
        assertThat(new ClientAddressResolver(trusted(cidr)).resolve(request(peer, header))).isEqualTo(expected);
    }

    @Test
    void repeatedChosenHeadersFormOneChainButOtherHeaderFamiliesAreNotFallbacks() {
        ClientAddressResolver resolver = new ClientAddressResolver(trusted("10.0.0.0/8"));
        MockHttpServletRequest request = request("10.0.0.1", "203.0.113.5");
        request.addHeader("X-Forwarded-For", "10.0.0.2");
        request.addHeader("X-Real-IP", "198.51.100.6");
        request.addHeader("Forwarded", "for=192.0.2.7");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
        MockHttpServletRequest missing = request("10.0.0.1", null);
        missing.addHeader("X-Real-IP", "198.51.100.6");
        missing.addHeader("Forwarded", "for=192.0.2.7");
        assertThat(resolver.resolve(missing)).isEqualTo("10.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "203.0.113.5,", ",203.0.113.5", "203.0.113.5,,10.0.0.2",
            "invalid.example", "999.1.2.3", "012.1.2.3", "127.1", "203.0.113.5:80",
            "[2001:db9::2]", "fe80::1%3", "unknown", "203.0.113.5\n", "203.0.113.5\u0000", "中文"})
    void malformedTrustedChainsFailClosedWithoutHostnameResolution(String header) {
        assertBadHeader(new ClientAddressResolver(trusted("127.0.0.1/32")), request("127.0.0.1", header));
    }

    @Test
    void byteAndHopLimitsApplyToTheEntireRepeatedHeaderChain() {
        AuthConfiguration configuration = trusted("10.0.0.0/8");
        String address = "203.0.113.5";
        configuration.setMaxForwardedHeaderBytes(address.length());
        ClientAddressResolver bytes = new ClientAddressResolver(configuration);
        assertThat(bytes.resolve(request("10.0.0.1", address))).isEqualTo(address);
        assertBadHeader(bytes, request("10.0.0.1", address + " "));
        MockHttpServletRequest repeated = request("10.0.0.1", address);
        repeated.addHeader("X-Forwarded-For", address);
        assertBadHeader(bytes, repeated);
        configuration.setMaxForwardedHeaderBytes(4_096);
        configuration.setMaxForwardedHops(2);
        ClientAddressResolver hops = new ClientAddressResolver(configuration);
        assertThat(hops.resolve(request("10.0.0.1", address + ",10.0.0.2"))).isEqualTo(address);
        assertBadHeader(hops, request("10.0.0.1", address + ",10.0.0.2,10.0.0.3"));
    }

    @Test
    void explicitlyChosenXRealIpRequiresExactlyOneLiteral() {
        AuthConfiguration configuration = trusted("127.0.0.1/32");
        configuration.setForwardedClientHeader("X-Real-IP");
        ClientAddressResolver resolver = new ClientAddressResolver(configuration);
        MockHttpServletRequest request = request("127.0.0.1", "198.51.100.1");
        request.addHeader("X-Real-IP", "203.0.113.5");
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.5");
        request.addHeader("X-Real-IP", "203.0.113.6");
        assertBadHeader(resolver, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"proxy.example/32", "127.0.0.1", "127.0.0.1/33", "::1/129",
            "127.0.0.1/-1", "127.0.0.1/32/32", "012.1.2.3/32", "", "fe80::1%3/64"})
    void invalidTrustedProxyConfigurationIsRejectedBeforeServingRequests(String cidr) {
        assertThatThrownBy(() -> new ClientAddressResolver(trusted(cidr)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trusted proxy CIDR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"framework", "native"})
    void frameworkSourceRewritingCannotBypassTheApplicationTrustPolicy(String strategy) {
        assertThatThrownBy(() -> new ClientAddressResolver(new AuthConfiguration(), strategy))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("forward-headers-strategy=none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"remote-ip-header", "protocol-header", "remoteIpHeader", "protocolHeader"})
    void explicitContainerHeadersCannotBypassDisabledFrameworkSourceRewriting(String property) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("native-source-properties",
                    Map.of("server.forward-headers-strategy", "none",
                            "server.tomcat.remoteip." + property, "X-Forwarded-For")));
            context.registerBean(AuthConfiguration.class, AuthConfiguration::new);
            context.register(ClientAddressResolver.class);

            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause().hasMessageContaining("Tomcat", "remote-ip-header", "protocol-header");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"remote-ip-header", "protocol-header"})
    void actualServletStartupRejectsNativeSourceRewritingBeforeReadiness(String property) {
        ConfigurableApplicationContext application = null;
        try {
            application = new SpringApplicationBuilder(Demo18Application.class, WebSocketSendHttpTest.IsolatedTaskStore.class)
                    .web(WebApplicationType.SERVLET).run(
                            "--server.port=0", "--server.address=127.0.0.1", "--server.forward-headers-strategy=none",
                            "--judge.auth.access-code=task03-native-test-only-access-material",
                            "--server.tomcat.remoteip." + property + "=X-Forwarded-For");
            fail("Native Tomcat source rewriting configuration must prevent readiness");
        } catch (Exception failure) {
            assertThat(failure).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause().hasMessageContaining("Tomcat", "remote-ip-header", "protocol-header");
        } finally {
            if (application != null) {
                application.close();
            }
        }
    }

    private void assertBadHeader(ClientAddressResolver resolver, MockHttpServletRequest request) {
        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    }

    private AuthConfiguration trusted(String cidr) {
        AuthConfiguration configuration = new AuthConfiguration();
        configuration.setTrustedProxyCidrs(List.of(cidr));
        return configuration;
    }

    private MockHttpServletRequest request(String peer, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (header != null) {
            request.addHeader("X-Forwarded-For", header);
        }
        return request;
    }
}
