package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.scan.worker.ScanLaunchStub;
import com.company.secscan.scan.worker.ScannerLaunchSpec;
import com.company.secscan.scan.worker.ScannerLauncher;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Plan addition 1 (atomicity): a scan's findings are stored and reconciled in ONE transaction. If reconcile
 * fails, the stored findings roll back with it — a worker crash between the two can never leave findings
 * unreconciled. Here reconcile is forced to throw; the scan must end with no persisted findings and no
 * sub-issues, and be requeued.
 */
class ReconcileAtomicityIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String CONTAINER_SHA =
            "91bca0a22047b5d052ba8324cbe895460270fb64cb26ee2d6d47d85d9a6cb78d";

    @MockBean
    ScannerLauncher launcher;
    @MockBean
    ReconcileService reconcile;

    @Autowired
    com.company.secscan.scan.ScanService scanService;
    @Autowired
    ScanClaimer claimer;
    @Autowired
    ScanJobProcessor processor;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanFindingRepository findings;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void aReconcileFailureRollsBackTheStoredFindings() {
        Mockito.when(launcher.run(any())).thenAnswer(inv -> {
            ScannerLaunchSpec spec = inv.getArgument(0);
            ScanLaunchStub.writeSecretFinding(spec.outDir(), "deadbeefcommit", CONTAINER_SHA, "AKIA");
            return new ScannerLauncher.LaunchResult(2, false, "stub log");
        });
        Mockito.doThrow(new IllegalStateException("boom during reconcile"))
                .when(reconcile).reconcile(any(), any());

        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file:///tmp/secscan-fixture", "svc/app", 101L, "ct", "read_api,read_repository",
                null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(repo.getId());
        UUID jobId = claimer.claimBatch(10).get(0);
        processor.process(jobId);

        // The whole recordSuccess+reconcile transaction rolled back: no findings, no sub-issues persisted.
        assertThat(findings.findByScanId(scan.id())).isEmpty();
        assertThat(subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId())).isEmpty();
        // The scan was requeued (a retryable processing error), not left half-committed.
        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_QUEUED);
        assertThat(scans.findById(scan.id()).orElseThrow().getReconciledAt()).isNull();
    }
}
