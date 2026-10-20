package com.company.secscan.intel;

import com.company.secscan.issue.ReScoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The WEEKLY endoflife.date refresh (api profile; D-055) — EOL dates move slowly, so weekly (not daily like
 * KEV/EPSS). Per-product upsert that never wipes (BLOCKER 6), then a re-score so EOL statuses that crossed their
 * date are reflected without a rescan (D-049). Guarded by the same {@code scheduled-sync-enabled} flag tests
 * disable. Single-instance assumption (ShedLock is M9).
 */
@Component
@Profile("api")
public class EolSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EolSyncScheduler.class);

    private final IntelProperties props;
    private final EolSyncService eolSync;
    private final ReScoreService reScore;

    public EolSyncScheduler(IntelProperties props, EolSyncService eolSync, ReScoreService reScore) {
        this.props = props;
        this.eolSync = eolSync;
        this.reScore = reScore;
    }

    @Scheduled(cron = "${secscan.intel.eol-cron:0 0 5 * * MON}")
    public void weeklySync() {
        if (!props.isScheduledSyncEnabled()) {
            return;
        }
        try {
            eolSync.refresh();
            reScore.rescoreAll();
        } catch (RuntimeException e) {
            log.warn("EOL weekly sync failed; keeping the previous cycles", e);
        }
    }
}
