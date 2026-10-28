package com.company.secscan.repository;

import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one selected project's {@link Repository}, each call in its own transaction. Kept separate from
 * {@link RepositoryService} so that a {@code DataIntegrityViolationException} from a concurrent insert rolls back
 * cleanly and the caller can recover in a fresh transaction (see
 * {@link RepositoryService#select(UUID, List)}). Enforces D-020 dedup: the first connection to select a project
 * keeps ownership while it stays owned and active; a detached or inactive repository is reattached to the
 * selecting connection, and the provisional-reassignment flag is only cleared when the selecting connection is
 * the repository's owner.
 */
@Component
class RepositoryUpserter {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    private final RepositoryRepository repositories;

    RepositoryUpserter(RepositoryRepository repositories) {
        this.repositories = repositories;
    }

    /**
     * Inserts the repository for {@code projectId}, or updates it if it already exists. A concurrent insert of
     * the same project loses on {@code uq_repository_workspace_project}; {@code saveAndFlush} surfaces that here
     * as {@code DataIntegrityViolationException} so the caller can {@link #reattach reattach} instead of 500ing.
     */
    @Transactional
    public Repository upsert(UUID connectionId, String host, long projectId, String pathWithNamespace,
                             String defaultBranch, List<String> languages) {
        // Identity is (workspace, provider, host, provider_repo_id) — D-080/V22. The same numeric project id on
        // two different GitLab hosts is two distinct repositories.
        return repositories.findByWorkspaceIdAndProviderAndHostAndProviderRepoId(
                        WORKSPACE, "gitlab", host, Long.toString(projectId))
                .map(r -> applyAndSave(r, connectionId, pathWithNamespace, defaultBranch, languages))
                // host is filled by the V22 trigger from the connection (= this connection's host); identity on
                // the row therefore matches the finder's (provider, host, providerRepoId) above.
                .orElseGet(() -> repositories.saveAndFlush(new Repository(WORKSPACE, connectionId, projectId,
                        pathWithNamespace, defaultBranch, languages)));
    }

    /**
     * Re-applies the selection to the row a concurrent winner just inserted (own transaction, after the losing
     * insert's transaction has rolled back). First-wins dedup means the winner keeps ownership.
     */
    @Transactional
    public Repository reattach(UUID connectionId, String host, long projectId, String pathWithNamespace,
                               String defaultBranch, List<String> languages) {
        Repository r = repositories.findByWorkspaceIdAndProviderAndHostAndProviderRepoId(
                        WORKSPACE, "gitlab", host, Long.toString(projectId))
                .orElseThrow(() -> new IllegalStateException(
                        "Repository for project " + projectId + " disappeared after a concurrent insert."));
        return applyAndSave(r, connectionId, pathWithNamespace, defaultBranch, languages);
    }

    /**
     * Provider-neutral upsert (GitHub / public). Identity is (workspace, provider, host, provider_repo_id);
     * inserts a GitLab or GitHub row accordingly (host filled by the V22 trigger from the connection). Race on
     * the identity index surfaces as {@code DataIntegrityViolationException} for the caller to {@link
     * #reattachSource reattach}.
     */
    @Transactional
    public Repository upsertSource(UUID connectionId, String provider, String host, String providerRepoId,
                                   String fullName, String defaultBranch, List<String> languages) {
        return repositories.findByWorkspaceIdAndProviderAndHostAndProviderRepoId(
                        WORKSPACE, provider, host, providerRepoId)
                .map(r -> applyAndSave(r, connectionId, fullName, defaultBranch, languages))
                .orElseGet(() -> repositories.saveAndFlush(newRow(connectionId, provider, providerRepoId, fullName,
                        defaultBranch, languages)));
    }

    @Transactional
    public Repository reattachSource(UUID connectionId, String provider, String host, String providerRepoId,
                                     String fullName, String defaultBranch, List<String> languages) {
        Repository r = repositories.findByWorkspaceIdAndProviderAndHostAndProviderRepoId(
                        WORKSPACE, provider, host, providerRepoId)
                .orElseThrow(() -> new IllegalStateException(
                        "Repository " + provider + ":" + providerRepoId + " disappeared after a concurrent insert."));
        return applyAndSave(r, connectionId, fullName, defaultBranch, languages);
    }

    private static Repository newRow(UUID connectionId, String provider, String providerRepoId, String fullName,
                                     String defaultBranch, List<String> languages) {
        if ("github".equals(provider)) {
            return Repository.github(WORKSPACE, connectionId, Long.parseLong(providerRepoId), fullName,
                    defaultBranch, languages);
        }
        return new Repository(WORKSPACE, connectionId, Long.parseLong(providerRepoId), fullName, defaultBranch,
                languages);
    }

    private Repository applyAndSave(Repository r, UUID connectionId, String pathWithNamespace,
                                    String defaultBranch, List<String> languages) {
        if (r.getConnectionId() == null || !r.isActive()) {
            // Detached (its connection was removed) or inactive: the selecting connection takes it over,
            // clearing the inactive/provisional state (D-020).
            r.reattachTo(connectionId);
        } else if (connectionId.equals(r.getConnectionId())) {
            // Re-selected through its owner: refresh and confirm any provisional reassignment onto that owner.
            r.activate();
        }
        // else: owned and active by another connection — first-wins dedup keeps ownership, and the provisional
        // flag is left for that owner's own next use to resolve (never cleared by a non-owner).
        r.refreshFromGitlab(pathWithNamespace, defaultBranch, languages);
        return repositories.save(r);
    }
}
