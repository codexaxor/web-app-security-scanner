package com.company.secscan.finding;

import java.util.List;

/**
 * One normalized finding from a scanner (pipeline step 7), tool-agnostic and free of Spring/JPA so it is
 * unit-testable and reusable by M3's reconcile. A secret finding carries only {@link #secretHash} (SHA-256) and
 * {@link #secretPreview} (first 4 characters) — never the raw value (CLAUDE.md security rules).
 *
 * <p>Fields not relevant to a finding's type are {@code null} (e.g. a secret has no package/version). The M5
 * SCA enrichment ({@link #cvss}, {@link #severityLabel}, {@link #cwe}, {@link #cveAliases},
 * {@link #advisorySummary}) is descriptive scoring input carried onto the sub-issue; none of it ever feeds a
 * fingerprint (domain rule 2).
 */
public record RawFinding(
        String type,
        String scanner,
        String ruleId,
        String vulnId,
        String packageName,
        String ecosystem,
        String installedVersion,
        String fixedVersion,
        String filePath,
        Integer line,
        String snippetHash,
        String secretHash,
        String secretPreview,
        String toolSeverity,
        String cvss,
        String severityLabel,
        List<String> cwe,
        List<String> cveAliases,
        String advisorySummary) {

    public static final String TYPE_SCA = "sca";
    public static final String TYPE_SAST = "sast";
    public static final String TYPE_SECRET = "secret";
    public static final String TYPE_IAC = "iac";
    public static final String TYPE_EOL = "eol";

    public static final String SCANNER_SECRET = "secret";
    public static final String SCANNER_SCA = "sca";
    public static final String SCANNER_SAST = "sast";
    public static final String SCANNER_IAC = "iac";
    public static final String SCANNER_EOL = "eol";

    /**
     * A secret finding. The raw value is never passed in; {@code secretHash} is its SHA-256 and
     * {@code secretPreview} its first 4 characters, both computed inside the scanner container (D-023).
     */
    public static RawFinding secret(String ruleId, String filePath, Integer line, String secretHash,
                                    String secretPreview, String toolSeverity) {
        return new RawFinding(TYPE_SECRET, SCANNER_SECRET, ruleId, null, null, null, null, null,
                filePath, line, null, secretHash, secretPreview, toolSeverity, null, null, null, null, null);
    }

    /**
     * A dependency (SCA) finding: one per OSV alias group (D-030 / plan change #3). {@code vulnId} is the
     * group's preferred id (lowest-sorted CVE, else GHSA/OSV), {@code ecosystem}/{@code packageName} drive the
     * group key, and {@code filePath} is the repo-relative manifest path. {@code toolSeverity} is OSV's
     * {@code max_severity} (the CVSS base number); {@code cvss} the chosen vector string (null when none);
     * {@code severityLabel} the advisory severity; {@code cwe}/{@code cveAliases} the OSV arrays; and
     * {@code advisorySummary} the one-line summary (subtitle fallback). No secret fields.
     */
    public static RawFinding sca(String vulnId, String packageName, String ecosystem, String installedVersion,
                                 String fixedVersion, String manifestPath, String toolSeverity, String cvss,
                                 String severityLabel, List<String> cwe, List<String> cveAliases,
                                 String advisorySummary) {
        return new RawFinding(TYPE_SCA, SCANNER_SCA, null, vulnId, packageName, ecosystem, installedVersion,
                fixedVersion, manifestPath, null, null, null, null, toolSeverity, cvss, severityLabel, cwe,
                cveAliases, advisorySummary);
    }

    /**
     * A SAST finding (Opengrep, M6). {@code ruleId} is the Opengrep {@code check_id} and {@code filePath} the
     * repo-relative path; {@code snippetHash} is the normalized-snippet hash ({@link SnippetNormalizer}) — these
     * three are the SAST fingerprint ({@code rule_id | file_path | normalized_snippet_hash}), never the line
     * number. {@code line} is carried for display only. {@code severityLabel} is the rule severity
     * (CRITICAL/HIGH/MEDIUM/LOW, mapped from Opengrep's metadata/level) that drives the SAST base (D-053);
     * {@code toolSeverity} is Opengrep's raw level; {@code cwe} the rule's CWE ids (subtitle input).
     */
    public static RawFinding sast(String ruleId, String filePath, Integer line, String snippetHash,
                                  String severityLabel, String toolSeverity, List<String> cwe) {
        return new RawFinding(TYPE_SAST, SCANNER_SAST, ruleId, null, null, null, null, null, filePath, line,
                snippetHash, null, null, toolSeverity, null, severityLabel, cwe, null, null);
    }

    /**
     * An IaC finding (Checkov, M6). {@code ruleId} is the Checkov {@code check_id}, {@code resourceName} the
     * Checkov {@code resource} (e.g. {@code aws_s3_bucket.foo}) — with {@code filePath} these are the IaC
     * fingerprint ({@code rule_id | file_path | resource_name}), never the line number. The resource name is
     * carried in {@code packageName} (the column reused for it; see {@code ReconcileService.fingerprintOf}).
     * {@code line} is display only; {@code severityLabel} is usually null (OSS Checkov checks carry no
     * severity), which the scorer maps to the IaC base MEDIUM (D-054).
     */
    public static RawFinding iac(String ruleId, String filePath, String resourceName, Integer line,
                                 String severityLabel) {
        return new RawFinding(TYPE_IAC, SCANNER_IAC, ruleId, null, resourceName, null, null, null, filePath,
                line, null, null, null, null, null, severityLabel, null, null, null);
    }
}
