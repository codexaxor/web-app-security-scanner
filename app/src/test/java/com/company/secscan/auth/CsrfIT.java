package com.company.secscan.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M8.8 SF-5 + CSRF nits. A cookie-authenticated unsafe request needs the CSRF token (→ 403 without it, 2xx with
 * it). A denial on a request with NO authenticated session (idle-expired) is the JSON 401, not 403. HTTP Basic
 * stays stateless and CSRF-exempt; the no-'@' lockout applies over Basic too; anonymous requests create no session.
 */
@AutoConfigureMockMvc
class CsrfIT extends AbstractGitLabIT {

    private static final UUID WS = Workspace.DEFAULT_ID;

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository appUsers;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    @BeforeEach
    void seedAdmin() {
        appUsers.deleteAll();
        appUsers.save(new AppUser(WS, "admin@test.local", encoder.encode("correct-horse"), "admin"));
    }

    private record Session(MockHttpSession session, String csrf) {
    }

    private Session login() throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin@test.local\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        int i = body.indexOf("\"csrfToken\":\"") + "\"csrfToken\":\"".length();
        String csrf = body.substring(i, body.indexOf('"', i));
        return new Session((MockHttpSession) res.getRequest().getSession(false), csrf);
    }

    private Repository seedRepo(long id) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WS, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WS, conn.getId(), id, "svc/" + id, "main", List.of("Java")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "PUT", "DELETE"})
    void aCookieAuthedUnsafeRequestWithoutTheCsrfTokenIs403(String method) throws Exception {
        Session s = login();
        UUID id = UUID.randomUUID();
        var req = switch (method) {
            case "PUT" -> put("/api/v1/gitlab/connections/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{}");
            case "DELETE" -> delete("/api/v1/gitlab/connections/{id}", id);
            default -> patch("/api/v1/repositories/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{}");
        };
        mvc.perform(req.session(s.session())).andExpect(status().isForbidden());
    }

    @Test
    void aCookieAuthedUnsafeRequestWithTheCsrfTokenSucceeds() throws Exception {
        Session s = login();
        Repository repo = seedRepo(7801L);
        mvc.perform(patch("/api/v1/repositories/{id}", repo.getId())
                        .session(s.session())
                        .header("X-CSRF-TOKEN", s.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sensitivity\":\"high\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void anIdleExpiredSessionOnAnUnsafeRequestIsA401NotA403() throws Exception {
        Session s = login();
        s.session().setAttribute(SessionTimeoutFilter.LAST_ACCESS,
                System.currentTimeMillis() - Duration.ofMinutes(31).toMillis());

        mvc.perform(patch("/api/v1/repositories/{id}", UUID.randomUUID())
                        .session(s.session())
                        .header("X-CSRF-TOKEN", s.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void logoutExpiresTheSessionCookie() throws Exception {
        Session s = login();
        mvc.perform(post("/api/v1/auth/logout")
                        .session(s.session())
                        .header("X-CSRF-TOKEN", s.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie",
                        Matchers.allOf(Matchers.containsString("SECSCAN_SESSION="), Matchers.containsString("Max-Age=0"))));
    }

    @Test
    void theNoAtLockoutAppliesOverBasicToo() throws Exception {
        // demo mode is off here → a non-email identifier is refused even over HTTP Basic.
        String basic = "Basic " + Base64.getEncoder().encodeToString("admin:correct-horse".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/repositories").header("Authorization", basic))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void basicPlusASessionCookieStaysStatelessAndSucceeds() throws Exception {
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString("admin@test.local:correct-horse".getBytes(StandardCharsets.UTF_8));
        MvcResult res = mvc.perform(get("/api/v1/repositories")
                        .header("Authorization", basic)
                        .session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andReturn();
        // The stateless Basic chain issues no session cookie.
        String setCookie = res.getResponse().getHeader("Set-Cookie");
        org.assertj.core.api.Assertions.assertThat(setCookie == null || !setCookie.contains("SECSCAN_SESSION")).isTrue();
    }

    @Test
    void anAnonymousRequestCreatesNoSession() throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/repositories")).andExpect(status().isUnauthorized()).andReturn();
        org.assertj.core.api.Assertions.assertThat(res.getRequest().getSession(false)).isNull();
        String setCookie = res.getResponse().getHeader("Set-Cookie");
        org.assertj.core.api.Assertions.assertThat(setCookie == null || !setCookie.contains("SECSCAN_SESSION")).isTrue();
    }
}
