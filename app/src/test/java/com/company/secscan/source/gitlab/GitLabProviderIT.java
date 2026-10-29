package com.company.secscan.source.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.source.CredentialStatus;
import com.company.secscan.source.ProviderError;
import com.company.secscan.source.ProviderException;
import com.company.secscan.source.SourceBranch;
import com.company.secscan.source.SourcePage;
import com.company.secscan.source.SourceRepo;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The GitLab adapter over the read-only client: credential verify, repo/branch reads, clone URL, error mapping. */
class GitLabProviderIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";
    private static final long PROJECT_ID = 102L;

    @Autowired
    GitLabProvider provider;

    private GitLabWireMock gitlab;

    @BeforeEach
    void setUp() {
        gitlab = new GitLabWireMock().stubVersion();
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void verifyCredentialReportsScopesForAValidToken() {
        gitlab.stubTokenSelf("token-self.json");
        CredentialStatus status = provider.verifyCredential(gitlab.baseUrl(), TOKEN);
        assertThat(status.valid()).isTrue();
        assertThat(status.scopes()).isNotEmpty();
    }

    @Test
    void verifyCredentialMapsA401ToInvalidCredential() {
        gitlab.stubTokenSelfStatus(401);
        assertThatThrownBy(() -> provider.verifyCredential(gitlab.baseUrl(), TOKEN))
                .isInstanceOf(ProviderException.class)
                .extracting(e -> ((ProviderException) e).error())
                .isEqualTo(ProviderError.INVALID_CREDENTIAL);
    }

    @Test
    void getRepositoryReturnsTheProviderIdentity() {
        gitlab.stubProjectById(PROJECT_ID, GitLabWireMock.sample("project.json"));
        SourceRepo repo = provider.getRepository(gitlab.baseUrl(), TOKEN, "102");
        assertThat(repo.providerRepoId()).isEqualTo("102");
        assertThat(repo.fullName()).isNotBlank();
    }

    @Test
    void listBranchesIsZeroBasedAndCarriesTotals() {
        gitlab.stubBranches(PROJECT_ID,
                "[{\"name\":\"main\",\"default\":true,\"protected\":true,"
                        + "\"commit\":{\"committed_date\":\"2026-01-01T00:00:00Z\"}}]",
                1, 1);
        SourcePage<SourceBranch> page = provider.listBranches(gitlab.baseUrl(), TOKEN, "102", null, 0, 50, "ws|conn");
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.items()).singleElement()
                .satisfies(b -> {
                    assertThat(b.name()).isEqualTo("main");
                    assertThat(b.isDefault()).isTrue();
                });
    }

    @Test
    void branchExistsIsTrueForAKnownBranchAndFalseForA404() {
        gitlab.stubBranch(PROJECT_ID, "main",
                "{\"name\":\"main\",\"default\":true,\"protected\":false}");
        gitlab.stubBranchStatus(PROJECT_ID, "gone", 404);
        assertThat(provider.branchExists(gitlab.baseUrl(), TOKEN, "102", "main")).isTrue();
        assertThat(provider.branchExists(gitlab.baseUrl(), TOKEN, "102", "gone")).isFalse();
    }

    @Test
    void cloneUrlIsHttpsAndCarriesNoToken() {
        String url = provider.cloneUrl("https://gitlab.example.com", "102", "acme/app");
        assertThat(url).isEqualTo("https://gitlab.example.com/acme/app.git");
        assertThat(url).doesNotContain(TOKEN).doesNotContain("@");
    }
}
