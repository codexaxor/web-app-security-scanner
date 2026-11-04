package com.company.secscan.signup;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * M8.10 item 4 (D-098): sign-up is limited to 5 attempts per client IP per 15 minutes → 429 with Retry-After; a
 * different client IP is unaffected. The test client connects from loopback, a trusted proxy by default, so the
 * client IP is taken from {@code X-Forwarded-For} (as behind the ui nginx).
 */
class SignUpRateLimitIT extends AbstractApiServerIT {

    @LocalServerPort
    int port;
    final TestRestTemplate rest = HttpSession.noRetryClient();   // never sleep on Retry-After
    @Autowired
    SignupRequestRepository requests;

    private ResponseEntity<String> signUpFrom(String ip, int n) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", ip);
        return HttpSession.postJson(rest, "http://localhost:" + port + "/api/v1/auth/signup",
                "{\"email\":\"user" + n + "@acme.test\",\"password\":\"a-good-long-password\","
                        + "\"workspaceName\":\"Acme\"}", h);
    }

    @Test
    void theSixthSignUpFromOneIpIsA429AndAnotherIpIsUnaffected() {
        for (int i = 0; i < 5; i++) {
            assertThat(signUpFrom("203.0.113.7", i).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        ResponseEntity<String> sixth = signUpFrom("203.0.113.7", 5);
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(HttpSession.field(sixth.getBody(), "error")).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(sixth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 900L);
        assertThat(requests.findByEmailAndStatus("user5@acme.test", SignupRequest.STATUS_PENDING)).isEmpty();

        assertThat(signUpFrom("198.51.100.9", 6).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void invalidAttemptsCountToo() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", "203.0.113.8");
        for (int i = 0; i < 5; i++) {
            HttpSession.postJson(rest, "http://localhost:" + port + "/api/v1/auth/signup",
                    "{\"email\":\"bad\",\"password\":\"x\",\"workspaceName\":\"\"}", h);
        }
        assertThat(signUpFrom("203.0.113.8", 1).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
