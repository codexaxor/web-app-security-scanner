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
}
