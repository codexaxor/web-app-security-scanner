package com.company.secscan.scan;

import com.company.secscan.workspace.Workspace;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a {@link Scan} and its {@link ScanJob} in one transaction. Separate from {@link ScanService} so a
 * concurrent double-submit that loses the {@code uq_scan_active_per_repository} race surfaces as a
 * {@code DataIntegrityViolationException} here (via {@code saveAndFlush}) and rolls back cleanly, letting the
 * caller return the already-queued scan instead of a 500.
 */
@Component
class ScanEnqueuer {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    private final ScanRepository scans;
    private final ScanJobRepository jobs;

    ScanEnqueuer(ScanRepository scans, ScanJobRepository jobs) {
        this.scans = scans;
        this.jobs = jobs;
    }

    @Transactional
    Scan enqueueManual(UUID repositoryId, String branch) {
        Scan scan = scans.saveAndFlush(Scan.manual(WORKSPACE, repositoryId, branch));
        jobs.saveAndFlush(new ScanJob(WORKSPACE, scan.getId()));
        return scan;
    }
}
