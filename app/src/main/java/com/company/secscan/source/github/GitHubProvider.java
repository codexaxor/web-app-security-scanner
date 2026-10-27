package com.company.secscan.source.github;

import com.company.secscan.source.CredentialStatus;
import com.company.secscan.source.SourceBranch;
import com.company.secscan.source.SourceConnectionRef;
import com.company.secscan.source.SourcePage;
import com.company.secscan.source.SourceProvider;
import com.company.secscan.source.SourceRepo;
import com.company.secscan.source.github.dto.GitHubBranch;
import com.company.secscan.source.github.dto.GitHubRepo;
import java.util.List;
import java.util.Locale;
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

    private final GitHubClient client;

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
        GitHubRepo repo = client.repoById(baseUrl, token, Long.parseLong(providerRepoId));
        SourcePage<GitHubBranch> raw = client.listBranches(baseUrl, token, repo.fullName(), page + 1, size);
        List<SourceBranch> items = raw.items().stream()
                .filter(b -> matches(b.name(), search))
                .map(b -> new SourceBranch(b.name(), b.name().equals(repo.defaultBranch()), b.isProtected(), null))
                .toList();
        return new SourcePage<>(items, raw.totalElements(), raw.totalPages());
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
