package com.company.secscan.scan.worker;

import com.company.secscan.config.SecretFingerprinterHolder;
import com.company.secscan.finding.RawFinding;
import com.company.secscan.issue.ReconcileService;
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
import com.company.secscan.scan.ScanWarning;
import com.company.secscan.scan.ScanWarningRepository;
import com.company.secscan.scan.parse.ParsedScan;
import com.company.secscan.scan.parse.ScannerTaskResult;
import java.time.Instant;
import java.util.List;
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
    private final ScanWarningRepository warnings;
    private final RepositoryRepository repositories;
    private final SecretFingerprinterHolder fingerprinter;
    private final ReconcileService reconcile;

    public ScanStateService(ScanRepository scans, ScanJobRepository jobs, ScanTaskRepository tasks,
                            ScanFindingRepository findings, ScanWarningRepository warnings,
                            RepositoryRepository repositories, SecretFingerprinterHolder fingerprinter,
                            ReconcileService reconcile) {
        this.scans = scans;
        this.jobs = jobs;
        this.tasks = tasks;
        this.findings = findings;
        this.warnings = warnings;
        this.repositories = repositories;
        this.fingerprinter = fingerprinter;
        this.reconcile = reconcile;
    }

    @Transactional
    public void markRunning(UUID workspaceId, UUID scanId) {
        Scan scan = scans.findByIdAndWorkspaceId(scanId, workspaceId).orElseThrow();
        scan.markRunning();
        scans.save(scan);
    }

    /**
     * Persists a completed pipeline: the commit and tool versions, one {@link ScanTask} per scanner, every raw
     * {@link ScanFinding}, the repository's last-scanned SHA, and the scan's terminal status; the job is done.
     */
    @Transactional
    public void recordSuccess(UUID workspaceId, UUID scanId, UUID jobId, UUID repositoryId, ParsedScan parsed,
                              String logExcerpt, String terminalStatus) {
        Scan scan = scans.findByIdAndWorkspaceId(scanId, workspaceId).orElseThrow();
        scan.recordCommit(parsed.commit());
        scan.recordToolVersions(parsed.toolVersions());
        scan.finish(terminalStatus);
        scans.save(scan);

        for (ScannerTaskResult t : parsed.tasks()) {
            String status = t.succeeded() ? ScanTask.STATUS_OK : ScanTask.STATUS_FAILED;
            ScanTask task = new ScanTask(scan.getWorkspaceId(), scanId, t.name(), status, t.exitCode(),
                    t.durationMs(), logExcerpt);
            // A no-packages SCA scan is ok but not authoritative to close findings this scan (D-034).
            task.setClosesFindings(t.closesFindings());
            tasks.save(task);
        }
        for (RawFinding f : parsed.findings()) {
            findings.save(toEntity(scan.getWorkspaceId(), scanId, repositoryId, f));
        }
        for (com.company.secscan.scan.parse.ScanWarning w : parsed.warnings()) {
            warnings.save(new ScanWarning(scan.getWorkspaceId(), repositoryId, scanId, w.code(), w.message(),
                    w.filePath()));
        }
        if (!parsed.findings().isEmpty()) {
            log.debug("Scan {} stored {} finding(s)", scanId, parsed.findings().size());
        }

        repositories.findByIdAndWorkspaceId(repositoryId, workspaceId).ifPresent(repo -> {
            repo.recordScannedSha(parsed.commit());
            repositories.save(repo);
        });

        // Reconcile in the SAME transaction (M3 plan addition 1): findings and their issue-model state commit
        // atomically, so a worker crash never leaves findings unreconciled. No network I/O inside reconcile.
        reconcile.reconcile(scanId, workspaceId);

        jobs.findByIdAndWorkspaceId(jobId, workspaceId).ifPresent(job -> {
            job.markDone();
            jobs.save(job);
        });
    }

    /** A retryable failure (timeout, no result, clone failure, unexpected error): requeue, or fail if exhausted. */
    @Transactional
    public void failOrRequeue(UUID workspaceId, UUID jobId, UUID scanId, String reason) {
        Scan scan = scans.findByIdAndWorkspaceId(scanId, workspaceId).orElseThrow();
        ScanJob job = jobs.findByIdAndWorkspaceId(jobId, workspaceId).orElseThrow();
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
     * The scanner container timed out and was killed (no manifest produced). On a terminal timeout (attempts
     * exhausted) record one {@code timed_out} {@link ScanTask} per scanner the image runs — so the outcome is
     * auditable and, per domain rule 3, a timed-out scanner never closes its findings — then fail the scan. If
     * attempts remain, requeue instead and write no tasks (the retry produces its own fresh tasks).
     */
    @Transactional
    public void failOrRequeueTimedOut(UUID workspaceId, UUID jobId, UUID scanId, List<String> scanners,
                                      String logExcerpt) {
        Scan scan = scans.findByIdAndWorkspaceId(scanId, workspaceId).orElseThrow();
        ScanJob job = jobs.findByIdAndWorkspaceId(jobId, workspaceId).orElseThrow();
        if (job.attemptsExhausted()) {
            log.warn("Scan {} timed out after {} attempts; recording timed_out task(s) and failing",
                    scanId, job.getAttempts());
            for (String scanner : scanners) {
                tasks.save(new ScanTask(workspaceId, scanId, scanner, ScanTask.STATUS_TIMED_OUT, null, null,
                        logExcerpt));
            }
            job.markFailed();
            scan.failWith("SCANNER_TIMEOUT");
        } else {
            log.info("Scan {} timed out; will be retried (attempt {})", scanId, job.getAttempts());
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
    public void failPermanently(UUID workspaceId, UUID jobId, UUID scanId, String failCode, String reason) {
        Scan scan = scans.findByIdAndWorkspaceId(scanId, workspaceId).orElseThrow();
        ScanJob job = jobs.findByIdAndWorkspaceId(jobId, workspaceId).orElseThrow();
        log.error("Scan {} failed (not retried, {}): {}", scanId, failCode, reason);
        scan.failWith(failCode);
        job.markFailed();
        scans.save(scan);
        jobs.save(job);
    }

    /** The connection cannot reach the repository (resolved out of band): fail the scan, finish the job. */
    @Transactional
    public void resolveNoAccess(UUID workspaceId, UUID jobId, UUID scanId, String reason) {
        Scan scan = scans.findByIdAndWorkspaceId(scanId, workspaceId).orElseThrow();
        ScanJob job = jobs.findByIdAndWorkspaceId(jobId, workspaceId).orElseThrow();
        log.warn("Scan {} failed (NO_ACCESSIBLE_CONNECTION): {}", scanId, reason);
        scan.failWith("NO_ACCESSIBLE_CONNECTION");
        job.markDone();
        scans.save(scan);
        jobs.save(job);
    }

    private ScanFinding toEntity(UUID workspaceId, UUID scanId, UUID repositoryId, RawFinding f) {
        if (RawFinding.TYPE_SCA.equals(f.type())) {
            ScanFinding sca = ScanFinding.sca(workspaceId, scanId, repositoryId, f.vulnId(), f.packageName(),
                    f.ecosystem(), f.installedVersion(), f.fixedVersion(), f.filePath(), f.toolSeverity());
            sca.setOsvEnrichment(f.cvss(), f.severityLabel(), f.cwe(), f.cveAliases(), f.advisorySummary());
            return sca;
        }
        if (RawFinding.TYPE_SECRET.equals(f.type())) {
            // The container emits a plain SHA-256; we key it with HMAC-SHA256 here so the bare digest is never
            // persisted (D-027). Throws a clear error if SECSCAN_FINGERPRINT_KEY is missing.
            String keyedHash = fingerprinter.require().hash(f.secretHash());
            return ScanFinding.secret(workspaceId, scanId, repositoryId, f.ruleId(), f.filePath(), f.line(),
                    keyedHash, f.secretPreview(), f.toolSeverity());
        }
        // SAST/IaC/EOL are added with their scanners (M6).
        throw new IllegalStateException("Unsupported finding type for persistence: " + f.type());
    }
}
