package com.company.secscan.source.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.source.ProviderError;
import com.company.secscan.source.ProviderException;
import com.company.secscan.source.SourceBranch;
import com.company.secscan.source.SourcePage;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitHubWireMock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.8 S5b: GitHub branch search across all branches. GitHub has no server-side branch search, so the adapter
 * fetches branch pages (per_page=100, cap 10 pages), filters in memory, and caches the repo + branch list for 5
 * minutes. Verifies: a match beyond the first page is found; the exact-match fast path is one call; a repeat
 * search is served from cache (0 calls); past 1,000 branches the result is flagged truncated; a rate limit
 * mid-fetch surfaces as the transient RATE_LIMITED.
 */
class GitHubBranchSearchIT extends AbstractGitLabIT {

    private static final String REPO_JSON =
            "{\"id\":123,\"full_name\":\"octo/app\",\"default_branch\":\"main\",\"private\":false,"
                    + "\"visibility\":\"public\",\"archived\":false,\"disabled\":false}";

    @Autowired
    GitHubProvider provider;

    private GitHubWireMock gh;

    @BeforeEach
    void setUp() {
        provider.clearCache();
        gh = new GitHubWireMock().stubRepoById(123, REPO_JSON);
    }

    @AfterEach
    void tearDown() {
        gh.stop();
    }

    /** A JSON array of {@code count} branches named {@code <prefix>-<start..>}; one may be the {@code needle}. */
    private static String branchesJson(String prefix, int start, int count, String needle) {
        String body = IntStream.range(0, count)
                .mapToObj(i -> i == 0 && needle != null
                        ? "{\"name\":\"" + needle + "\",\"protected\":false}"
                        : "{\"name\":\"" + prefix + "-" + (start + i) + "\",\"protected\":false}")
                .collect(Collectors.joining(","));
        return "[" + body + "]";
    }

    private static String branchesJson(String prefix, int start, int count) {
        return branchesJson(prefix, start, count, null);
    }

    @Test
    void findsAMatchThatIsBeyondTheFirstPage() {
        // 250 branches over 3 pages; the match "release-candidate" sits on page 3.
        gh.stubBranchPage("octo/app", 1, branchesJson("feat", 1, 100), 3);
        gh.stubBranchPage("octo/app", 2, branchesJson("feat", 101, 100), 3);
        gh.stubBranchPage("octo/app", 3, branchesJson("release-candidate", 201, 50, "release-candidate"), 3);
        // No exact branch named "release" → 404 so the search falls through to the full fetch.
        gh.stubBranch("octo/app", "release", 404);

        SourcePage<SourceBranch> page = provider.listBranches(gh.baseUrl(), null, "123", "release", 0, 50, "ws|conn");

        assertThat(page.items()).extracting(SourceBranch::name).contains("release-candidate");
        assertThat(page.truncated()).isFalse();
    }

    @Test
    void theExactMatchFastPathIsASingleBranchCall() {
        // Warm the repo cache with a no-search page call, then reset the request journal.
        gh.stubBranchPage("octo/app", 1, branchesJson("feat", 1, 10), 1);
        provider.listBranches(gh.baseUrl(), null, "123", null, 0, 50, "ws|conn");
        gh.resetRequests();

        gh.stubBranch("octo/app", "main", 200);   // exact match exists
        SourcePage<SourceBranch> page = provider.listBranches(gh.baseUrl(), null, "123", "main", 0, 50, "ws|conn");

        assertThat(page.items()).extracting(SourceBranch::name).containsExactly("main");
        // Exactly one GitHub call (the exact-branch GET); the repo metadata came from cache.
        assertThat(gh.totalRequestCount()).isEqualTo(1);
    }

    @Test
    void aRepeatSearchWithinTheTtlMakesNoGitHubCalls() {
        gh.stubBranchPage("octo/app", 1, branchesJson("feat", 1, 100), 2);
        gh.stubBranchPage("octo/app", 2, branchesJson("feat", 101, 20, "zzz-target"), 2);
        gh.stubBranch("octo/app", "target", 404);

        SourcePage<SourceBranch> first = provider.listBranches(gh.baseUrl(), null, "123", "target", 0, 50, "ws|conn");
        assertThat(first.items()).extracting(SourceBranch::name).contains("zzz-target");

        gh.resetRequests();
        SourcePage<SourceBranch> second = provider.listBranches(gh.baseUrl(), null, "123", "target", 0, 50, "ws|conn");

        assertThat(second.items()).extracting(SourceBranch::name).contains("zzz-target");
        assertThat(gh.totalRequestCount()).isZero();   // served entirely from the 5-minute cache
    }

    @Test
    void flagsTruncatedPastTheThousandBranchCap() {
        // 10 full pages of 100 with a Link rel="last" pointing past the cap (page 11) → truncated.
        for (int p = 1; p <= 10; p++) {
            gh.stubBranchPage("octo/app", p, branchesJson("feat", (p - 1) * 100 + 1, 100), 11);
        }
        gh.stubBranch("octo/app", "feat", 404);

        SourcePage<SourceBranch> page = provider.listBranches(gh.baseUrl(), null, "123", "feat", 0, 50, "ws|conn");

        assertThat(page.truncated()).isTrue();
        assertThat(page.items()).isNotEmpty();
    }

    @Test
    void branchExistsDoesAFreshLookupEvenWhenTheBranchIsStillInTheCache() {
        // Populate the branch-list cache with a search that includes "dev".
        gh.stubBranchPage("octo/app", 1, branchesJson("dev", 1, 10, "dev"), 1);
        gh.stubBranch("octo/app", "dev-search", 404);
        provider.listBranches(gh.baseUrl(), null, "123", "dev-search", 0, 50, "ws|conn"); // caches the full list incl. "dev"

        // "dev" is in the cached list, but upstream it has just been deleted (exact GET → 404).
        gh.stubBranch("octo/app", "dev", 404);
        assertThat(provider.branchExists(gh.baseUrl(), null, "123", "dev")).isFalse();
    }

    @Test
    void aSecondWorkspaceOrConnectionIsNotServedAnothersCachedEntry() {   // SF-1
        gh.stubBranchPage("octo/app", 1, branchesJson("feat", 1, 20, "zzz-target"), 1);
        gh.stubBranch("octo/app", "target", 404);

        // Workspace/connection A (with a token) caches the full branch list for repo 123.
        provider.listBranches(gh.baseUrl(), "github_pat_A", "123", "target", 0, 50, "wsA|connA");
        gh.resetRequests();

        // A different workspace/connection B (public, anonymous) requests the SAME repo id → must NOT be served
        // A's cached entry (which was fetched with a token); it does its own fetch.
        provider.listBranches(gh.baseUrl(), null, "123", "target", 0, 50, "wsB|connB");
        assertThat(gh.totalRequestCount()).isPositive();
    }

    @Test
    void aRateLimitMidFetchSurfacesAsTransientRateLimited() {
        gh.stubBranchPage("octo/app", 1, branchesJson("feat", 1, 100), 5);
        gh.stubBranchPageStatus("octo/app", 2, 429, "Retry-After", "60");   // rate limited on page 2
        gh.stubBranch("octo/app", "feat", 404);

        assertThatThrownBy(() -> provider.listBranches(gh.baseUrl(), null, "123", "feat", 0, 50, "ws|conn"))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> {
                    ProviderException pe = (ProviderException) e;
                    assertThat(pe.error()).isEqualTo(ProviderError.RATE_LIMITED);
                    assertThat(pe.isTransient()).isTrue();
                });
    }
}
