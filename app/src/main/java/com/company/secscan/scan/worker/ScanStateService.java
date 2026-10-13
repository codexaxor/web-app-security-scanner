package com.company.secscan.scan.worker;

import com.company.secscan.config.SecretFingerprinterHolder;
import com.company.secscan.finding.RawFinding;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanJob;
import com.company.secscan.scan.ScanJobRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.scan.parse.ParsedScan;
import com.company.secscan.scan.parse.ScannerTaskResult;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short write transactions that move a scan and its job through the pipeline. Kept separate from the
 * orchestration ({@code ScanJobProcessor}) so no transaction is held across the container run or any network
 * I/O: each method here is a brief, self-contained commit.
 */
@Service
public class ScanStateService {

    private static final Logger log = LoggerFactory.getLogger(ScanStateService.class);
    private static final long RETRY_BACKOFF_SECONDS = 30;

    private final ScanRepository scans;
    private final ScanJobRepository jobs;
    private final ScanTaskRepository tasks;
    private final ScanFindingRepository findings;
    private final RepositoryRepository repositories;
    private final SecretFingerprinterHolder fingerprinter;

    public ScanStateService(ScanRepository scans, ScanJobRepository jobs, ScanTaskRepository tasks,
                            ScanFindingRepository findings, RepositoryRepository repositories,
                            SecretFingerprinterHolder fingerprinter) {
        this.scans = scans;
        this.jobs = jobs;
        this.tasks = tasks;
        this.findings = findings;
        this.repositories = repositories;
        this.fingerprinter = fingerprinter;
    }

    @Transactional
    public void markRunning(UUID scanId) {
        Scan scan = scans.findById(scanId).orElseThrow();
        scan.markRunning();
        scans.save(scan);
    }

    /**
     * Persists a completed pipeline: the commit and tool versions, one {@link ScanTask} per scanner, every raw
     * {@link ScanFinding}, the repository's last-scanned SHA, and the scan's terminal status; the job is done.
     */
    @Transactional
    public void recordSuccess(UUID scanId, UUID jobId, UUID repositoryId, ParsedScan parsed, String logExcerpt,
                              String terminalStatus) {
        Scan scan = scans.findById(scanId).orElseThrow();
        scan.recordCommit(parsed.commit());
        scan.recordToolVersions(parsed.toolVersions());
        scan.finish(terminalStatus);
        scans.save(scan);

        for (ScannerTaskResult t : parsed.tasks()) {
            String status = t.succeeded() ? ScanTask.STATUS_OK : ScanTask.STATUS_FAILED;
            tasks.save(new ScanTask(scan.getWorkspaceId(), scanId, t.name(), status, t.exitCode(),
                    t.durationMs(), logExcerpt));
        }
        for (RawFinding f : parsed.findings()) {
            findings.save(toEntity(scan.getWorkspaceId(), scanId, repositoryId, f));
        }
        if (!parsed.findings().isEmpty()) {
            log.debug("Scan {} stored {} secret finding(s) (hashes keyed with HMAC-SHA256)",
                    scanId, parsed.findings().size());
        }

        repositories.findById(repositoryId).ifPresent(repo -> {
            repo.recordScannedSha(parsed.commit());
            repositories.save(repo);
        });

        jobs.findById(jobId).ifPresent(job -> {
            job.markDone();
            jobs.save(job);
        });
    }

    /** A retryable failure (timeout, no result, clone failure, unexpected error): requeue, or fail if exhausted. */
    @Transactional
    public void failOrRequeue(UUID jobId, UUID scanId, String reason) {
        Scan scan = scans.findById(scanId).orElseThrow();
        ScanJob job = jobs.findById(jobId).orElseThrow();
        if (job.attemptsExhausted()) {
            log.warn("Scan {} failed after {} attempts: {}", scanId, job.getAttempts(), reason);
            job.markFailed();
            scan.finish(Scan.STATUS_FAILED);
        } else {
            log.info("Scan {} will be retried (attempt {}): {}", scanId, job.getAttempts(), reason);
            job.requeue(Instant.now().plusSeconds(RETRY_BACKOFF_SECONDS));
            scan.markQueuedForRetry();
        }
        jobs.save(job);
        scans.save(scan);
    }

    /**
     * A configuration error (work root not writable, Docker unreachable, scanner image missing): fail the scan
     * once with a clear reason and DO NOT retry — retrying a misconfiguration just wastes attempts.
     */
    @Transactional
    public void failPermanently(UUID jobId, UUID scanId, String reason) {
        Scan scan = scans.findById(scanId).orElseThrow();
        ScanJob job = jobs.findById(jobId).orElseThrow();
        log.error("Scan {} failed (not retried): {}", scanId, reason);
        scan.finish(Scan.STATUS_FAILED);
        job.markFailed();
        scans.save(scan);
        jobs.save(job);
    }

    /** The connection cannot reach the repository (resolved out of band): fail the scan, finish the job. */
    @Transactional
    public void resolveNoAccess(UUID jobId, UUID scanId, String reason) {
        Scan scan = scans.findById(scanId).orElseThrow();
        ScanJob job = jobs.findById(jobId).orElseThrow();
        log.warn("Scan {} failed: {}", scanId, reason);
        scan.finish(Scan.STATUS_FAILED);
        job.markDone();
        scans.save(scan);
        jobs.save(job);
    }

    private ScanFinding toEntity(UUID workspaceId, UUID scanId, UUID repositoryId, RawFinding f) {
        // M2 produces only secret findings; other types are added with their scanners (M4/M6).
        // The container emits a plain SHA-256; we key it with HMAC-SHA256 here so the bare digest is never
        // persisted (D-027). Throws a clear error if SECSCAN_FINGERPRINT_KEY is missing.
        String keyedHash = fingerprinter.require().hash(f.secretHash());
        return ScanFinding.secret(workspaceId, scanId, repositoryId, f.ruleId(), f.filePath(), f.line(),
                keyedHash, f.secretPreview(), f.toolSeverity());
    }
}
