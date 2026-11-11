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
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-076.5 re-baseline on a branch switch (amendment A4/A5): switch-caused closes/reopens use
 * {@code BRANCH_CHANGED} (never plain solved/reopened), a same-branch rescan adds no events, a failed scanner
 * defers its re-baseline to its next success, and an ignored finding absent on the new branch stays ignored.
 * Drives {@link ReconcileService} directly over seeded scans/tasks/findings (no scanner container needed).
 */
class RebaselineReconcileIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    ReconcileService reconcile;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanTaskRepository tasks;
    @Autowired
    ScanFindingRepository findings;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    TriageEventRepository events;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    IgnoreRuleRepository ignoreRules;

    private Repository seedRepo(long projectId) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "file:///local", "svc-" + projectId, projectId, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), projectId, "svc/" + projectId, "main",
                List.of("Java")));
    }

    /** Reconciles one SCA scan on {@code branch} containing the given vuln ids (sca ok + closes findings). */
    private UUID runSca(Repository repo, String branch, boolean scaOk, String... vulnIds) {
        Scan scan = scans.save(Scan.manual(WORKSPACE, repo.getId(), branch));
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA,
                scaOk ? ScanTask.STATUS_OK : "failed", scaOk ? 0 : 1, 100L, null);
        tasks.save(task);
        for (String vulnId : vulnIds) {
            findings.save(ScanFinding.sca(WORKSPACE, scan.getId(), repo.getId(), vulnId,
                    "pkg-" + vulnId, "Maven", "1.0.0", "2.0.0", "pom.xml", "high"));
        }
        // Move the scan out of queued/running (as recordSuccess would) so the next scan of this repo does not hit
        // the partial unique index uq_scan_active_per_repository, then reconcile.
        scan.finish(scaOk ? Scan.STATUS_COMPLETED : Scan.STATUS_PARTIAL);
        scans.save(scan);
        reconcile.reconcile(scan.getId(), WORKSPACE);
        return scan.getId();
    }

    private SubIssue subFor(Repository repo, String vulnId) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()).stream()
                .filter(s -> vulnId.equals(s.getVulnId())).findFirst().orElseThrow();
    }

    private List<String> reasons(UUID subId) {
        return events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, subId).stream()
                .map(TriageEvent::getReasonCode).toList();
    }

    @Test
    void threeBranchCycleReBaselinesWithoutPlainSolvedOrReopened() {
        Repository repo = seedRepo(5101L);

        runSca(repo, "main", true, "CVE-1", "CVE-2");          // F1,F2 open
        UUID f1 = subFor(repo, "CVE-1").getId();
        UUID f2 = subFor(repo, "CVE-2").getId();
        assertThat(reasons(f1)).containsExactly(TriageEvent.REASON_SEEN);

        runSca(repo, "dev", true, "CVE-2", "CVE-3");           // F1 absent → BRANCH_CHANGED; F3 new
        UUID f3 = subFor(repo, "CVE-3").getId();
        assertThat(subFor(repo, "CVE-1").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(reasons(f1)).containsExactly(TriageEvent.REASON_SEEN, TriageEvent.REASON_BRANCH_CHANGED);

        runSca(repo, "staging", true, "CVE-3");                // F2 absent → BRANCH_CHANGED
        assertThat(subFor(repo, "CVE-2").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(reasons(f2)).containsExactly(TriageEvent.REASON_SEEN, TriageEvent.REASON_BRANCH_CHANGED);

        runSca(repo, "main", true, "CVE-1", "CVE-2");          // back to main: F1,F2 reopen; F3 solved — all BRANCH_CHANGED
        assertThat(subFor(repo, "CVE-1").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(subFor(repo, "CVE-2").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(subFor(repo, "CVE-3").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);

        // No switch ever produced a plain solve or reopen — only SEEN and BRANCH_CHANGED.
        List<String> allReasons = events.findAll().stream().map(TriageEvent::getReasonCode).toList();
        assertThat(allReasons).doesNotContain(TriageEvent.REASON_SOLVED_NOT_DETECTED, TriageEvent.REASON_REOPENED);
        assertThat(allReasons).containsOnly(TriageEvent.REASON_SEEN, TriageEvent.REASON_BRANCH_CHANGED);

        // Same-branch rescan adds zero triage events (F1,F2 seen; F3 already solved, untouched).
        long before = events.count();
        runSca(repo, "main", true, "CVE-1", "CVE-2");
        assertThat(events.count()).isEqualTo(before);
        assertThat(subFor(repo, "CVE-1").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(f3).isEqualTo(subFor(repo, "CVE-3").getId()); // no duplicate rows
    }

    @Test
    void aFailedScannerDefersItsReBaselineToTheNextSuccess() {
        Repository repo = seedRepo(5102L);

        runSca(repo, "main", true, "CVE-9");                   // F9 open on main
        UUID f9 = subFor(repo, "CVE-9").getId();

        runSca(repo, "dev", false);                            // sca FAILED on the switch scan → closes nothing
        assertThat(subFor(repo, "CVE-9").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(reasons(f9)).containsExactly(TriageEvent.REASON_SEEN); // untouched

        runSca(repo, "dev", true);                             // sca succeeds on dev, F9 absent → deferred re-baseline
        assertThat(subFor(repo, "CVE-9").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(reasons(f9)).containsExactly(TriageEvent.REASON_SEEN, TriageEvent.REASON_BRANCH_CHANGED);
    }

    @Test
    void anIgnoredFindingWithAReDerivableHoldAbsentOnTheNewBranchIsSolvedBranchChanged() {
        // D-083 (OD-19) CHANGES the M8.1 behaviour: on a branch switch, an ignored finding whose hold is
        // re-derivable (here a CVE-scope rule — structural, re-applies to any equivalent live finding) and which
        // is absent on the new branch is now SOLVED with BRANCH_CHANGED, not left ignored forever (D-076's
        // "absent-but-ignored after a branch switch" family). If it reappears on another branch it is re-ignored
        // by the same cause, so user intent is preserved. (Pre-M8.5 this asserted it "stays ignored".)
        Repository repo = seedRepo(5103L);

        runSca(repo, "main", true, "CVE-7");                   // F7 open on main
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_CVE, repo.getId(), "CVE-7", "not relevant",
                "user"));
        runSca(repo, "main", true, "CVE-7");
        assertThat(subFor(repo, "CVE-7").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED); // held before the switch

        runSca(repo, "dev", true);                             // F7 absent on dev, re-derivable hold → solved

        SubIssue after = subFor(repo, "CVE-7");
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(reasons(after.getId())).endsWith(TriageEvent.REASON_BRANCH_CHANGED);
        assertThat(reasons(after.getId())).doesNotContain(TriageEvent.REASON_SOLVED_NOT_DETECTED);

        // Switching back to main re-detects CVE-7; it reopens (BRANCH_CHANGED) and is re-ignored by the CVE rule.
        runSca(repo, "main", true, "CVE-7");
        assertThat(subFor(repo, "CVE-7").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
    }

    @Test
    void aSubIssueScopeManualIgnoreAbsentOnTheNewBranchStaysIgnored() {
        // The carve-out (D-083): a sub-issue-scope manual ignore is an explicit human decision on this exact
        // finding and is NEVER retired by OD-19 — it stays ignored across the switch (audit preserved).
        Repository repo = seedRepo(5104L);

        runSca(repo, "main", true, "CVE-8");                   // F8 open on main
        String fingerprint = subFor(repo, "CVE-8").getFingerprint();
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_SUBISSUE, repo.getId(), fingerprint,
                "accepted risk", "user"));
        runSca(repo, "main", true, "CVE-8");
        assertThat(subFor(repo, "CVE-8").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        runSca(repo, "dev", true);                             // F8 absent on dev, sub-issue manual hold → kept

        SubIssue after = subFor(repo, "CVE-8");
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(reasons(after.getId())).doesNotContain(TriageEvent.REASON_BRANCH_CHANGED,
                TriageEvent.REASON_SOLVED_NOT_DETECTED);
    }

    @Test
    void switchToBranchWithNoManifestsReBaselinesPriorScaFindings() {
        // M8.1 D-1: a switch to a branch with NO manifests makes the SCA scan no-packages (ok but
        // closes_findings=false). The prior branch's open SCA findings must still re-baseline (solved with
        // BRANCH_CHANGED), not linger on the newly-tracked empty branch.
        Repository repo = seedRepo(5105L);

        runSca(repo, "main", true, "CVE-11", "CVE-12");        // F11,F12 open on main
        assertThat(subFor(repo, "CVE-11").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);

        runScaNoPackages(repo, "dev");                         // dev has no manifests: ok + closes_findings=false

        assertThat(subFor(repo, "CVE-11").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(subFor(repo, "CVE-12").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(reasons(subFor(repo, "CVE-11").getId()))
                .containsExactly(TriageEvent.REASON_SEEN, TriageEvent.REASON_BRANCH_CHANGED);
    }

    /** A successful SCA scan that found no packages (OSV exit 128 → closes_findings=false) with no findings. */
    private void runScaNoPackages(Repository repo, String branch) {
        Scan scan = scans.save(Scan.manual(WORKSPACE, repo.getId(), branch));
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, 0, 100L,
                null);
        task.setClosesFindings(false); // OSV exit 128: ok, but not same-branch-authoritative (D-034)
        tasks.save(task);
        scan.finish(Scan.STATUS_COMPLETED);
        scans.save(scan);
        reconcile.reconcile(scan.getId(), WORKSPACE);
    }
}
