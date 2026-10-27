package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.reconcile.Fingerprints;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.triage.ReasonCode;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The OD-19 one-off retire pass ({@link IgnoredOrphanRetirer}, D-073 part 2 / D-083): retires stale ignored
 * findings absent from the repository's latest authoritative scan on its effective branch, whose hold is
 * re-derivable. Since M8.5 (B-1) reconcile itself closes a same-branch orphan the moment it disappears, so this
 * retroactive pass exists only for orphans that accumulated BEFORE that behaviour — and the tests seed that
 * legacy state directly (an ignored row absent from a reconciled authoritative scan) rather than producing it
 * through a live scan (which reconcile would now close). A sub-issue-scope manual ignore, a non-authoritative or
 * failed latest scan, and a branch mismatch all retire nothing; the pass is idempotent; dry-run changes nothing.
 */
class IgnoredOrphanRetirerIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired IgnoredOrphanRetirer retirer;
    @Autowired TriageService triage;
    @Autowired ReconcileService reconcile;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository findings;
    @Autowired SubIssueRepository subIssues;
    @Autowired IssueGroupRepository groups;
    @Autowired TriageEventRepository events;
    @Autowired RepositoryRepository repositories;
    @Autowired GitlabConnectionRepository connections;
    @Autowired IgnoreRuleRepository ignoreRules;

    private Repository seedRepo(long projectId) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "file:///local", "svc-" + projectId, projectId, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), projectId, "svc/" + projectId, "main",
                List.of("Java")));
    }

    /** A reconciled SCA scan on {@code branch}; sca ok/failed + closes/doesn't. Returns the saved scan. */
    private Scan reconciledScan(Repository repo, String branch, boolean scaOk, boolean closes) {
        Scan scan = Scan.manual(WORKSPACE, repo.getId(), branch);
        scan.markRunning();
        scan.finish(scaOk ? Scan.STATUS_COMPLETED : Scan.STATUS_PARTIAL);
        scan.markReconciled();
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA,
                scaOk ? ScanTask.STATUS_OK : "failed", scaOk ? 0 : 1, 100L, null);
        task.setClosesFindings(closes);
        tasks.save(task);
        return scan;
    }

    private IssueGroup groupFor(Repository repo, String cve) {
        String key = Fingerprints.groupKey("sca", "Maven/pkg-" + cve);
        return groups.findByWorkspaceIdAndRepositoryIdAndGroupKey(WORKSPACE, repo.getId(), key)
                .orElseGet(() -> groups.save(new IssueGroup(WORKSPACE, repo.getId(), "sca", key, "pkg-" + cve,
                        null)));
    }

    /** Seeds an IGNORED SCA sub-issue directly (the legacy orphan shape), with {@code lastSeenScanId} absent. */
    private SubIssue seedIgnoredSca(Repository repo, String cve, UUID ruleId, UUID lastSeenScanId) {
        IssueGroup g = groupFor(repo, cve);
        SubIssue sub = new SubIssue(WORKSPACE, g.getId(), repo.getId(), "sca", ScanTask.SCANNER_SCA,
                Fingerprints.sca("pkg-" + cve, cve, "pom.xml"), lastSeenScanId);
        sub.setVulnId(cve);
        sub.setPackageName("pkg-" + cve);
        sub.setFilePath("pom.xml");
        sub.setCveAliases(List.of(cve));
        sub.ignore(ReasonCode.IGNORE_RULE.code(), ruleId);
        return subIssues.save(sub);
    }

    private IgnoreRule cveRule(Repository repo, String cve) {
        return ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_CVE, repo.getId(), cve, "accepted",
                "user"));
    }

    private SubIssue subFor(Repository repo, String cve) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()).stream()
                .filter(s -> cve.equals(s.getVulnId())).findFirst().orElseThrow();
    }

    private List<String> reasons(UUID subId) {
        return events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, subId).stream()
                .map(TriageEvent::getReasonCode).toList();
    }

    /** F held ignored by a re-derivable CVE rule, absent from the latest authoritative reconciled scan on main. */
    private Repository seedOrphan(long projectId, String heldCve) {
        Repository repo = seedRepo(projectId);
        reconciledScan(repo, "main", true, true);          // latest authoritative scan; F absent from it
        seedIgnoredSca(repo, heldCve, cveRule(repo, heldCve).getId(), null);
        assertThat(subFor(repo, heldCve).getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        return repo;
    }

    @Test
    void dryRunListsCandidatesWithoutChangeThenRetireSolvesWithSolvedNotDetected() {
        Repository repo = seedOrphan(6101L, "CVE-100");
        UUID f = subFor(repo, "CVE-100").getId();
        UUID latest = scans.findFirstByWorkspaceIdAndRepositoryIdAndReconciledAtIsNotNullOrderByCreatedAtDesc(
                WORKSPACE, repo.getId()).orElseThrow().getId();

        IgnoredOrphanRetirer.RetireResult dry = retirer.retire(true);
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.candidates()).isEqualTo(1);
        assertThat(dry.retired()).isZero();
        assertThat(dry.candidateList()).singleElement().satisfies(c -> {
            assertThat(c.subIssueId()).isEqualTo(f);
            assertThat(c.reason()).isEqualTo("IGNORE_RULE");
        });
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED); // unchanged

        IgnoredOrphanRetirer.RetireResult run = retirer.retire(false);
        assertThat(run.retired()).isEqualTo(1);
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(subFor(repo, "CVE-100").getIgnoredByRuleId()).isNull();  // B-2: markers cleared
        assertThat(reasons(f)).endsWith(TriageEvent.REASON_SOLVED_NOT_DETECTED);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, f).stream()
                .filter(e -> TriageEvent.REASON_SOLVED_NOT_DETECTED.equals(e.getReasonCode()))
                .findFirst().orElseThrow().getScanId()).isEqualTo(latest);
    }

    @Test
    void secondRunRetiresNothing() {
        seedOrphan(6102L, "CVE-100");
        assertThat(retirer.retire(false).retired()).isEqualTo(1);
        assertThat(retirer.retire(false).retired()).isZero();
        assertThat(retirer.retire(true).candidates()).isZero();
    }

    @Test
    void subIssueScopeManualIgnoreIsNeverRetired() {
        Repository repo = seedRepo(6103L);
        reconciledScan(repo, "main", true, true);
        // Held by a SUB-ISSUE-scope manual ignore (match value = fingerprint) — an explicit per-finding decision.
        String fingerprint = Fingerprints.sca("pkg-CVE-100", "CVE-100", "pom.xml");
        UUID ruleId = ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_SUBISSUE, repo.getId(),
                fingerprint, "accepted risk", "user")).getId();
        seedIgnoredSca(repo, "CVE-100", ruleId, null);
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        assertThat(retirer.retire(true).candidates()).isZero();
        assertThat(retirer.retire(false).retired()).isZero();
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED); // preserved
    }

    @Test
    void aNonAuthoritativeLatestScanRetiresNothing() {
        Repository repo = seedRepo(6104L);
        reconciledScan(repo, "main", true, false);         // sca ok but no-packages (closes_findings=false)
        seedIgnoredSca(repo, "CVE-100", cveRule(repo, "CVE-100").getId(), null);

        assertThat(retirer.retire(true).candidates()).isZero();            // not authoritative → nothing
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
    }

    @Test
    void absentIgnoredWithFailedScannerUnchanged() {
        // S-4: the latest scan's SCA scanner FAILED → not authoritative → the absent ignored finding is untouched.
        Repository repo = seedRepo(6106L);
        reconciledScan(repo, "main", false, false);        // sca FAILED
        seedIgnoredSca(repo, "CVE-100", cveRule(repo, "CVE-100").getId(), null);

        assertThat(retirer.retire(true).candidates()).isZero();
        assertThat(retirer.retire(false).retired()).isZero();
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
    }

    @Test
    void aPresentIgnoredFindingIsNotRetired() {
        Repository repo = seedRepo(6105L);
        Scan latest = reconciledScan(repo, "main", true, true);
        // last_seen points AT the latest scan → still present → not an orphan.
        seedIgnoredSca(repo, "CVE-100", cveRule(repo, "CVE-100").getId(), latest.getId());

        assertThat(retirer.retire(true).candidates()).isZero();            // present → not a candidate
    }

    @Test
    void switchBackBeforeRescanRetiresNothing() {
        // S-1: the tracked branch was changed to one not yet scanned — the latest reconciled scan is still on the
        // OLD branch, so its absence evidence does not apply to the branch we now track. Retire nothing.
        Repository repo = seedOrphan(6107L, "CVE-100");                    // latest scan is on "main"
        repo.setScanBranch("dev");                                         // switch tracked branch, no rescan yet
        repositories.save(repo);
        assertThat(repo.effectiveBranch()).isEqualTo("dev");

        assertThat(retirer.retire(true).candidates()).isZero();            // latest scan branch (main) != dev
        assertThat(retirer.retire(false).retired()).isZero();
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
    }

    @Test
    void retireThenDeleteRuleWritesNoEventAndRowStaysSolved() {
        // B-2: after OD-19 solves an ignored finding, its ignore markers are cleared — deleting the rule that
        // once held it must neither reopen it nor write a triage_event (removeRule iterates only ignored rows).
        Repository repo = seedRepo(6108L);
        reconciledScan(repo, "main", true, true);
        IgnoreRule rule = cveRule(repo, "CVE-100");
        SubIssue seeded = seedIgnoredSca(repo, "CVE-100", rule.getId(), null);
        assertThat(retirer.retire(false).retired()).isEqualTo(1);
        UUID f = seeded.getId();
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(subFor(repo, "CVE-100").getIgnoredByRuleId()).isNull();   // cleared by solveIgnored
        long eventsBefore = events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, f).size();

        triage.removeRule(rule.getId());

        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);  // still solved
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, f)).hasSize((int) eventsBefore);
    }

    @Test
    void aReopenedRetiredRowCarriesNoStaleRuleId() {
        // B-2: a row solved by the retire pass has no ignored_by_rule_id; if a later scan re-detects it (rule
        // already removed), it reopens to OPEN with no stale rule id.
        Repository repo = seedRepo(6109L);
        reconciledScan(repo, "main", true, true);
        IgnoreRule rule = cveRule(repo, "CVE-100");
        seedIgnoredSca(repo, "CVE-100", rule.getId(), null);
        assertThat(retirer.retire(false).retired()).isEqualTo(1);
        triage.removeRule(rule.getId());                                   // drop the rule so it won't re-ignore

        reconcileScanWith(repo, "main", "CVE-100");                        // F re-detected on the same branch

        SubIssue after = subFor(repo, "CVE-100");
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(after.getIgnoredByRuleId()).isNull();                   // no stale rule id
        assertThat(after.getIgnoreReason()).isNull();
    }

    /** Reconciles a live SCA scan on {@code branch} containing the given CVEs (sca ok + closes findings). */
    private void reconcileScanWith(Repository repo, String branch, String... cves) {
        Scan scan = scans.save(Scan.manual(WORKSPACE, repo.getId(), branch));
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, 0, 100L,
                null);
        task.setClosesFindings(true);
        tasks.save(task);
        for (String cve : cves) {
            findings.save(ScanFinding.sca(WORKSPACE, scan.getId(), repo.getId(), cve, "pkg-" + cve, "Maven",
                    "1.0.0", "2.0.0", "pom.xml", "high"));
        }
        scan.finish(Scan.STATUS_COMPLETED);
        scans.save(scan);
        reconcile.reconcile(scan.getId(), WORKSPACE);
    }
}
