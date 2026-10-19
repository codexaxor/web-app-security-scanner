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
 */
public record ScoreInput(
        String type,
        String maxSeverity,
        String severityLabel,
        String secretRuleId,
        boolean onKev,
        String repoSensitivity) {
}
