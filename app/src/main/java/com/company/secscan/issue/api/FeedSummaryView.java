package com.company.secscan.issue.api;

/**
 * The Feed summary bar (OD-14 item 4). {@code bands} are group counts for the current view (Focus/All) + type +
 * repository + includeInactive filters. The last-7-day buckets are distinct sub-issues ("findings"), honouring
 * type + repository filters only (not band, not view — change 3): {@code newFindings} first seen in 7 days,
 * {@code solved} (a triage_event to solved), {@code autoIgnored} (a triage_event to ignored by the system).
 */
public record FeedSummaryView(BandCounts bands, long totalGroups,
                              long newFindings, long solved, long autoIgnored) {

    public record BandCounts(long critical, long high, long medium, long low) {
    }
}
