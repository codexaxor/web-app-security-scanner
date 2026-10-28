package com.company.secscan.source.github;

import com.company.secscan.source.CredentialStatus;
import com.company.secscan.source.SourceBranch;
import com.company.secscan.source.SourceConnectionRef;
import com.company.secscan.source.SourcePage;
import com.company.secscan.source.SourceProvider;
import com.company.secscan.source.SourceRepo;
import com.company.secscan.source.github.dto.GitHubBranch;
import com.company.secscan.source.github.dto.GitHubRepo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The GitHub {@link SourceProvider} (github.com; GitHub Enterprise Server later). Uses a fine-grained PAT with
 * read-only Contents + Metadata (D-085); for a public ({@code credential_kind=none}) connection the token is
 * null and calls are anonymous. Identity is GitHub's stable numeric repository id (as text). Clone URLs never
 * carry the token (D-014). GitHub exposes no scopes for a fine-grained PAT and no branch-list search, so scopes
 * are empty and branch search is applied client-side on the fetched page.
 */
@Component
public class GitHubProvider implements SourceProvider {

    static final String ID = "github";

    // M8.8 S5b: GitHub has no server-side branch search, so for a search term we fetch branch pages and filter
    // in memory. To keep the picker responsive and bound the API cost, the repo metadata and the fetched branch
    // list are cached per repository (key = baseUrl|providerRepoId; repo identity is unique per host, so this is
    // effectively per-repo / per-workspace) for a short TTL, and the full fetch is capped.
    private static final int SEARCH_PAGE_SIZE = 100;         // GitHub's max per_page
    private static final int MAX_SEARCH_PAGES = 10;          // cap: scan at most 1,000 branches
    static final int SEARCH_CAP = SEARCH_PAGE_SIZE * MAX_SEARCH_PAGES;
    private static final long CACHE_TTL_MS = Duration.ofMinutes(5).toMillis();

    private final GitHubClient client;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** A per-repo cache entry: the repo metadata (always), and the full branch list once a search has fetched it. */
    private record Entry(long at, GitHubRepo repo, List<GitHubBranch> allBranches, boolean truncated) {
    }

    public GitHubProvider(GitHubClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public CredentialStatus verifyCredential(String baseUrl, String token) {
        if (token == null) {
            return CredentialStatus.anonymous();
        }
        GitHubClient.GitHubOwner user = client.currentUser(baseUrl, token);
        // A fine-grained PAT does not expose its scopes or expiry over the API.
        return new CredentialStatus(true, List.of(), null,
                "Token verified" + (user.login() != null ? " for " + user.login() + "." : "."));
    }

    @Override
    public SourcePage<SourceRepo> listRepositories(SourceConnectionRef connection, String token, String search,
                                                   int page, int size) {
        if (connection.isSingleRepo()) {
            return new SourcePage<>(List.of(getRepository(connection.baseUrl(), token,
                    connection.providerRepoId())), 1, 1);
        }
        boolean org = client.owner(connection.baseUrl(), token, connection.ownerLogin()).isOrganization();
        SourcePage<GitHubRepo> raw = client.listOwnerRepos(connection.baseUrl(), token, connection.ownerLogin(),
                org, page + 1, size);
        List<SourceRepo> items = raw.items().stream()
                .filter(r -> matches(r.fullName(), search))
                .map(GitHubProvider::toRepo)
                .toList();
        return new SourcePage<>(items, raw.totalElements(), raw.totalPages());
    }

    @Override
    public SourceRepo getRepository(String baseUrl, String token, String providerRepoId) {
        return toRepo(client.repoById(baseUrl, token, Long.parseLong(providerRepoId)));
    }

    @Override
    public SourcePage<SourceBranch> listBranches(String baseUrl, String token, String providerRepoId,
                                                 String search, int page, int size) {
        String key = baseUrl + "|" + providerRepoId;
        Entry entry = freshEntry(key, baseUrl, token, providerRepoId);   // repo metadata (cached, ≤5 min)
        GitHubRepo repo = entry.repo();
        boolean hasSearch = search != null && !search.isBlank();

        if (!hasSearch) {
            // No search: one GitHub page per picker page (no full fetch). Serve from the cached full list if a
            // prior search already fetched it.
            if (entry.allBranches() != null) {
                return pageOf(filterAndMap(entry.allBranches(), null, repo), page, size, entry.truncated());
            }
            SourcePage<GitHubBranch> raw = client.listBranches(baseUrl, token, repo.fullName(), page + 1, size);
            List<SourceBranch> items = raw.items().stream().map(b -> toBranch(b, repo)).toList();
            return new SourcePage<>(items, raw.totalElements(), raw.totalPages(), false);
        }

        // Search: serve from the cached full list if present; else try the exact-match fast path (1 call), then
        // fall back to a capped full fetch (cached for the next search).
        if (entry.allBranches() == null) {
            GitHubBranch exact = client.getBranch(baseUrl, token, repo.fullName(), search);
            if (exact != null) {
                return new SourcePage<>(List.of(toBranch(exact, repo)), 1, 1, false);
            }
            FetchResult fetched = fetchAllBranches(baseUrl, token, repo.fullName());
            entry = new Entry(entry.at(), repo, fetched.branches(), fetched.truncated());
            cache.put(key, entry);
        }
        return pageOf(filterAndMap(entry.allBranches(), search, repo), page, size, entry.truncated());
    }

    private Entry freshEntry(String key, String baseUrl, String token, String providerRepoId) {
        Entry cached = cache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_TTL_MS) {
            return cached;
        }
        GitHubRepo repo = client.repoById(baseUrl, token, Long.parseLong(providerRepoId));
        Entry fresh = new Entry(System.currentTimeMillis(), repo, null, false);
        cache.put(key, fresh);
        return fresh;
    }

