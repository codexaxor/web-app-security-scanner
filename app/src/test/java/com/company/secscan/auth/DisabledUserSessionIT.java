package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * D-098: disabling a user ends their LIVE session on the very next request (session invalidated → JSON 401,
 * which stays 401 even after re-enabling — the old session is gone) and refuses their Basic requests.
 */
class DisabledUserSessionIT extends AbstractApiServerIT {

    static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    AppUserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JdbcTemplate jdbc;

    UUID userId;

    @BeforeEach
    void seed() {
        userId = users.save(new AppUser(Workspace.DEFAULT_ID, "user@test.local", encoder.encode(PASSWORD),
                "admin")).getId();
    }

    @Test
    void disablingEndsALiveSessionOnItsNextRequest() {
        HttpSession session = HttpSession.browser(rest, port, "user@test.local", PASSWORD);
        assertThat(session.get("/api/v1/repositories").getStatusCode()).isEqualTo(HttpStatus.OK);

        setStatus("disabled");
        var refused = session.get("/api/v1/repositories");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refused.getBody()).contains("\"error\":\"UNAUTHORIZED\"");

        setStatus("active");
        assertThat(session.get("/api/v1/repositories").getStatusCode())
                .as("the invalidated session does not come back").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aDisabledUsersBasicRequestsAreRefused() {
        HttpSession basic = HttpSession.basic(rest, port, "user@test.local", PASSWORD);
        assertThat(basic.get("/api/v1/repositories").getStatusCode()).isEqualTo(HttpStatus.OK);

        setStatus("disabled");
        assertThat(basic.get("/api/v1/repositories").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aDeletedUsersSessionIsRefused() {
        HttpSession session = HttpSession.browser(rest, port, "user@test.local", PASSWORD);
        users.deleteById(userId);
        assertThat(session.get("/api/v1/repositories").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private void setStatus(String status) {
        jdbc.update("update app_user set status = ? where id = ?", status, userId);
    }
}
