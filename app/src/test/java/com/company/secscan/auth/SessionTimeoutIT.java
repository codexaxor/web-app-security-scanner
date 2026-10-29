package com.company.secscan.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * D-092 session lifetimes, both enforced by {@link SessionTimeoutFilter}: an idle session (no request within the
 * idle window) AND a session older than the absolute cap are invalidated, so the next request is anonymous → 401.
 * Driven deterministically by ageing the session's timestamp attributes (no sleeps, no real clock wait).
 */
@AutoConfigureMockMvc
class SessionTimeoutIT extends AbstractGitLabIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository appUsers;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seedAdmin() {
        appUsers.deleteAll();
        appUsers.save(new AppUser(Workspace.DEFAULT_ID, "admin@test.local", encoder.encode("correct-horse"),
                "admin"));
    }

    private MockHttpSession signIn() throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin@test.local\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    @Test
    void anActiveSessionStaysSignedIn() throws Exception {
        MockHttpSession session = signIn();
        mvc.perform(get("/api/v1/auth/session").session(session)).andExpect(status().isOk());
    }

    @Test
    void anIdleSessionBeyond30MinutesIsInvalidated() throws Exception {
        MockHttpSession session = signIn();
        long longAgo = System.currentTimeMillis() - Duration.ofMinutes(31).toMillis();
        session.setAttribute(SessionTimeoutFilter.LAST_ACCESS, longAgo);

        mvc.perform(get("/api/v1/auth/session").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionOlderThanTheAbsolute8HourCapIsInvalidatedEvenIfActive() throws Exception {
        MockHttpSession session = signIn();
        long createdLongAgo = System.currentTimeMillis() - Duration.ofHours(8).plusMinutes(1).toMillis();
        session.setAttribute(SessionTimeoutFilter.CREATED_AT, createdLongAgo);
        // LAST_ACCESS is recent (active user) — the absolute cap must still apply.
        session.setAttribute(SessionTimeoutFilter.LAST_ACCESS, System.currentTimeMillis());

        mvc.perform(get("/api/v1/auth/session").session(session)).andExpect(status().isUnauthorized());
    }
}
