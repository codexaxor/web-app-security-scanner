package com.company.secscan.issue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
            where g.workspaceId = :ws and r.id = g.repositoryId and r.workspaceId = :ws
              and (:repositoryId is null or g.repositoryId = :repositoryId)
              and (:type is null or g.type = :type)
              and (:category is null or g.level2Category = :category)
              and (:search is null or lower(g.title) like :search escape '\\'
                   or lower(g.subtitle) like :search escape '\\')
              and g.status in :statuses
              and (:bandLo is null or (g.severityScore >= :bandLo and g.severityScore <= :bandHi))
              and (:includeInactive = true or r.active = true)
            order by g.severityScore desc nulls last, g.firstSeenAt desc, g.id desc
            """)
    Page<IssueGroup> searchFeed(@Param("ws") UUID ws,
                                @Param("repositoryId") UUID repositoryId,
                                @Param("type") String type,
                                @Param("category") String category,
                                @Param("search") String search,
                                @Param("statuses") java.util.List<String> statuses,
                                @Param("bandLo") Integer bandLo,
                                @Param("bandHi") Integer bandHi,
                                @Param("includeInactive") boolean includeInactive,
                                Pageable pageable);

    /**
     * Distinct level-2 categories present in the feed (for the category filter), each with its group count, for
     * the same view statuses / repository / activeness scope as the feed. Workspace-scoped; null categories are
     * excluded. Returns rows [level2Category, count] ordered by count desc then name.
     */
    @Query("""
            select g.level2Category, count(g) from IssueGroup g, Repository r
            where g.workspaceId = :ws and r.id = g.repositoryId and r.workspaceId = :ws
              and (:repositoryId is null or g.repositoryId = :repositoryId)
              and g.status in :statuses
              and (:includeInactive = true or r.active = true)
              and g.level2Category is not null
            group by g.level2Category
            order by count(g) desc, g.level2Category asc
            """)
    java.util.List<Object[]> categoryCounts(@Param("ws") UUID ws,
                                            @Param("repositoryId") UUID repositoryId,
                                            @Param("statuses") java.util.List<String> statuses,
                                            @Param("includeInactive") boolean includeInactive);

    /** Groups in a workspace whose level-2 category is not yet set — the OD-19/D-083 startup backfill input. */
    java.util.List<IssueGroup> findByWorkspaceIdAndLevel2CategoryIsNull(UUID workspaceId);

    /**
     * Backfill the taxonomy columns for ONE group, only while still unset (idempotent) and workspace-scoped: a
     * column-scoped UPDATE (never a full-entity save) so it cannot clobber a concurrent reconcile. Returns rows
     * updated (0 when already set). {@code owasp} may be null.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update issue_group set level2_category = :level2, owasp_category = :owasp "
            + "where id = :id and workspace_id = :ws and level2_category is null", nativeQuery = true)
    int backfillTaxonomyIfNull(@Param("id") UUID id, @Param("ws") UUID ws, @Param("level2") String level2,
                               @Param("owasp") String owasp);

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
            where g.workspaceId = :ws and r.id = g.repositoryId and r.workspaceId = :ws
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

    /**
     * SF-2: the score-only rollup write — ONLY the derived columns {@code severity_score}, {@code fix_time_minutes}
     * and {@code subtitle}, and ONLY while the group is still {@code open}. A column-scoped UPDATE (never a
     * full-entity save) guarded by {@code workspace_id} + {@code status = 'open'}: the re-score / sensitivity path
     * must never write {@code status} and must not resurrect a score onto a group that a concurrent reconcile has
     * flipped to {@code ignored}/{@code solved} in the window since the re-score read it (domain rule 3, B1).
     * {@code subtitle} is left unchanged when {@code :subtitle} is null (an enrichment gap never blanks it).
     * {@code clearAutomatically} drops the first-level cache so later reads see the new values. Returns rows
     * updated (0 when the group is no longer open).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update issue_group set severity_score = :severity, fix_time_minutes = :fixTime, "
            + "subtitle = coalesce(:subtitle, subtitle) "
            + "where id = :id and workspace_id = :ws and status = 'open'", nativeQuery = true)
    int updateScoreRollupIfOpen(@Param("id") UUID id, @Param("ws") UUID ws, @Param("severity") Integer severity,
                                @Param("fixTime") Integer fixTime, @Param("subtitle") String subtitle);
}
