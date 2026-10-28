package com.company.secscan.scan;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.api.Pagination;
import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
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
    private final GitLabClient gitlab;
    private final GitlabConnectionRepository connections;
    private final TokenCipherHolder cipher;

    public ScanService(ScanRepository scans, ScanTaskRepository tasks, ScanFindingRepository findings,
                       ScanWarningRepository warnings, RepositoryRepository repositories, ScanEnqueuer enqueuer,
                       GitLabClient gitlab, GitlabConnectionRepository connections, TokenCipherHolder cipher) {
        this.scans = scans;
        this.tasks = tasks;
        this.findings = findings;
        this.warnings = warnings;
        this.repositories = repositories;
        this.enqueuer = enqueuer;
        this.gitlab = gitlab;
        this.connections = connections;
        this.cipher = cipher;
    }

    /**
     * Queues a manual scan for a repository, or returns the scan already in flight. Not transactional at the
     * method level so the enqueue race can be caught and recovered (see {@link ScanEnqueuer}).
     */
    public ScanView startManual(UUID repositoryId) {
        Repository repo = requireScannable(repositoryId);
        Optional<ScanView> inFlight = inFlight(repositoryId).map(ScanView::of);
        if (inFlight.isPresent()) {
            return inFlight.get();
        }
        BranchResolution branch = resolveBranch(repo);
        return enqueueRecoveringRace(repo.getId(), branch);
    }

    /** The branch to clone, and whether a GitLab refresh at enqueue failed (A6 → a scan warning). */
    private record BranchResolution(String branch, boolean refreshFailed) {
    }

    /**
     * The branch the scan will clone, snapshotted now (D-076): the configured {@code scan_branch} verbatim, or —
     * when following the default (A6) — the CURRENT GitLab default branch, refreshed via the API at enqueue so a
     * default-branch change is treated like a switch (re-baseline). The refreshed value updates
     * {@code repository.default_branch} (INFO log, no status change). On a GitLab failure (or a local connection)
     * the last-known stored default is used; a failure also flags a scan warning.
     */
    private BranchResolution resolveBranch(Repository repo) {
        if (repo.getScanBranch() != null) {
            return new BranchResolution(repo.getScanBranch(), false);
        }
        GitlabConnection conn = repo.getConnectionId() == null ? null
                : connections.findByIdAndWorkspaceId(repo.getConnectionId(), WORKSPACE).orElse(null);
        if (conn == null || isLocal(conn)) {
            return new BranchResolution(repo.getDefaultBranch(), false);
        }
        try {
            String token = cipher.require().decrypt(conn.getTokenCiphertext());
            String current = gitlab.getProject(conn.getBaseUrl(), token, repo.getGitlabProjectId()).defaultBranch();
            // N-2: a GitLab default we cannot validate is not trusted — keep the stored value and warn.
            if (current != null && !BranchName.isValid(current)) {
                log.warn("Repository {} GitLab default branch is not a valid ref name; keeping the stored default.",
                        repo.getId());
                return new BranchResolution(repo.getDefaultBranch(), true);
            }
            if (current != null && !current.equals(repo.getDefaultBranch())) {
                log.info("Repository {} default branch changed in GitLab; updating the stored default.",
                        repo.getId());
                // SF-1: column-scoped update in its own tx (no whole-entity save of the detached repo).
                enqueuer.recordDefaultBranch(repo.getId(), current);
            }
            return new BranchResolution(current != null ? current : repo.getDefaultBranch(), false);
        } catch (RuntimeException e) {
            log.warn("Could not refresh the default branch for repository {} at enqueue ({}); scanning the last "
                    + "known default.", repo.getId(), e.getClass().getSimpleName());
            return new BranchResolution(repo.getDefaultBranch(), true);
        }
    }

    private static boolean isLocal(GitlabConnection conn) {
        return conn.getBaseUrl() != null && conn.getBaseUrl().startsWith("file:");
    }

    private ScanView enqueueRecoveringRace(UUID repositoryId, BranchResolution branch) {
        try {
            Scan scan = enqueuer.enqueueManual(repositoryId, branch.branch());
            if (branch.refreshFailed()) {
                warnings.save(new ScanWarning(WORKSPACE, repositoryId, scan.getId(), "DEFAULT_BRANCH_UNAVAILABLE",
                        "Could not refresh the default branch from GitLab; scanning the last known default branch.",
                        null));
            }
            return ScanView.of(scan);
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
        var pageResult = scans.findByWorkspaceIdAndRepositoryId(WORKSPACE, repositoryId, Pagination.of(page, size));
        java.util.Map<UUID, Long> warnCounts = warningCounts(pageResult.getContent());
        return PagedResponse.fromPage(pageResult.map(s ->
                ScanView.of(s, warnCounts.getOrDefault(s.getId(), 0L))));
    }

    /** Warning counts for a page of scans in one query (no N+1); workspace-scoped. */
    private java.util.Map<UUID, Long> warningCounts(List<Scan> pageScans) {
        if (pageScans.isEmpty()) {
            return java.util.Map.of();
        }
        List<UUID> ids = pageScans.stream().map(Scan::getId).toList();
        java.util.Map<UUID, Long> out = new java.util.HashMap<>();
        for (Object[] row : warnings.countByScanIds(WORKSPACE, ids)) {
            out.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    /** All scans in the workspace, newest-first, paginated (OD-14 item 2). Size capped at {@link Pagination#MAX_SIZE}. */
    @Transactional(readOnly = true)
    public PagedResponse<ScanView> listAll(int page, int size) {
        return PagedResponse.fromPage(scans
                .findByWorkspaceId(WORKSPACE, Pagination.of(page, size,
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
                .findByWorkspaceIdAndScanId(WORKSPACE, scanId, Pagination.of(page, size))
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
