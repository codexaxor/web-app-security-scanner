package com.company.secscan.source;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitLabException;
import com.company.secscan.gitlab.GitLabHostPolicy;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.gitlab.dto.GitLabProject;
import com.company.secscan.source.github.GitHubClient;
import com.company.secscan.source.github.GitHubProvider;
import com.company.secscan.source.github.dto.GitHubRepo;
import com.company.secscan.source.gitlab.GitLabProvider;
import com.company.secscan.workspace.Workspace;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Creates provider-neutral source connections for GitHub (owner or single repository, with a fine-grained PAT)
 * and public repositories (by URL, anonymous), persisting {@code gitlab_connection} rows with the right
 * provider / credential_kind / target / identity (V22, D-080/D-085). GitLab connections keep their own service
 * (group/project listing). Tokens are encrypted at rest and never logged (D-014). Every host is checked against
 * the allowlist (D-018/D-089) before any network call.
 */
@Service
public class SourceConnectionService {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    private final GitlabConnectionRepository connections;
    private final TokenCipherHolder cipher;
    private final GitLabHostPolicy hostPolicy;
    private final GitHubClient gitHub;
    private final GitLabClient gitLab;
    /** github.com in production; a WireMock base URL in tests. The client maps github.com → api.github.com. */
    private final String gitHubBase;
    private final String gitHubHost;

    public SourceConnectionService(GitlabConnectionRepository connections, TokenCipherHolder cipher,
                                   GitLabHostPolicy hostPolicy, GitHubClient gitHub, GitLabClient gitLab,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${secscan.github.base-url:https://github.com}") String gitHubBase) {
        this.connections = connections;
        this.cipher = cipher;
        this.hostPolicy = hostPolicy;
        this.gitHub = gitHub;
        this.gitLab = gitLab;
        this.gitHubBase = gitHubBase;
        this.gitHubHost = SourceHosts.authorityOf(gitHubBase);
    }

    /** Connects a GitHub owner (user/org); the fine-grained PAT must see it. Rejects a duplicate in this workspace. */
    public GitlabConnection createGitHubOwner(String ownerLogin, String token) {
        hostPolicy.requireAllowed(gitHubBase);
        gitHub.currentUser(gitHubBase, token);                 // verifies the token (401 → ProviderException)
        gitHub.owner(gitHubBase, token, ownerLogin);           // confirms the owner is visible (404 → NOT_FOUND)
        requireNotConnected(connections.findByWorkspaceIdAndProviderAndHostAndOwnerLogin(
                WORKSPACE, GitlabConnection.PROVIDER_GITHUB, gitHubHost, ownerLogin), "owner " + ownerLogin);
        String ciphertext = cipher.require().encrypt(token);
        return save(GitlabConnection.forGitHubOwner(WORKSPACE, gitHubBase, ownerLogin, ciphertext, null, null));
    }

    /** Connects a single GitHub repository ({@code owner/name}); resolves its stable numeric id for identity. */
    public GitlabConnection createGitHubRepo(String fullName, String token) {
        hostPolicy.requireAllowed(gitHubBase);
        GitHubRepo repo = gitHub.repoByFullName(gitHubBase, token, fullName);   // verifies + resolves id
        String providerRepoId = Long.toString(repo.id());
        requireNotConnected(connections.findByWorkspaceIdAndProviderAndHostAndProviderRepoId(
                WORKSPACE, GitlabConnection.PROVIDER_GITHUB, gitHubHost, providerRepoId), "repository " + fullName);
        String ciphertext = cipher.require().encrypt(token);
        return save(GitlabConnection.forSingleRepo(WORKSPACE, gitHubBase, GitlabConnection.PROVIDER_GITHUB,
                GitlabConnection.CREDENTIAL_TOKEN, providerRepoId, repo.fullName(), ciphertext, null, null));
    }

    /** Adds a public repository by URL (anonymous clone; credential_kind 'none'). Host must be allowlisted. */
    public GitlabConnection createPublicRepo(String url) {
        PublicRepoUrl parsed = PublicRepoUrl.parse(url);
        hostPolicy.requireAllowed(parsed.baseUrl());
        String providerRepoId;
        String fullName;
        if (GitlabConnection.PROVIDER_GITHUB.equals(parsed.provider())) {
            GitHubRepo repo = gitHub.repoByFullName(parsed.baseUrl(), null, parsed.fullName());   // anonymous
            providerRepoId = Long.toString(repo.id());
            fullName = repo.fullName();
        } else {
            GitLabProject project = resolveGitLabAnonymously(parsed.baseUrl(), parsed.fullName());
            providerRepoId = Long.toString(project.id());
            fullName = project.pathWithNamespace();
        }
        requireNotConnected(connections.findByWorkspaceIdAndProviderAndHostAndProviderRepoId(
                WORKSPACE, parsed.provider(), parsed.host(), providerRepoId), "public repository " + fullName);
        return save(GitlabConnection.forSingleRepo(WORKSPACE, parsed.baseUrl(), parsed.provider(),
                GitlabConnection.CREDENTIAL_NONE, providerRepoId, fullName, null, null, null));
    }

    private GitLabProject resolveGitLabAnonymously(String baseUrl, String projectPath) {
        try {
            return gitLab.resolveProject(baseUrl, null, projectPath);
        } catch (GitLabException e) {
            throw new ProviderException(GitLabProvider.toProviderError(e), e.getMessage(), e);
        }
    }

    private GitlabConnection save(GitlabConnection connection) {
        try {
            return connections.saveAndFlush(connection);
        } catch (DataIntegrityViolationException e) {
            // A concurrent create inserted the same target first (the host-scoped unique index rejected this one).
            throw new ProviderException(ProviderError.UNEXPECTED, "This source is already connected.", e);
        }
    }

    private static void requireNotConnected(java.util.Optional<GitlabConnection> existing, String what) {
        if (existing.isPresent()) {
            throw new ProviderException(ProviderError.UNEXPECTED, "This workspace is already connected to " + what
                    + ". Edit that connection to rotate its token.");
        }
    }
}
