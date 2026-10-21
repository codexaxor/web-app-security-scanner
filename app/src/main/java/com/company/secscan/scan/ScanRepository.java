package com.company.secscan.scan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link Scan}. Every lookup is scoped by workspace (CLAUDE.md domain rule 1). */
public interface ScanRepository extends JpaRepository<Scan, UUID> {

    Optional<Scan> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Page<Scan> findByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId, Pageable pageable);

    /** In-flight scans for a repository (queued or running): used to avoid enqueuing a duplicate. */
    List<Scan> findByWorkspaceIdAndRepositoryIdAndStatusIn(UUID workspaceId, UUID repositoryId,
                                                           List<String> statuses);

    /**
     * Stale-guard (M3): true if a newer scan of the same repository has already been reconciled. Reconcile of
     * an older, out-of-order scan is then skipped so its stale view cannot re-open or re-solve findings.
     * Workspace-scoped (CLAUDE.md domain rule 1), though {@code repositoryId} is already workspace-unique.
     */
    boolean existsByWorkspaceIdAndRepositoryIdAndReconciledAtIsNotNullAndCreatedAtGreaterThan(
            UUID workspaceId, UUID repositoryId, java.time.Instant createdAt);

    /** The most recently reconciled scan of a repository — the basis for an API-time unignore's status (D-058). */
    Optional<Scan> findFirstByWorkspaceIdAndRepositoryIdAndReconciledAtIsNotNullOrderByReconciledAtDesc(
            UUID workspaceId, UUID repositoryId);
}
