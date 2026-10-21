package com.company.secscan.triage;

/**
 * The EPSS triage/scoring policy (SPEC adjustment table; D-060). Pure, no I/O. EPSS is a probability in
 * {@code [0,1]} (FIRST EPSS); a {@code null} value means "no EPSS known" and never triggers anything.
 *
 * <p>Two SEPARATE effects, kept apart on purpose (D-060):
 * <ul>
 *   <li><b>Status</b> — {@link #isLowEpssAutoIgnore}: {@code EPSS < 1%} AND not on KEV → {@link ReasonCode#LOW_EPSS}
 *       auto-ignore (a real status change at reconcile/on-toggle, with a triage_event);</li>
 *   <li><b>Score only</b> — {@link #scoreDelta}: {@code [0,5%)} → −10, {@code [5%,10%)} → −5, {@code ≥10%} → 0.
 *       The two tiers are mutually exclusive (strict bands) so they never double-apply, and this path writes NO
 *       triage_event.</li>
 * </ul>
 * Both are gated by the workspace EPSS toggle at the caller; a KEV-listed finding is never auto-ignored.
 */
public final class EpssPolicy {

    /** {@code EPSS < this} (and not KEV) → LOW_EPSS auto-ignore. */
    public static final double LOW_EPSS_MAX = 0.01;   // 1%
    /** {@code EPSS < this} → the −10 tier. */
    public static final double TIER_10_MAX = 0.05;    // 5%
    /** {@code EPSS < this} (and ≥ 5%) → the −5 tier. */
    public static final double TIER_5_MAX = 0.10;     // 10%

    public static final int SCORE_DELTA_UNDER_5 = -10;
    public static final int SCORE_DELTA_5_TO_10 = -5;

    private EpssPolicy() {
    }

    /** True when {@code EPSS < 1%} AND the finding is not on KEV — the LOW_EPSS status auto-ignore (D-060). */
    public static boolean isLowEpssAutoIgnore(Double epss, boolean onKev) {
        return epss != null && !onKev && epss < LOW_EPSS_MAX;
    }

    /**
     * The scoring-only EPSS delta (D-060): {@code [0,5%)} → −10, {@code [5%,10%)} → −5, else 0. Strict bands, so
     * exactly one tier applies — never both. {@code null} EPSS → 0.
     */
    public static int scoreDelta(Double epss) {
        if (epss == null) {
            return 0;
        }
        if (epss < TIER_10_MAX) {
            return SCORE_DELTA_UNDER_5;
        }
        if (epss < TIER_5_MAX) {
            return SCORE_DELTA_5_TO_10;
        }
        return 0;
    }

    /** A human label for the applied tier, for the score breakdown; null when no tier applies. */
    public static String tierLabel(Double epss) {
        int delta = scoreDelta(epss);
        if (delta == SCORE_DELTA_UNDER_5) {
            return "EPSS below 5%";
        }
        if (delta == SCORE_DELTA_5_TO_10) {
            return "EPSS between 5% and 10%";
        }
        return null;
    }
}
