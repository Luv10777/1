package com.wuyao.growth.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class ClientIpResolverTest {
    @Test void ignoresSpoofedHeadersFromUntrustedPeers() {
        var request = request("203.0.113.8", "192.0.2.1");
        assertThat(new ClientIpResolver("").resolve(request)).isEqualTo("203.0.113.8");
        assertThat(new ClientIpResolver("10.0.0.0/24").resolve(request)).isEqualTo("203.0.113.8");
    }
    @Test void trustsOnlyTheConfiguredRightmostProxyChain() {
        var resolver = new ClientIpResolver("10.0.0.0/24,::1/128");
        assertThat(resolver.resolve(request("10.0.0.1", "192.0.2.1,203.0.113.8,10.0.0.2")))
                .isEqualTo("203.0.113.8");
        assertThat(resolver.resolve(request("::1", "2001:db8::1"))).isEqualTo("2001:db8::1");
    }
    @Test void malformedAndOversizedHeadersFallBackToPeer() {
        var resolver = new ClientIpResolver("10.0.0.0/24");
        for (String value : java.util.List.of("evil.example", "999.999.999.999", "1.2.3.4,", " ", "1".repeat(2049))) {
            assertThat(resolver.resolve(request("10.0.0.1", value))).isEqualTo("10.0.0.1");
        }
    }
    private MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwarded); return request;
    }
}
