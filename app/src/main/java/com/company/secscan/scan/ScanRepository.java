package com.company.secscan.scan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data store for {@link Scan}. Every lookup is scoped by workspace (CLAUDE.md domain rule 1). */
public interface ScanRepository extends JpaRepository<Scan, UUID> {

    Optional<Scan> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Page<Scan> findByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId, Pageable pageable);

    /** All scans in the workspace, newest-first — the workspace-wide scans list (OD-14 item 2). */
    Page<Scan> findByWorkspaceId(UUID workspaceId, Pageable pageable);

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

    /** Workspace-scoped batch lookup by id (domain rule 1): resolve a page of events' scans to their branch. */
    List<Scan> findByWorkspaceIdAndIdIn(UUID workspaceId, List<UUID> ids);

    /**
     * The branch at which a given {@code scanner} last successfully closed findings for a repository (D-076
     * re-baseline): the newest-CREATED reconciled scan OTHER than {@code currentScanId} whose {@code scan_task}
     * for that scanner was {@code ok} and authoritative to close ({@code closes_findings}), and whose
     * {@code branch} is known. Returns null when the scanner has never had such a scan — then it is NOT
     * re-baselined (nothing prior to re-baseline against). Derived from scan/scan_task history, so a failed
     * scanner on a switch scan defers its re-baseline to its next success automatically (it never advanced this
     * value). Ordered by {@code created_at} (N-3): a stale, out-of-order scan that was skip-reconciled LATER must
     * not out-rank the genuinely newest scan and cause a spurious re-baseline. Workspace-scoped (domain rule 1).
     */
    @Query(value = """
            select s.branch from scan s
            join scan_task t on t.scan_id = s.id
            where s.workspace_id = :workspaceId and s.repository_id = :repositoryId
              and t.scanner = :scanner and t.status = 'ok' and t.closes_findings = true
              and s.reconciled_at is not null and s.branch is not null and s.id <> :currentScanId
            order by s.created_at desc
            limit 1
            """, nativeQuery = true)
    String findLastClosingBranch(@Param("workspaceId") UUID workspaceId,
                                 @Param("repositoryId") UUID repositoryId,
                                 @Param("scanner") String scanner,
                                 @Param("currentScanId") UUID currentScanId);
}
