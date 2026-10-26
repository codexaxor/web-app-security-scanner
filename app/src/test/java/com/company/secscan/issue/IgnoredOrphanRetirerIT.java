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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The OD-19 one-off retire pass ({@link IgnoredOrphanRetirer}, D-073 part 2 / D-083): retires stale ignored
 * findings absent from the repository's latest authoritative scan on its effective branch, whose hold is
 * re-derivable. A sub-issue-scope manual ignore and a non-authoritative latest scan retire nothing; the pass is
 * idempotent; dry-run changes nothing and returns the candidates.
 */
class IgnoredOrphanRetirerIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired IgnoredOrphanRetirer retirer;
    @Autowired ReconcileService reconcile;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository findings;
    @Autowired SubIssueRepository subIssues;
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

    /** One SCA scan on {@code branch}; sca ok + closes findings (authoritative). Returns the scan id. */
    private UUID runSca(Repository repo, String branch, String... vulnIds) {
        return runSca(repo, branch, true, true, vulnIds);
    }

    private UUID runSca(Repository repo, String branch, boolean scaOk, boolean closes, String... vulnIds) {
        Scan scan = scans.save(Scan.manual(WORKSPACE, repo.getId(), branch));
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA,
                scaOk ? ScanTask.STATUS_OK : "failed", scaOk ? 0 : 1, 100L, null);
        task.setClosesFindings(closes);
        tasks.save(task);
        for (String vulnId : vulnIds) {
            findings.save(ScanFinding.sca(WORKSPACE, scan.getId(), repo.getId(), vulnId, "pkg-" + vulnId, "Maven",
                    "1.0.0", "2.0.0", "pom.xml", "high"));
        }
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

    /** F held ignored by a re-derivable CVE rule, absent from the latest authoritative scan on its branch. */
    private Repository seedOrphan(long projectId, String heldCve) {
        Repository repo = seedRepo(projectId);
        runSca(repo, "main", heldCve);                                     // F open
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_CVE, repo.getId(), heldCve, "accepted", "user"));
        runSca(repo, "main", heldCve);                                     // F ignored (IGNORE_RULE)
        runSca(repo, "main", "CVE-OTHER");                                 // latest authoritative scan; F absent
        assertThat(subFor(repo, heldCve).getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        return repo;
    }

    @Test
    void dryRunListsCandidatesWithoutChangeThenRetireSolvesWithSolvedNotDetected() {
        Repository repo = seedOrphan(6101L, "CVE-100");
        UUID f = subFor(repo, "CVE-100").getId();
        UUID latest = scans.findFirstByWorkspaceIdAndRepositoryIdAndBranchAndReconciledAtIsNotNullOrderByCreatedAtDesc(
                WORKSPACE, repo.getId(), "main").orElseThrow().getId();

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
        runSca(repo, "main", "CVE-100");
        String fingerprint = subFor(repo, "CVE-100").getFingerprint();
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_SUBISSUE, repo.getId(), fingerprint,
                "accepted risk", "user"));
        runSca(repo, "main", "CVE-100");                                   // F ignored by sub-issue manual rule
        runSca(repo, "main", "CVE-OTHER");                                 // F absent, authoritative scan
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        assertThat(retirer.retire(true).candidates()).isZero();
        assertThat(retirer.retire(false).retired()).isZero();
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED); // preserved
    }

    @Test
    void aNonAuthoritativeLatestScanRetiresNothing() {
        Repository repo = seedRepo(6104L);
        runSca(repo, "main", "CVE-100");
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_CVE, repo.getId(), "CVE-100", "x", "user"));
        runSca(repo, "main", "CVE-100");                                   // F ignored
        runSca(repo, "main", true, false);                                 // latest scan: sca ok but no-packages
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        assertThat(retirer.retire(true).candidates()).isZero();            // not authoritative → nothing
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
    }

    @Test
    void aPresentIgnoredFindingIsNotRetired() {
        Repository repo = seedRepo(6105L);
        runSca(repo, "main", "CVE-100");
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_CVE, repo.getId(), "CVE-100", "x", "user"));
        runSca(repo, "main", "CVE-100");                                   // F ignored
        runSca(repo, "main", "CVE-100", "CVE-OTHER");                      // F still present in latest scan
        assertThat(subFor(repo, "CVE-100").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        assertThat(retirer.retire(true).candidates()).isZero();            // present → not a candidate
    }
}
