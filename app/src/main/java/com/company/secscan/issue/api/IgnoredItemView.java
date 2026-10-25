package com.company.secscan.issue.api;

import com.company.secscan.issue.IgnoreRule;
import com.company.secscan.issue.SubIssue;
import java.time.Instant;
import java.util.UUID;

/**
 * One ignored finding for the Ignored view (SPEC): the finding, its reason CODE, and — for a manual or
 * {@code .secscan.yml} ignore — the holding rule's human reason and who created it. A cause-based auto-ignore
 * (EXCLUDED_PATH / DEV_DEPENDENCY / …) has no rule, so {@code ruleReason}/{@code createdBy} are null.
 */
public record IgnoredItemView(UUID subIssueId, UUID groupId, String type, String scanner, String vulnId,
                              String ruleId, String packageName, String filePath, Integer line, String reasonCode,
                              UUID ignoreRuleId, String ruleScope, String ruleReason, String createdBy,
                              Instant ignoredAt) {

    public static IgnoredItemView of(SubIssue s, IgnoreRule rule) {
        return new IgnoredItemView(s.getId(), s.getGroupId(), s.getType(), s.getScanner(), s.getVulnId(),
                s.getRuleId(), s.getPackageName(), s.getFilePath(), s.getLine(), s.getIgnoreReason(),
                rule == null ? null : rule.getId(), rule == null ? null : rule.getScope(),
                rule == null ? null : rule.getReason(), rule == null ? null : rule.getCreatedBy(),
                s.getIgnoredAt());
    }
}
