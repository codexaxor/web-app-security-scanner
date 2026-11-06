package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

/**
 * Approval item 3 (D-098): when the direct peer is NOT a trusted proxy, a spoofed {@code X-Forwarded-For} is
 * ignored — rotating the header cannot dodge the login limit, because every attempt is keyed by the real peer.
 * Here the trusted proxies exclude loopback, so the test client (127.0.0.1) is an untrusted peer.
 */
@TestPropertySource(properties = "SECSCAN_TRUSTED_PROXIES=10.255.255.0/24")
class ClientIpSpoofIT extends AbstractApiServerIT {

    static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    int port;
    final TestRestTemplate rest = HttpSession.noRetryClient();
    @Autowired
    AppUserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        users.save(new AppUser(Workspace.DEFAULT_ID, "victim@test.local", encoder.encode(PASSWORD), "admin"));
    }

    @Test
    void aSpoofedForwardedForFromAnUntrustedPeerIsIgnored() {
        for (int i = 0; i < 5; i++) {
            assertThat(loginFrom("203.0.113." + (i + 10), "wrong-password-123").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // A sixth "new" IP in the header is still the same real peer → the (account, IP) pair is blocked.
        assertThat(loginFrom("203.0.113.99", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<String> loginFrom(String spoofedIp, String password) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", spoofedIp);
        return HttpSession.postJson(rest, "http://localhost:" + port + "/api/v1/auth/login",
                "{\"username\":\"victim@test.local\",\"password\":\"" + password + "\"}", h);
    }
}
