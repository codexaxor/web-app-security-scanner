package com.company.secscan.issue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link TriageEvent} (the issue-status audit trail). */
public interface TriageEventRepository extends JpaRepository<TriageEvent, UUID> {

    /** The audit trail for one sub-issue, oldest first. Workspace-scoped (CLAUDE.md domain rule 1). */
    List<TriageEvent> findByWorkspaceIdAndSubIssueIdOrderByAtAsc(UUID workspaceId, UUID subIssueId);
}
