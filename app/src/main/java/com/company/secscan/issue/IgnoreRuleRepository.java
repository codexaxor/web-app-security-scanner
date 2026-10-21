package com.company.secscan.issue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link IgnoreRule}. Every query is workspace-scoped (CLAUDE.md domain rule 1). */
public interface IgnoreRuleRepository extends JpaRepository<IgnoreRule, UUID> {

    /** All rules in a workspace — reconcile loads these to apply active ignores to a scan's findings. */
    List<IgnoreRule> findByWorkspaceId(UUID workspaceId);

    /** Paginated list of rules in a workspace (the Ignored view, rules tab). */
    Page<IgnoreRule> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    /** One rule by id, workspace-scoped (so a rule from another tenant is never touched). */
    Optional<IgnoreRule> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    /** The .secscan.yml-materialized system rules for one repository — the D-057 revoke lifecycle reconciles these. */
    List<IgnoreRule> findByWorkspaceIdAndRepositoryIdAndCreatedBy(UUID workspaceId, UUID repositoryId,
                                                                  String createdBy);
}
