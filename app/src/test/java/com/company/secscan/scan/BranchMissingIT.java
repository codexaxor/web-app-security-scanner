package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.parse.ParsedScan;
import com.company.secscan.scan.parse.ScannerTaskResult;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.scan.worker.ScanStateService;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-076 amendment A5: when the configured scan branch no longer exists in GitLab, the next scan fails cleanly
 * BEFORE cloning — a {@code BRANCH_NOT_FOUND} fail reason + scan warning, no reconcile (closes nothing), and the
 * repository flagged branch-missing for the UI.
 */
class BranchMissingIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    ScanService scanService;
    @Autowired
    ScanClaimer claimer;
    @Autowired
    ScanJobProcessor processor;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanWarningRepository warnings;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    TokenCipherHolder cipher;
    @Autowired
    ScanStateService state;

    private GitLabWireMock gitlab;

    @AfterEach
    void stopGitlab() {
        if (gitlab != null) {
            gitlab.stop();
        }
    }

    private Repository seed(long projectId, String branch) {
        gitlab = new GitLabWireMock();
        String ciphertext = cipher.require().encrypt("glpat-test-token");
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, gitlab.baseUrl(), "svc", projectId, ciphertext, "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), projectId, "svc/app", "main",
                List.of("Java")));
        repo.setScanBranch(branch);
        repositories.save(repo);
        return repo;
    }

    private Scan runAndGet(Repository repo) {
        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));
        return scans.findById(scan.id()).orElseThrow();
    }

    @Test
    void aMissingBranchFailsBeforeCloningAndClosesNothing() {   // SF-4: branch 404 but project reachable
        Repository repo = seed(9301L, "ghost");
        gitlab.stubBranchStatus(9301L, "ghost", 404);           // branch gone
        gitlab.stubProjectById(9301L,
                "{\"id\":9301,\"path_with_namespace\":\"svc/app\",\"default_branch\":\"main\",\"empty_repo\":false}");

        Scan finished = runAndGet(repo);

        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(finished.getFailReason()).isEqualTo("BRANCH_NOT_FOUND");
        assertThat(finished.getReconciledAt()).isNull();                 // no reconcile → closes nothing
        assertThat(warnings.findByScanId(finished.getId())).anySatisfy(w -> {
            assertThat(w.getCode()).isEqualTo("BRANCH_NOT_FOUND");
            assertThat(w.getMessage()).contains("ghost");
        });
        assertThat(repositories.findById(repo.getId()).orElseThrow().isBranchMissing()).isTrue();
    }

    @Test
    void anUnreachableProjectFailsDistinctlyWithoutFlaggingBranchMissing() {   // SF-4: branch 404 + project 404
        Repository repo = seed(9302L, "ghost");
        gitlab.stubBranchStatus(9302L, "ghost", 404);
        gitlab.stubProjectStatus("9302", 404);                 // project itself not reachable / no access

        Scan finished = runAndGet(repo);

        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(finished.getFailReason()).isEqualTo("PROJECT_UNREACHABLE");
        assertThat(repositories.findById(repo.getId()).orElseThrow().isBranchMissing()).isFalse();
    }

    @Test
    void aTransientBranchCheckErrorProceedsPastThePreCloneGate() {   // A5: 5xx on the branch check → proceed
        Repository repo = seed(9303L, "dev");
        gitlab.stubBranchStatus(9303L, "dev", 500);             // transient error on the branch check
        gitlab.stubProjectById(9303L,
                "{\"id\":9303,\"path_with_namespace\":\"svc/app\",\"default_branch\":\"main\",\"empty_repo\":false}");

        Scan finished = runAndGet(repo);

        // It did NOT fail-fast as branch-missing / project-unreachable: the verdict was TRANSIENT, so the worker
        // proceeded to the clone (which then fails here — WireMock is not a git server — for a non-branch reason).
        assertThat(finished.getFailReason()).isNotIn("BRANCH_NOT_FOUND", "PROJECT_UNREACHABLE");
        assertThat(finished.getReconciledAt()).isNull();                 // a failed clone closes nothing
        assertThat(repositories.findById(repo.getId()).orElseThrow().isBranchMissing()).isFalse();
    }

    @Test
    void branchMissingIsClearedOnTheNextSuccessfulScan() {   // A5: a good scan clears a stale branch-missing flag
        Repository repo = seed(9304L, "dev");
        repo.markBranchMissing();
        repositories.save(repo);
        assertThat(repositories.findById(repo.getId()).orElseThrow().isBranchMissing()).isTrue();

        Scan scan = scans.save(Scan.manual(WORKSPACE, repo.getId(), "dev"));
        scan.markRunning();
        scans.save(scan);
        ScannerTaskResult sca = new ScannerTaskResult("sca", "ok", 0, 5L, "osv.json", false);
        ParsedScan parsed = new ParsedScan(false, "commit-ok", Map.of(), List.of(sca), List.of(), List.of(),
                SecscanConfig.absent(), List.of(), ParsedScan.SCOPE_STATUS_OK, List.of(), List.of());
        state.recordSuccess(WORKSPACE, scan.getId(), UUID.randomUUID(), repo.getId(), parsed, null,
                Scan.STATUS_COMPLETED);

        assertThat(repositories.findById(repo.getId()).orElseThrow().isBranchMissing()).isFalse();
    }
}
