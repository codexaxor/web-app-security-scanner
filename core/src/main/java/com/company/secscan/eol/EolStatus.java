package com.company.secscan.eol;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Classifies a detected release cycle against its endoflife.date facts (D-055). Pure (takes {@code today} so it
 * is clock-free and unit-testable). Used by the worker's EOL enricher to decide whether a detected cycle is an
 * issue worth persisting, and by the scorer for the EOL base (past-EOL 75 / ending-soon 45, Slice 4). Carries
 * no line number and is never a fingerprint input.
 */
public final class EolStatus {

    /** The cycle has reached end of life (an issue; base 75). */
    public static final String PAST_EOL = "past_eol";
    /** The cycle reaches end of life within the warning window (default 90 days) (an issue; base 45). */
    public static final String ENDING_SOON = "ending_soon";
    /** The cycle is still maintained (NOT an issue). */
    public static final String MAINTAINED = "maintained";

    public static final int ENDING_SOON_DAYS = 90;

    private EolStatus() {
    }

    /**
     * @param isEol   endoflife.date {@code isEol} for the cycle
     * @param eolFrom endoflife.date {@code eolFrom} (may be null)
     * @param today   the reference date
     * @return {@link #PAST_EOL}, {@link #ENDING_SOON} or {@link #MAINTAINED}
     */
    public static String of(boolean isEol, LocalDate eolFrom, LocalDate today) {
        if (isEol || (eolFrom != null && !eolFrom.isAfter(today))) {
            return PAST_EOL;
        }
        if (eolFrom != null && ChronoUnit.DAYS.between(today, eolFrom) <= ENDING_SOON_DAYS) {
            return ENDING_SOON;
        }
        return MAINTAINED;
    }

    /** True when the status denotes an actual issue worth surfacing (past-EOL or ending soon). */
    public static boolean isIssue(String status) {
        return PAST_EOL.equals(status) || ENDING_SOON.equals(status);
    }
}
