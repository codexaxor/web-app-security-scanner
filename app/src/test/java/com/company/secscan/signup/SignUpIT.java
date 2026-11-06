package com.company.secscan.signup;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * M8.10 item 3 (D-093/D-098): the public sign-up endpoint. A new email becomes ONE pending request (no workspace,
 * no app_user); an existing account, an already-pending email and a new email all get the IDENTICAL 202 (no account
 * enumeration) and nothing about an existing account changes. A rejected email may sign up again. Input is
 * validated (password ≥ 2 chars (D-101) and ≤ 72 bytes, valid email, workspace name 1–80). JSON-only, no CSRF needed.
 */
class SignUpIT extends AbstractApiServerIT {

    static final String PASSWORD = "a-good-long-password";

    @LocalServerPort
    int port;
    final TestRestTemplate rest = HttpSession.noRetryClient();   // a 429 must fail fast, never sleep
    /** Each call comes from its own client IP so this class tests sign-up, not the per-IP limit. */
    final java.util.concurrent.atomic.AtomicInteger ip = new java.util.concurrent.atomic.AtomicInteger(1);
    @Autowired
    SignupRequestRepository requests;
    @Autowired
    AppUserRepository users;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    PasswordEncoder encoder;

    UUID operatorId;

    @BeforeEach
    void seed() {
        AppUser existing = new AppUser(Workspace.DEFAULT_ID, "existing@test.local", encoder.encode("original-pass-1"),
                "admin");
        existing.promoteToOperator();
        operatorId = users.save(existing).getId();
    }

    private ResponseEntity<String> signUp(String email, String password, String workspaceName) {
        return HttpSession.postJson(rest, "http://localhost:" + port + "/api/v1/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"workspaceName\":\""
                        + workspaceName + "\"}", forwardedFor("192.0.2." + ip.getAndIncrement()));
    }

    @Test
    void aNewEmailBecomesOnePendingRequestAndNothingElse() {
        long workspacesBefore = workspaces.count();
        long usersBefore = users.count();

        var res = signUp("New@Acme.test", PASSWORD, "  Acme  ");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        SignupRequest r = requests.findByEmailAndStatus("new@acme.test", SignupRequest.STATUS_PENDING).orElseThrow();
        assertThat(r.getRequestedWorkspaceName()).isEqualTo("Acme");
        assertThat(encoder.matches(PASSWORD, r.getPasswordHash())).isTrue();
        assertThat(workspaces.count()).isEqualTo(workspacesBefore);
        assertThat(users.count()).isEqualTo(usersBefore);
        // End to end: the correct password now says "awaiting approval".
        var login = HttpSession.login(rest, port, "new@acme.test", PASSWORD);
        assertThat(HttpSession.field(login.getBody(), "error")).isEqualTo("ACCOUNT_PENDING");
    }

    @Test
    void newExistingAndPendingEmailsGetTheIdenticalResponse() {
        var fresh = signUp("fresh@acme.test", PASSWORD, "Acme");
        var pendingAgain = signUp("FRESH@acme.test", "another-long-password", "Other");
        var existing = signUp("existing@test.local", PASSWORD, "Hijack");

        for (var r : java.util.List.of(pendingAgain, existing)) {
            assertThat(r.getStatusCode()).isEqualTo(fresh.getStatusCode());
            assertThat(r.getBody()).isEqualTo(fresh.getBody());
        }
        // Nothing about the existing account or the first pending request changed.
        assertThat(encoder.matches("original-pass-1",
                users.findByEmail("existing@test.local").orElseThrow().getPasswordHash())).isTrue();
        assertThat(requests.findByEmailAndStatus("existing@test.local", SignupRequest.STATUS_PENDING)).isEmpty();
        SignupRequest first = requests.findByEmailAndStatus("fresh@acme.test", SignupRequest.STATUS_PENDING)
                .orElseThrow();
        assertThat(first.getRequestedWorkspaceName()).isEqualTo("Acme");
        assertThat(encoder.matches(PASSWORD, first.getPasswordHash())).isTrue();
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    void aRejectedEmailMaySignUpAgain() {
        signUp("again@acme.test", PASSWORD, "Acme");
        SignupRequest r = requests.findByEmailAndStatus("again@acme.test", SignupRequest.STATUS_PENDING).orElseThrow();
        r.reject(operatorId, Instant.now());
        requests.save(r);

        assertThat(signUp("again@acme.test", PASSWORD, "Acme 2").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(requests.findByEmailAndStatus("again@acme.test", SignupRequest.STATUS_PENDING).orElseThrow()
                .getRequestedWorkspaceName()).isEqualTo("Acme 2");
    }

    @Test
    void invalidInputIsA400WithoutCreatingAnything() {
        assertInvalid(signUp("not-an-email", PASSWORD, "Acme"));
        assertInvalid(signUp("short@acme.test", "a", "Acme"));                       // D-101: 1 char < 2 min
        assertInvalid(signUp("long@acme.test", "é".repeat(37), "Acme"));             // 74 bytes > 72
        assertInvalid(signUp("blank@acme.test", PASSWORD, "   "));
        assertInvalid(signUp("wide@acme.test", PASSWORD, "x".repeat(81)));
        assertInvalid(signUp("\u00fcnicode@acme.test", PASSWORD, "Acme"));               // review N-3: ASCII only
        assertInvalid(signUp("reserved1@acme.test", PASSWORD, "Default"));              // review N-6
        assertInvalid(signUp("reserved2@acme.test", PASSWORD, "  dEfAuLt "));
        assertThat(requests.count()).isZero();
        assertThat(signUp("ok@acme.test", "ab", "x".repeat(80)).getStatusCode())    // D-101: 2 chars accepted
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(signUp("near@acme.test", PASSWORD, "Default Two").getStatusCode())
                .as("only the exact reserved name is refused").isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void itIsJsonOnlyAndNeedsNoCsrfOrSession() {
        HttpHeaders form = new HttpHeaders();
        form.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var res = rest.exchange("http://localhost:" + port + "/api/v1/auth/signup", HttpMethod.POST,
                new HttpEntity<>("email=a@b.test&password=a-good-long-password&workspaceName=x", form),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(HttpSession.sessionCookie(signUp("nosession@acme.test", PASSWORD, "Acme"))).isNull();
    }

    private static HttpHeaders forwardedFor(String clientIp) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", clientIp);
        return h;
    }

    private static void assertInvalid(ResponseEntity<String> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(HttpSession.field(res.getBody(), "error")).isEqualTo("VALIDATION_FAILED");
    }
}
