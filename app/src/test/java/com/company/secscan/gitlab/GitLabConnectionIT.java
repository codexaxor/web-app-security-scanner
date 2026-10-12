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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Connection-test / create / update behavior against mocked GitLab (group mode); failures are categorized. */
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
        gitlab = new GitLabWireMock();
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    private UUID groupConnectionId() {
        return connections.findByWorkspaceIdAndGitlabGroupId(Workspace.DEFAULT_ID, 42L).orElseThrow().getId();
    }

    @Test
    void successShowsVersionScopesAndExpiry() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "group", "services");

        assertThat(r.ok()).isTrue();
        assertThat(r.targetType()).isEqualTo("group");
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

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "group", "services");

        assertThat(r.ok()).isTrue();
        assertThat(r.expiringSoon()).isTrue();
    }

    @Test
    void invalidTokenIsReported() {
        gitlab.stubVersion().stubTokenSelfStatus(401).stubGroup("services");

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "group", "services");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.INVALID_TOKEN.name());
    }

    @Test
    void missingScopeIsReported() {
        gitlab.stubVersion().stubTokenSelf("token-self-missing-scope.json").stubGroup("services");

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "group", "services");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.MISSING_SCOPES.name());
        assertThat(r.message()).contains("read_repository");
    }

    @Test
    void expiredTokenIsReported() {
        gitlab.stubVersion().stubTokenSelf("token-self-expired.json").stubGroup("services");

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "group", "services");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.TOKEN_EXPIRED.name());
    }

    @Test
    void unreachableHostIsReported() {
        String deadUrl = gitlab.baseUrl();
        gitlab.stop();

        ConnectionTestResult r = service.test(deadUrl, TOKEN, "group", "services");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.UNREACHABLE.name());
    }

    @Test
    void createEncryptsTokenAndStoresConnection() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");

        ConnectionTestResult r = service.create(gitlab.baseUrl(), TOKEN, "group", "services");
        assertThat(r.ok()).isTrue();

        GitlabConnection saved = connections.findByWorkspaceIdAndGitlabGroupId(Workspace.DEFAULT_ID, 42L)
                .orElseThrow();
        assertThat(saved.isGroup()).isTrue();
        assertThat(saved.getLastVerifiedAt()).isNotNull();
        // The token is stored only as ciphertext, never in plaintext.
        assertThat(saved.getTokenCiphertext()).isNotEqualTo(TOKEN);
        assertThat(saved.getTokenCiphertext()).doesNotContain(TOKEN);
    }

    @Test
    void creatingTheSameGroupTwiceIsRejected() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");
        assertThat(service.create(gitlab.baseUrl(), TOKEN, "group", "services").ok()).isTrue();

        ConnectionTestResult second = service.create(gitlab.baseUrl(), TOKEN, "group", "services");

        assertThat(second.ok()).isFalse();
        assertThat(second.errorCode()).isEqualTo(GitLabError.CONNECTION_EXISTS.name());
        assertThat(connections.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(1);
    }

    @Test
    void updateRotatesTokenAndKeepsSelectedRepositories() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services")
                .stubProjectsFromSample(42L).stubLanguages(101, "{\"Java\":100.0}");
        service.create(gitlab.baseUrl(), TOKEN, "group", "services");
        UUID connId = groupConnectionId();
        repositoryService.select(connId, List.of(101L));
        String firstCiphertext = connections.findById(connId).orElseThrow().getTokenCiphertext();

        // Rotate the token on the same connection (same value here; a fresh IV still changes the ciphertext).
        ConnectionTestResult r = service.update(connId, gitlab.baseUrl(), TOKEN, "services");

        assertThat(r.ok()).isTrue();
        assertThat(connections.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(1); // updated in place
        assertThat(connections.findById(connId).orElseThrow().getTokenCiphertext())
                .isNotEqualTo(firstCiphertext);
        assertThat(repositories.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(1); // selection kept
    }

    @Test
    void updatingToADifferentTargetIsRejected() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");
        service.create(gitlab.baseUrl(), TOKEN, "group", "services"); // group id 42
        UUID connId = groupConnectionId();

        gitlab.stubGroupBody("other-group", "{\"id\":99,\"full_path\":\"other-group\"}");
        ConnectionTestResult r = service.update(connId, gitlab.baseUrl(), TOKEN, "other-group");

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.CONNECTION_TARGET_MISMATCH.name());
        // The existing connection is unchanged (still group 42).
        assertThat(connections.findById(connId).orElseThrow().getGitlabGroupId()).isEqualTo(42L);
    }
}
