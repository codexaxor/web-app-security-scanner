package com.company.secscan.issue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data store for {@link SubIssue}. */
public interface SubIssueRepository extends JpaRepository<SubIssue, UUID> {

    /** All sub-issues for a repository — reconcile loads these to compare against a scan's findings. */
    List<SubIssue> findByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId);

    /** All sub-issues in a group — used when recomputing the group's derived status. Workspace-scoped (rule 1). */
    List<SubIssue> findByWorkspaceIdAndGroupId(UUID workspaceId, UUID groupId);

    /** All sub-issues in a workspace with the given status — the re-score job loads the OPEN ones (D-049). */
    List<SubIssue> findByWorkspaceIdAndStatus(UUID workspaceId, String status);

    /** One repository's sub-issues with the given status — the per-repo re-score (sensitivity change) loads the
     * OPEN ones (D-049/D-070, change 5). Workspace-scoped (rule 1). */
    List<SubIssue> findByWorkspaceIdAndRepositoryIdAndStatus(UUID workspaceId, UUID repositoryId, String status);

    /** One sub-issue by id, workspace-scoped — the side panel / events endpoints (OD-14 items 5/6). */
    java.util.Optional<SubIssue> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /**
     * Feed-summary "New in last 7 days" (OD-14 item 4, change 3; D-083): distinct sub-issues first seen (row
     * created) since {@code since} AND first seen ON THE REPOSITORY'S CURRENTLY TRACKED (effective) branch —
     * {@code coalesce(scan_branch, default_branch)} (D-076). A finding first seen on a now-abandoned branch is
     * NOT "New" on the branch we now scan, which fixes the across-a-branch-switch over-count. Honours the
     * type + repository filters only (not band, not view) and excludes inactive repositories' findings unless
     * {@code includeInactive}. A reopened finding keeps its original created_at, so it is not counted as New;
     * a finding with no first-seen scan (legacy/null) is excluded.
     */
    @Query("""
            select count(distinct s.id)
            from SubIssue s, com.company.secscan.repository.Repository r,
                 com.company.secscan.scan.Scan fs
            where s.workspaceId = :ws and r.workspaceId = :ws and fs.workspaceId = :ws
              and r.id = s.repositoryId and fs.id = s.firstSeenScanId
              and s.createdAt >= :since
              and fs.branch = coalesce(r.scanBranch, r.defaultBranch)
              and (:repositoryId is null or s.repositoryId = :repositoryId)
              and (:type is null or s.type = :type)
              and (:includeInactive = true or r.active = true)
            """)
    long countNewSince(@Param("ws") UUID ws, @Param("repositoryId") UUID repositoryId,
                       @Param("type") String type, @Param("includeInactive") boolean includeInactive,
                       @Param("since") java.time.Instant since);

    /** Dashboard (OD-14 item 7): OPEN sub-issue counts by severity band. Returns [critical, high, medium, low,
     *  total]. Excludes inactive repositories' findings unless {@code includeInactive} (change 2). */
    @Query("""
            select
              sum(case when s.score >= 90 then 1 else 0 end),
              sum(case when s.score >= 70 and s.score < 90 then 1 else 0 end),
              sum(case when s.score >= 40 and s.score < 70 then 1 else 0 end),
              sum(case when s.score >= 1  and s.score < 40 then 1 else 0 end),
              count(s)
            from SubIssue s, com.company.secscan.repository.Repository r
            where s.workspaceId = :ws and r.id = s.repositoryId and s.status = 'open'
              and (:includeInactive = true or r.active = true)
            """)
    java.util.List<Object[]> openBandCounts(@Param("ws") UUID ws,
                                            @Param("includeInactive") boolean includeInactive);

    /** Dashboard: OPEN sub-issue counts grouped by type. Returns rows of [type, count]. */
    @Query("""
            select s.type, count(s)
            from SubIssue s, com.company.secscan.repository.Repository r
            where s.workspaceId = :ws and r.id = s.repositoryId and s.status = 'open'
              and (:includeInactive = true or r.active = true)
            group by s.type
            """)
    java.util.List<Object[]> openCountsByType(@Param("ws") UUID ws,
                                              @Param("includeInactive") boolean includeInactive);

    /** Dashboard inactive line: OPEN findings that sit in inactive repositories (not scanned, excluded by default). */
    @Query("""
            select count(s)
            from SubIssue s, com.company.secscan.repository.Repository r
            where s.workspaceId = :ws and r.id = s.repositoryId and s.status = 'open' and r.active = false
            """)
    long countOpenInInactiveRepos(@Param("ws") UUID ws);

    /** Every sub-issue in a workspace — a manual ignore rule with no repository scope applies across all repos. */
    List<SubIssue> findByWorkspaceId(UUID workspaceId);

    /** Paginated view by status — the Ignored view loads the {@code ignored} ones. */
    Page<SubIssue> findByWorkspaceIdAndStatus(UUID workspaceId, String status, Pageable pageable);

    /** Paginated Ignored view filtered by reason code (M8.8: the "ignored by this rule" / LOW_EPSS link). */
    Page<SubIssue> findByWorkspaceIdAndStatusAndIgnoreReason(UUID workspaceId, String status, String ignoreReason,
                                                            Pageable pageable);

    /** The sub-issues currently held ignored by one rule — unignore when the rule is removed (D-058). */
    List<SubIssue> findByWorkspaceIdAndIgnoredByRuleId(UUID workspaceId, UUID ignoredByRuleId);

    /**
     * The sub-issues with a given status held by one rule (B-2): {@link TriageService#removeRule} iterates only
     * the {@code ignored} ones, so a row the retire pass solved (its {@code ignored_by_rule_id} is cleared by
     * {@link SubIssue#solveIgnored()}, and even a stale pointer is filtered out here) is never reopened by a
     * later rule deletion. Workspace-scoped (domain rule 1).
     */
    List<SubIssue> findByWorkspaceIdAndStatusAndIgnoredByRuleId(UUID workspaceId, String status,
                                                                UUID ignoredByRuleId);

    /**
     * OD-19 column-scoped retire (N-1): solves one stale ignored orphan IN PLACE — status ignored → solved,
     * stamping {@code solved_at} and CLEARING the ignore markers (B-2) — and ONLY while it is still
     * {@code ignored} in this workspace. A full-entity save is avoided so a concurrent reconcile that already
     * changed the row makes this a no-op (0 rows) rather than clobbering it; the caller then writes no
     * {@code triage_event}. {@code clearAutomatically} drops the stale first-level cache so the group rollup
     * that follows re-reads the solved rows. Returns the number of rows updated (0 or 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update sub_issue set status = 'solved', solved_at = now(), ignore_reason = null, "
            + "ignored_by_rule_id = null, ignored_at = null "
            + "where id = :id and workspace_id = :ws and status = 'ignored'", nativeQuery = true)
    int solveIgnoredOrphanIfStillIgnored(@Param("id") UUID id, @Param("ws") UUID ws);

    Page<SubIssue> findByWorkspaceIdAndGroupId(UUID workspaceId, UUID groupId, Pageable pageable);

    /**
     * The Feed's per-group finding counts broken down by status (M8 S10e): rows of [groupId, status, count] for
     * each of the given groups. The Feed shows open vs ignored separately (a group can hold a mix); groups with
     * no sub-issue of a status are simply absent. One query for the whole page — no N+1 per row. Workspace-scoped.
     */
    @Query("""
            select s.groupId, s.status, count(s) from SubIssue s
            where s.workspaceId = :ws and s.groupId in :groupIds
            group by s.groupId, s.status
            """)
    java.util.List<Object[]> countByGroupIdsAndStatus(@Param("ws") UUID ws, @Param("groupIds") List<UUID> groupIds);

    /**
     * Per-group location stats for the Feed/Issues-tab "location (+N more)" column (M8.6): a deterministic
     * representative file path ({@code min}) and the number of DISTINCT file paths per group, over sub-issues
     * with a file path. One query per page (no N+1); workspace-scoped. Groups with no file-path finding are
     * absent. Returns rows [groupId, primaryFilePath, distinctLocationCount].
     */
    @Query("""
            select s.groupId, min(s.filePath), count(distinct s.filePath) from SubIssue s
            where s.workspaceId = :ws and s.groupId in :groupIds and s.filePath is not null
            group by s.groupId
            """)
    java.util.List<Object[]> locationStatsByGroupIds(@Param("ws") UUID ws, @Param("groupIds") List<UUID> groupIds);

    /**
     * Writes ONLY the derived score columns of a sub-issue, and ONLY while it is still {@code open} (B1). The
     * {@code status = 'open'} guard means a row that reconcile has solved in the window since the re-score read
     * it is skipped: its {@code status}/{@code solved_at}/{@code last_seen_scan_id} are never touched and its
     * score never resurrects an {@code open}-era value. A column-scoped UPDATE (never a full-entity save), so the
     * re-score path cannot lose a concurrent reconcile's write to any non-score column. Native (jsonb cast for
     * {@code score_breakdown}); {@code clearAutomatically} drops the stale first-level cache so the group rollup
     * that follows re-reads the new scores from the DB. Returns the number of rows updated (0 if no longer open).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update sub_issue set score = :score, score_breakdown = cast(:breakdown as jsonb), "
            + "in_kev = :inKev, epss = :epss where id = :id and status = 'open'", nativeQuery = true)
    int updateScoreColumnsIfOpen(@Param("id") UUID id, @Param("score") Integer score,
                                 @Param("breakdown") String breakdown, @Param("inKev") boolean inKev,
                                 @Param("epss") BigDecimal epss);

    /**
     * SF-7: the distinct fixed versions across one group's OPEN sub-issues (nulls excluded), workspace-scoped.
     * The side panel turns this into the SCA "upgrade to &lt;v&gt;" fix line (one value), "see each location"
     * (several) or no fix line (none). No version comparison — the ordering/choice is the UI's concern.
     */
    @Query("""
            select distinct s.fixedVersion from SubIssue s
            where s.workspaceId = :ws and s.groupId = :groupId and s.status = 'open'
              and s.fixedVersion is not null
            """)
    List<String> findOpenFixedVersions(@Param("ws") UUID ws, @Param("groupId") UUID groupId);
}
