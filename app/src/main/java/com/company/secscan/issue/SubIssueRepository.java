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

    Page<SubIssue> findByWorkspaceIdAndGroupId(UUID workspaceId, UUID groupId, Pageable pageable);

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
}
