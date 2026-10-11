package com.company.secscan.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.api.ConnectionTestResult;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.repository.RepositoryService;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Connection-test behavior against mocked GitLab: success shows version + expiry; failures are categorized. */
class GitLabConnectionIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";

    @Autowired
    GitLabConnectionService service;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryService repositoryService;
    @Autowired
    RepositoryRepository repositories;

    private GitLabWireMock gitlab;

    @BeforeEach
    void setUp() {
        connections.deleteAll();
        gitlab = new GitLabWireMock();
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void successShowsVersionScopesAndExpiry() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "services");

        assertThat(r.ok()).isTrue();
        assertThat(r.gitlabVersion()).isEqualTo("16.11.1");
        assertThat(r.groupId()).isEqualTo(42L);
        assertThat(r.tokenScopes()).containsExactlyInAnyOrder("read_api", "read_repository");
        assertThat(r.tokenExpiresAt()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(r.expiringSoon()).isFalse();
    }

    @Test
    void warnsWhenTokenExpiresSoon() {
        String soon = LocalDate.now().plusDays(10).toString();
        gitlab.stubVersion()
                .stubTokenSelfBody("{\"active\":true,\"revoked\":false,"
                        + "\"scopes\":[\"read_api\",\"read_repository\"],\"expires_at\":\"" + soon + "\"}")
                .stubGroup("services");

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "services");

        assertThat(r.ok()).isTrue();
        assertThat(r.expiringSoon()).isTrue();
    }

    @Test
    void invalidTokenIsReported() {
        gitlab.stubVersion().stubTokenSelfStatus(401).stubGroup("services");

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "services");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.INVALID_TOKEN.name());
    }

    @Test
    void missingScopeIsReported() {
        gitlab.stubVersion().stubTokenSelf("token-self-missing-scope.json").stubGroup("services");

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "services");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.MISSING_SCOPES.name());
        assertThat(r.message()).contains("read_repository");
    }

    @Test
    void expiredTokenIsReported() {
        gitlab.stubVersion().stubTokenSelf("token-self-expired.json").stubGroup("services");

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "services");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.TOKEN_EXPIRED.name());
    }

    @Test
    void unreachableHostIsReported() {
        // Nothing stubbed and the server is stopped → connection refused.
        String deadUrl = gitlab.baseUrl();
        gitlab.stop();

        ConnectionTestResult r = service.test(deadUrl, TOKEN, "services");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.UNREACHABLE.name());
    }

    @Test
    void saveEncryptsTokenAndStoresConnection() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");

        ConnectionTestResult r = service.save(gitlab.baseUrl(), TOKEN, "services");
        assertThat(r.ok()).isTrue();

        GitlabConnection saved = connections.findByWorkspaceId(
                com.company.secscan.workspace.Workspace.DEFAULT_ID).orElseThrow();
        assertThat(saved.getGitlabGroupId()).isEqualTo(42L);
        // The token is stored only as ciphertext, never in plaintext.
        assertThat(saved.getTokenCiphertext()).isNotEqualTo(TOKEN);
        assertThat(saved.getTokenCiphertext()).doesNotContain(TOKEN);
    }

    @Test
    void resavingSameGroupRotatesTokenAndKeepsSelectedRepositories() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services")
                .stubProjectsFromSample(42L).stubLanguages(101, "{\"Java\":100.0}");
        service.save(gitlab.baseUrl(), TOKEN, "services");
        repositoryService.select(List.of(101L));
        String firstCiphertext = connections.findByWorkspaceId(Workspace.DEFAULT_ID).orElseThrow()
                .getTokenCiphertext();

        // Re-save the same group with a rotated token (same value here; a fresh IV still changes the ciphertext).
        ConnectionTestResult r = service.save(gitlab.baseUrl(), TOKEN, "services");

        assertThat(r.ok()).isTrue();
        assertThat(connections.findAll()).hasSize(1); // updated in place, not duplicated
        assertThat(connections.findByWorkspaceId(Workspace.DEFAULT_ID).orElseThrow().getTokenCiphertext())
                .isNotEqualTo(firstCiphertext);
        assertThat(repositories.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(1); // selection kept
    }

    @Test
    void savingADifferentGroupIsRejected() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");
        service.save(gitlab.baseUrl(), TOKEN, "services"); // group id 42

        gitlab.stubGroupBody("other-group",
                "{\"id\":99,\"full_path\":\"other-group\"}");
        ConnectionTestResult r = service.save(gitlab.baseUrl(), TOKEN, "other-group");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.CONNECTION_GROUP_MISMATCH.name());
        // The existing connection is unchanged.
        assertThat(connections.findByWorkspaceId(Workspace.DEFAULT_ID).orElseThrow().getGitlabGroupId())
                .isEqualTo(42L);
    }
}
