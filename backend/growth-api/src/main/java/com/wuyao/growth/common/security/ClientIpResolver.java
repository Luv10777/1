package com.wuyao.growth.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;

/** Trust forwarding headers only when the socket peer is an explicitly configured proxy. */
@Component
public class ClientIpResolver {
    private final List<IpAddressMatcher> proxies;

    public ClientIpResolver(@Value("${growth.security.trusted-proxies:}") String trustedProxies) {
        proxies = Arrays.stream(trustedProxies.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(IpAddressMatcher::new).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!isTrusted(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || forwarded.length() > 2048) return peer;
        String[] chain = forwarded.split(",", -1);
        // Walk from the immediate proxy towards the client, ignoring spoofed entries on the left.
        for (int i = chain.length - 1; i >= 0 && isTrusted(peer); i--) {
            String candidate = chain[i].trim();
            if (!candidate.matches("[0-9a-fA-F:.]+")) return request.getRemoteAddr();
            try {
                new IpAddressMatcher(candidate); // validates a literal address; never resolves hostnames
            } catch (IllegalArgumentException e) {
                return request.getRemoteAddr();
            }
            peer = candidate;
        }
        return peer;
    }

    private boolean isTrusted(String address) {
        return address != null && proxies.stream().anyMatch(proxy -> proxy.matches(address));
    }
}
