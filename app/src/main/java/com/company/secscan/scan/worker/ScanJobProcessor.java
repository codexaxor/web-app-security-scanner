package com.company.secscan.scan.worker;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitLabError;
import com.company.secscan.gitlab.GitLabException;
import com.company.secscan.gitlab.GitLabHostPolicy;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryConnectionGuard;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanJob;
import com.company.secscan.scan.ScanJobRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.parse.ParsedScan;
import com.company.secscan.scan.parse.ScannerOutputParser;
import com.company.secscan.scan.parse.ScannerTaskResult;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs one claimed scan job end to end: mark running → (provisional reachability probe) → launch the scanner
 * container → parse {@code /out} → persist findings and the terminal status. Holds no transaction across the
 * container run or any network I/O — every DB change goes through {@link ScanStateService}'s short
 * transactions. The scanner container is always removed by {@link ScannerLauncher}, and the per-scan workspace
 * (with its token file) is deleted in a {@code finally}.
 */
@Component
@Profile("worker")
public class ScanJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ScanJobProcessor.class);

    /**
     * The scanners this image runs, used only to record {@code timed_out} tasks when the whole container is
     * killed on timeout (no manifest is produced then): one disposable container runs all of them, so a timeout
     * times out all of them (secret, SCA, SAST, IaC, EOL).
     */
    private static final List<String> IMAGE_SCANNERS = List.of(
            com.company.secscan.scan.ScanTask.SCANNER_SECRET, com.company.secscan.scan.ScanTask.SCANNER_SCA,
            com.company.secscan.scan.ScanTask.SCANNER_SAST, com.company.secscan.scan.ScanTask.SCANNER_IAC,
            com.company.secscan.scan.ScanTask.SCANNER_EOL);

    private final ScanRepository scans;
    private final ScanJobRepository jobs;
    private final RepositoryRepository repositories;
    private final GitlabConnectionRepository connections;
    private final TokenCipherHolder cipher;
    private final GitLabClient client;
    private final RepositoryConnectionGuard guard;
    private final ScannerLauncher launcher;
    private final ScannerOutputParser parser;
    private final ScanStateService state;
    private final ScannerProperties props;
    private final GitLabHostPolicy hostPolicy;

    public ScanJobProcessor(ScanRepository scans, ScanJobRepository jobs, RepositoryRepository repositories,
                            GitlabConnectionRepository connections, TokenCipherHolder cipher, GitLabClient client,
                            RepositoryConnectionGuard guard, ScannerLauncher launcher, ScannerOutputParser parser,
                            ScanStateService state, ScannerProperties props, GitLabHostPolicy hostPolicy) {
        this.scans = scans;
        this.jobs = jobs;
        this.repositories = repositories;
        this.connections = connections;
        this.cipher = cipher;
        this.client = client;
        this.guard = guard;
        this.launcher = launcher;
        this.parser = parser;
        this.state = state;
        this.props = props;
        this.hostPolicy = hostPolicy;
    }

    public void process(UUID jobId) {
        // The claimed job is the trust root (domain rule 1): this findById is deliberately NOT workspace-scoped —
        // the job was already claimed for this worker (the scan_job claim exception), and its workspace_id scopes
        // every later lookup here. Listed among CLAUDE.md's documented workspace-agnostic exceptions.
        ScanJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        UUID workspaceId = job.getWorkspaceId();
        Scan scan = scans.findByIdAndWorkspaceId(job.getScanId(), workspaceId).orElseThrow();
        Repository repo = repositories.findByIdAndWorkspaceId(scan.getRepositoryId(), workspaceId).orElseThrow();
        GitlabConnection conn = repo.getConnectionId() == null ? null
                : connections.findByIdAndWorkspaceId(repo.getConnectionId(), workspaceId).orElse(null);

        state.markRunning(workspaceId, scan.getId());

        if (conn == null) {
            state.resolveNoAccess(workspaceId, jobId, scan.getId(), "repository has no connection");
            return;
        }

        // Validate the clone scheme/host at clone time, BEFORE decrypting the token or launching anything.
        // Production allows only https to an allowlisted host (http only for an allowlisted loopback host,
        // D-018); the api's save-time check is not trusted here (an SSRF guard, the row may have changed).
        // A file:// clone is accepted ONLY when the test-only property is set (default off), so a stored
        // file:// URL cannot drive the worker to read a local path in production.
        if (isLocal(conn)) {
            if (!props.isAllowLocalFileClone()) {
                state.failPermanently(workspaceId, jobId, scan.getId(), "HOST_NOT_ALLOWED",
                        "file:// clone URLs are disabled (test-only: secscan.scanner.allow-local-file-clone). "
                                + "Production clones must use https to an allowlisted host (D-018).");
                return;
            }
        } else {
            String cloneUrl = httpsCloneUrl(conn.getBaseUrl(), repo.getPathWithNamespace());
            try {
                hostPolicy.requireAllowed(cloneUrl);
            } catch (GitLabException e) {
                state.failPermanently(workspaceId, jobId, scan.getId(), e.getError().name(),
                        "clone URL host not allowed (SECSCAN_GITLAB_ALLOWED_HOSTS): " + e.getError());
                return;
            }
        }

        String token = isLocal(conn) ? null : cipher.require().decrypt(conn.getTokenCiphertext());

        ScanWorkspace ws;
        try {
            ws = ScanWorkspace.create(Path.of(props.getWorkRoot()), scan.getId().toString());
        } catch (RuntimeException e) {
            // Config error (work root missing / not writable by the worker user): fail once, do not retry.
            state.failPermanently(workspaceId, jobId, scan.getId(), "WORK_ROOT_NOT_WRITABLE",
                    "scan work root not writable (" + props.getWorkRoot() + "): " + e.getMessage());
            return;
        }

        try (ws) {
            if (!probeProvisionalAccess(repo, conn, token, workspaceId, jobId, scan.getId())) {
                return;
            }
            // A5 (D-076) / SF-4: verify the configured branch BEFORE cloning, disambiguating a GitLab 404.
            if (!isLocal(conn) && scan.getBranch() != null) {
                switch (branchVerdict(repo, conn, token, scan.getBranch())) {
                    case MISSING -> {
                        // Definite branch-not-found (project reachable): fail cleanly, flag branch-missing.
                        state.failBranchMissing(workspaceId, jobId, scan.getId(), repo.getId(), scan.getBranch());
                        return;
                    }
                    case PROJECT_UNREACHABLE -> {
                        // Project 404 / no access: a distinct failure, NOT branch-missing.
                        state.failPermanently(workspaceId, jobId, scan.getId(), "PROJECT_UNREACHABLE",
                                "project not reachable while verifying the scan branch");
                        return;
                    }
                    default -> {
                        // OK, or a transient GitLab error (5xx / timeout / rate-limited): proceed with the clone.
                    }
                }
            }
            Path tokenFile = token == null ? null : ws.writeToken(token);
            ScannerLaunchSpec spec = buildSpec(scan, repo, conn, ws, tokenFile);

            ScannerLauncher.LaunchResult result;
            try {
                result = launcher.run(spec);
            } catch (NotFoundException e) {
                state.failPermanently(workspaceId, jobId, scan.getId(), "SCANNER_IMAGE_MISSING",
                        "scanner image not found: " + props.getImage() + " (build it: "
                                + "docker build -t " + props.getImage() + " scanner-image/)");
                return;
            } catch (DockerException | IllegalStateException e) {
                // Daemon unreachable, or the launcher refused to run (e.g. would-be-root): a config error.
                state.failPermanently(workspaceId, jobId, scan.getId(), "DOCKER_ERROR",
                        "Docker error launching the scanner: "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
                return;
            }

            if (result.timedOut()) {
                // The whole container was killed, so there is no manifest; record a timed_out task per scanner
                // (so a terminal timeout is auditable and its findings stay untouched) then requeue-or-fail.
                state.failOrRequeueTimedOut(workspaceId, jobId, scan.getId(), IMAGE_SCANNERS, result.logExcerpt());
                return;
            }
            if (!Files.exists(ws.outDir().resolve("result.json"))) {
                state.failOrRequeue(workspaceId, jobId, scan.getId(),
                        "scanner produced no result.json (exit " + result.exitCode() + ")");
                return;
            }
            ParsedScan parsed = parser.parse(ws.outDir());
            if (parsed.cloneFailed()) {
                state.failOrRequeue(workspaceId, jobId, scan.getId(), "clone failed inside the scanner container");
                return;
            }
            String terminal = deriveTerminalStatus(parsed);
            state.recordSuccess(workspaceId, scan.getId(), jobId, repo.getId(), parsed, result.logExcerpt(),
                    terminal);
            log.info("Scan {} finished: {} ({} finding(s))", scan.getId(), terminal, parsed.findings().size());
        } catch (RuntimeException e) {
            log.error("Scan {} processing error", scan.getId(), e);
            state.failOrRequeue(workspaceId, jobId, scan.getId(),
                    "processing error: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Change #2: for a provisionally-reassigned repository on a real GitLab connection, verify access BEFORE
     * cloning. Denied (401/403/404) → deactivate it (reusing {@link RepositoryConnectionGuard}) and fail the
     * scan; reachable → confirm the reassignment and continue. Returns false if the caller should stop.
     */
    private boolean probeProvisionalAccess(Repository repo, GitlabConnection conn, String token, UUID workspaceId,
                                           UUID jobId, UUID scanId) {
        if (!repo.isProvisional() || isLocal(conn)) {
            return true;
        }
        try {
            client.projectLanguages(conn.getBaseUrl(), token, repo.getGitlabProjectId());
            guard.confirm(conn.getId());
            return true;
        } catch (GitLabException e) {
            if (accessDenied(e.getError())) {
                guard.deactivate(conn.getId());
                state.resolveNoAccess(workspaceId, jobId, scanId,
                        "connection cannot reach repository: " + e.getError());
            } else {
                state.failOrRequeue(workspaceId, jobId, scanId, "reachability probe failed: " + e.getError());
            }
            return false;
        }
    }

    private ScannerLaunchSpec buildSpec(Scan scan, Repository repo, GitlabConnection conn, ScanWorkspace ws,
                                        Path tokenFile) {
        String cloneUrl;
        Path localSource = null;
        boolean networkDisabled;
        if (isLocal(conn)) {
            cloneUrl = "file:///src";
            localSource = Path.of(URI.create(conn.getBaseUrl()));
            // Local file:// clones run offline by default (proving isolation); the one live-osv test flips
            // secscan.scanner.local-clone-network so OSV can reach deps.dev end-to-end.
            networkDisabled = !props.isLocalCloneNetwork();
        } else {
            cloneUrl = httpsCloneUrl(conn.getBaseUrl(), repo.getPathWithNamespace());
            networkDisabled = false;
        }
        // The branch to clone was snapshotted onto the scan at enqueue (D-076); fall back to the repo default for
        // any legacy scan row with no branch.
        String branch = scan.getBranch() != null ? scan.getBranch() : repo.getDefaultBranch();
        return new ScannerLaunchSpec(scan.getId().toString(), props.getImage(), cloneUrl,
                branch, props.getCloneDepth(), ws.outDir(), tokenFile, localSource,
                props.getTmpfsSize(), props.getMemory().toBytes(), props.getCpus(), props.getPidsLimit(),
                networkDisabled, ws.ownerUserSpec(), props.getTimeout());
    }

    private static String httpsCloneUrl(String baseUrl, String pathWithNamespace) {
        String b = baseUrl.strip();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + "/" + pathWithNamespace + ".git";
    }

    private static boolean isLocal(GitlabConnection conn) {
        return conn.getBaseUrl() != null && conn.getBaseUrl().startsWith("file:");
    }

    /** The pre-clone branch check outcome (SF-4). */
    enum BranchVerdict { OK, MISSING, PROJECT_UNREACHABLE, TRANSIENT }

    /**
     * A5 (D-076) / SF-4: classify the configured branch before cloning, disambiguating GitLab's 404 (which it
     * returns for BOTH a missing branch and a missing/unreadable project):
     * <ul>
     *   <li>{@code getBranch} OK → {@link BranchVerdict#OK};</li>
     *   <li>{@code getBranch} 404 ({@link GitLabError#BRANCH_NOT_FOUND}) AND the project IS reachable
     *       ({@code getProject} OK) → {@link BranchVerdict#MISSING} (definite branch-not-found);</li>
     *   <li>{@code getBranch} 404 but the project is NOT reachable ({@code getProject} throws: 404 / no access /
     *       error) → {@link BranchVerdict#PROJECT_UNREACHABLE} (do NOT flag branch-missing);</li>
     *   <li>any other {@code getBranch} error (5xx / timeout / rate-limited / redirect) →
     *       {@link BranchVerdict#TRANSIENT} (proceed with the clone rather than wrongly flagging the branch gone).</li>
     * </ul>
     */
    BranchVerdict branchVerdict(Repository repo, GitlabConnection conn, String token, String branch) {
        try {
            client.getBranch(conn.getBaseUrl(), token, repo.getGitlabProjectId(), branch);
            return BranchVerdict.OK;
        } catch (GitLabException e) {
            if (e.getError() != GitLabError.BRANCH_NOT_FOUND) {
                return BranchVerdict.TRANSIENT;
            }
            try {
                client.getProject(conn.getBaseUrl(), token, repo.getGitlabProjectId());
                return BranchVerdict.MISSING;              // project reachable, branch genuinely gone
            } catch (GitLabException projectError) {
                return BranchVerdict.PROJECT_UNREACHABLE;  // 404 was the project / no access, not the branch
            }
        }
    }

    private static boolean accessDenied(GitLabError error) {
        return switch (error) {
            case INVALID_TOKEN, TOKEN_EXPIRED, MISSING_SCOPES,
                    GROUP_NOT_FOUND, PROJECT_NOT_FOUND, NOT_FOUND -> true;
            default -> false;
        };
    }

    private static String deriveTerminalStatus(ParsedScan parsed) {
        // Reached only after a successful clone, so the scan did run. Per SPEC step 12 ("scan marked complete,
        // or partial if any scanner failed"): all scanners ok → completed; any scanner failed → partial
        // (a failed scanner never closes its findings — M3). FAILED here only defends the impossible
        // "no scanner ran at all" case; clone/timeout/infra failures are handled before we get here.
        long ok = parsed.tasks().stream().filter(ScannerTaskResult::succeeded).count();
        int total = parsed.tasks().size();
        if (total == 0) {
            return Scan.STATUS_FAILED;
        }
        return ok == total ? Scan.STATUS_COMPLETED : Scan.STATUS_PARTIAL;
    }
}
