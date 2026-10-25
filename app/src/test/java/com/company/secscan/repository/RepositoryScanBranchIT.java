package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabException;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** D-076.2: PATCH scanBranch — validated by git ref-format AND existence in GitLab; null resets; 404/409 rules. */
class RepositoryScanBranchIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String BRANCH_JSON =
            "{\"name\":\"dev\",\"default\":false,\"protected\":false,\"commit\":{\"committed_date\":\"2026-10-20T10:00:00Z\"}}";

    @Autowired
    RepositoryService service;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    TokenCipherHolder cipher;

    private GitLabWireMock gitlab;

    @AfterEach
    void stopGitlab() {
        if (gitlab != null) {
            gitlab.stop();
        }
    }

    private Repository seedRepo(long projectId) {
        gitlab = new GitLabWireMock();
        String ciphertext = cipher.require().encrypt("glpat-test-token");
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, gitlab.baseUrl(), "svc-" + projectId, projectId, ciphertext,
                "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), projectId, "svc/" + projectId, "main",
                List.of("Java")));
    }

    @Test
    void setsAnExistingBranch() {
        Repository repo = seedRepo(7101L);
        gitlab.stubBranch(7101L, "dev", BRANCH_JSON);

        var view = service.patch(repo.getId(), null, null, null, TextNode.valueOf("dev"));

        assertThat(view.scanBranch()).isEqualTo("dev");
        assertThat(view.effectiveBranch()).isEqualTo("dev");
        assertThat(repositories.findById(repo.getId()).orElseThrow().getScanBranch()).isEqualTo("dev");
    }

    @Test
    void rejectsAnUnknownBranchWith400BranchNotFound() {
        Repository repo = seedRepo(7102L);
        gitlab.stubBranchStatus(7102L, "nope", 404);

        assertThatThrownBy(() -> service.patch(repo.getId(), null, null, null, TextNode.valueOf("nope")))
                .isInstanceOf(GitLabException.class)
                .satisfies(e -> assertThat(((GitLabException) e).getError().name()).isEqualTo("BRANCH_NOT_FOUND"));
    }

    @Test
    void rejectsAnInvalidBranchNameWithoutCallingGitLab() {
        Repository repo = seedRepo(7103L);
        // No GitLab stub: an invalid ref name must be refused before any network call.
        assertThatThrownBy(() -> service.patch(repo.getId(), null, null, null, TextNode.valueOf("-bad")))
                .isInstanceOf(GitLabException.class)
                .satisfies(e -> assertThat(((GitLabException) e).getError().name()).isEqualTo("BRANCH_NOT_FOUND"));
    }

    @Test
    void nullResetsToTheDefaultBranch() {
        Repository repo = seedRepo(7104L);
        gitlab.stubBranch(7104L, "dev", BRANCH_JSON);
        service.patch(repo.getId(), null, null, null, TextNode.valueOf("dev"));

        var view = service.patch(repo.getId(), null, null, null, NullNode.getInstance());

        assertThat(view.scanBranch()).isNull();
        assertThat(view.effectiveBranch()).isEqualTo("main");   // falls back to the GitLab default
    }

    @Test
    void returns404ForARepositoryOutsideTheWorkspace() {
        seedRepo(7105L);  // starts a GitLab mock so @AfterEach can stop it
        assertThatThrownBy(() -> service.patch(UUID.randomUUID(), null, null, null, TextNode.valueOf("dev")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void settingABranchOnAnInactiveRepositoryIs409() {
        Repository repo = seedRepo(7106L);
        repo.deactivate("DEACTIVATED_BY_USER");
        repositories.save(repo);

        assertThatThrownBy(() -> service.patch(repo.getId(), null, null, null, TextNode.valueOf("dev")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
