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
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Domain rule 3 / D-030 for the M6 types: an open SAST/IaC/EOL finding is NEVER solved by a scan whose scanner
 * FAILED. First scan (scanner ok, finding present) opens the issue; second scan (scanner failed, finding
 * absent) must leave it open — a failed scanner closes nothing. Reconcile-level (rows seeded directly, no
 * container).
 */
class FailedScannerClosesNothingIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final AtomicLong SEQ = new AtomicLong(900);

    @Autowired
    ReconcileService reconcile;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanTaskRepository tasks;
    @Autowired
    ScanFindingRepository scanFindings;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void failedSastScannerClosesNothing() {
        assertStaysOpen(ScanTask.SCANNER_SAST, (scan, repo) -> scanFindings.save(
                ScanFinding.sast(WORKSPACE, scan, repo, "rule.x", "src/A.java", 1, "snip", "HIGH", "ERROR",
                        List.of("CWE-89"))));
    }

    @Test
    void failedIacScannerClosesNothing() {
        assertStaysOpen(ScanTask.SCANNER_IAC, (scan, repo) -> scanFindings.save(
                ScanFinding.iac(WORKSPACE, scan, repo, "CKV_AWS_24", "main.tf", "aws_security_group.open", 4,
                        null)));
    }

    @Test
    void failedEolScannerClosesNothing() {
        assertStaysOpen(ScanTask.SCANNER_EOL, (scan, repo) -> scanFindings.save(
                ScanFinding.eol(WORKSPACE, scan, repo, "spring-boot", "2.1", "pom.xml")));
    }

    private void assertStaysOpen(String scanner, BiConsumer<UUID, UUID> seedFinding) {
        UUID repo = seedRepository();

        // Scan 1: the scanner succeeds and reports the finding → it opens.
        UUID scan1 = seedScan(repo, scanner, ScanTask.STATUS_OK, true);
        seedFinding.accept(scan1, repo);
        reconcile.reconcile(scan1, WORKSPACE);
        SubIssue opened = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .findFirst().orElseThrow();
        assertThat(opened.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);

        // Scan 2: the scanner FAILED and reported nothing → the finding must NOT be solved.
        UUID scan2 = seedScan(repo, scanner, ScanTask.STATUS_FAILED, false);
        reconcile.reconcile(scan2, WORKSPACE);

        SubIssue after = subIssues.findById(opened.getId()).orElseThrow();
        assertThat(after.getStatus()).as("%s: a failed scanner closes nothing", scanner)
                .isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(after.getSolvedAt()).isNull();
    }

    private UUID seedRepository() {
        long seq = SEQ.incrementAndGet();
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + seq, 40L + seq, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 1000L + seq, "svc/app-" + seq, "main",
                List.of("Java"))).getId();
    }

    private UUID seedScan(UUID repoId, String scanner, String status, boolean closesFindings) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), scanner, status, 0, 5L, null);
        task.setClosesFindings(closesFindings);
        tasks.save(task);
        return scan.getId();
    }
}
