package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.signup.SignupRequest;
import com.company.secscan.signup.SignupRequestRepository;
import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * M8.10 item 4 / approval item 2 (D-098): failed sign-ins are limited per (account, IP) 5, per IP 20 and per
 * account 50 per 15 minutes — for the JSON login AND HTTP Basic, which share the counters. Once a key is over,
 * even the correct password gets 429 + Retry-After (no password check). A success clears its (account, IP) pair;
 * a correct password for a pending sign-up is not a failure. Client IPs come from X-Forwarded-For (loopback is a
 * trusted proxy by default).
 */
class LoginRateLimitIT extends AbstractApiServerIT {

    static final String PASSWORD = "correct-horse-battery";
    static final String WRONG = "wrong-password-123";

    @LocalServerPort
    int port;
    final TestRestTemplate rest = HttpSession.noRetryClient();   // a 429 must fail fast, never sleep
    @Autowired
    AppUserRepository users;
    @Autowired
    SignupRequestRepository signups;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        String hash = encoder.encode(PASSWORD);
        for (String u : new String[] {"alice", "bob", "carol", "dave", "erin", "frank"}) {
            users.save(new AppUser(Workspace.DEFAULT_ID, u + "@test.local", hash, "admin"));
        }
        signups.save(SignupRequest.pending("pending@test.local", hash, "Acme"));
    }

    @Test
    void perAccountAndIpFiveFailuresBlockThatPairOnly() {
        for (int i = 0; i < 5; i++) {
            assertThat(login("alice", WRONG, "203.0.113.1").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<String> blocked = login("alice", PASSWORD, "203.0.113.1");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(HttpSession.field(blocked.getBody(), "error")).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 900L);

        assertThat(login("alice", PASSWORD, "203.0.113.2").getStatusCode()).isEqualTo(HttpStatus.OK);  // other IP
        assertThat(login("bob", PASSWORD, "203.0.113.1").getStatusCode()).isEqualTo(HttpStatus.OK);    // other user
    }

    @Test
    void perIpTwentyFailuresBlockThatIpForEveryAccount() {
        String[] accounts = {"alice", "bob", "carol", "dave", "erin"};
        for (int i = 0; i < 20; i++) {                       // 4 per account: no pair reaches 5
            login(accounts[i % 5], WRONG, "203.0.113.3");
        }
        assertThat(login("frank", PASSWORD, "203.0.113.3").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login("frank", PASSWORD, "203.0.113.4").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void perAccountFiftyFailuresBlockThatAccountFromEveryIp() {
        for (int i = 0; i < 50; i++) {                       // 4 per IP: neither a pair nor an IP reaches its limit
            login("carol", WRONG, "198.51.100." + (i / 4 + 1));
        }
        assertThat(login("carol", PASSWORD, "198.51.100.200").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login("dave", PASSWORD, "198.51.100.200").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void basicSharesTheCountersAndAnswers429() {
        for (int i = 0; i < 5; i++) {
            assertThat(basic("dave", WRONG, "203.0.113.5").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<String> blocked = basic("dave", PASSWORD, "203.0.113.5");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(login("dave", PASSWORD, "203.0.113.5").getStatusCode())
                .as("the JSON login is blocked by Basic failures").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aSuccessClearsThePairAndCorrectPasswordsAreNotFailures() {
        for (int i = 0; i < 4; i++) {
            login("erin", WRONG, "203.0.113.6");
        }
        assertThat(login("erin", PASSWORD, "203.0.113.6").getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 4; i++) {
            login("erin", WRONG, "203.0.113.6");
        }
        assertThat(login("erin", PASSWORD, "203.0.113.6").getStatusCode()).isEqualTo(HttpStatus.OK);

        for (int i = 0; i < 7; i++) {   // correct password of a pending sign-up: always 403, never counted
            assertThat(login("pending", PASSWORD, "203.0.113.7").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    private ResponseEntity<String> login(String user, String password, String ip) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", ip);
        return HttpSession.postJson(rest, "http://localhost:" + port + "/api/v1/auth/login",
                "{\"username\":\"" + user + "@test.local\",\"password\":\"" + password + "\"}", h);
    }

    private ResponseEntity<String> basic(String user, String password, String ip) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", ip);
        h.add(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                .encodeToString((user + "@test.local:" + password).getBytes(StandardCharsets.UTF_8)));
        return rest.exchange("http://localhost:" + port + "/api/v1/repositories", HttpMethod.GET,
                new HttpEntity<>(h), String.class);
    }
}
