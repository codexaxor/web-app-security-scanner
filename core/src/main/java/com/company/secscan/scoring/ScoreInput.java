package com.company.secscan.scoring;

/**
 * The persisted inputs a score is derived from (SPEC "Severity"). Deliberately holds only primitives so the
 * scoring function is pure and callable from both the worker (scan-time reconcile) and the api (re-score job),
 * and so the base is always recomputed from these inputs — never read back out of {@code score_breakdown}.
 *
 * @param type            the finding type ({@code sca}, {@code secret}, …)
 * @param maxSeverity     OSV {@code groups[].max_severity} (a 0–10 CVSS base as a string; may be blank) — SCA base
 * @param severityLabel   OSV {@code database_specific.severity} (CRITICAL/HIGH/MODERATE/LOW) — the no-CVSS SCA base
 * @param secretRuleId    the Betterleaks rule id — the secret base (D-045)
 * @param onKev           true when the vuln id or any CVE alias is in the CISA KEV catalog (+5, D-046)
 * @param repoSensitivity the repository sensitivity ({@code high} +5 / {@code low} −10 / else 0)
 * @param eolStatus       for EOL findings, {@link com.company.secscan.eol.EolStatus} — the EOL base (D-055, M6)
 * @param testPath        true when a SAST finding is in a test path (the −15 adjustment, M6)
 * @param epss            the FIRST EPSS probability in [0,1], or null when unknown — the scoring-only tier (D-060)
 * @param epssAdjustmentsEnabled true when the workspace EPSS toggle is on; the EPSS tier applies only then (D-060)
 * @param iacSeverityOverride for an IaC finding, a maintained per-check_id severity label (CRITICAL/HIGH/MEDIUM/
 *                            LOW) that replaces the default MEDIUM base (D-083 OD-27, V20); null when none. It is
 *                            consulted only for IaC and never changes status or the fingerprint.
 */
public record ScoreInput(
        String type,
        String maxSeverity,
        String severityLabel,
        String secretRuleId,
        boolean onKev,
        String repoSensitivity,
        String eolStatus,
        boolean testPath,
        Double epss,
        boolean epssAdjustmentsEnabled,
        String iacSeverityOverride) {

    /** Backward-compatible convenience: no EOL status and not a test path (SCA/secret/KEV callers). */
    public ScoreInput(String type, String maxSeverity, String severityLabel, String secretRuleId,
                      boolean onKev, String repoSensitivity) {
        this(type, maxSeverity, severityLabel, secretRuleId, onKev, repoSensitivity, null, false, null, false,
                null);
    }

    /** Convenience preserving the M6 arity (EOL status + test path), with EPSS adjustments off. */
    public ScoreInput(String type, String maxSeverity, String severityLabel, String secretRuleId,
                      boolean onKev, String repoSensitivity, String eolStatus, boolean testPath) {
        this(type, maxSeverity, severityLabel, secretRuleId, onKev, repoSensitivity, eolStatus, testPath,
                null, false, null);
    }

    /** Convenience preserving the M5/M7 arity (EPSS toggle), with no IaC severity override. */
    public ScoreInput(String type, String maxSeverity, String severityLabel, String secretRuleId,
                      boolean onKev, String repoSensitivity, String eolStatus, boolean testPath,
                      Double epss, boolean epssAdjustmentsEnabled) {
        this(type, maxSeverity, severityLabel, secretRuleId, onKev, repoSensitivity, eolStatus, testPath,
                epss, epssAdjustmentsEnabled, null);
    }
}
