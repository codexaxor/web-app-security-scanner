package com.company.secscan.intel;

import org.slf4j.Logger;

/**
 * Empty/short-feed guard for the KEV/EPSS full-refresh loads (SF2). A full refresh deletes the old catalog
 * before loading the new one, so a transient truncated or garbled download must never be allowed to replace a
 * healthy catalog with (near-)nothing. {@link #shouldReplace} returns false — and logs an error — when the
 * freshly parsed row count is 0, below an absolute floor, or a drastic shrink relative to what is already
 * stored; the caller then keeps the existing rows untouched.
 */
final class IntelLoadGuard {

    private IntelLoadGuard() {
    }

    /**
     * @param newCount rows just parsed from the feed
     * @param currentCount rows currently in the table
     * @param floor absolute minimum acceptable row count for this feed
     * @param minFraction new count must be at least this fraction of {@code currentCount}
     * @return true to proceed with the replace; false to abort and keep the existing rows (an error is logged)
     */
    static boolean shouldReplace(Logger log, String feed, int newCount, long currentCount, int floor,
                                 double minFraction) {
        if (newCount == 0) {
            log.error("{} sync: feed parsed to 0 rows; keeping the existing {} row(s) (SF2).", feed, currentCount);
            return false;
        }
        if (newCount < floor) {
            log.error("{} sync: feed parsed to {} rows, below the sanity floor of {}; keeping the existing {} "
                    + "row(s) (SF2).", feed, newCount, floor, currentCount);
            return false;
        }
        if (currentCount > 0 && newCount < minFraction * currentCount) {
            log.error("{} sync: feed parsed to {} rows, below {}% of the current {} row(s); keeping the existing "
                    + "rows (SF2).", feed, newCount, Math.round(minFraction * 100), currentCount);
            return false;
        }
        return true;
    }
}
