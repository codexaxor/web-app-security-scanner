package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.intel.KevEntry;
import com.company.secscan.intel.KevEntryRepository;
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
 * M8 D-2 (D-083): a manual unignore re-scores the reopened finding IMMEDIATELY (api profile, where
 * {@link ReScoreService} is present) — so a KEV listing added while it was ignored is reflected at once, with no
 * status change event beyond the unignore and no rescan.
 */
class UnignoreRescoreIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String CVE = "CVE-2021-45046";

    @Autowired TriageService triage;
    @Autowired ReconcileService reconcile;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository findings;
    @Autowired SubIssueRepository subIssues;
    @Autowired RepositoryRepository repositories;
    @Autowired GitlabConnectionRepository connections;
    @Autowired KevEntryRepository kevEntries;

    @BeforeEach
    void clearKev() {
        kevEntries.deleteAllInBatch();
    }

    @Test
    void unignoreRescoresReopenedFindingImmediately() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "file:///local", "svc", 8300L, "ct", "read_api,read_repository", null));
        UUID repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 8301L, "svc/app", "main",
                List.of("Java"))).getId();

        Scan scan = scans.save(Scan.manual(WORKSPACE, repo, "main"));
        tasks.save(new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, 0, 5L, null));
        // Mid severity (base 50) so the +5 KEV adjustment is visible and does not clamp at 100.
        ScanFinding f = ScanFinding.sca(WORKSPACE, scan.getId(), repo, CVE, "org.foo:bar", "Maven", "1.0", "2.0",
                "pom.xml", "5.0");
        f.setOsvEnrichment("CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:L/A:L", "MEDIUM", List.of("CWE-94"),
                List.of(CVE), "RCE");
        findings.save(f);
        scan.finish(Scan.STATUS_COMPLETED);
        scans.save(scan);
        reconcile.reconcile(scan.getId(), WORKSPACE);

        SubIssue before = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).get(0);
        int scoreBefore = before.getScore();
        assertThat(before.isInKev()).isFalse();

        // Ignore the finding, then — while it is ignored — the CVE lands on CISA KEV.
        var rule = triage.ignoreSubIssue(before.getId(), "accepted for now");
        assertThat(subIssues.findById(before.getId()).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_IGNORED);
        kevEntries.save(new KevEntry(CVE, "Log4Shell-adjacent", null));

        // Unignore → reopened to open AND re-scored immediately: now on KEV (+5), with no rescan.
        triage.removeRule(rule.id());

        SubIssue after = subIssues.findById(before.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(after.isInKev()).isTrue();
        assertThat(after.getScore()).isEqualTo(scoreBefore + 5);
        assertThat(after.getScoreBreakdown()).anyMatch(l -> l.code().equals("on_kev"));
    }
}
