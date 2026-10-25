package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
import com.company.secscan.issue.ReScoreService;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.issue.SubIssueScorer;
import com.company.secscan.issue.TriageEventRepository;
import com.company.secscan.reconcile.Fingerprints;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** change 5: the per-repo re-score uses the same status='open' guard as rescoreAll/rescoreWorkspace — a sub-issue
 *  solved in the window since it was read is not resurrected, while an open sibling is re-scored; 0 triage_event. */
class SensitivityRescoreConcurrencyIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    RepositoryService repoService;
    @Autowired
    ReScoreService reScore;
    @Autowired
    SubIssueScorer scorer;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    TriageEventRepository events;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    @Test
    void aSolvedSubIsNotResurrectedWhileAnOpenSiblingIsRescored() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 9701L, "ct", "read_api,read_repository", null));
        var repo = repositories.save(new com.company.secscan.repository.Repository(
                WORKSPACE, conn.getId(), 9701L, "svc/race", "main", List.of("Java")));
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repo.getId(), "sca",
                Fingerprints.groupKey("sca", "pkg"), "pkg", null));

        SubIssue solved = newSub(group.getId(), repo.getId(), "fp-solved", "8.0");
        SubIssue open = newSub(group.getId(), repo.getId(), "fp-open", "8.0");
        UUID solvedId = subIssues.save(solved).getId();
        UUID openId = subIssues.save(open).getId();

        // reconcile solved one of them in the window; make the repo high-sensitivity so a re-score WOULD bump +5.
        SubIssue toSolve = subIssues.findById(solvedId).orElseThrow();
        toSolve.solve();
        subIssues.save(toSolve);
        var solvedAt = subIssues.findById(solvedId).orElseThrow().getSolvedAt();
        repoService.patch(repo.getId(), "high", null, null); // triggers rescoreRepository

        SubIssue afterSolved = subIssues.findById(solvedId).orElseThrow();
        assertThat(afterSolved.getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(afterSolved.getScore()).isEqualTo(80);          // NOT resurrected to 85
        assertThat(afterSolved.getSolvedAt()).isEqualTo(solvedAt);

        SubIssue afterOpen = subIssues.findById(openId).orElseThrow();
        assertThat(afterOpen.getScore()).isEqualTo(85);            // open sibling re-scored (+5 high sensitivity)
        assertThat(events.count()).isZero();                       // never a triage_event
    }

    private SubIssue newSub(UUID groupId, UUID repoId, String fp, String cvss) {
        SubIssue s = new SubIssue(WORKSPACE, groupId, repoId, "sca", "sca", fp, null);
        s.setVulnId("CVE-2022-42889");
        s.setToolSeverity(cvss);
        scorer.score(s, "normal"); // score 80, open
        return s;
    }
}
