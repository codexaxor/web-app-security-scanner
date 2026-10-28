package com.company.secscan.intel;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store for {@link IacSeverityOverride}. A global config table (no {@code workspace_id}, D-050 family): its
 * {@code findById(checkId)} lookup is a documented CLAUDE.md domain-rule-1 exception — the override is joined to
 * workspace-scoped {@code sub_issue} rows at score time, which carry the scoping.
 */
public interface IacSeverityOverrideRepository extends JpaRepository<IacSeverityOverride, String> {
}
