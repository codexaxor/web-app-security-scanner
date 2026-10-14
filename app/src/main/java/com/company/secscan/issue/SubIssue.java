package com.company.secscan.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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

    @Column(name = "status", nullable = false)
    private String status = STATUS_OPEN;

    @Column(name = "ignore_reason")
    private String ignoreReason;

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

    public String getStatus() {
        return status;
    }

    public String getIgnoreReason() {
        return ignoreReason;
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
