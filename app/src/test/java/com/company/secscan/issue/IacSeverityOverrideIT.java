package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.intel.IacSeverityOverride;
import com.company.secscan.intel.IacSeverityOverrideRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V20 (D-083 OD-27, D-087): a per-check_id IaC severity override changes the SCORE only — never a status change
 * and never a triage_event — proven through the re-score path (D-049). Without an override the IaC base stays
 * MEDIUM (50).
 */
class IacSeverityOverrideIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired ReconcileService reconcile;
    @Autowired ReScoreService reScore;
    @Autowired IacSeverityOverrideRepository iacOverrides;
    @Autowired SubIssueRepository subIssues;
    @Autowired TriageEventRepository events;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository scanFindings;
    @Autowired RepositoryRepository repositories;
    @Autowired GitlabConnectionRepository connections;

    @BeforeEach
    void clearOverrides() {
        iacOverrides.deleteAll(); // global table (D-050) — not covered by the base DB cleanup
    }

    private UUID seedRepo() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "file:///local", "svc", 7000L, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 7001L, "svc/iac", "main",
                List.of("HCL"))).getId();
    }

    private UUID seedIacScan(UUID repoId, String checkId) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_IAC, ScanTask.STATUS_OK, 0, 5L, null);
        tasks.save(task);
        scanFindings.save(ScanFinding.iac(WORKSPACE, scan.getId(), repoId, checkId, "main.tf",
                "aws_security_group.open", 4, null)); // null severity → default MEDIUM base
        return scan.getId();
    }

    private SubIssue onlySub(UUID repoId) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repoId).stream().findFirst().orElseThrow();
    }

    @Test
    void overrideChangesScoreOnlyNoStatusNoEvent() {
        UUID repo = seedRepo();
        reconcile.reconcile(seedIacScan(repo, "CKV_AWS_24"), WORKSPACE);
        SubIssue before = onlySub(repo);
        assertThat(before.getScore()).isEqualTo(50);        // default MEDIUM
        assertThat(before.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        long eventsBefore = events.count();

        iacOverrides.save(new IacSeverityOverride("CKV_AWS_24", "CRITICAL", "test override"));
        reScore.rescoreRepository(WORKSPACE, repo);

        SubIssue after = onlySub(repo);
        assertThat(after.getScore()).isEqualTo(90);          // override → CRITICAL base
        assertThat(after.getScoreBreakdown()).anyMatch(l -> l.code().equals("base_iac") && l.delta() == 90);
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_OPEN); // no status change
        assertThat(events.count()).isEqualTo(eventsBefore);           // no triage_event
    }

    @Test
    void absentOverrideKeepsMediumBase() {
        UUID repo = seedRepo();
        reconcile.reconcile(seedIacScan(repo, "CKV_AWS_99"), WORKSPACE);
        reScore.rescoreRepository(WORKSPACE, repo);
        assertThat(onlySub(repo).getScore()).isEqualTo(50);
    }
}
