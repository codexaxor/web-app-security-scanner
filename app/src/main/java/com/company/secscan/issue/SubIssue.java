package com.company.secscan.issue;

import com.company.secscan.scoring.ScoreLine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One concrete finding (SPEC data model). Its identity is {@link #fingerprint} — status changes only by
 * comparing fingerprints across scans, never by line number. {@link #scanner} records the scanner that produced
 * it so reconcile can honour per-scanner success (a failed scanner never closes its findings; domain rule 3).
 *
 * <p>For a secret, {@link #secretHash} is the keyed HMAC (D-027) and {@link #keyId} the short fingerprint-key id
 * (D-029); neither is ever exposed by the API — only {@link #secretPreview}. {@link #line} is informational and
 * is not part of the fingerprint.
 */
@Entity
@Table(name = "sub_issue")
public class SubIssue {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_IGNORED = "ignored";
    public static final String STATUS_SOLVED = "solved";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "scanner", nullable = false)
    private String scanner;

    @Column(name = "fingerprint", nullable = false)
    private String fingerprint;

    @Column(name = "vuln_id")
    private String vulnId;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "package")
    private String packageName;

    @Column(name = "installed_version")
    private String installedVersion;

    @Column(name = "fixed_version")
    private String fixedVersion;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "line")
    private Integer line;

    @Column(name = "snippet_hash")
    private String snippetHash;

    @Column(name = "secret_hash")
    private String secretHash;

    @Column(name = "secret_preview")
    private String secretPreview;

    @Column(name = "key_id")
    private String keyId;

    @Column(name = "tool_severity")
    private String toolSeverity;

    // --- M5 scoring fields (derived; NEVER part of the fingerprint) ---

    /** 0-100 severity (SPEC bands). Recomputed at scan time and by the re-score job. */
    @Column(name = "score")
    private Integer score;

    /** Itemized breakdown of how {@link #score} was reached (base + each adjustment); jsonb. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_breakdown", columnDefinition = "jsonb")
    private List<ScoreLine> scoreBreakdown;

    /** The chosen CVSS vector string (v4 preferred over v3), or null when OSV supplied no vector (D-044). */
    @Column(name = "cvss")
    private String cvss;

    /** OSV {@code database_specific.severity} (CRITICAL/HIGH/MODERATE/LOW): the no-CVSS base source (D-044). */
    @Column(name = "severity_label")
    private String severityLabel;

    /** CWE ids from OSV {@code database_specific.cwe_ids}, in array order; jsonb (may be empty). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cwe", columnDefinition = "jsonb")
    private List<String> cwe;

    /** FIRST EPSS probability for this finding (stored + displayed; adjustments OFF in M5, D-047). */
    @Column(name = "epss")
    private BigDecimal epss;

    /** True when the vuln id or any {@link #cveAliases} is in the CISA KEV catalog (D-046). */
    @Column(name = "in_kev", nullable = false)
    private boolean inKev = false;

    /** The group's CVE aliases, for KEV "any CVE alias" matching and DB-only re-score (D-051). NOT a fingerprint input. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cve_aliases", columnDefinition = "jsonb")
    private List<String> cveAliases;

    /** OSV advisory one-line summary — the subtitle fallback when cwe is empty/unknown (D-048). Descriptive only. */
    @Column(name = "advisory_summary")
    private String advisorySummary;

    @Column(name = "status", nullable = false)
    private String status = STATUS_OPEN;

    /** The reason CODE while ignored (EXCLUDED_PATH / IGNORE_RULE / DEV_DEPENDENCY / ...); null when not ignored. */
    @Column(name = "ignore_reason")
    private String ignoreReason;

    /** The manual/ .secscan.yml rule currently holding this ignored (D-058); null for a cause-based auto-ignore. */
    @Column(name = "ignored_by_rule_id")
    private UUID ignoredByRuleId;

    @Column(name = "ignored_at")
    private Instant ignoredAt;

    /**
     * The highest-precedence AUTO cause currently detected for this finding (EXCLUDED_PATH / SECRET_PLACEHOLDER /
     * DEV_DEPENDENCY / TEST_SCOPE / LOW_EPSS / DUPLICATE_RULE), or null when no auto cause applies (SF1, V18).
     * Written every reconcile INDEPENDENTLY of {@link #ignoreReason} — even when an {@code IGNORE_RULE} holds the
     * finding — so the API (removeRule / EPSS sweep) can keep a finding ignored under a scan-time-only cause it
     * cannot itself recompute. Writing it is never a status change and never writes a triage_event.
     */
    @Column(name = "auto_ignore_reason")
    private String autoIgnoreReason;

    @Column(name = "first_seen_scan_id")
    private UUID firstSeenScanId;

    @Column(name = "last_seen_scan_id")
    private UUID lastSeenScanId;

    @Column(name = "solved_at")
    private Instant solvedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected SubIssue() {
        // for JPA
    }

    public SubIssue(UUID workspaceId, UUID groupId, UUID repositoryId, String type, String scanner,
                    String fingerprint, UUID firstSeenScanId) {
        this.workspaceId = workspaceId;
        this.groupId = groupId;
        this.repositoryId = repositoryId;
        this.type = type;
        this.scanner = scanner;
        this.fingerprint = fingerprint;
        this.status = STATUS_OPEN;
        this.firstSeenScanId = firstSeenScanId;
        this.lastSeenScanId = firstSeenScanId;
    }

    /** Records that this scan saw the finding again (no status change). */
    public void recordSeen(UUID scanId) {
        this.lastSeenScanId = scanId;
    }

    /** Reopens a previously solved finding seen again in this scan. */
    public void reopen(UUID scanId) {
        this.status = STATUS_OPEN;
        this.solvedAt = null;
        this.lastSeenScanId = scanId;
    }

    /** Marks an open finding solved because it was absent from a scan whose scanner succeeded. */
    public void solve() {
        this.status = STATUS_SOLVED;
        this.solvedAt = Instant.now();
    }

    /**
     * Moves the finding to {@code ignored} (M7, domain rule 2: status changes only through reconcile, each with a
     * triage_event written by the caller). {@code reasonCode} is one of the triage reason codes; {@code ruleId}
     * is the manual/.secscan.yml rule holding it, or null for a cause-based auto-ignore (EXCLUDED_PATH, LOW_EPSS,
     * DEV_DEPENDENCY, SECRET_PLACEHOLDER, DUPLICATE_RULE). Clears {@code solvedAt} — an ignored finding is not solved.
     */
    public void ignore(String reasonCode, UUID ruleId) {
        this.status = STATUS_IGNORED;
        this.ignoreReason = reasonCode;
        this.ignoredByRuleId = ruleId;
        this.ignoredAt = Instant.now();
        this.solvedAt = null;
    }

    /**
     * Returns the finding to its reconcile-computed status (unignore). {@code newStatus} is {@link #STATUS_OPEN}
     * or {@link #STATUS_SOLVED} as computed from the latest scan where its scanner succeeded (D-058). Clears the
     * ignore markers; sets/clears {@code solvedAt} to match the new status.
     */
    public void unignore(String newStatus) {
        this.status = newStatus;
        this.ignoreReason = null;
        this.ignoredByRuleId = null;
        this.ignoredAt = null;
        this.solvedAt = STATUS_SOLVED.equals(newStatus) ? Instant.now() : null;
    }

    // --- mutable descriptive fields, refreshed from the latest finding ---

    public void setVulnId(String vulnId) {
        this.vulnId = vulnId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void setInstalledVersion(String installedVersion) {
        this.installedVersion = installedVersion;
    }

    public void setFixedVersion(String fixedVersion) {
        this.fixedVersion = fixedVersion;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public void setLine(Integer line) {
        this.line = line;
    }

    public void setSnippetHash(String snippetHash) {
        this.snippetHash = snippetHash;
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public void setSecretPreview(String secretPreview) {
        this.secretPreview = secretPreview;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public void setToolSeverity(String toolSeverity) {
        this.toolSeverity = toolSeverity;
    }

    public void setCvss(String cvss) {
        this.cvss = cvss;
    }

    public void setSeverityLabel(String severityLabel) {
        this.severityLabel = severityLabel;
    }

    public void setCwe(List<String> cwe) {
        this.cwe = cwe;
    }

    public void setCveAliases(List<String> cveAliases) {
        this.cveAliases = cveAliases;
    }

    public void setAdvisorySummary(String advisorySummary) {
        this.advisorySummary = advisorySummary;
    }

    // --- scoring outputs, set by the scorer (scan-time reconcile and the api re-score job) ---

    public void setScore(Integer score) {
        this.score = score;
    }

    public void setScoreBreakdown(List<ScoreLine> scoreBreakdown) {
        this.scoreBreakdown = scoreBreakdown;
    }

    public void setEpss(BigDecimal epss) {
        this.epss = epss;
    }

    public void setInKev(boolean inKev) {
        this.inKev = inKev;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getType() {
        return type;
    }

    public String getScanner() {
        return scanner;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getVulnId() {
        return vulnId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getInstalledVersion() {
        return installedVersion;
    }

    public String getFixedVersion() {
        return fixedVersion;
    }

    public String getFilePath() {
        return filePath;
    }

    public Integer getLine() {
        return line;
    }

    public String getSnippetHash() {
        return snippetHash;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public String getSecretPreview() {
        return secretPreview;
    }

    public String getKeyId() {
        return keyId;
    }

    public String getToolSeverity() {
        return toolSeverity;
    }

    public Integer getScore() {
        return score;
    }

    public List<ScoreLine> getScoreBreakdown() {
        return scoreBreakdown;
    }

    public String getCvss() {
        return cvss;
    }

    public String getSeverityLabel() {
        return severityLabel;
    }

    public List<String> getCwe() {
        return cwe;
    }

    public BigDecimal getEpss() {
        return epss;
    }

    public boolean isInKev() {
        return inKev;
    }

    public List<String> getCveAliases() {
        return cveAliases;
    }

    public String getAdvisorySummary() {
        return advisorySummary;
    }

    public String getStatus() {
        return status;
    }

    public String getAutoIgnoreReason() {
        return autoIgnoreReason;
    }

    /** Sets the detected auto cause (SF1). Never a status change; the caller writes no triage_event. */
    public void setAutoIgnoreReason(String autoIgnoreReason) {
        this.autoIgnoreReason = autoIgnoreReason;
    }

    public String getIgnoreReason() {
        return ignoreReason;
    }

    public UUID getIgnoredByRuleId() {
        return ignoredByRuleId;
    }

    public Instant getIgnoredAt() {
        return ignoredAt;
    }

    public UUID getFirstSeenScanId() {
        return firstSeenScanId;
    }

    public UUID getLastSeenScanId() {
        return lastSeenScanId;
    }

    public Instant getSolvedAt() {
        return solvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
