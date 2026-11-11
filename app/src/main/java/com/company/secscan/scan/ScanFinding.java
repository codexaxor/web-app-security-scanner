package com.company.secscan.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A raw normalized finding from one scanner (D-024, pipeline step 7). Stored per scan with no dedup or
 * reconcile — M3 turns these into {@code issue_group}/{@code sub_issue} with stable fingerprints. A secret
 * finding carries only {@code secretHash} (a keyed HMAC-SHA256 of the container's SHA-256, D-027) and
 * {@code secretPreview} (masked); the raw value, and even the bare SHA-256, are never stored (CLAUDE.md
 * security rules).
 */
@Entity
@Table(name = "scan_finding")
public class ScanFinding {

    public static final String TYPE_SCA = "sca";
    public static final String TYPE_SAST = "sast";
    public static final String TYPE_SECRET = "secret";
    public static final String TYPE_IAC = "iac";
    public static final String TYPE_EOL = "eol";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "scanner", nullable = false)
    private String scanner;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "vuln_id")
    private String vulnId;

    @Column(name = "package")
    private String packageName;

    @Column(name = "ecosystem")
    private String ecosystem;

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

    @Column(name = "tool_severity")
    private String toolSeverity;

    // --- M5 OSV enrichment (descriptive; carried to the sub-issue, never a fingerprint input) ---

    @Column(name = "cvss")
    private String cvss;

    @Column(name = "severity_label")
    private String severityLabel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cwe", columnDefinition = "jsonb")
    private List<String> cwe;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cve_aliases", columnDefinition = "jsonb")
    private List<String> cveAliases;

    @Column(name = "advisory_summary")
    private String advisorySummary;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ScanFinding() {
        // for JPA
    }

    private ScanFinding(UUID workspaceId, UUID scanId, UUID repositoryId, String type, String scanner) {
        this.workspaceId = workspaceId;
        this.scanId = scanId;
        this.repositoryId = repositoryId;
        this.type = type;
        this.scanner = scanner;
    }

    /**
     * A secret finding. {@code secretHash} is the keyed HMAC-SHA256 of the container's SHA-256 of the secret
     * (D-027) and {@code secretPreview} is a masked preview — the value itself, and the bare digest, are never
     * passed in or stored.
     */
    public static ScanFinding secret(UUID workspaceId, UUID scanId, UUID repositoryId, String ruleId,
                                     String filePath, Integer line, String secretHash, String secretPreview,
                                     String toolSeverity) {
        ScanFinding f = new ScanFinding(workspaceId, scanId, repositoryId, TYPE_SECRET,
                ScanTask.SCANNER_SECRET);
        f.ruleId = ruleId;
        f.filePath = filePath;
        f.line = line;
        f.secretHash = secretHash;
        f.secretPreview = secretPreview;
        f.toolSeverity = toolSeverity;
        return f;
    }

    /**
     * A dependency (SCA) finding from OSV-Scanner (M4): {@code vulnId} is the preferred id (CVE, else GHSA/OSV),
     * {@code ecosystem}/{@code packageName} drive the group key, and {@code filePath} is the repo-relative
     * manifest path. No secret fields.
     */
    public static ScanFinding sca(UUID workspaceId, UUID scanId, UUID repositoryId, String vulnId,
                                  String packageName, String ecosystem, String installedVersion,
                                  String fixedVersion, String filePath, String toolSeverity) {
        ScanFinding f = new ScanFinding(workspaceId, scanId, repositoryId, TYPE_SCA, ScanTask.SCANNER_SCA);
        f.vulnId = vulnId;
        f.packageName = packageName;
        f.ecosystem = ecosystem;
        f.installedVersion = installedVersion;
        f.fixedVersion = fixedVersion;
        f.filePath = filePath;
        f.toolSeverity = toolSeverity;
        return f;
    }

    /**
     * A SAST finding from Opengrep (M6). {@code ruleId} = Opengrep check_id, {@code snippetHash} = normalized
     * snippet hash — with {@code filePath} these are the SAST fingerprint (never {@code line}). {@code line} is
     * display-only; {@code severityLabel} (the rule severity) and {@code cwe} are descriptive scoring inputs.
     */
    public static ScanFinding sast(UUID workspaceId, UUID scanId, UUID repositoryId, String ruleId,
                                   String filePath, Integer line, String snippetHash, String severityLabel,
                                   String toolSeverity, List<String> cwe) {
        ScanFinding f = new ScanFinding(workspaceId, scanId, repositoryId, TYPE_SAST, ScanTask.SCANNER_SAST);
        f.ruleId = ruleId;
        f.filePath = filePath;
        f.line = line;
        f.snippetHash = snippetHash;
        f.severityLabel = severityLabel;
        f.toolSeverity = toolSeverity;
        f.cwe = cwe;
        return f;
    }

    /**
     * An IaC finding from Checkov (M6). {@code ruleId} = check_id, {@code resourceName} = Checkov resource
     * (stored in the {@code package} column) — with {@code filePath} these are the IaC fingerprint (never
     * {@code line}). {@code line} is display-only; {@code severityLabel} is usually null (→ base MEDIUM, D-054).
     */
    public static ScanFinding iac(UUID workspaceId, UUID scanId, UUID repositoryId, String ruleId,
                                  String filePath, String resourceName, Integer line, String severityLabel) {
        ScanFinding f = new ScanFinding(workspaceId, scanId, repositoryId, TYPE_IAC, ScanTask.SCANNER_IAC);
        f.ruleId = ruleId;
        f.filePath = filePath;
        f.packageName = resourceName; // the resource name is carried in the package column (fingerprint input)
        f.line = line;
        f.severityLabel = severityLabel;
        return f;
    }

    /**
     * An EOL finding (static detector, M6). {@code product} is carried in the {@code package} column and
     * {@code cycle} in {@code installed_version} — with {@code filePath} these are the EOL fingerprint
     * ({@code product | cycle | file_path}), never {@code line}. The EOL date/base come from the eol_cycle cache
     * at score time, not here.
     */
    public static ScanFinding eol(UUID workspaceId, UUID scanId, UUID repositoryId, String product, String cycle,
                                  String filePath) {
        ScanFinding f = new ScanFinding(workspaceId, scanId, repositoryId, TYPE_EOL, ScanTask.SCANNER_EOL);
        f.packageName = product;
        f.installedVersion = cycle;
        f.filePath = filePath;
        return f;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getScanId() {
        return scanId;
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

    public String getRuleId() {
        return ruleId;
    }

    public String getVulnId() {
        return vulnId;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getEcosystem() {
        return ecosystem;
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

    public String getToolSeverity() {
        return toolSeverity;
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

    public List<String> getCveAliases() {
        return cveAliases;
    }

    public String getAdvisorySummary() {
        return advisorySummary;
    }

    /** Sets the one-line advisory/description (M8.5: the Checkov check_name for IaC). Descriptive only. */
    public void setAdvisorySummary(String advisorySummary) {
        this.advisorySummary = advisorySummary;
    }

    /** Sets the OSV-derived descriptive enrichment (M5) after the SCA factory builds the row. */
    public void setOsvEnrichment(String cvss, String severityLabel, List<String> cwe, List<String> cveAliases,
                                 String advisorySummary) {
        this.cvss = cvss;
        this.severityLabel = severityLabel;
        this.cwe = cwe;
        this.cveAliases = cveAliases;
        this.advisorySummary = advisorySummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
