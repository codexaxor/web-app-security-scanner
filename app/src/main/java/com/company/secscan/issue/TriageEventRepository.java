package com.company.secscan.issue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data store for {@link TriageEvent} (the issue-status audit trail). */
public interface TriageEventRepository extends JpaRepository<TriageEvent, UUID> {

    /** The audit trail for one sub-issue, oldest first. Workspace-scoped (CLAUDE.md domain rule 1). */
    List<TriageEvent> findByWorkspaceIdAndSubIssueIdOrderByAtAsc(UUID workspaceId, UUID subIssueId);

    /** One sub-issue's status history, newest first, paginated — the side panel history (OD-14 item 5). */
    Page<TriageEvent> findByWorkspaceIdAndSubIssueIdOrderByAtDesc(UUID workspaceId, UUID subIssueId,
                                                                 Pageable pageable);

    /**
     * Feed-summary counts of distinct sub-issues that got a status change to {@code toStatus} (and, when
     * {@code actor} is non-null, by that actor) since {@code since} (OD-14 item 4, change 3). Honours type +
     * repository filters only, and excludes inactive repositories' findings unless {@code includeInactive}.
     * Used for "Solved" (to_status=solved) and "Auto-ignored" (to_status=ignored, actor=system) in the last 7 days.
     */
    @Query("""
            select count(distinct e.subIssueId)
            from TriageEvent e, SubIssue s, com.company.secscan.repository.Repository r
            where e.workspaceId = :ws and s.id = e.subIssueId and r.id = s.repositoryId
              and e.toStatus = :toStatus and e.at >= :since
              and (:actor is null or e.actor = :actor)
              and (:repositoryId is null or s.repositoryId = :repositoryId)
              and (:type is null or s.type = :type)
              and (:includeInactive = true or r.active = true)
            """)
    long countDistinctSubIssuesByTransitionSince(@Param("ws") UUID ws,
                                                 @Param("toStatus") String toStatus,
                                                 @Param("actor") String actor,
                                                 @Param("repositoryId") UUID repositoryId,
                                                 @Param("type") String type,
                                                 @Param("includeInactive") boolean includeInactive,
                                                 @Param("since") Instant since);
}
