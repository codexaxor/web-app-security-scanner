package com.company.secscan.dashboard;

import java.util.List;

/**
 * The Dashboard summary (OD-14 item 7): current counts by band for groups and for findings (sub-issues), the
 * issue-type breakdown, scan health (repositories whose last scan is partial/failed or raised warnings), and the
 * inactive-repository line. Inactive repositories are excluded from the band/type totals by default (change 2).
 */
public record DashboardSummaryView(BandCounts groupBands, long totalGroups,
                                   BandCounts findingBands, long totalFindings,
                                   List<TypeCount> byType,
                                   ScanHealth scanHealth,
                                   InactiveLine inactive) {

    public record BandCounts(long critical, long high, long medium, long low) {
    }

    public record TypeCount(String type, long count) {
    }

    /**
     * Scan health: the unhealthy repository count plus the server-computed split the UI renders directly —
     * {@code failedPartialCount} (last scan failed or partial) and {@code withWarningsCount} (completed but
     * raised warnings). The UI shows these numbers verbatim and never recounts the {@code repos} list (N-3).
     */
    public record ScanHealth(long unhealthyRepoCount, long failedPartialCount, long withWarningsCount,
                             List<RepoHealth> repos) {
    }

    public record RepoHealth(String repositoryPath, String lastScanStatus, long warningCount) {
    }

    public record InactiveLine(long inactiveRepoCount, long openFindingsInInactiveRepos) {
    }
}
