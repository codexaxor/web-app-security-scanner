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
 * B-1: a stale ignored orphan is closed at reconcile the moment it genuinely disappears, even on the SAME branch
 * (no branch switch) — not left to accumulate. A SAST snippet shift moves a finding to a new fingerprint: the old
 * (ignored, re-derivably held) fingerprint is solved with SOLVED_NOT_DETECTED while the new one is created and
 * ignored by the same rule, so repeated scans never grow the ignored-orphan count (S-4 adds the failed-scanner
 * guard: an absent ignored finding whose scanner FAILED is untouched). Drives {@link ReconcileService} directly.
 */
class SameBranchOrphanReconcileIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String RULE = "java.lang.security.audit.xss";
    private static final String FILE = "src/Main.java";

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

    /** One SAST scan on {@code branch} with a finding at (RULE, FILE, snippetHash); sast ok + closes findings. */
    private UUID runSast(Repository repo, String branch, boolean sastOk, String snippetHash) {
        Scan scan = scans.save(Scan.manual(WORKSPACE, repo.getId(), branch));
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SAST,
                sastOk ? ScanTask.STATUS_OK : "failed", sastOk ? 0 : 1, 100L, null);
        tasks.save(task);
        if (snippetHash != null) {
            findings.save(ScanFinding.sast(WORKSPACE, scan.getId(), repo.getId(), RULE, FILE, 42, snippetHash,
                    "high", "high", List.of("CWE-79")));
        }
        scan.finish(sastOk ? Scan.STATUS_COMPLETED : Scan.STATUS_PARTIAL);
        scans.save(scan);
        reconcile.reconcile(scan.getId(), WORKSPACE);
        return scan.getId();
    }

    private SubIssue subForSnippet(Repository repo, String snippetHash) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()).stream()
                .filter(s -> snippetHash.equals(s.getSnippetHash())).findFirst().orElseThrow();
    }

    private List<String> reasons(UUID subId) {
        return events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, subId).stream()
                .map(TriageEvent::getReasonCode).toList();
    }

    private long ignoredCount(Repository repo) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()).stream()
                .filter(s -> SubIssue.STATUS_IGNORED.equals(s.getStatus())).count();
    }

    @Test
    void sameBranchSnippetShiftSolvesOldIgnoredFingerprintAndIgnoresNew() {
        Repository repo = seedRepo(7101L);
        runSast(repo, "main", true, "aaa");                            // F(aaa) open
        // A rule-scope ignore (structural, re-derivable — matches any snippet of this rule).
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_RULE, repo.getId(), RULE, "accepted", "user"));
        runSast(repo, "main", true, "aaa");                            // F(aaa) ignored by IGNORE_RULE
        UUID old = subForSnippet(repo, "aaa").getId();
        assertThat(subForSnippet(repo, "aaa").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        runSast(repo, "main", true, "bbb");                            // snippet shifts → new fingerprint

        // Old fingerprint absent on the SAME branch, scanner authoritative, re-derivable hold → SOLVED_NOT_DETECTED.
        SubIssue oldAfter = subIssues.findById(old).orElseThrow();
        assertThat(oldAfter.getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(reasons(old)).endsWith(TriageEvent.REASON_SOLVED_NOT_DETECTED);
        assertThat(reasons(old)).doesNotContain(TriageEvent.REASON_BRANCH_CHANGED);
        // solveIgnored cleared the ignore markers (B-2).
        assertThat(oldAfter.getIgnoreReason()).isNull();
        assertThat(oldAfter.getIgnoredByRuleId()).isNull();
        // New fingerprint created and ignored by the same rule.
        assertThat(subForSnippet(repo, "bbb").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
    }

    @Test
    void repeatScanCreatesNoNewOrphansWithAChangedSnippetHash() {
        Repository repo = seedRepo(7102L);
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_RULE, repo.getId(), RULE, "accepted", "user"));
        // Each scan shifts the snippet hash — a fresh fingerprint every time, all matched by the rule.
        for (String snippet : List.of("s1", "s2", "s3", "s4", "s5")) {
            runSast(repo, "main", true, snippet);
            // At most ONE ignored orphan survives any scan: the current fingerprint; the prior one is solved.
            assertThat(ignoredCount(repo)).isLessThanOrEqualTo(1);
        }
        // Reconcile already closed every prior orphan, so the one-off retire pass has nothing to do.
        assertThat(subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()))
                .filteredOn(s -> SubIssue.STATUS_IGNORED.equals(s.getStatus())).hasSize(1);
        assertThat(subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()))
                .filteredOn(s -> SubIssue.STATUS_SOLVED.equals(s.getStatus())).hasSize(4);
    }

    @Test
    void absentIgnoredWithFailedScannerUnchanged() {
        Repository repo = seedRepo(7103L);
        runSast(repo, "main", true, "aaa");                            // F(aaa) open
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_RULE, repo.getId(), RULE, "accepted", "user"));
        runSast(repo, "main", true, "aaa");                            // F(aaa) ignored
        UUID f = subForSnippet(repo, "aaa").getId();

        runSast(repo, "main", false, null);                            // sast FAILED this scan → closes nothing

        SubIssue after = subIssues.findById(f).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);            // untouched (domain rule 3)
        assertThat(reasons(f)).doesNotContain(TriageEvent.REASON_SOLVED_NOT_DETECTED,
                TriageEvent.REASON_BRANCH_CHANGED);
    }
}
