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
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.triage.ReasonCode;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-064: the SPEC default-dir + minified-file exclusions do NOT apply to {@code secret} findings — a leaked
 * credential in {@code dist/} (or a {@code *.min.js}) is still a real exposure. An explicit {@code .secscan.yml}
 * exclude.paths entry still suppresses it (EXCLUDED_PATH), for every type.
 */
class SecretPathExclusionIT extends AbstractScanWorkerIT {

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
    void secretInADefaultDirStaysOpenButAnExplicitExcludePathIgnoresIt() {
        UUID repo = seedRepository();
        UUID scan = seedScan(repo);
        seedSecret(scan, repo, "dist/app.js");                 // default dir — a real key here must stay OPEN
        seedSecret(scan, repo, "web/bundle.min.js");           // minified — must stay OPEN too
        seedSecret(scan, repo, "legacy/keys.txt");             // under an explicit .secscan.yml exclude path

        // .secscan.yml excludes only "legacy".
        SecscanConfig cfg = new SecscanConfig(true, List.of("legacy"), Map.of(), false);
        reconcile.reconcile(scan, WORKSPACE, new TriageContext(cfg, Map.of(), false));

        List<SubIssue> subs = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo);
        assertThat(byFile(subs, "dist/app.js").getStatus()).as("secret in dist/ is a real exposure")
                .isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(byFile(subs, "web/bundle.min.js").getStatus()).as("secret in a minified file stays open")
                .isEqualTo(SubIssue.STATUS_OPEN);

        SubIssue excluded = byFile(subs, "legacy/keys.txt");
        assertThat(excluded.getStatus()).as("explicit .secscan.yml exclude.paths still applies to secrets")
                .isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(excluded.getIgnoreReason()).isEqualTo(ReasonCode.EXCLUDED_PATH.code());
    }

    private void seedSecret(UUID scanId, UUID repoId, String filePath) {
        scanFindings.save(ScanFinding.secret(WORKSPACE, scanId, repoId, "aws-access-key", filePath, 1,
                "hmac-" + filePath, "AKIA", "HIGH"));
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
