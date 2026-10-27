package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitLabHostPolicy;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.source.SourceConnectionService;
import com.company.secscan.source.SourceHosts;
import com.company.secscan.source.SourceRepo;
import com.company.secscan.source.github.GitHubClient;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitHubWireMock;
import com.company.secscan.api.PagedResponse;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Provider-neutral selection: a GitHub owner connection lists and selects repos as provider-neutral rows (V6c). */
class SourceSelectionIT extends AbstractGitLabIT {

    private static final String TOKEN = "github_pat_EXAMPLE_not_real";
    private static final String REPO_JSON =
            "{\"id\":123,\"full_name\":\"octo/app\",\"default_branch\":\"main\",\"private\":true,"
                    + "\"visibility\":\"private\",\"archived\":false,\"disabled\":false,\"size\":10}";

    @Autowired GitlabConnectionRepository connections;
    @Autowired TokenCipherHolder cipher;
    @Autowired GitLabHostPolicy hostPolicy;
    @Autowired GitHubClient gitHubClient;
    @Autowired GitLabClient gitLabClient;
    @Autowired RepositoryService repositoryService;
    @Autowired RepositoryRepository repositories;

    private GitHubWireMock gh;
    private UUID ownerConnId;

    @BeforeEach
    void setUp() {
        gh = new GitHubWireMock();
        gh.stubUser("octo", "User").stubOwner("octo", "Organization");
        SourceConnectionService svc = new SourceConnectionService(connections, cipher, hostPolicy, gitHubClient,
                gitLabClient, gh.baseUrl());
        ownerConnId = svc.createGitHubOwner("octo", TOKEN).getId();
    }

    @AfterEach
    void tearDown() {
        gh.stop();
    }

    @Test
    void listsAnOwnersRepositoriesThroughTheAdapter() {
        gh.stubOwnerReposPage(true, "octo", 1,
                "[{\"id\":1,\"full_name\":\"octo/a\",\"default_branch\":\"main\"},"
                        + "{\"id\":2,\"full_name\":\"octo/b\",\"default_branch\":\"main\"}]", 1);
        PagedResponse<SourceRepo> page = repositoryService.listSourceRepositories(ownerConnId, null, 0, 100);
        assertThat(page.content()).extracting(SourceRepo::fullName).containsExactly("octo/a", "octo/b");
    }

    @Test
    void selectsAGitHubRepositoryAsAProviderNeutralRow() {
        gh.stubRepoById(123, REPO_JSON);
        List<RepositoryView> selected = repositoryService.selectSource(ownerConnId, List.of("123"));
        assertThat(selected).hasSize(1);

        List<Repository> stored = repositories.findByWorkspaceId(Workspace.DEFAULT_ID);
        assertThat(stored).hasSize(1);
        Repository repo = stored.get(0);
        assertThat(repo.getProvider()).isEqualTo("github");
        assertThat(repo.getProviderRepoId()).isEqualTo("123");
        assertThat(repo.getGitlabProjectId()).isNull();                          // GitHub row: no GitLab id
        assertThat(repo.getHost()).isEqualTo(SourceHosts.authorityOf(gh.baseUrl()));
        assertThat(repo.getPathWithNamespace()).isEqualTo("octo/app");
        assertThat(repo.getConnectionId()).isEqualTo(ownerConnId);
        assertThat(repo.isActive()).isTrue();
    }
}
