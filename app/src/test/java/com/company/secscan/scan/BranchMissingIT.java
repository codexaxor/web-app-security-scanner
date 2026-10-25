package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
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

    private GitLabWireMock gitlab;

    @AfterEach
    void stopGitlab() {
        if (gitlab != null) {
            gitlab.stop();
        }
    }

    @Test
    void aMissingBranchFailsBeforeCloningAndClosesNothing() {
        gitlab = new GitLabWireMock();
        gitlab.stubBranchStatus(9301L, "ghost", 404);   // the configured branch was deleted in GitLab
        String ciphertext = cipher.require().encrypt("glpat-test-token");
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, gitlab.baseUrl(), "svc", 9301L, ciphertext, "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 9301L, "svc/app", "main",
                List.of("Java")));
        repo.setScanBranch("ghost");
        repositories.save(repo);

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        Scan finished = scans.findById(scan.id()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(finished.getFailReason()).isEqualTo("BRANCH_NOT_FOUND");
        assertThat(finished.getReconciledAt()).isNull();                 // no reconcile → closes nothing
        assertThat(warnings.findByScanId(scan.id())).anySatisfy(w -> {
            assertThat(w.getCode()).isEqualTo("BRANCH_NOT_FOUND");
            assertThat(w.getMessage()).contains("ghost");
        });
        assertThat(repositories.findById(repo.getId()).orElseThrow().isBranchMissing()).isTrue();
    }
}
