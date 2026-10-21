package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review B1 regression: a REAL short secret on a NORMAL path — masked preview {@code ****} (D-027), under 12
 * chars — must stay OPEN. {@code SECRET_PLACEHOLDER} is now decided on the file path ALONE and never consults
 * the preview, so neither the {@code ****} mask nor a token that happens to start chan/exam/plac can false-ignore
 * a real secret. A key under a genuine {@code *.example} path still auto-ignores (the placeholder control).
 */
class SecretPlaceholderRegressionIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired ReconcileService reconcile;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository scanFindings;
    @Autowired SubIssueRepository subIssues;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryRepository repositories;

    @Test
    void realShortSecretOnANormalPathStaysOpen() {
        UUID repo = seedRepository();
        UUID scan = seedScan(repo);
        // A real private key on a normal path, masked to **** (short value, D-027). Must stay OPEN.
        seedSecret(scan, repo, "config/deploy-key.pem", "****");
        // A placeholder control: the SAME **** preview, but a *.example path → still auto-ignored on path alone.
        seedSecret(scan, repo, "config/deploy-key.pem.example", "****");

        reconcile.reconcile(scan, WORKSPACE, TriageContext.empty());

        List<SubIssue> subs = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo);
        SubIssue real = byFile(subs, "config/deploy-key.pem");
        assertThat(real.getStatus()).as("a real short secret on a normal path is never a placeholder")
                .isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(real.getIgnoreReason()).isNull();

        SubIssue example = byFile(subs, "config/deploy-key.pem.example");
        assertThat(example.getStatus()).as("a *.example path is a placeholder on path alone")
                .isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(example.getIgnoreReason())
                .isEqualTo(com.company.secscan.triage.ReasonCode.SECRET_PLACEHOLDER.code());
    }

    private void seedSecret(UUID scanId, UUID repoId, String filePath, String preview) {
        scanFindings.save(ScanFinding.secret(WORKSPACE, scanId, repoId, "private-key", filePath, 1,
                "hmac-" + filePath, preview, "HIGH"));
    }

    private static SubIssue byFile(List<SubIssue> subs, String filePath) {
        return subs.stream().filter(s -> filePath.equals(s.getFilePath())).findFirst().orElseThrow();
    }

    private UUID seedRepository() {
        long seq = SEQ.incrementAndGet();
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + seq, 40L + seq, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(),
                1000L + seq, "svc/app-" + seq, "main", List.of("Java"))).getId();
    }

    private UUID seedScan(UUID repoId) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SECRET, ScanTask.STATUS_OK, 0, 5L,
                null);
        task.setClosesFindings(true);
        tasks.save(task);
        return scan.getId();
    }
}
