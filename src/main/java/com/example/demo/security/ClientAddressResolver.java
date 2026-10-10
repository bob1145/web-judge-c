package com.example.demo.security;

import com.example.demo.config.AuthConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

@Component
public final class ClientAddressResolver {
    private final List<IpAddressMatcher> trustedProxies;
    private final String forwardedHeader;
    private final int maxHeaderBytes;
    private final int maxHops;

    public ClientAddressResolver(AuthConfiguration configuration) {
        this(configuration, "none");
    }

    @Autowired
    public ClientAddressResolver(AuthConfiguration configuration,
                                 @Value("${server.forward-headers-strategy:none}") String strategy,
                                 Environment environment) {
        this(configuration, strategy);
        ServerProperties.Tomcat.Remoteip nativeHeaders = Binder.get(environment)
                .bind("server.tomcat.remoteip", ServerProperties.Tomcat.Remoteip.class)
                .orElseGet(ServerProperties.Tomcat.Remoteip::new);
        if (StringUtils.hasText(nativeHeaders.getRemoteIpHeader()) || StringUtils.hasText(nativeHeaders.getProtocolHeader())) {
            throw new IllegalArgumentException(
                    "Client source resolution requires Tomcat remote-ip-header and protocol-header to be unset");
        }
    }

    public ClientAddressResolver(AuthConfiguration configuration, String strategy) {
        if (!"none".equalsIgnoreCase(strategy)) {
            throw new IllegalArgumentException("Client source resolution requires server.forward-headers-strategy=none");
        }
        forwardedHeader = configuration.getForwardedClientHeader();
        maxHeaderBytes = configuration.getMaxForwardedHeaderBytes();
        maxHops = configuration.getMaxForwardedHops();
        if ((!"X-Forwarded-For".equalsIgnoreCase(forwardedHeader)
                && !"X-Real-IP".equalsIgnoreCase(forwardedHeader)) || maxHeaderBytes < 1 || maxHops < 1
                || configuration.getTrustedProxyCidrs() == null) {
            throw new IllegalArgumentException("A supported client header, positive limits and trusted proxy CIDRs are required");
        }
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String cidr : configuration.getTrustedProxyCidrs()) {
            try {
                int separator = cidr == null ? -1 : cidr.indexOf("/");
                if (separator < 1 || separator != cidr.lastIndexOf("/")
                        || !cidr.substring(separator + 1).matches("[0-9]{1,3}")) {
                    throw new IllegalArgumentException("Invalid CIDR");
                }
                InetAddress address = literalAddress(cidr.substring(0, separator));
                int bits = Integer.parseInt(cidr.substring(separator + 1));
                if (bits > address.getAddress().length * 8) {
                    throw new IllegalArgumentException("Invalid CIDR prefix");
                }
                matchers.add(new IpAddressMatcher(address.getHostAddress() + "/" + bits));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR", exception);
            }
        }
        trustedProxies = List.copyOf(matchers);
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (trustedProxies.isEmpty()) {
            return remoteAddress;
        }
        try {
            String peer = remoteAddress;
            int scope = peer == null ? -1 : peer.indexOf("%");
            if (scope >= 0) {
                peer = peer.substring(0, scope);
            }
            if (!isTrusted(literalAddress(peer).getHostAddress())) {
                return remoteAddress;
            }
            Enumeration<String> headers = request.getHeaders(forwardedHeader);
            if (headers == null || !headers.hasMoreElements()) {
                return remoteAddress;
            }
            StringBuilder chain = new StringBuilder();
            while (headers.hasMoreElements()) {
                String value = headers.nextElement();
                int delimiter = chain.length() == 0 ? 0 : 1;
                if (value == null || value.isBlank()
                        || value.length() > maxHeaderBytes - chain.length() - delimiter) {
                    throw new IllegalArgumentException("Invalid header length");
                }
                for (int index = 0; index < value.length(); index++) {
                    char character = value.charAt(index);
                    if (character > 0x7e || (character < 0x20 && character != '\t')) {
                        throw new IllegalArgumentException("Invalid header encoding");
                    }
                }
                if (delimiter != 0) {
                    chain.append(",");
                }
                chain.append(value);
            }
            int hopCount = 1;
            for (int index = 0; index < chain.length(); index++) {
                if (chain.charAt(index) == ',' && ++hopCount > maxHops) {
                    throw new IllegalArgumentException("Too many proxy hops");
                }
            }
            if ("X-Real-IP".equalsIgnoreCase(forwardedHeader) && hopCount != 1) {
                throw new IllegalArgumentException("X-Real-IP requires a single address");
            }
            String[] hops = chain.toString().split(",", -1);
            for (int index = 0; index < hops.length; index++) {
                hops[index] = literalAddress(hops[index].trim()).getHostAddress();
            }
            for (int index = hops.length - 1; index >= 0; index--) {
                if (!isTrusted(hops[index]) || index == 0) {
                    return hops[index];
                }
            }
            throw new IllegalArgumentException("Empty client address chain");
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid forwarded client address");
        }
    }

    private boolean isTrusted(String address) {
        return trustedProxies.stream().anyMatch(proxy -> proxy.matches(address));
    }

    private static InetAddress literalAddress(String value) {
        if (value == null || value.isEmpty() || value.length() > 45) {
            throw new IllegalArgumentException("An IP literal is required");
        }
        try {
            if (value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.]+")) {
                return InetAddress.getByName(value);
            }
            String[] octets = value.split("[.]", -1);
            if (octets.length != 4) {
                throw new IllegalArgumentException("An IPv4 or IPv6 literal is required");
            }
            byte[] address = new byte[4];
            for (int index = 0; index < octets.length; index++) {
                if (!octets[index].matches("0|[1-9][0-9]{0,2}")) {
                    throw new IllegalArgumentException("Invalid IPv4 octet");
                }
                int octet = Integer.parseInt(octets[index]);
                if (octet > 255) {
                    throw new IllegalArgumentException("Invalid IPv4 octet");
                }
                address[index] = (byte) octet;
            }
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Invalid IP literal", exception);
        }
    }
}
