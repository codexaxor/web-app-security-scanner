package com.company.secscan.finding;

/**
 * One normalized finding from a scanner (pipeline step 7), tool-agnostic and free of Spring/JPA so it is
 * unit-testable and reusable by M3's reconcile. A secret finding carries only {@link #secretHash} (SHA-256) and
 * {@link #secretPreview} (first 4 characters) — never the raw value (CLAUDE.md security rules).
 *
 * <p>Fields not relevant to a finding's type are {@code null} (e.g. a secret has no package/version).
 */
public record RawFinding(
        String type,
        String scanner,
        String ruleId,
        String vulnId,
        String packageName,
        String installedVersion,
        String fixedVersion,
        String filePath,
        Integer line,
        String snippetHash,
        String secretHash,
        String secretPreview,
        String toolSeverity) {

    public static final String TYPE_SCA = "sca";
    public static final String TYPE_SAST = "sast";
    public static final String TYPE_SECRET = "secret";
    public static final String TYPE_IAC = "iac";
    public static final String TYPE_EOL = "eol";

    public static final String SCANNER_SECRET = "secret";

    /**
     * A secret finding. The raw value is never passed in; {@code secretHash} is its SHA-256 and
     * {@code secretPreview} its first 4 characters, both computed inside the scanner container (D-023).
     */
    public static RawFinding secret(String ruleId, String filePath, Integer line, String secretHash,
                                    String secretPreview, String toolSeverity) {
        return new RawFinding(TYPE_SECRET, SCANNER_SECRET, ruleId, null, null, null, null,
                filePath, line, null, secretHash, secretPreview, toolSeverity);
    }
}
