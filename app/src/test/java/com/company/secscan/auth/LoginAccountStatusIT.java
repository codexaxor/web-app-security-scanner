package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.signup.SignupRequest;
import com.company.secscan.signup.SignupRequestRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * D-098 login semantics: a wrong password is the SAME 401 for every account state (active, disabled, pending
 * sign-up, unknown); only the CORRECT password reveals the state — 403 ACCOUNT_PENDING for a pending sign-up,
 * 403 ACCOUNT_INACTIVE for a disabled user — and HTTP Basic never reveals it (generic 401).
 */
class LoginAccountStatusIT extends AbstractApiServerIT {

    static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    AppUserRepository users;
    @Autowired
    SignupRequestRepository signups;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        users.save(new AppUser(Workspace.DEFAULT_ID, "active@test.local", encoder.encode(PASSWORD), "admin"));
        AppUser disabled = users.save(new AppUser(Workspace.DEFAULT_ID, "disabled@test.local",
                encoder.encode(PASSWORD), "admin"));
        jdbc.update("update app_user set status = 'disabled' where id = ?", disabled.getId());
        signups.save(SignupRequest.pending("pending@test.local", encoder.encode(PASSWORD), "Acme"));
    }

    @Test
    void aWrongPasswordIsTheSame401ForEveryAccountState() {
        ResponseEntity<String> active = login("active@test.local", "wrong-password-123");
        ResponseEntity<String> disabled = login("disabled@test.local", "wrong-password-123");
        ResponseEntity<String> pending = login("pending@test.local", "wrong-password-123");
        ResponseEntity<String> unknown = login("nobody@test.local", "wrong-password-123");

        for (ResponseEntity<String> r : java.util.List.of(active, disabled, pending, unknown)) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(r.getBody()).isEqualTo(active.getBody());
        }
        assertThat(active.getBody()).doesNotContain("ACCOUNT_");
    }

    @Test
    void theCorrectPasswordForAPendingSignUpIsA403AccountPending() {
        ResponseEntity<String> r = login("pending@test.local", PASSWORD);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(HttpSession.field(r.getBody(), "error")).isEqualTo("ACCOUNT_PENDING");
        assertThat(HttpSession.sessionCookie(r)).as("no session for a pending account").isNull();
    }

    @Test
    void theCorrectPasswordForADisabledUserIsA403AccountInactive() {
        ResponseEntity<String> r = login("Disabled@Test.local", PASSWORD);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(HttpSession.field(r.getBody(), "error")).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(HttpSession.sessionCookie(r)).isNull();
    }

    @Test
    void anActiveUserStillSignsIn() {
        assertThat(login("active@test.local", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void basicNeverRevealsTheAccountState() {
        ResponseEntity<String> pending = HttpSession.basic(rest, port, "pending@test.local", PASSWORD)
                .get("/api/v1/auth/session");
        ResponseEntity<String> disabled = HttpSession.basic(rest, port, "disabled@test.local", PASSWORD)
                .get("/api/v1/auth/session");
        ResponseEntity<String> wrong = HttpSession.basic(rest, port, "active@test.local", "wrong-password-123")
                .get("/api/v1/auth/session");

        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(pending.getBody()).isEqualTo(wrong.getBody());
        assertThat(disabled.getBody()).isEqualTo(wrong.getBody());
    }

    private ResponseEntity<String> login(String user, String password) {
        return HttpSession.login(rest, port, user, password);
    }
}
