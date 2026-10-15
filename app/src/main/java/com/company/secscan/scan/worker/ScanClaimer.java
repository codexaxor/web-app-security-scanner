package com.company.secscan.scan.worker;

import com.company.secscan.scan.ScanJob;
import com.company.secscan.scan.ScanJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The queue mechanics (D-003): claim runnable jobs with {@code FOR UPDATE SKIP LOCKED} and lease them, and
 * reclaim leases abandoned by a crashed/hung worker. Claiming runs in a short transaction that commits the
 * lease; the actual (long) work happens afterwards in {@link ScanJobProcessor}, outside any transaction.
 */
@Component
@Profile("worker")
public class ScanClaimer {

    private static final Logger log = LoggerFactory.getLogger(ScanClaimer.class);

    private final ScanJobRepository jobs;
    private final ScanStateService state;
    private final String workerId;

    public ScanClaimer(ScanJobRepository jobs, ScanStateService state) {
        this.jobs = jobs;
        this.state = state;
        this.workerId = buildWorkerId();
    }

    /** Claims and leases up to {@code max} runnable jobs; returns their ids for the caller to process. */
    @Transactional
    public List<UUID> claimBatch(int max) {
        if (max <= 0) {
            return List.of();
        }
        List<UUID> claimed = new ArrayList<>();
        for (ScanJob job : jobs.claimable(max)) {
            job.lease(workerId);
            jobs.save(job);
            claimed.add(job.getId());
        }
        if (!claimed.isEmpty()) {
            log.debug("Worker {} claimed {} scan job(s)", workerId, claimed.size());
        }
        return claimed;
    }

    /**
     * Reclaims leases older than {@code olderThan}: the owning worker died or hung. Each is requeued (or failed
     * if its attempts are exhausted) via {@link ScanStateService}, within this transaction's row locks.
     */
    @Transactional
    public void sweepStaleLeases(Duration olderThan) {
        Instant threshold = Instant.now().minus(olderThan);
        for (ScanJob job : jobs.staleLeases(threshold)) {
            log.warn("Reclaiming stale lease on scan job {} (locked by {} at {})",
                    job.getId(), job.getLockedBy(), job.getLockedAt());
            state.failOrRequeue(job.getWorkspaceId(), job.getId(), job.getScanId(), "lease expired");
        }
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "worker";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
