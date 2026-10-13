package com.company.secscan.issue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link SubIssue}. */
public interface SubIssueRepository extends JpaRepository<SubIssue, UUID> {

    /** All sub-issues for a repository — reconcile loads these to compare against a scan's findings. */
    List<SubIssue> findByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId);

    /** All sub-issues in a group — used when recomputing the group's derived status. */
    List<SubIssue> findByGroupId(UUID groupId);

    Page<SubIssue> findByWorkspaceIdAndGroupId(UUID workspaceId, UUID groupId, Pageable pageable);
}
