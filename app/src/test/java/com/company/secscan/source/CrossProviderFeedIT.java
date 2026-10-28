package com.company.secscan.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitLabConnectionService;
import com.company.secscan.gitlab.GitLabHostPolicy;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
import com.company.secscan.issue.IssueService;
import com.company.secscan.issue.api.IssueGroupView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.repository.RepositoryService;
import com.company.secscan.source.github.GitHubClient;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitHubWireMock;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/**
 * M8.7 item 11 (automated portion): one workspace holds self-managed GitLab + a second GitLab host (gitlab.com)
 * + a private GitHub repo + a public repository (credential_kind none), and they share one feed (the Repositories
 * list). Each repository carries the right provider/host identity and its connection the right credential kind.
 * The real clone + mirror parity are the end-of-train manual walkthrough (D-086); here the four coexist with the
 * correct credential model. Cloning auth-user per provider is unit-covered by ScannerContainerConfigTest.
 */
class CrossProviderFeedIT extends AbstractGitLabIT {

    private static final UUID WS = Workspace.DEFAULT_ID;
    private static final String GL_TOKEN = "glpat-EXAMPLE-not-real";
    private static final String GH_TOKEN = "github_pat_EXAMPLE_not_real";

    @Autowired GitLabConnectionService gitlabConnections;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryService repositoryService;
    @Autowired RepositoryRepository repositories;
    @Autowired IssueService issueService;
    @Autowired IssueGroupRepository groups;
    @Autowired TokenCipherHolder cipher;
    @Autowired GitLabHostPolicy hostPolicy;
    @Autowired GitHubClient gitHubClient;
    @Autowired GitLabClient gitLabClient;

    private GitLabWireMock selfManaged;   // self-managed GitLab
    private GitLabWireMock dotCom;         // a second GitLab host ("gitlab.com")
    private GitLabWireMock publicGl;       // a public GitLab repo (anonymous)
    private GitHubWireMock github;         // a private GitHub repo

    @BeforeEach
    void setUp() {
        selfManaged = gitlab(201, "ns/a");
        dotCom = gitlab(202, "ns/b");
        publicGl = gitlab(303, "pub/proj");
        github = new GitHubWireMock().stubUser("octo", "User").stubOwner("octo", "Organization")
                .stubRepoById(123, "{\"id\":123,\"full_name\":\"octo/app\",\"default_branch\":\"main\","
                        + "\"private\":true,\"visibility\":\"private\",\"archived\":false,\"disabled\":false}");
    }

    @AfterEach
    void tearDown() {
        selfManaged.stop();
        dotCom.stop();
        publicGl.stop();
        github.stop();
    }

    private static GitLabWireMock gitlab(long id, String path) {
        String enc = path.replace("/", "%2F");
        String body = "{\"id\":" + id + ",\"path_with_namespace\":\"" + path + "\",\"default_branch\":\"main\","
                + "\"archived\":false,\"empty_repo\":false}";
        GitLabWireMock m = new GitLabWireMock().stubVersion().stubTokenSelf("token-self.json");
        m.stubProjectBody(enc, body);
        m.stubProjectById(id, body);
        m.stubLanguages(id, "{\"Java\":100.0}");
        return m;
    }

    /** Selects the four cross-provider sources into the one workspace (reused by both tests). */
    private void seedFourSources() {
        // Two GitLab hosts (self-managed + gitlab.com), token-credentialed.
        gitlabConnections.create(selfManaged.baseUrl(), GL_TOKEN, "project", "ns/a");
        gitlabConnections.create(dotCom.baseUrl(), GL_TOKEN, "project", "ns/b");
        repositoryService.select(projectConn(selfManaged, 201), List.of(201L));
        repositoryService.select(projectConn(dotCom, 202), List.of(202L));

        // A private GitHub repo (token) selected through its owner connection.
        SourceConnectionService gh = new SourceConnectionService(connections, cipher, hostPolicy, gitHubClient,
                gitLabClient, github.baseUrl());
        UUID ghConn = gh.createGitHubOwner("octo", GH_TOKEN).getId();
        repositoryService.selectSource(ghConn, List.of("123"));

        // A public repository added by URL (anonymous; credential_kind none) and selected.
        UUID pubConn = gh.createPublicRepo(publicGl.baseUrl() + "/pub/proj").getId();
        repositoryService.selectSource(pubConn, List.of("303"));
    }

