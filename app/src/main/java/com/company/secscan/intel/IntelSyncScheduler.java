package com.company.secscan.intel;

import com.company.secscan.issue.ReScoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily KEV/EPSS refresh (api profile; D-046/D-047). Runs once a day, loads both feeds (TLS always on) and
 * then re-scores open sub-issues (D-049). Each feed is isolated so one failing does not block the other or the
 * re-score. Single-instance assumption (ShedLock/multi-instance is M9). Disable via
 * {@code secscan.intel.scheduled-sync-enabled=false} (tests do, driving sync explicitly).
 */
@Component
@Profile("api")
public class IntelSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntelSyncScheduler.class);

    private final IntelProperties props;
    private final KevSyncService kevSync;
    private final EpssSyncService epssSync;
    private final ReScoreService reScore;

    public IntelSyncScheduler(IntelProperties props, KevSyncService kevSync, EpssSyncService epssSync,
                              ReScoreService reScore) {
        this.props = props;
        this.kevSync = kevSync;
        this.epssSync = epssSync;
        this.reScore = reScore;
    }

    @Scheduled(cron = "${secscan.intel.cron:0 0 4 * * *}")
    public void dailySync() {
        if (!props.isScheduledSyncEnabled()) {
            return;
        }
        boolean refreshed = false;
        try {
            kevSync.refresh();
            refreshed = true;
        } catch (RuntimeException e) {
            log.warn("KEV sync failed; keeping the previous catalog", e);
        }
        try {
            epssSync.refresh();
            refreshed = true;
        } catch (RuntimeException e) {
            log.warn("EPSS sync failed; keeping the previous scores", e);
        }
        if (refreshed) {
            reScore.rescoreAll();
        }
    }
}
