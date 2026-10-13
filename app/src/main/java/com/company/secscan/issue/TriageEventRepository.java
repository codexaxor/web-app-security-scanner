package com.company.secscan.issue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link TriageEvent} (the issue-status audit trail). */
public interface TriageEventRepository extends JpaRepository<TriageEvent, UUID> {

    List<TriageEvent> findBySubIssueIdOrderByAtAsc(UUID subIssueId);
}
