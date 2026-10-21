package com.company.secscan.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.intel.EpssScore;
import com.company.secscan.intel.EpssScoreRepository;
import com.company.secscan.issue.ReconcileService;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.issue.TriageEvent;
import com.company.secscan.issue.TriageEventRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.support.AbstractGitLabIT;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S6 (D-060): the EPSS toggle as an on-TOGGLE status sweep via WorkspaceSettingsService (api). Enabling it
 * auto-ignores existing open low-EPSS findings LOW_EPSS (status + triage_event) without a rescan; disabling it
 * clears them back to open. The scoring tiers are applied by the re-score it triggers, with no triage_event.
 */
class EpssToggleSweepIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = com.company.secscan.workspace.Workspace.DEFAULT_ID;

    @Autowired WorkspaceSettingsService settings;
    @Autowired ReconcileService reconcile;
    @Autowired SubIssueRepository subIssues;
    @Autowired TriageEventRepository events;
    @Autowired EpssScoreRepository epssScores;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository scanFindings;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryRepository repositories;

    @BeforeEach
    void epssOff() {
        settings.setEpssAutoIgnore(WORKSPACE, false);
    }

    @Test
    void enablingIgnoresExistingLowEpssAndDisablingRestoresIt() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2100-7", "org.a:a");
        epssScores.save(new EpssScore("CVE-2100-7", new BigDecimal("0.00200"), new BigDecimal("0.1"))); // 0.2%
        reconcile.reconcile(scan, WORKSPACE); // toggle still off → the finding is open
        SubIssue open = only(repo);
        assertThat(open.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);

        // Enable: the open low-EPSS finding is auto-ignored LOW_EPSS with a triage_event (no rescan needed).
        settings.setEpssAutoIgnore(WORKSPACE, true);
        SubIssue afterOn = only(repo);
        assertThat(afterOn.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(afterOn.getIgnoreReason()).isEqualTo("LOW_EPSS");
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, afterOn.getId()))
                .anyMatch(e -> "LOW_EPSS".equals(e.getReasonCode())
                        && SubIssue.STATUS_IGNORED.equals(e.getToStatus()));

        // Disable: the LOW_EPSS ignore clears back to the reconcile-computed status (still seen → open).
        settings.setEpssAutoIgnore(WORKSPACE, false);
        SubIssue afterOff = only(repo);
        assertThat(afterOff.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(afterOff.getIgnoreReason()).isNull();
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, afterOff.getId()))
                .anyMatch(e -> TriageEvent.REASON_UNIGNORED.equals(e.getReasonCode()));
    }

    private SubIssue only(UUID repo) {
        List<SubIssue> subs = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo);
        assertThat(subs).hasSize(1);
        return subs.get(0);
    }

    private UUID seedRepo() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 55L, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 7055L, "svc/epss", "main",
                List.of("Java"))).getId();
    }

    private UUID seedScan(UUID repoId) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, 0, 5L, null);
        task.setClosesFindings(true);
        tasks.save(task);
        return scan.getId();
    }

    private void seedSca(UUID scanId, UUID repoId, String cve, String pkg) {
        ScanFinding f = ScanFinding.sca(WORKSPACE, scanId, repoId, cve, pkg, "Maven", "1.0.0", "1.0.1", "pom.xml",
                "9.8");
        f.setOsvEnrichment("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H", "CRITICAL", List.of("CWE-94"),
                List.of(cve), "RCE");
        scanFindings.save(f);
    }
}
