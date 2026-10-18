package com.company.secscan.scoring;

import com.company.secscan.finding.RawFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The single scoring function both callers use — the worker at scan time (via reconcile) and the api re-score
 * job — so a stored score and a recomputed score are byte-for-byte the same (SPEC "Severity"; D-044..D-049).
 * Pure: no Spring, no I/O, no clock. KEV/EPSS/sensitivity are resolved by the caller and handed in via
 * {@link ScoreInput}.
 *
 * <p>Base (D-044 SCA / D-045 secret) + active adjustments (on-KEV +5; repo sensitivity high +5 / low −10),
 * clamped to 1–100. EPSS tiers, PoC +1 and SAST test-path −15 are NOT applied in M5. Every contribution is an
 * itemized {@link ScoreLine} whose {@code delta}s sum to the final score (a {@code clamp} line absorbs the
 * difference when the raw total falls outside 1–100), so the UI can explain the number without ever reading the
 * base back out of the breakdown.
 */
public final class Scoring {

    public static final String BAND_CRITICAL = "critical";
    public static final String BAND_HIGH = "high";
    public static final String BAND_MEDIUM = "medium";
    public static final String BAND_LOW = "low";

    public static final int MIN_SCORE = 1;
    public static final int MAX_SCORE = 100;

    static final int KEV_DELTA = 5;
    static final int SENSITIVITY_HIGH_DELTA = 5;
    static final int SENSITIVITY_LOW_DELTA = -10;

    /** No-CVSS base from the advisory severity label (D-044). An unknown/absent label defaults to MODERATE. */
    static final int BASE_CRITICAL = 95;
    static final int BASE_HIGH = 80;
    static final int BASE_MODERATE = 55;
    static final int BASE_LOW = 25;

    /** Secret base by Betterleaks rule id (D-045): private-key & cloud-credential 90, other tokens 75, passwords 60. */
    private static final int SECRET_BASE_KEY_OR_CLOUD = 90;
    private static final int SECRET_BASE_TOKEN = 75; // also the unmapped default
    private static final int SECRET_BASE_PASSWORD = 60;

    private static final Set<String> SECRET_RULES_KEY_OR_CLOUD = Set.of(
            "private-key", "aws-access-token", "aws-access-key", "gcp-api-key", "gcp-service-account",
            "azure-storage-account-access-key", "azure-ad-client-secret", "private-key-pem");
    private static final Set<String> SECRET_RULES_PASSWORD = Set.of(
            "generic-password", "password-in-url");
    private static final Set<String> SECRET_RULES_TOKEN = Set.of(
            "github-pat", "github-oauth", "github-app-token", "gitlab-pat", "slack-access-token",
            "stripe-access-token", "jwt", "generic-api-key");

    private Scoring() {
    }

    public static ScoreResult score(ScoreInput in) {
        List<ScoreLine> breakdown = new ArrayList<>();
        BaseResult base = base(in);
        breakdown.add(new ScoreLine(base.code, base.detail, base.value));
        int raw = base.value;

        if (in.onKev()) {
            breakdown.add(new ScoreLine("on_kev", "Listed in the CISA Known Exploited Vulnerabilities catalog",
                    KEV_DELTA));
            raw += KEV_DELTA;
        }
        String sensitivity = in.repoSensitivity() == null ? "" : in.repoSensitivity();
        if ("high".equalsIgnoreCase(sensitivity)) {
            breakdown.add(new ScoreLine("sensitivity_high", "Repository marked high sensitivity",
                    SENSITIVITY_HIGH_DELTA));
            raw += SENSITIVITY_HIGH_DELTA;
        } else if ("low".equalsIgnoreCase(sensitivity)) {
            breakdown.add(new ScoreLine("sensitivity_low", "Repository marked low sensitivity",
                    SENSITIVITY_LOW_DELTA));
            raw += SENSITIVITY_LOW_DELTA;
        }

        int clamped = Math.max(MIN_SCORE, Math.min(MAX_SCORE, raw));
        if (clamped != raw) {
            breakdown.add(new ScoreLine("clamp", "Clamped to the 1–100 range (was " + raw + ")", clamped - raw));
        }
        return new ScoreResult(clamped, band(clamped), List.copyOf(breakdown), base.source);
    }

    /** The SPEC severity band for a 0–100 score. */
    public static String band(int score) {
        if (score >= 90) {
            return BAND_CRITICAL;
        }
        if (score >= 70) {
            return BAND_HIGH;
        }
        if (score >= 40) {
            return BAND_MEDIUM;
        }
        return BAND_LOW;
    }

    /**
     * True when a Betterleaks rule id maps to a known secret base. The caller (which has logging) warns when a
     * rule is unmapped (it still scores 75, D-045); core stays logging-free and pure.
     */
    public static boolean isMappedSecretRule(String ruleId) {
        return SECRET_RULES_KEY_OR_CLOUD.contains(ruleId)
                || SECRET_RULES_PASSWORD.contains(ruleId)
                || SECRET_RULES_TOKEN.contains(ruleId);
    }

    private static BaseResult base(ScoreInput in) {
        if (RawFinding.TYPE_SECRET.equals(in.type())) {
            return secretBase(in.secretRuleId());
        }
        // SCA (and, until M6 wires their own bases, any CVSS/label-bearing type): prefer the CVSS base number.
        Double num = parseSeverity(in.maxSeverity());
        if (num != null) {
            int value = (int) Math.round(num * 10);
            value = Math.max(MIN_SCORE, Math.min(MAX_SCORE, value));
            String vector = in.maxSeverity().trim();
            return new BaseResult("base_cvss", "CVSS base " + vector + " × 10", value, "vector");
        }
        int value = labelBase(in.severityLabel());
        String label = in.severityLabel() == null ? "unspecified" : in.severityLabel();
        return new BaseResult("base_label", "No CVSS vector; advisory severity " + label, value, "label");
    }

    private static BaseResult secretBase(String ruleId) {
        int value;
        if (ruleId != null && SECRET_RULES_KEY_OR_CLOUD.contains(ruleId)) {
            value = SECRET_BASE_KEY_OR_CLOUD;
        } else if (ruleId != null && SECRET_RULES_PASSWORD.contains(ruleId)) {
            value = SECRET_BASE_PASSWORD;
        } else {
            value = SECRET_BASE_TOKEN; // known tokens and any unmapped rule (D-045)
        }
        return new BaseResult("base_secret", "Secret rule " + (ruleId == null ? "unknown" : ruleId), value,
                "secret");
    }

    /** CRITICAL 95 / HIGH 80 / MODERATE 55 / LOW 25; unknown/absent → MODERATE (defensive; OSV always labels SCA). */
    private static int labelBase(String label) {
        if (label == null) {
            return BASE_MODERATE;
        }
        return switch (label.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "CRITICAL" -> BASE_CRITICAL;
            case "HIGH" -> BASE_HIGH;
            case "MODERATE", "MEDIUM" -> BASE_MODERATE;
            case "LOW" -> BASE_LOW;
            default -> BASE_MODERATE;
        };
    }

    /** Parses a 0–10 CVSS base string; null/blank/non-numeric → null (fall back to the label). */
    private static Double parseSeverity(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            double v = Double.parseDouble(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record BaseResult(String code, String detail, int value, String source) {
    }
}
