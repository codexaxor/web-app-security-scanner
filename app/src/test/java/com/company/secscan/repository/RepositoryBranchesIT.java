package com.company.secscan.repository;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.api.BranchView;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** D-076.3 / A3: the branch-picker endpoint — list with default/protected chips, server-side search, pagination. */
class RepositoryBranchesIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

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
    void listsBranchesWithDefaultAndProtectedFlagsAndTotals() {
        Repository repo = seedRepo(8101L);
        String body = "[{\"name\":\"main\",\"default\":true,\"protected\":true,"
                + "\"commit\":{\"committed_date\":\"2026-10-01T09:00:00Z\"}},"
                + "{\"name\":\"dev\",\"default\":false,\"protected\":false,"
                + "\"commit\":{\"committed_date\":\"2026-10-20T10:00:00Z\"}}]";
        gitlab.stubBranches(8101L, body, 2, 1);

        var pageResult = service.listBranches(repo.getId(), null, 0, 50);

        assertThat(pageResult.totalElements()).isEqualTo(2);
        assertThat(pageResult.content()).extracting(BranchView::name).containsExactly("main", "dev");
        BranchView main = pageResult.content().get(0);
        assertThat(main.isDefault()).isTrue();
        assertThat(main.isProtected()).isTrue();
        assertThat(main.lastCommitAt()).isEqualTo("2026-10-01T09:00:00Z");
        assertThat(pageResult.content().get(1).isDefault()).isFalse();
    }

    @Test
    void pagesBeyond100Branches() {
        Repository repo = seedRepo(8102L);
        // 100 branches on this page; GitLab reports 150 total over 2 pages (A3).
        String body = "[" + java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> "{\"name\":\"b" + i + "\",\"default\":false,\"protected\":false}")
                .collect(Collectors.joining(",")) + "]";
        gitlab.stubBranches(8102L, body, 150, 2);

        var pageResult = service.listBranches(repo.getId(), null, 0, 100);

        assertThat(pageResult.content()).hasSize(100);
        assertThat(pageResult.totalElements()).isEqualTo(150);
        assertThat(pageResult.totalPages()).isEqualTo(2);
    }

    @Test
    void passesSearchToGitLab() {
        Repository repo = seedRepo(8103L);
        gitlab.stubBranches(8103L, "[{\"name\":\"release/1.2\",\"default\":false,\"protected\":true}]", 1, 1);

        var pageResult = service.listBranches(repo.getId(), "release/", 0, 50);

        assertThat(pageResult.content()).extracting(BranchView::name).containsExactly("release/1.2");
        gitlab.server().verify(getRequestedFor(urlPathEqualTo("/api/v4/projects/8103/repository/branches"))
                .withQueryParam("search", com.github.tomakehurst.wiremock.client.WireMock.equalTo("release/")));
    }

    @Test
    void rejectsAnOverlongSearchWith400() {
        Repository repo = seedRepo(8104L);
        assertThatThrownBy(() -> service.listBranches(repo.getId(), "x".repeat(101), 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
