package com.company.secscan.scoring;

import com.company.secscan.finding.RawFinding;
import com.company.secscan.triage.EpssPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The single scoring function both callers use — the worker at scan time (via reconcile) and the api re-score
 * job — so a stored score and a recomputed score are byte-for-byte the same (SPEC "Severity"; D-044..D-049).
 * Pure: no Spring, no I/O, no clock. KEV/EPSS/sensitivity are resolved by the caller and handed in via
 * {@link ScoreInput}.
 *
 * <p>Base per type — SCA (D-044) / secret (D-045) / SAST (D-053) / IaC (D-054) / EOL (D-055) — plus active
 * adjustments: on-KEV +5, repo sensitivity high +5 / low −10, and the SAST test-path −15 (M6). EPSS tiers and
 * PoC +1 are still NOT applied. Every contribution is an itemized {@link ScoreLine} whose {@code delta}s sum to
 * the final score (a {@code clamp} line absorbs the difference when the raw total falls outside 1–100), so the
 * UI can explain the number without ever reading the base back out of the breakdown.
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

    /** SAST/IaC base by rule severity (SPEC; D-053/D-054): critical 90 / high 75 / medium 50 / low 25. */
    static final int SAST_IAC_CRITICAL = 90;
    static final int SAST_IAC_HIGH = 75;
    static final int SAST_IAC_MEDIUM = 50; // also the default (Opengrep level fallback; Checkov OSS no-severity)
    static final int SAST_IAC_LOW = 25;

    /** EOL base (SPEC; D-055): past end of life 75 / ends within 90 days 45. */
    static final int EOL_PAST = 75;
    static final int EOL_ENDING_SOON = 45;

    /** SAST finding in a test path: −15 (SPEC adjustment, Default On; M6). */
    static final int TEST_PATH_DELTA = -15;

    /** Secret base by Betterleaks rule id (D-045): private-key & cloud-credential 90, other tokens 75, passwords 60. */
    private static final int SECRET_BASE_KEY_OR_CLOUD = 90;
    private static final int SECRET_BASE_TOKEN = 75; // also the unmapped default
    private static final int SECRET_BASE_PASSWORD = 60;

    // SF7: only rule ids VERIFIED to exist in the pinned Betterleaks 1.7.3 default ruleset are mapped; every other
    // rule falls through to the 75 default and is logged once per run (D-045) so it can be mapped deliberately.
    // Verified against: D-045 (private-key, github-pat known-good), the captured real Betterleaks output sample
    // (github-pat), and the secret fixtures' documented 1.7.3 behaviour (fixtures/secrets-token/README.md:
    // github-pat is stable; aws-access-token is a composite rule in 1.7.3; a lone AWS key falls to generic-api-key;
    // fixtures/secrets-suppressed likewise). Unverifiable guesses (aws-access-key, private-key-pem, gcp-*, azure-*,
    // generic-password, password-in-url, github-oauth/app-token, gitlab-pat, slack-*, stripe-*, jwt) were removed —
    // the full 442-rule ruleset could not be re-fetched here (github egress blocked). No verified password rule id
    // yet, so SECRET_RULES_PASSWORD is intentionally empty (passwords currently take the 75 default).
    private static final Set<String> SECRET_RULES_KEY_OR_CLOUD = Set.of(
            "private-key", "aws-access-token");
    private static final Set<String> SECRET_RULES_PASSWORD = Set.of();
    private static final Set<String> SECRET_RULES_TOKEN = Set.of(
            "github-pat", "generic-api-key");

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

        // SAST test-path adjustment (SPEC, Default On; M6): a finding in a test path is lower priority. It is an
        // itemized adjustment (not an exclusion — that is M7's EXCLUDED_PATH), and applies to SAST only.
        if (RawFinding.TYPE_SAST.equals(in.type()) && in.testPath()) {
            breakdown.add(new ScoreLine("test_path", "SAST finding in a test path", TEST_PATH_DELTA));
            raw += TEST_PATH_DELTA;
        }

        // EPSS scoring tier (SPEC adjustment, Default Off; D-060): [0,5%) −10, [5%,10%) −5, else 0. Scoring-only
        // (no status change, no triage_event — the LOW_EPSS auto-ignore is a separate reconcile/on-toggle path),
        // and the two tiers are mutually exclusive so they never double-apply. Applied only when the workspace
        // EPSS toggle is on. EPSS is a descriptive, NON-fingerprint input (D-051).
        if (in.epssAdjustmentsEnabled()) {
            int epssDelta = EpssPolicy.scoreDelta(in.epss());
            if (epssDelta != 0) {
                breakdown.add(new ScoreLine("epss_tier", EpssPolicy.tierLabel(in.epss()), epssDelta));
                raw += epssDelta;
            }
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
        if (RawFinding.TYPE_SAST.equals(in.type())) {
            return sastIacBase(in.severityLabel(), "base_sast", "SAST rule severity ");
        }
        if (RawFinding.TYPE_IAC.equals(in.type())) {
            return sastIacBase(in.severityLabel(), "base_iac", "IaC rule severity ");
        }
        if (RawFinding.TYPE_EOL.equals(in.type())) {
            return eolBase(in.eolStatus());
        }
        // SCA: prefer the CVSS base number, else the advisory severity label.
        Double num = parseSeverity(in.maxSeverity());
        if (num != null) {
            int value = (int) Math.round(num * 10);
            value = Math.max(MIN_SCORE, Math.min(MAX_SCORE, value));
            String cvssBase = in.maxSeverity().trim();
            return new BaseResult("base_cvss", "CVSS base " + cvssBase + " × 10", value, "cvss_base");
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

    /**
     * SAST/IaC base from the rule severity label (D-053/D-054): CRITICAL 90 / HIGH 75 / MEDIUM 50 / LOW 25.
     * An absent/unknown label defaults to MEDIUM (50) — the Opengrep level fallback already yields one of these,
     * and OSS Checkov checks carry no severity, so IaC defaults to MEDIUM (NIT 7).
     */
    private static BaseResult sastIacBase(String label, String code, String detailPrefix) {
        String norm = label == null ? "" : label.trim().toUpperCase(java.util.Locale.ROOT);
        int value = switch (norm) {
            case "CRITICAL" -> SAST_IAC_CRITICAL;
            case "HIGH" -> SAST_IAC_HIGH;
            case "LOW" -> SAST_IAC_LOW;
            default -> SAST_IAC_MEDIUM; // MEDIUM and unknown/absent
        };
        String shown = norm.isEmpty() ? "unspecified (default MEDIUM)" : norm;
        return new BaseResult(code, detailPrefix + shown, value, "label");
    }

    /** EOL base (D-055): past-EOL 75 / ending-soon 45; an indeterminate status defaults to the ending-soon
     * floor (45) — conservative; a persisted EOL finding always had a determinate status when created. */
    private static BaseResult eolBase(String eolStatus) {
        if (com.company.secscan.eol.EolStatus.PAST_EOL.equals(eolStatus)) {
            return new BaseResult("base_eol", "Runtime/framework is past end of life", EOL_PAST, "eol");
        }
        return new BaseResult("base_eol", "Runtime/framework reaches end of life within 90 days",
                EOL_ENDING_SOON, "eol");
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
