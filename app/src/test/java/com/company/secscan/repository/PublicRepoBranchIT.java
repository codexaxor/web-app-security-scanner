package com.company.secscan.repository;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.gitlab.GitLabError;
import com.company.secscan.gitlab.GitLabException;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.api.BranchView;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.scan.ScanService;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitHubWireMock;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.8 item 1 (the blocker): a public repository ({@code credential_kind='none'}, no stored token) must list
 * branches, validate + save a scan branch, and refresh its default branch at enqueue (A6) — WITHOUT decrypting
 * a (blank) ciphertext and WITHOUT sending any credential header. Proven for a public GitLab AND a public
 * GitHub connection, through the provider adapter (the paths that previously called the GitLab client with a raw
 * decrypt and crashed with "ciphertext must not be blank"). A5 (worker pre-clone) is {@code PublicRepoBranchMissingIT}.
 */
class PublicRepoBranchIT extends AbstractGitLabIT {

    private static final UUID WS = Workspace.DEFAULT_ID;

    @Autowired
    RepositoryService service;
    @Autowired
    ScanService scanService;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    private GitLabWireMock gitlab;
    private GitHubWireMock github;

    @AfterEach
    void stop() {
        if (gitlab != null) {
            gitlab.stop();
        }
        if (github != null) {
            github.stop();
        }
    }

    /** A public GitLab single-repo connection (no token) + its repository. */
    private Repository publicGitLabRepo(long projectId) {
        gitlab = new GitLabWireMock();
        GitlabConnection conn = connections.save(GitlabConnection.forSingleRepo(
                WS, gitlab.baseUrl(), GitlabConnection.PROVIDER_GITLAB, GitlabConnection.CREDENTIAL_NONE,
                Long.toString(projectId), "pub/" + projectId, null, null, null));
        return repositories.save(new Repository(WS, conn.getId(), projectId, "pub/" + projectId, "main",
                List.of("Java")));
    }

    /** A public GitHub single-repo connection (no token) + its repository (GitHub numeric id, no GitLab id). */
    private Repository publicGitHubRepo(long repoId, String fullName) {
        github = new GitHubWireMock();
        GitlabConnection conn = connections.save(GitlabConnection.forSingleRepo(
                WS, github.baseUrl(), GitlabConnection.PROVIDER_GITHUB, GitlabConnection.CREDENTIAL_NONE,
                Long.toString(repoId), fullName, null, null, null));
        return repositories.save(Repository.github(WS, conn.getId(), repoId, fullName, "main",
                List.of("TypeScript")));
    }

    @Test
    void listsBranchesForAPublicGitLabRepoWithoutATokenHeader() {
        Repository repo = publicGitLabRepo(8201L);
        gitlab.stubBranches(8201L,
                "[{\"name\":\"main\",\"default\":true,\"protected\":true},"
                        + "{\"name\":\"dev\",\"default\":false,\"protected\":false}]", 2, 1);

        var page = service.listBranches(repo.getId(), null, 0, 50);

        assertThat(page.content()).extracting(BranchView::name).containsExactly("main", "dev");
        assertThat(page.content().get(0).isDefault()).isTrue();
        // No credential was decrypted or sent: GitLab's PRIVATE-TOKEN header is absent on the branches call.
        gitlab.server().verify(getRequestedFor(urlPathEqualTo("/api/v4/projects/8201/repository/branches"))
                .withHeader("PRIVATE-TOKEN", absent()));
    }

    @Test
    void listsBranchesForAPublicGitHubRepoWithoutATokenHeader() {
        Repository repo = publicGitHubRepo(123L, "octo/pub");
        github.stubRepoById(123L, "{\"id\":123,\"full_name\":\"octo/pub\",\"default_branch\":\"main\","
                + "\"private\":false,\"visibility\":\"public\",\"archived\":false,\"disabled\":false}");
        github.stubBranches("octo/pub",
                "[{\"name\":\"main\",\"protected\":true},{\"name\":\"dev\",\"protected\":false}]");

        var page = service.listBranches(repo.getId(), null, 0, 100);

        assertThat(page.content()).extracting(BranchView::name).contains("main", "dev");
        assertThat(page.content().stream().filter(BranchView::isDefault)).singleElement()
                .satisfies(b -> assertThat(b.name()).isEqualTo("main"));
        // No Authorization header on the anonymous GitHub branches call.
        github.server().verify(getRequestedFor(urlPathEqualTo("/repos/octo/pub/branches"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void validatesAndSavesAScanBranchForAPublicRepoWithoutAToken() {
        Repository repo = publicGitLabRepo(8202L);
        gitlab.stubBranch(8202L, "dev", "{\"name\":\"dev\",\"default\":false,\"protected\":false}");

        RepositoryView view = service.patch(repo.getId(), null, null, null, new TextNode("dev"));

        assertThat(view.scanBranch()).isEqualTo("dev");
        assertThat(repositories.findById(repo.getId()).orElseThrow().getScanBranch()).isEqualTo("dev");
        gitlab.server().verify(getRequestedFor(urlPathEqualTo("/api/v4/projects/8202/repository/branches/dev"))
                .withHeader("PRIVATE-TOKEN", absent()));
    }

    @Test
    void rejectsAMissingScanBranchForAPublicRepoWith400() {
        Repository repo = publicGitLabRepo(8203L);
        gitlab.stubBranchStatus(8203L, "ghost", 404);

        assertThatThrownBy(() -> service.patch(repo.getId(), null, null, null, new TextNode("ghost")))
                .isInstanceOf(GitLabException.class)
                .satisfies(e -> assertThat(((GitLabException) e).getError()).isEqualTo(GitLabError.BRANCH_NOT_FOUND));
        // The branch stays unchanged (no save on a failed validation).
        assertThat(repositories.findById(repo.getId()).orElseThrow().getScanBranch()).isNull();
    }

    @Test
    void refreshesTheDefaultBranchAtEnqueueForAPublicRepoWithoutAToken() {   // A6
        Repository repo = publicGitLabRepo(8204L);
        gitlab.stubProjectById(8204L,
                "{\"id\":8204,\"path_with_namespace\":\"pub/8204\",\"default_branch\":\"release\","
                        + "\"empty_repo\":false}");

        ScanView scan = scanService.startManual(repo.getId());

        assertThat(scan.branch()).isEqualTo("release");
        assertThat(repositories.findById(repo.getId()).orElseThrow().getDefaultBranch()).isEqualTo("release");
        gitlab.server().verify(getRequestedFor(urlPathEqualTo("/api/v4/projects/8204"))
                .withHeader("PRIVATE-TOKEN", absent()));
    }
}
