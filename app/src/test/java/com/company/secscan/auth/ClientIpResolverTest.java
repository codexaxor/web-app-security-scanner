package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * D-098 client IP: {@code X-Forwarded-For} counts only when the direct peer is a trusted proxy
 * ({@code SECSCAN_TRUSTED_PROXIES}); a spoofed header from an untrusted peer is ignored; a malformed entry falls
 * back to the peer.
 */
class ClientIpResolverTest {

    private static MockHttpServletRequest from(String peer, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(peer);
        if (xff != null) {
            r.addHeader("X-Forwarded-For", xff);
        }
        return r;
    }

    @Test
    void theDefaultTrustsOnlyLoopback() {
        ClientIpResolver resolver = new ClientIpResolver("127.0.0.1/32,::1/128");
        assertThat(resolver.resolve(from("127.0.0.1", "203.0.113.5"))).isEqualTo("203.0.113.5");
        assertThat(resolver.resolve(from("172.30.0.4", "203.0.113.5"))).isEqualTo("172.30.0.4");
    }

    @Test
    void aSpoofedHeaderFromAnUntrustedPeerIsIgnored() {
        ClientIpResolver resolver = new ClientIpResolver("172.30.0.0/24");
        assertThat(resolver.resolve(from("198.51.100.20", "10.0.0.1"))).isEqualTo("198.51.100.20");
        assertThat(resolver.resolve(from("198.51.100.20", null))).isEqualTo("198.51.100.20");
    }

    @Test
    void aTrustedProxyTakesTheRightmostEntry() {
        ClientIpResolver resolver = new ClientIpResolver("172.30.0.0/24");
        // A client-supplied leftmost entry cannot choose the key: the proxy's own (rightmost) value wins.
        assertThat(resolver.resolve(from("172.30.0.4", "1.2.3.4, 203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(from("172.30.0.4", "2001:db8::7"))).isEqualTo("2001:db8::7");
    }

    @Test
    void aMalformedForwardedValueFallsBackToThePeer() {
        ClientIpResolver resolver = new ClientIpResolver("172.30.0.0/24");
        assertThat(resolver.resolve(from("172.30.0.4", "evil.example.com"))).isEqualTo("172.30.0.4");
        assertThat(resolver.resolve(from("172.30.0.4", "<script>"))).isEqualTo("172.30.0.4");
    }

    @Test
    void hostnamesAreRejectedInTheConfiguration() {
        assertThatThrownBy(() -> new ClientIpResolver("proxy.internal/32"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
