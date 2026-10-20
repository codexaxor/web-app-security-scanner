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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The M6 fingerprints are FROZEN: rescanning the same code at a new commit (only line numbers move) must NOT
 * churn the issue model. Scan twice — the second scan reports the same SAST/IaC/EOL findings but with different
 * line numbers — and prove each finding stays the same sub-issue ("seen"), with the SAME fingerprint and ZERO
 * new triage_event rows. This proves the fingerprints are line-free (domain rule 2) and a rescan is 0-triage
 * (like D-035 for SCA). Reconcile-level (rows seeded directly, no container).
 */
class FingerprintFreezeIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

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
    TriageEventRepository events;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void rescanWithMovedLinesKeepsFingerprintsAndWritesNoNewTriageEvents() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-freeze", 55L, "ct", "read_api,read_repository", null));
        UUID repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 1055L, "svc/freeze", "main",
                List.of("Java"))).getId();

        // Scan 1: one SAST, one IaC and one EOL finding (line 10) → 3 new sub-issues, 3 SEEN events.
        UUID scan1 = seedScan(repo);
        seedFindings(scan1, repo, 10);
        reconcile.reconcile(scan1, WORKSPACE);

        List<SubIssue> afterScan1 = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo);
        assertThat(afterScan1).hasSize(3);
        List<String> fpsAfter1 = afterScan1.stream().map(SubIssue::getFingerprint).sorted().toList();
        long eventsAfter1 = events.count();
        assertThat(eventsAfter1).isEqualTo(3); // 3 SEEN (new-open) events

        // Scan 2: the SAME findings, only the line numbers moved (10 → 999).
        UUID scan2 = seedScan(repo);
        seedFindings(scan2, repo, 999);
        reconcile.reconcile(scan2, WORKSPACE);

        List<SubIssue> afterScan2 = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo);
        // Same three sub-issues, same fingerprints — nothing re-fingerprinted by the line move.
        assertThat(afterScan2).hasSize(3);
        assertThat(afterScan2.stream().map(SubIssue::getFingerprint).sorted().toList()).isEqualTo(fpsAfter1);
        assertThat(afterScan2).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SubIssue.STATUS_OPEN));
        // 0-triage rescan: no reopened/solved/new events were written by the second scan.
        assertThat(events.count()).as("a line-only rescan writes no new triage_event").isEqualTo(eventsAfter1);
    }

    private UUID seedScan(UUID repoId) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        for (String scanner : List.of(ScanTask.SCANNER_SAST, ScanTask.SCANNER_IAC, ScanTask.SCANNER_EOL)) {
            ScanTask task = new ScanTask(WORKSPACE, scan.getId(), scanner, ScanTask.STATUS_OK, 0, 5L, null);
            task.setClosesFindings(true);
            tasks.save(task);
        }
        return scan.getId();
    }

    private void seedFindings(UUID scan, UUID repo, int line) {
        scanFindings.save(ScanFinding.sast(WORKSPACE, scan, repo, "rule.x", "src/A.java", line, "snip", "HIGH",
                "ERROR", List.of("CWE-89")));
        scanFindings.save(ScanFinding.iac(WORKSPACE, scan, repo, "CKV_AWS_24", "main.tf",
                "aws_security_group.open", line, null));
        scanFindings.save(ScanFinding.eol(WORKSPACE, scan, repo, "spring-boot", "2.1", "pom.xml"));
    }
}
