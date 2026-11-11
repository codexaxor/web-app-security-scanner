package com.company.secscan.source.github;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.source.CredentialStatus;
import com.company.secscan.source.ProviderError;
import com.company.secscan.source.ProviderException;
import com.company.secscan.source.SourceBranch;
import com.company.secscan.source.SourceConnectionRef;
import com.company.secscan.source.SourcePage;
import com.company.secscan.source.SourceRepo;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitHubWireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The GitHub adapter against a WireMock GitHub API (M8.7 item 4 + item 11 GitHub fixtures). */
class GitHubProviderIT extends AbstractGitLabIT {

    private static final String TOKEN = "github_pat_EXAMPLE_not_real";
    private static final String REPO_JSON =
            "{\"id\":123,\"full_name\":\"octo/app\",\"default_branch\":\"main\",\"private\":true,"
                    + "\"visibility\":\"private\",\"archived\":false,\"disabled\":false,\"size\":10}";

    @Autowired
    GitHubProvider provider;

    private GitHubWireMock gh;

    @BeforeEach
    void setUp() {
        provider.clearCache();
        gh = new GitHubWireMock();
    }

    @AfterEach
    void tearDown() {
        gh.stop();
    }

    private SourceConnectionRef ownerRef(String owner) {
        return new SourceConnectionRef("github", gh.baseUrl(), "localhost", "owner", null, owner, owner, null, null);
    }

    @Test
    void anAnonymousRateLimitOnThePublicBranchPickerIsTransientRateLimited() {   // M8.8 item 2
        // A public repository (token == null) hitting GitHub's unauthenticated rate limit: the branch picker
        // resolves the repo first, so a 403-with-exhausted-rate-limit there must map to the existing transient
        // RATE_LIMITED error — never a "repo gone" NOT_FOUND — and carry no Authorization header.
        gh.stubRepoByIdStatus(123, 403, "x-ratelimit-remaining", "0", "x-ratelimit-reset", "9999999999");
        assertThatThrownBy(() -> provider.listBranches(gh.baseUrl(), null, "123", null, 0, 100, "ws|conn"))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.error()).isEqualTo(ProviderError.RATE_LIMITED);
                    assertThat(pe.isTransient()).isTrue();
                });
        gh.server().verify(getRequestedFor(urlEqualTo("/repositories/123"))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.absent()));
    }

    @Test
    void verifyCredentialIsValidAndSendsTheTokenInTheAuthorizationHeaderNotTheUrl() {
        gh.stubUser("octo", "User");
        CredentialStatus status = provider.verifyCredential(gh.baseUrl(), TOKEN);
        assertThat(status.valid()).isTrue();
        // The token travels only in the Authorization header, never the URL (D-014).
        gh.server().verify(getRequestedFor(urlEqualTo("/user"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        gh.server().verify(getRequestedFor(urlEqualTo("/user")));
    }

    @Test
    void a401MapsToInvalidCredentialAndTheMessageHasNoToken() {
        gh.stubUserStatus(401);
        assertThatThrownBy(() -> provider.verifyCredential(gh.baseUrl(), TOKEN))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    assertThat(((ProviderException) e).error()).isEqualTo(ProviderError.INVALID_CREDENTIAL);
                    assertThat(e.getMessage()).doesNotContain(TOKEN);
                });
    }

    @Test
    void getRepositoryReturnsGitHubNumericIdentity() {
        gh.stubRepoById(123, REPO_JSON);
        SourceRepo repo = provider.getRepository(gh.baseUrl(), TOKEN, "123");
        assertThat(repo.providerRepoId()).isEqualTo("123");
        assertThat(repo.fullName()).isEqualTo("octo/app");
        assertThat(repo.visibility()).isEqualTo("private");
    }

    @Test
    void listRepositoriesPaginatesViaTheLinkHeader() {
        gh.stubOwner("octo", "User");
        gh.stubOwnerReposPage(false, "octo", 1,
                "[{\"id\":1,\"full_name\":\"octo/a\",\"default_branch\":\"main\"},"
                        + "{\"id\":2,\"full_name\":\"octo/b\",\"default_branch\":\"main\"}]", 3);
        SourcePage<SourceRepo> page = provider.listRepositories(ownerRef("octo"), TOKEN, null, 0, 100);
        assertThat(page.items()).extracting(SourceRepo::fullName).containsExactly("octo/a", "octo/b");
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void branchExistsIsTrueForAKnownBranchAndFalseForA404() {
        gh.stubRepoById(123, REPO_JSON);
        gh.stubBranch("octo/app", "main", 200);
        gh.stubBranch("octo/app", "gone", 404);
        assertThat(provider.branchExists(gh.baseUrl(), TOKEN, "123", "main")).isTrue();
        assertThat(provider.branchExists(gh.baseUrl(), TOKEN, "123", "gone")).isFalse();
    }

    @Test
    void listBranchesMarksTheDefaultBranch() {
        gh.stubRepoById(123, REPO_JSON);
        gh.stubBranches("octo/app",
                "[{\"name\":\"main\",\"protected\":true},{\"name\":\"dev\",\"protected\":false}]");
        SourcePage<SourceBranch> page = provider.listBranches(gh.baseUrl(), TOKEN, "123", null, 0, 100, "ws|conn");
        assertThat(page.items()).anySatisfy(b -> {
            assertThat(b.name()).isEqualTo("main");
            assertThat(b.isDefault()).isTrue();
        });
    }

    @Test
    void a429WithRetryAfterIsTransientRateLimited() {
        gh.stubUserStatus(429, "Retry-After", "77");
        assertThatThrownBy(() -> provider.verifyCredential(gh.baseUrl(), TOKEN))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.error()).isEqualTo(ProviderError.RATE_LIMITED);
                    assertThat(pe.isTransient()).isTrue();
                    assertThat(pe.retryAfterSeconds()).isEqualTo(77L);
                });
    }

    @Test
    void anExhaustedRateLimitOn403IsTransient() {
        gh.stubUserStatus(403, "x-ratelimit-remaining", "0", "x-ratelimit-reset", "9999999999");
        assertThatThrownBy(() -> provider.verifyCredential(gh.baseUrl(), TOKEN))
                .isInstanceOf(ProviderException.class)
                .extracting(e -> ((ProviderException) e).error())
                .isEqualTo(ProviderError.RATE_LIMITED);
    }

    @Test
    void a404MapsToNotFound() {
        gh.stubRepoByIdStatus(999, 404);
        assertThatThrownBy(() -> provider.getRepository(gh.baseUrl(), TOKEN, "999"))
                .isInstanceOf(ProviderException.class)
                .extracting(e -> ((ProviderException) e).error())
                .isEqualTo(ProviderError.NOT_FOUND);
    }

    @Test
    void aRedirectIsNotFollowed() {
        gh.stubRepoByIdStatus(123, 302, "Location", "https://evil.example.com/");
        assertThatThrownBy(() -> provider.getRepository(gh.baseUrl(), TOKEN, "123"))
                .isInstanceOf(ProviderException.class)
                .extracting(e -> ((ProviderException) e).error())
                .isEqualTo(ProviderError.UNEXPECTED_REDIRECT);
    }

    @Test
    void cloneUrlIsHttpsAndCarriesNoToken() {
        String url = provider.cloneUrl("https://github.com", "123", "octo/app");
        assertThat(url).isEqualTo("https://github.com/octo/app.git");
        assertThat(url).doesNotContain(TOKEN).doesNotContain("@");
    }
}