    private record FetchResult(List<GitHubBranch> branches, boolean truncated) {
    }

    /** Fetches up to {@link #MAX_SEARCH_PAGES} pages of {@link #SEARCH_PAGE_SIZE}; flags truncation past the cap. */
    private FetchResult fetchAllBranches(String baseUrl, String token, String fullName) {
        List<GitHubBranch> all = new ArrayList<>();
        for (int p = 1; p <= MAX_SEARCH_PAGES; p++) {
            SourcePage<GitHubBranch> raw = client.listBranches(baseUrl, token, fullName, p, SEARCH_PAGE_SIZE);
            all.addAll(raw.items());
            if (raw.items().size() < SEARCH_PAGE_SIZE) {
                return new FetchResult(all, false);           // reached the last page within the cap
            }
            if (p == MAX_SEARCH_PAGES && raw.totalPages() > MAX_SEARCH_PAGES) {
                return new FetchResult(all, true);            // more branches exist beyond the 1,000 scanned
            }
        }
        return new FetchResult(all, false);
    }

    private static List<SourceBranch> filterAndMap(List<GitHubBranch> branches, String search, GitHubRepo repo) {
        return branches.stream()
                .filter(b -> matches(b.name(), search))
                .map(b -> toBranch(b, repo))
                .toList();
    }

    private static SourcePage<SourceBranch> pageOf(List<SourceBranch> all, int page, int size, boolean truncated) {
        int safeSize = Math.max(1, size);
        int from = Math.min(Math.max(0, page) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        int totalPages = (int) Math.ceil((double) all.size() / safeSize);
        return new SourcePage<>(all.subList(from, to), all.size(), Math.max(totalPages, 1), truncated);
    }

    private static SourceBranch toBranch(GitHubBranch b, GitHubRepo repo) {
        return new SourceBranch(b.name(), b.name().equals(repo.defaultBranch()), b.isProtected(), null);
    }

    @Override
    public boolean branchExists(String baseUrl, String token, String providerRepoId, String branch) {
        GitHubRepo repo = client.repoById(baseUrl, token, Long.parseLong(providerRepoId));
        return client.branchExists(baseUrl, token, repo.fullName(), branch);
    }

    @Override
    public String cloneUrl(String baseUrl, String providerRepoId, String repoFullName) {
        String base = baseUrl == null ? "https://github.com" : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + repoFullName + ".git";
    }

    private static SourceRepo toRepo(GitHubRepo r) {
        return new SourceRepo(Long.toString(r.id()), r.fullName(), r.defaultBranch(), r.reportedVisibility(),
                r.isEmpty(), r.notSelectableReason());
    }

    private static boolean matches(String value, String search) {
        return search == null || search.isBlank()
                || (value != null && value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)));
    }
}
