package com.company.secscan.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data store for {@link Repository}. (The doubled name is the Spring Data interface for the
 * {@code Repository} entity — the entity carries the domain name from the spec.)
 */
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {

    /** Workspace-scoped lookup (domain rule 1): a repository is only visible within its own workspace. */
    Optional<Repository> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Page<Repository> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    List<Repository> findByWorkspaceId(UUID workspaceId);

    Optional<Repository> findByWorkspaceIdAndGitlabProjectId(UUID workspaceId, long gitlabProjectId);

    List<Repository> findByWorkspaceIdAndGitlabProjectIdIn(UUID workspaceId, List<Long> gitlabProjectIds);

    List<Repository> findByWorkspaceIdAndConnectionId(UUID workspaceId, UUID connectionId);

    // --- Repositories-screen rollups (OD-14 item 1). All counts computed in SQL, workspace-scoped. Rows are
    // Object[]: [repository_id (UUID), ...]. The service assembles them into a per-repo summary. ---

    /** Open issue-group counts per repository, bucketed into the SPEC severity bands. */
    @Query(value = """
            select repository_id,
                   count(*) filter (where severity_score >= 90)                          as critical,
                   count(*) filter (where severity_score >= 70 and severity_score < 90)   as high,
                   count(*) filter (where severity_score >= 40 and severity_score < 70)   as medium,
                   count(*) filter (where severity_score >= 1  and severity_score < 40)   as low
            from issue_group
            where workspace_id = :ws and status = 'open'
            group by repository_id
            """, nativeQuery = true)
    List<Object[]> openBandsByRepo(@Param("ws") UUID ws);

    /** Ignored sub-issue counts per repository. */
    @Query(value = """
            select repository_id, count(*)
            from sub_issue
            where workspace_id = :ws and status = 'ignored'
            group by repository_id
            """, nativeQuery = true)
    List<Object[]> ignoredCountByRepo(@Param("ws") UUID ws);

    /** The latest scan per repository (by created_at) with that scan's warning count. */
    @Query(value = """
            select distinct on (s.repository_id)
                   s.repository_id, s.id, s.status, s.finished_at,
                   (select count(*) from scan_warning w where w.scan_id = s.id) as warning_count
            from scan s
            where s.workspace_id = :ws
            order by s.repository_id, s.created_at desc
            """, nativeQuery = true)
    List<Object[]> lastScanByRepo(@Param("ws") UUID ws);
}
