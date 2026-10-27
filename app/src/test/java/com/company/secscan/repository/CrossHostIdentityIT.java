package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitLabConnectionService;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.source.SourceHosts;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.7 item 3: the same numeric GitLab project id on two different hosts is two distinct repositories, in one
 * workspace, one feed. Identity is (workspace, provider, host, provider_repo_id) — V22/D-080.
 */
class CrossHostIdentityIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";
    private static final long PROJECT_ID = 102L;
    private static final String PATH = "services/ib-keycloak";
    private static final String ENCODED = "services%2Fib-keycloak";

    @Autowired
    GitLabConnectionService connectionService;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryService repositoryService;
    @Autowired
    RepositoryRepository repositories;

    private GitLabWireMock hostA;
    private GitLabWireMock hostB;

    @BeforeEach
    void setUp() {
        hostA = stubbedHost();
        hostB = stubbedHost();
        connectionService.create(hostA.baseUrl(), TOKEN, "project", PATH);
        connectionService.create(hostB.baseUrl(), TOKEN, "project", PATH);
    }

    @AfterEach
    void tearDown() {
        hostA.stop();
        hostB.stop();
    }

    private static GitLabWireMock stubbedHost() {
        return new GitLabWireMock()
                .stubVersion()
                .stubTokenSelf("token-self.json")
                .stubProject(ENCODED)
                .stubLanguages(PROJECT_ID, "{\"Java\":100.0}");
    }

    private UUID projectConnId(GitLabWireMock host) {
        return connections.findByWorkspaceIdAndProviderAndHostAndGitlabProjectId(
                        Workspace.DEFAULT_ID, GitlabConnection.PROVIDER_GITLAB,
                        SourceHosts.authorityOf(host.baseUrl()), PROJECT_ID)
                .orElseThrow()
                .getId();
    }

    @Test
    void sameProjectIdOnTwoHostsYieldsTwoDistinctRepositories() {
        // Both hosts have a connection to "project 102"; neither create was rejected as a duplicate.
        assertThat(connections.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(2);

        repositoryService.select(projectConnId(hostA), List.of(PROJECT_ID));
        repositoryService.select(projectConnId(hostB), List.of(PROJECT_ID));

        List<Repository> stored = repositories.findByWorkspaceId(Workspace.DEFAULT_ID);
        assertThat(stored).hasSize(2);
        // Two rows, same provider_repo_id, distinct hosts — both active, one feed.
        assertThat(stored.stream().map(Repository::getProviderRepoId)).containsExactly("102", "102");
        assertThat(stored.stream().map(Repository::getHost).distinct())
                .containsExactlyInAnyOrder(SourceHosts.authorityOf(hostA.baseUrl()),
                        SourceHosts.authorityOf(hostB.baseUrl()));
        assertThat(stored).allMatch(Repository::isActive);
    }
}
