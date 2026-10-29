package com.company.secscan.source.gitlab;

import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitLabException;
import com.company.secscan.gitlab.dto.GitLabBranch;
import com.company.secscan.gitlab.dto.GitLabProject;
import com.company.secscan.gitlab.dto.GitLabTokenInfo;
import com.company.secscan.source.CredentialStatus;
import com.company.secscan.source.ProviderError;
import com.company.secscan.source.ProviderException;
import com.company.secscan.source.SourceBranch;
import com.company.secscan.source.SourceConnectionRef;
import com.company.secscan.source.SourcePage;
import com.company.secscan.source.SourceProvider;
import com.company.secscan.source.SourceRepo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The GitLab {@link SourceProvider}, delegating to the existing read-only {@link GitLabClient} and mapping its
 * {@link GitLabException}/{@code GitLabError} to the one provider-neutral {@link ProviderException} taxonomy
 * (M8.7 items 2, 3). Today's GitLab behaviour is unchanged — this is a thin adapter over the same client, so the
 * self-managed host keeps working exactly as before and gitlab.com/other hosts work through the same code.
 */
@Component
public class GitLabProvider implements SourceProvider {

    static final String ID = "gitlab";

    private final GitLabClient client;

    public GitLabProvider(GitLabClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public CredentialStatus verifyCredential(String baseUrl, String token) {
        if (token == null) {
            // No credential (should not happen for GitLab today) — confirm the host answers, report anonymous.
            map(() -> client.version(baseUrl, null));
            return CredentialStatus.anonymous();
        }
        GitLabTokenInfo info = map(() -> client.tokenInfo(baseUrl, token));
        boolean valid = info.active() && !info.revoked();
        String message = valid ? "Token verified." : (info.revoked() ? "Token revoked." : "Token inactive.");
        return new CredentialStatus(valid, info.scopes(), info.expiresAt(), message);
    }

    @Override
    public SourcePage<SourceRepo> listRepositories(SourceConnectionRef connection, String token, String search,
                                                   int page, int size) {
        if (connection.isGroup()) {
            List<GitLabProject> all = map(() ->
                    client.listGroupProjects(connection.baseUrl(), token, connection.targetId()));
            List<SourceRepo> matched = all.stream()
                    .filter(p -> !p.archived() && !p.markedForDeletion())
                    .filter(p -> search == null || search.isBlank()
                            || p.pathWithNamespace().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(GitLabProject::pathWithNamespace))
                    .map(GitLabProvider::toRepo)
                    .toList();
            return paginate(matched, page, size);
        }
        // A single project/repo connection exposes exactly one repository.
        SourceRepo one = getRepository(connection.baseUrl(), token, connection.providerRepoId());
        return paginate(List.of(one), page, size);
    }

    @Override
    public SourceRepo getRepository(String baseUrl, String token, String providerRepoId) {
        GitLabProject p = map(() -> client.getProject(baseUrl, token, Long.parseLong(providerRepoId)));
        return toRepo(p);
    }

    @Override
    public SourcePage<SourceBranch> listBranches(String baseUrl, String token, String providerRepoId,
                                                 String search, int page, int size, String cacheScope) {
        // GitLab searches server-side and does not cache, so cacheScope is unused here.
        // GitLab paging is 1-based; the contract is 0-based.
        GitLabClient.Paged<GitLabBranch> paged = map(() ->
                client.listBranches(baseUrl, token, Long.parseLong(providerRepoId), search, page + 1, size));
        List<SourceBranch> items = paged.items().stream().map(GitLabProvider::toBranch).toList();
        return new SourcePage<>(items, paged.totalElements(), paged.totalPages());
    }

    @Override
    public boolean branchExists(String baseUrl, String token, String providerRepoId, String branch) {
        try {
            client.getBranch(baseUrl, token, Long.parseLong(providerRepoId), branch);
            return true;
        } catch (GitLabException e) {
            ProviderError mapped = toProviderError(e);
            if (mapped == ProviderError.NOT_FOUND) {
                return false;
            }
            throw new ProviderException(mapped, e.getMessage(), e);
        }
    }

    @Override
    public String cloneUrl(String baseUrl, String providerRepoId, String repoFullName) {
        // https://<host>/<path-with-namespace>.git — never carries a token (D-014 one-time file delivers it).
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + repoFullName + ".git";
    }

    // --- mapping ---

    private static SourceRepo toRepo(GitLabProject p) {
        return new SourceRepo(Long.toString(p.id()), p.pathWithNamespace(), p.defaultBranch(), null,
                p.isEmpty(), p.notSelectableReason());
    }

    private static SourceBranch toBranch(GitLabBranch b) {
        return new SourceBranch(b.name(), b.isDefault(), b.isProtected(), b.lastCommitAt());
    }

    private static <T> SourcePage<T> paginate(List<T> all, int page, int size) {
        int from = Math.min(Math.max(page, 0) * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = size <= 0 ? 1 : (int) Math.ceil(all.size() / (double) size);
        return new SourcePage<>(new ArrayList<>(all.subList(from, to)), all.size(), Math.max(totalPages, 1));
    }

    private <T> T map(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (GitLabException e) {
            throw new ProviderException(toProviderError(e), e.getMessage(), e);
        }
    }

    /** Maps a GitLab error category to the provider-neutral one (transient flags preserved). */
    public static ProviderError toProviderError(GitLabException e) {
        return switch (e.getError()) {
            case INVALID_TOKEN, TOKEN_EXPIRED -> ProviderError.INVALID_CREDENTIAL;
            case MISSING_SCOPES -> ProviderError.FORBIDDEN;
            case RATE_LIMITED -> ProviderError.RATE_LIMITED;
            case GROUP_NOT_FOUND, PROJECT_NOT_FOUND, NOT_FOUND, BRANCH_NOT_FOUND -> ProviderError.NOT_FOUND;
            case UNREACHABLE -> ProviderError.UNREACHABLE;
            case HOST_NOT_ALLOWED -> ProviderError.HOST_NOT_ALLOWED;
            case UNEXPECTED_REDIRECT -> ProviderError.UNEXPECTED_REDIRECT;
            default -> ProviderError.UNEXPECTED;
        };
    }
}
