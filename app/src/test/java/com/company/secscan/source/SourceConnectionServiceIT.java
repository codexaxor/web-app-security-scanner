package com.company.secscan.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitLabHostPolicy;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.source.github.GitHubClient;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitHubWireMock;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Provider-neutral connection CREATE for GitHub (owner/repo) and public repos (M8.7 items 4, 5). */
class SourceConnectionServiceIT extends AbstractGitLabIT {

    private static final String TOKEN = "github_pat_EXAMPLE_not_real";
    private static final String REPO_JSON =
            "{\"id\":123,\"full_name\":\"octo/app\",\"default_branch\":\"main\",\"private\":true,"
                    + "\"visibility\":\"private\",\"archived\":false,\"disabled\":false,\"size\":10}";

    @Autowired GitlabConnectionRepository connections;
    @Autowired TokenCipherHolder cipher;
    @Autowired GitLabHostPolicy hostPolicy;
    @Autowired GitHubClient gitHubClient;
    @Autowired GitLabClient gitLabClient;

    private GitHubWireMock gh;

    @BeforeEach
    void setUp() {
        gh = new GitHubWireMock();
    }

    @AfterEach
    void tearDown() {
        gh.stop();
    }

    /** The service pointed at the WireMock GitHub (the client maps github.com → api.github.com in production). */
    private SourceConnectionService serviceFor(String gitHubBase) {
        return new SourceConnectionService(connections, cipher, hostPolicy, gitHubClient, gitLabClient, gitHubBase);
    }

    @Test
    void createsAGitHubOwnerConnectionWithAnEncryptedToken() {
        gh.stubUser("octo", "User").stubOwner("octo", "Organization");
        GitlabConnection c = serviceFor(gh.baseUrl()).createGitHubOwner("octo", TOKEN);
        assertThat(c.getProvider()).isEqualTo("github");
        assertThat(c.getTargetType()).isEqualTo("owner");
        assertThat(c.getCredentialKind()).isEqualTo("token");
        assertThat(c.getOwnerLogin()).isEqualTo("octo");
        assertThat(c.getTokenCiphertext()).isNotBlank();
        assertThat(c.getTokenCiphertext()).doesNotContain(TOKEN);   // encrypted at rest (D-014)
    }

    @Test
    void createsAGitHubSingleRepoConnectionResolvingItsNumericId() {
        gh.stubRepoById(123, REPO_JSON);
        gh.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/repos/octo/app"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(REPO_JSON)));
        GitlabConnection c = serviceFor(gh.baseUrl()).createGitHubRepo("octo/app", TOKEN);
        assertThat(c.getProvider()).isEqualTo("github");
        assertThat(c.getTargetType()).isEqualTo("repo");
        assertThat(c.getProviderRepoId()).isEqualTo("123");
        assertThat(c.getRepoFullName()).isEqualTo("octo/app");
    }

    @Test
    void rejectsADuplicateGitHubRepo() {
        gh.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/repos/octo/app"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(REPO_JSON)));
        SourceConnectionService service = serviceFor(gh.baseUrl());
        service.createGitHubRepo("octo/app", TOKEN);
        assertThatThrownBy(() -> service.createGitHubRepo("octo/app", TOKEN))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("already connected");
    }

    @Test
    void addsAPublicGitLabRepositoryAnonymouslyWithCredentialKindNone() {
        GitLabWireMock gitlab = new GitLabWireMock();
        try {
            gitlab.stubProject("group%2Fproj");   // GET /projects/<encoded path> → project.json (id 102)
            String url = gitlab.baseUrl() + "/group/proj";
            GitlabConnection c = serviceFor(gh.baseUrl()).createPublicRepo(url);
            assertThat(c.getProvider()).isEqualTo("gitlab");
            assertThat(c.getTargetType()).isEqualTo("repo");
            assertThat(c.getCredentialKind()).isEqualTo("none");
            assertThat(c.getTokenCiphertext()).isNull();
            assertThat(c.getProviderRepoId()).isEqualTo("102");
            assertThat(connections.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(1);
        } finally {
            gitlab.stop();
        }
    }
}
