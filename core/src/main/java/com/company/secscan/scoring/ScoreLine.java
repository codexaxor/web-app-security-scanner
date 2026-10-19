package com.company.secscan.scoring;

/**
 * One itemized entry in a sub-issue's {@code score_breakdown} (SPEC "Severity": every adjustment is stored so
 * the UI can explain the score). {@code code} is a stable machine code (e.g. {@code base_cvss}, {@code on_kev},
 * {@code sensitivity_high}, {@code clamp}); {@code detail} is a human-readable note; {@code delta} is the signed
 * contribution to the running score (the base line carries the base value as its delta). Persisted as jsonb and
 * NEVER read back to drive scoring — the base is always recomputed from the persisted inputs (D-044).
 */
public record ScoreLine(String code, String detail, int delta) {
}
