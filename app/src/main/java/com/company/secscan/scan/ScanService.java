package com.company.secscan.scan;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanDetailView;
import com.company.secscan.scan.api.ScanTaskView;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.api.ScanWarningView;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Starts scans (manual trigger / "Start Scan") and lists them. At most one scan is in flight per repository:
 * a start while a queued/running scan exists returns that scan rather than enqueuing a second (enforced both
 * here and by {@code uq_scan_active_per_repository}; a concurrent double-submit is recovered). Scheduled and
 * commit-triggered scans arrive in M9.
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final List<String> IN_FLIGHT = List.of(Scan.STATUS_QUEUED, Scan.STATUS_RUNNING);

    private final ScanRepository scans;
    private final ScanTaskRepository tasks;
    private final ScanFindingRepository findings;
    private final ScanWarningRepository warnings;
    private final RepositoryRepository repositories;
    private final ScanEnqueuer enqueuer;

    public ScanService(ScanRepository scans, ScanTaskRepository tasks, ScanFindingRepository findings,
                       ScanWarningRepository warnings, RepositoryRepository repositories, ScanEnqueuer enqueuer) {
        this.scans = scans;
        this.tasks = tasks;
        this.findings = findings;
        this.warnings = warnings;
        this.repositories = repositories;
        this.enqueuer = enqueuer;
    }

    /**
     * Queues a manual scan for a repository, or returns the scan already in flight. Not transactional at the
     * method level so the enqueue race can be caught and recovered (see {@link ScanEnqueuer}).
     */
    public ScanView startManual(UUID repositoryId) {
        Repository repo = requireScannable(repositoryId);
        return inFlight(repositoryId)
                .map(ScanView::of)
                .orElseGet(() -> enqueueRecoveringRace(repo.getId()));
    }

    private ScanView enqueueRecoveringRace(UUID repositoryId) {
        try {
            return ScanView.of(enqueuer.enqueueManual(repositoryId));
        } catch (DataIntegrityViolationException race) {
            // A concurrent start won the uq_scan_active_per_repository race; return that scan.
            log.debug("Concurrent scan start for repository {}; returning the existing in-flight scan",
                    repositoryId);
            return inFlight(repositoryId).map(ScanView::of)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "A scan is already in progress for this repository."));
        }
    }

    @Transactional(readOnly = true)
    public PagedResponse<ScanView> listForRepository(UUID repositoryId, int page, int size) {
        requireScannable(repositoryId, false);
        return PagedResponse.fromPage(scans
                .findByWorkspaceIdAndRepositoryId(WORKSPACE, repositoryId,
                        PageRequest.of(Math.max(0, page), Math.max(1, size)))
                .map(ScanView::of));
    }

    /** All scans in the workspace, newest-first, paginated (OD-14 item 2). */
    @Transactional(readOnly = true)
    public PagedResponse<ScanView> listAll(int page, int size) {
        return PagedResponse.fromPage(scans
                .findByWorkspaceId(WORKSPACE, PageRequest.of(Math.max(0, page), Math.max(1, size),
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
                                "createdAt")))
                .map(ScanView::of));
    }

    /** One scan's warnings, paginated (OD-14 item 2); 404 if the scan is not in this workspace. */
    @Transactional(readOnly = true)
    public PagedResponse<ScanWarningView> listWarnings(UUID scanId, int page, int size) {
        scans.findByIdAndWorkspaceId(scanId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such scan."));
        return PagedResponse.fromPage(warnings
                .findByWorkspaceIdAndScanId(WORKSPACE, scanId,
                        PageRequest.of(Math.max(0, page), Math.max(1, size)))
                .map(ScanWarningView::of));
    }

    @Transactional(readOnly = true)
    public ScanDetailView get(UUID scanId) {
        Scan scan = scans.findByIdAndWorkspaceId(scanId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such scan."));
        List<ScanTaskView> taskViews = tasks.findByScanId(scanId).stream().map(ScanTaskView::of).toList();
        return new ScanDetailView(ScanView.of(scan), scan.getToolVersions(), taskViews,
                findings.countByScanId(scanId));
    }

    private Optional<Scan> inFlight(UUID repositoryId) {
        return scans.findByWorkspaceIdAndRepositoryIdAndStatusIn(WORKSPACE, repositoryId, IN_FLIGHT)
                .stream().findFirst();
    }

    private Repository requireScannable(UUID repositoryId) {
        return requireScannable(repositoryId, true);
    }

    private Repository requireScannable(UUID repositoryId, boolean requireActive) {
        Repository repo = repositories.findById(repositoryId)
                .filter(r -> r.getWorkspaceId().equals(WORKSPACE))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such repository."));
        if (requireActive && !repo.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Repository is inactive (" + repo.getInactiveReason() + ") and cannot be scanned.");
        }
        return repo;
    }
}
