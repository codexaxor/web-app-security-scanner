package com.company.secscan.issue.api;

import com.company.secscan.issue.SubIssue;
import java.time.Instant;
import java.util.UUID;

/**
 * A sub-issue as shown in the issue side panel (API shape; the UI is M8). For a secret it exposes only the
 * masked {@code secretPreview} — never the stored HMAC {@code secret_hash}, the {@code key_id}, the raw
 * fingerprint or the snippet hash (M3 plan addition 6, CLAUDE.md security rules).
 */
public record SubIssueView(UUID id, UUID groupId, String type, String scanner, String status,
                           String vulnId, String ruleId, String packageName, String installedVersion,
                           String fixedVersion, String filePath, Integer line, String secretPreview,
                           String toolSeverity, String ignoreReason, UUID firstSeenScanId, UUID lastSeenScanId,
                           Instant solvedAt, Instant createdAt) {

    public static SubIssueView of(SubIssue s) {
        return new SubIssueView(s.getId(), s.getGroupId(), s.getType(), s.getScanner(), s.getStatus(),
                s.getVulnId(), s.getRuleId(), s.getPackageName(), s.getInstalledVersion(), s.getFixedVersion(),
                s.getFilePath(), s.getLine(), s.getSecretPreview(), s.getToolSeverity(), s.getIgnoreReason(),
                s.getFirstSeenScanId(), s.getLastSeenScanId(), s.getSolvedAt(), s.getCreatedAt());
    }
}
