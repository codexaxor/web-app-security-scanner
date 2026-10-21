package com.company.secscan.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IgnoreRule;
import com.company.secscan.issue.IgnoreRuleRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** change 9: ResponseStatusException / validation errors become {error,message} with no stack trace / class
 *  name; the JSON 401 entrypoint (no WWW-Authenticate) is unchanged. */
@AutoConfigureMockMvc
class ApiErrorBodyIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

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
    @Autowired
    IgnoreRuleRepository ignoreRules;

    private String auth;

    @BeforeEach
    void seedAdmin() {
        appUsers.deleteAll();
        appUsers.save(new AppUser(WORKSPACE, "admin@test.local", encoder.encode("pw"), "admin"));
        auth = "Basic " + Base64.getEncoder()
                .encodeToString("admin@test.local:pw".getBytes(StandardCharsets.UTF_8));
    }

    private Repository seedRepo(long id) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), id, "svc/" + id, "main", List.of("Java")));
    }

    @Test
    void badSensitivityIsA400WithTheErrorMessageShapeAndNoStackTrace() throws Exception {
        Repository repo = seedRepo(9601L);
        mvc.perform(patch("/api/v1/repositories/{id}", repo.getId())
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sensitivity\":\"paranoid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void unknownScanIsA404WithTheErrorShape() throws Exception {
        mvc.perform(get("/api/v1/scans/{id}", UUID.randomUUID()).header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void deletingASecscanYmlRuleIsA409WithTheEditInRepoMessage() throws Exception {
        Repository repo = seedRepo(9602L);
        IgnoreRule rule = ignoreRules.save(new IgnoreRule(WORKSPACE, "cve", repo.getId(), "CVE-2025-0001",
                "offline only", IgnoreRule.CREATED_BY_SECSCAN_YML));
        mvc.perform(delete("/api/v1/ignore-rules/{id}", rule.getId()).header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString(".secscan.yml")));
    }

    @Test
    void unauthenticatedStaysAJson401WithoutWwwAuthenticate() throws Exception {
        mvc.perform(get("/api/v1/repositories"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }
}
