package com.company.secscan.issue.api;

import com.company.secscan.issue.IgnoreRule;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.scoring.CweDictionary;
import com.company.secscan.scoring.Scoring;
import com.company.secscan.scoring.ScoreLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A sub-issue as shown in the issue side panel (API shape; the UI is M8). For a secret it exposes only the
 * masked {@code secretPreview} — never the stored HMAC {@code secret_hash}, the {@code key_id}, the raw
 * fingerprint or the snippet hash (M3 plan addition 6, CLAUDE.md security rules).
 *
 * <p>M5 scoring fields: {@code score}/{@code band}, the itemized {@code scoreBreakdown}, the chosen CVSS
 * {@code cvss} vector (and {@code severityLabel} when there is no vector), the {@code cwe} ids with a resolved
 * {@code cwePhrase}, {@code inKev}, and the stored {@code epss} probability. EPSS is displayed only (adjustments
 * off in M5); when present, {@code epssAttribution} carries the required © FIRST.org line (D-047).
 */
public record SubIssueView(UUID id, UUID groupId, String type, String scanner, String status,
                           String vulnId, String ruleId, String packageName, String installedVersion,
                           String fixedVersion, String filePath, Integer line, String secretPreview,
                           String toolSeverity, String ignoreReason, Integer score, String band,
                           List<ScoreLine> scoreBreakdown, String cvss, String severityLabel, List<String> cwe,
                           String cwePhrase, Boolean inKev, BigDecimal epss, String epssAttribution,
                           UUID firstSeenScanId, UUID lastSeenScanId, Instant solvedAt, Instant createdAt,
                           UUID ignoreRuleId, String ruleScope, String ruleReason, String ruleCreatedBy) {

    /** The © FIRST.org attribution shown wherever an EPSS value is surfaced (D-047 / EPSS terms of use). */
    public static final String EPSS_ATTRIBUTION = "EPSS data © FIRST.org — https://www.first.org/epss/";

    /** Without a holding rule (auto-ignore causes carry no rule; the reason code is {@code ignoreReason}). */
    public static SubIssueView of(SubIssue s) {
        return of(s, null);
    }

    /**
     * {@code rule} is the manual/.secscan.yml rule holding this finding ignored (from {@code ignored_by_rule_id}),
     * or null for an auto-ignore cause or an open/solved finding (OD-14 item 6). The rule fields drive the
     * panel's "ignored by rule / scope / reason / creator" block and the unignore-vs-409-vs-auto-cause logic.
     */
    public static SubIssueView of(SubIssue s, IgnoreRule rule) {
        Integer score = s.getScore();
        String cwePhrase = CweDictionary.getInstance().phrase(s.getCwe(), s.getAdvisorySummary());
        String epssAttribution = s.getEpss() != null ? EPSS_ATTRIBUTION : null;
        return new SubIssueView(s.getId(), s.getGroupId(), s.getType(), s.getScanner(), s.getStatus(),
                s.getVulnId(), s.getRuleId(), s.getPackageName(), s.getInstalledVersion(), s.getFixedVersion(),
                s.getFilePath(), s.getLine(), s.getSecretPreview(), s.getToolSeverity(), s.getIgnoreReason(),
                score, score == null ? null : Scoring.band(score), s.getScoreBreakdown(), s.getCvss(),
                s.getSeverityLabel(), s.getCwe(), cwePhrase, s.isInKev(), s.getEpss(), epssAttribution,
                s.getFirstSeenScanId(), s.getLastSeenScanId(), s.getSolvedAt(), s.getCreatedAt(),
                rule == null ? null : rule.getId(),
                rule == null ? null : rule.getScope(),
                rule == null ? null : rule.getReason(),
                rule == null ? null : rule.getCreatedBy());
    }
}
