package com.company.secscan.issue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data store for {@link IssueGroup}. */
public interface IssueGroupRepository extends JpaRepository<IssueGroup, UUID> {

    Optional<IssueGroup> findByWorkspaceIdAndRepositoryIdAndGroupKey(UUID workspaceId, UUID repositoryId,
                                                                     String groupKey);

    /** All groups in a repository — the triage step maps a sub-issue's group id to its stable group_key. */
    java.util.List<IssueGroup> findByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId);

    /**
     * Server-side paginated, workspace-scoped feed with optional repository/type/status filters (a {@code null}
     * filter matches all). Ordered newest-first by first-seen (severity-ordered feed lands with M5 scoring).
     */
    @Query("""
            select g from IssueGroup g
            where g.workspaceId = :workspaceId
              and (:repositoryId is null or g.repositoryId = :repositoryId)
              and (:type is null or g.type = :type)
              and (:status is null or g.status = :status)
            order by g.firstSeenAt desc, g.id desc
            """)
    Page<IssueGroup> search(@Param("workspaceId") UUID workspaceId,
                            @Param("repositoryId") UUID repositoryId,
                            @Param("type") String type,
                            @Param("status") String status,
                            Pageable pageable);

    Optional<IssueGroup> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /**
     * The M8 Feed query (OD-14 item 3): workspace-scoped, filtered by repository/type, a status set (Focus =
     * {@code open}; All = {@code open, ignored}; solved never shown), an optional severity band range
     * ({@code bandLo} null = no band filter; a null-score all-ignored group is excluded by any band filter), and
     * {@code includeInactive} (default false → only active repositories' groups). Documented sort:
     * {@code severity_score desc NULLS LAST, first_seen_at desc, id desc}.
     */
    @Query("""
            select g from IssueGroup g, Repository r
            where g.workspaceId = :ws and r.id = g.repositoryId
              and (:repositoryId is null or g.repositoryId = :repositoryId)
              and (:type is null or g.type = :type)
              and g.status in :statuses
              and (:bandLo is null or (g.severityScore >= :bandLo and g.severityScore <= :bandHi))
              and (:includeInactive = true or r.active = true)
            order by g.severityScore desc nulls last, g.firstSeenAt desc, g.id desc
            """)
    Page<IssueGroup> searchFeed(@Param("ws") UUID ws,
                                @Param("repositoryId") UUID repositoryId,
                                @Param("type") String type,
                                @Param("statuses") java.util.List<String> statuses,
                                @Param("bandLo") Integer bandLo,
                                @Param("bandHi") Integer bandHi,
                                @Param("includeInactive") boolean includeInactive,
                                Pageable pageable);

    /**
     * Band counts for the Feed summary (OD-14 item 4): for the same filters as the feed (view statuses, type,
     * repository, includeInactive), the number of groups in each severity band plus the total. Null-score
     * (all-ignored) groups fall into no band and are only in {@code total}.
     * Returns one row: [critical, high, medium, low, total].
     */
    @Query("""
            select
              sum(case when g.severityScore >= 90 then 1 else 0 end),
              sum(case when g.severityScore >= 70 and g.severityScore < 90 then 1 else 0 end),
              sum(case when g.severityScore >= 40 and g.severityScore < 70 then 1 else 0 end),
              sum(case when g.severityScore >= 1  and g.severityScore < 40 then 1 else 0 end),
              count(g)
            from IssueGroup g, Repository r
            where g.workspaceId = :ws and r.id = g.repositoryId
              and (:repositoryId is null or g.repositoryId = :repositoryId)
              and (:type is null or g.type = :type)
              and g.status in :statuses
              and (:includeInactive = true or r.active = true)
            """)
    java.util.List<Object[]> bandCounts(@Param("ws") UUID ws,
                        @Param("repositoryId") UUID repositoryId,
                        @Param("type") String type,
                        @Param("statuses") java.util.List<String> statuses,
                        @Param("includeInactive") boolean includeInactive);
}
