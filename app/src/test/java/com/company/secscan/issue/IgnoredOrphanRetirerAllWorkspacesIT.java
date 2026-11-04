package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.reconcile.Fingerprints;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.triage.ReasonCode;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.10 inventory item 15: the operator's retire pass ({@link IgnoredOrphanRetirer}) runs across ALL workspaces
 * (like the re-score sweep) instead of only Default, and every change it makes — the solved row and its
 * {@code triage_event} — stays in that row's own workspace.
 */
class IgnoredOrphanRetirerAllWorkspacesIT extends AbstractGitLabIT {

    @Autowired IgnoredOrphanRetirer retirer;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired SubIssueRepository subIssues;
    @Autowired IssueGroupRepository groups;
    @Autowired TriageEventRepository events;
    @Autowired RepositoryRepository repositories;
    @Autowired GitlabConnectionRepository connections;
    @Autowired IgnoreRuleRepository ignoreRules;
    @Autowired WorkspaceRepository workspaces;

    @Test
    void retiresOrphansInEveryWorkspaceAndKeepsEachChangeInItsOwnWorkspace() {
        UUID b = workspaces.save(new Workspace("Tenant B")).getId();
        UUID inDefault = seedOrphan(Workspace.DEFAULT_ID, 7101L, "CVE-7101");
        UUID inB = seedOrphan(b, 7102L, "CVE-7102");

        IgnoredOrphanRetirer.RetireResult dry = retirer.retire(true);
        assertThat(dry.candidateList()).extracting(IgnoredOrphanRetirer.Candidate::subIssueId)
                .containsExactlyInAnyOrder(inDefault, inB);

        IgnoredOrphanRetirer.RetireResult run = retirer.retire(false);
        assertThat(run.retired()).isEqualTo(2);

        assertSolvedWithin(Workspace.DEFAULT_ID, inDefault);
        assertSolvedWithin(b, inB);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(Workspace.DEFAULT_ID, inB)).isEmpty();
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(b, inDefault)).isEmpty();
    }

    private void assertSolvedWithin(UUID workspaceId, UUID subId) {
        SubIssue sub = subIssues.findById(subId).orElseThrow();
        assertThat(sub.getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(workspaceId, subId))
                .extracting(TriageEvent::getReasonCode).contains("SOLVED_NOT_DETECTED");
    }

    /** An ignored SCA row held by a CVE rule, absent from the repo's latest authoritative reconciled scan. */
    private UUID seedOrphan(UUID ws, long projectId, String cve) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                ws, "file:///local", "svc-" + projectId, projectId, "ct", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(ws, conn.getId(), projectId, "svc/" + projectId, "main",
                List.of("Java")));
        Scan scan = Scan.manual(ws, repo.getId(), "main");
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan.markReconciled();
        scan = scans.save(scan);
        ScanTask task = new ScanTask(ws, scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, 0, 100L, null);
        task.setClosesFindings(true);
        tasks.save(task);

        String key = Fingerprints.groupKey("sca", "Maven/pkg-" + cve);
        IssueGroup g = groups.save(new IssueGroup(ws, repo.getId(), "sca", key, "pkg-" + cve, null));
        IgnoreRule rule = ignoreRules.save(new IgnoreRule(ws, IgnoreRule.SCOPE_CVE, repo.getId(), cve, "accepted",
                "user"));
        SubIssue sub = new SubIssue(ws, g.getId(), repo.getId(), "sca", ScanTask.SCANNER_SCA,
                Fingerprints.sca("pkg-" + cve, cve, "pom.xml"), null);
        sub.setVulnId(cve);
        sub.setPackageName("pkg-" + cve);
        sub.setFilePath("pom.xml");
        sub.setCveAliases(List.of(cve));
        sub.ignore(ReasonCode.IGNORE_RULE.code(), rule.getId());
        return subIssues.save(sub).getId();
    }
}
