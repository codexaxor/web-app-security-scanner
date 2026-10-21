package com.company.secscan.issue.api;

import com.company.secscan.issue.IgnoreRule;
import java.time.Instant;
import java.util.UUID;

/** An ignore rule as returned by the triage API (the Ignored view, rules tab). */
public record IgnoreRuleView(UUID id, String scope, UUID repositoryId, String matchValue, String reason,
                             String createdBy, Instant createdAt) {

    public static IgnoreRuleView of(IgnoreRule r) {
        return new IgnoreRuleView(r.getId(), r.getScope(), r.getRepositoryId(), r.getMatchValue(), r.getReason(),
                r.getCreatedBy(), r.getCreatedAt());
    }
}