    @Test
    void oneWorkspaceHoldsFourSourcesAcrossProvidersInOneFeed() {
        seedFourSources();

        List<Repository> repos = repositories.findByWorkspaceId(WS);
        assertThat(repos).hasSize(4);
        assertThat(repos).allMatch(Repository::isActive);

        // One feed, four sources: providers and hosts are distinct identities.
        Map<String, Long> byProvider = repos.stream().collect(
                java.util.stream.Collectors.groupingBy(Repository::getProvider, java.util.stream.Collectors.counting()));
        assertThat(byProvider).containsEntry("gitlab", 3L).containsEntry("github", 1L);
        assertThat(repos.stream().map(Repository::getHost).distinct().count()).isEqualTo(4);

        // The GitHub repo keeps GitHub's numeric id as identity and no GitLab id.
        Repository gitHubRepo = repos.stream().filter(r -> r.getProvider().equals("github")).findFirst().orElseThrow();
        assertThat(gitHubRepo.getProviderRepoId()).isEqualTo("123");
        assertThat(gitHubRepo.getGitlabProjectId()).isNull();

        // Credential kinds: three token connections, one public 'none' with no stored token.
        List<GitlabConnection> conns = connections.findByWorkspaceId(WS);
        assertThat(conns).hasSize(4);
        assertThat(conns.stream().filter(c -> c.getCredentialKind().equals("none"))).singleElement()
                .satisfies(c -> assertThat(c.getTokenCiphertext()).isNull());
        assertThat(conns.stream().filter(c -> c.getCredentialKind().equals("token")).count()).isEqualTo(3);
    }

    @Test
    void providerFilterNarrowsTheRepositoriesListAndFeed() {   // SF-1
        seedFourSources();
        List<Repository> repos = repositories.findByWorkspaceId(WS);
        Repository gitHubRepo = repos.stream().filter(r -> r.getProvider().equals("github")).findFirst().orElseThrow();
        Repository gitLabRepo = repos.stream().filter(r -> r.getProvider().equals("gitlab")).findFirst().orElseThrow();
        // An open issue group on one repo of each provider (so the feed has a row per provider).
        openGroup(gitHubRepo.getId(), "gh", "gh finding");
        openGroup(gitLabRepo.getId(), "gl", "gl finding");

        // Repositories list: the provider filter is server-side + workspace-scoped (gitlab 3, github 1, none 4).
        assertThat(repositoryService.listSelected(null, true, false, false, "github", "name", 0, 50).totalElements())
                .isEqualTo(1);
        assertThat(repositoryService.listSelected(null, true, false, false, "gitlab", "name", 0, 50).totalElements())
                .isEqualTo(3);
        assertThat(repositoryService.listSelected(null, true, false, false, null, "name", 0, 50).totalElements())
                .isEqualTo(4);

        // Feed (issue-groups): the provider filter narrows to that provider's groups.
        assertThat(issueService.listGroups(null, null, null, null, "github", "all", null, true, 0, 50).content())
                .extracting(IssueGroupView::title).containsExactly("gh finding");
        assertThat(issueService.listGroups(null, null, null, null, "gitlab", "all", null, true, 0, 50).content())
                .extracting(IssueGroupView::title).containsExactly("gl finding");

        // Validated whitelist: anything else is a 400 on both endpoints (never trusted unchecked).
        assertThatThrownBy(() -> repositoryService.listSelected(null, true, false, false, "bogus", "name", 0, 50))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> issueService.listGroups(null, null, null, null, "bogus", "all", null, true, 0, 50))
                .isInstanceOf(ResponseStatusException.class);
    }

    private void openGroup(UUID repoId, String key, String title) {
        IssueGroup g = new IssueGroup(WS, repoId, "sca", "sca|" + key, title, null);
        g.setSeverityScore(90);
        groups.save(g);
    }

    private UUID projectConn(GitLabWireMock host, long projectId) {
        return connections.findByWorkspaceIdAndProviderAndHostAndGitlabProjectId(
                WS, GitlabConnection.PROVIDER_GITLAB, SourceHosts.authorityOf(host.baseUrl()), projectId)
                .orElseThrow().getId();
    }
}
