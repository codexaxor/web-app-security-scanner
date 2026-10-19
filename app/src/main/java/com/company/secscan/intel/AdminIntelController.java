package com.company.secscan.intel;

import com.company.secscan.issue.ReScoreService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-triggerable intel re-sync and re-score (api profile), so an operator (or the done-when check) can force
 * a refresh without waiting for the daily cron. Behind the same HTTP Basic auth as the rest of {@code /api/**}
 * (D-010). Each sync is followed by a re-score (D-049).
 */
@RestController
@Profile("api")
@RequestMapping("/api/v1/admin/intel")
public class AdminIntelController {

    private final KevSyncService kevSync;
    private final EpssSyncService epssSync;
    private final EolSyncService eolSync;
    private final ReScoreService reScore;

    public AdminIntelController(KevSyncService kevSync, EpssSyncService epssSync, EolSyncService eolSync,
                                ReScoreService reScore) {
        this.kevSync = kevSync;
        this.epssSync = epssSync;
        this.eolSync = eolSync;
        this.reScore = reScore;
    }

    /** Re-fetches the CISA KEV catalog, then re-scores open sub-issues. */
    @PostMapping("/kev/sync")
    public SyncResult syncKev() {
        int loaded = kevSync.refresh();
        int rescored = reScore.rescoreAll();
        return new SyncResult("kev", loaded, rescored);
    }

    /** Re-fetches the FIRST EPSS bulk scores, then re-scores open sub-issues. */
    @PostMapping("/epss/sync")
    public SyncResult syncEpss() {
        int loaded = epssSync.refresh();
        int rescored = reScore.rescoreAll();
        return new SyncResult("epss", loaded, rescored);
    }

    /** Re-fetches endoflife.date cycles for the detected products (per-product upsert), then re-scores. */
    @PostMapping("/eol/sync")
    public SyncResult syncEol() {
        int loaded = eolSync.refresh();
        int rescored = reScore.rescoreAll();
        return new SyncResult("eol", loaded, rescored);
    }

    /** Re-scores open sub-issues against the current caches without re-fetching a feed. */
    @PostMapping("/rescore")
    public SyncResult rescore() {
        int rescored = reScore.rescoreAll();
        return new SyncResult("rescore", 0, rescored);
    }

    /** Result of a manual sync/re-score: the feed, rows loaded, and open sub-issues recomputed. */
    public record SyncResult(String feed, int loaded, int rescored) {
    }
}
