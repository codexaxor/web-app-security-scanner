package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.api.SubIssueView;
import com.company.secscan.reconcile.Fingerprints;
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
 * SCA reconcile behaviour driven by seeding rows directly (no scanner container): group key includes the
 * ecosystem (plan change #7), the API exposes the SCA fields (change #8), a failed OSV run and a no-packages
 * run (exit 128, {@code closes_findings = false}) both close nothing (domain rule 3, D-034, change #1), and the
 * ordinary solved/reopened lifecycle.
 */
class ScaReconcileIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    ReconcileService reconcile;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanTaskRepository tasks;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    TriageEventRepository events;
    @Autowired
    ScanFindingRepository scanFindings;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    private Repository seedRepository() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 42L, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));
    }

    /** A finished scan with one SCA task of the given status/authority and no findings; returns its id. */
    private UUID seedScanWithScaTask(UUID repoId, String taskStatus, boolean closesFindings) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(ScanTask.STATUS_OK.equals(taskStatus) ? Scan.STATUS_COMPLETED : Scan.STATUS_PARTIAL);
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA, taskStatus, 0, 5L, null);
        task.setClosesFindings(closesFindings);
        tasks.save(task);
        return scan.getId();
    }

    private void seedScaFinding(UUID scanId, UUID repoId, String vulnId, String pkg, String ecosystem,
                                String version, String path) {
        scanFindings.save(ScanFinding.sca(WORKSPACE, scanId, repoId, vulnId, pkg, ecosystem, version,
                null, path, null));
    }

    private SubIssue seedOpenSca(UUID repoId, String vulnId, String pkg, String ecosystem, String path) {
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repoId, "sca",
                Fingerprints.groupKey("sca", ecosystem + "/" + pkg), pkg, null));
        SubIssue sub = new SubIssue(WORKSPACE, group.getId(), repoId, "sca", ScanTask.SCANNER_SCA,
                Fingerprints.sca(pkg, vulnId, path), null);
        sub.setVulnId(vulnId);
        sub.setPackageName(pkg);
        sub.setInstalledVersion("1.0.0");
        return subIssues.save(sub);
    }

    @Test
    void scaFindingsOpenAndGroupByEcosystem() {
        Repository repo = seedRepository();
        UUID scanId = seedScanWithScaTask(repo.getId(), ScanTask.STATUS_OK, true);
        // Same package name in two ecosystems -> two groups (plan change #7).
        seedScaFinding(scanId, repo.getId(), "CVE-2021-1", "left-pad", "npm", "1.0.0", "package-lock.json");
        seedScaFinding(scanId, repo.getId(), "CVE-2021-2", "left-pad", "Maven", "1.0.0", "pom.xml");

        reconcile.reconcile(scanId, WORKSPACE);

        assertThat(groups.findAll()).extracting(IssueGroup::getGroupKey)
                .containsExactlyInAnyOrder("sca|npm/left-pad", "sca|Maven/left-pad");
        List<SubIssue> subs = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId());
        assertThat(subs).hasSize(2).allSatisfy(s -> assertThat(s.getStatus()).isEqualTo(SubIssue.STATUS_OPEN));

        // The API exposes the SCA fields (plan change #8).
        SubIssueView view = SubIssueView.of(subs.get(0));
        assertThat(view.vulnId()).startsWith("CVE-2021-");
        assertThat(view.packageName()).isEqualTo("left-pad");
        assertThat(view.installedVersion()).isEqualTo("1.0.0");
    }

    @Test
    void aFailedOsvRunClosesNoScaIssue() {
        Repository repo = seedRepository();
        SubIssue open = seedOpenSca(repo.getId(), "CVE-2021-9", "foo", "npm", "package-lock.json");
        // OSV failed (e.g. offline, exit 127): a failed scanner closes nothing.
        UUID scanId = seedScanWithScaTask(repo.getId(), ScanTask.STATUS_FAILED, false);

        reconcile.reconcile(scanId, WORKSPACE);

        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, open.getId())).isEmpty();
    }

    @Test
    void aNoPackagesScanClosesNoScaIssue() {
        Repository repo = seedRepository();
        SubIssue open = seedOpenSca(repo.getId(), "CVE-2021-9", "foo", "npm", "package-lock.json");
        // OSV ran ok but found no packages (exit 128): closes_findings = false -> not authoritative to solve.
        UUID scanId = seedScanWithScaTask(repo.getId(), ScanTask.STATUS_OK, false);

        reconcile.reconcile(scanId, WORKSPACE);

        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, open.getId())).isEmpty();
    }

    @Test
    void aMissingScaFindingIsSolvedWhenTheScannerCloses() {
        Repository repo = seedRepository();
        SubIssue open = seedOpenSca(repo.getId(), "CVE-2021-9", "foo", "npm", "package-lock.json");
        // OSV ran ok with packages (closes_findings = true) but the finding is gone -> solved.
        UUID scanId = seedScanWithScaTask(repo.getId(), ScanTask.STATUS_OK, true);

        reconcile.reconcile(scanId, WORKSPACE);

        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, open.getId()))
                .singleElement().satisfies(e -> assertThat(e.getToStatus()).isEqualTo(SubIssue.STATUS_SOLVED));
    }
}
