package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M8.10 item 9: demo mode (D-091) is unchanged by tenancy and bound to the Default workspace. The demo user lives
 * in Default, is never the operator, cannot reach the operator API, sees Default's data and never another
 * tenant's, and sign-up cannot claim the demo username (it is not an email).
 */
@AutoConfigureMockMvc
class DemoModeTenancyIT extends AbstractGitLabIT {

    @DynamicPropertySource
    static void demoOn(DynamicPropertyRegistry registry) {
        registry.add("SECSCAN_DEMO_MODE", () -> "true");
        registry.add("SECSCAN_DEMO_USERNAME", () -> "demotenant");
        registry.add("SECSCAN_DEMO_PASSWORD", () -> "demo-pass");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository users;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void removeOtherTenant() {
        repositories.deleteAllInBatch();
        connections.deleteAllInBatch();
        jdbc.update("delete from workspace where id <> ?", Workspace.DEFAULT_ID);
    }

    private MockHttpSession demoLogin() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demotenant\",\"password\":\"demo-pass\"}"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    @Test
    void theDemoUserLivesInDefaultAndIsNeverTheOperator() throws Exception {
        AppUser demo = users.findByEmail("demotenant").orElseThrow();
        assertThat(demo.getWorkspaceId()).isEqualTo(Workspace.DEFAULT_ID);
        assertThat(demo.isOperator()).isFalse();
        String defaultName = workspaces.findById(Workspace.DEFAULT_ID).orElseThrow().getName();

        mvc.perform(get("/api/v1/auth/session").session(demoLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(true))
                .andExpect(jsonPath("$.workspaceName").value(defaultName))
                .andExpect(jsonPath("$.operator").value(false));
    }

    @Test
    void theDemoUserCannotReachTheOperatorApi() throws Exception {
        MockHttpSession s = demoLogin();
        mvc.perform(get("/api/v1/admin/sign-ups").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").session(s)).andExpect(status().isForbidden());
    }

    @Test
    void theDemoUserSeesDefaultDataAndNeverAnotherTenants() throws Exception {
        UUID other = workspaces.save(new Workspace("Other tenant")).getId();
        UUID defConn = connections.save(GitlabConnection.forProject(Workspace.DEFAULT_ID, "file:///tmp/demo-d",
                "d/app", 501L, "ct", "read_api,read_repository", null)).getId();
        UUID otherConn = connections.save(GitlabConnection.forProject(other, "file:///tmp/demo-o", "o/app", 502L,
                "ct", "read_api,read_repository", null)).getId();
        repositories.save(new Repository(Workspace.DEFAULT_ID, defConn, 501L, "d/default-demo-repo", "main",
                List.of("Java")));
        repositories.save(new Repository(other, otherConn, 502L, "o/other-tenant-repo", "main", List.of("Java")));

        String body = mvc.perform(get("/api/v1/repositories").session(demoLogin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("d/default-demo-repo").doesNotContain("o/other-tenant-repo");
    }

    @Test
    void signUpCannotClaimTheDemoUsername() throws Exception {
        // A non-email identifier is a format error (400, says nothing about existing accounts) and never becomes
        // a sign-up request, so nobody can sign up "as" the demo user.
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "198.51.100.77")
                        .content("{\"email\":\"demotenant\",\"password\":\"a-long-enough-password\","
                                + "\"workspaceName\":\"Demo clone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from signup_request where email = 'demotenant'",
                Long.class)).isZero();
        assertThat(users.findByEmail("demotenant").orElseThrow().getWorkspaceId()).isEqualTo(Workspace.DEFAULT_ID);
    }
}
