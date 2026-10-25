package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
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

/** A sensitivity change re-scores the repo's open sub-issues (score-only), writing 0 triage_event and never
 *  changing status or fingerprint (OD-15 / D-070, change 5). */
class SensitivityRescoreIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String CVE = "CVE-2022-42889";

    @Autowired
    RepositoryService service;
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
    void settingHighSensitivityBumpsOpenScoresByFiveWithNoTriageEventOrStatusChange() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 9201L, "ct", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 9201L, "svc/scored", "main",
                List.of("Java")));
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repo.getId(), "sca",
                Fingerprints.groupKey("sca", "Maven/org.apache.commons:commons-text"),
                "org.apache.commons:commons-text", null));
        SubIssue sub = new SubIssue(WORKSPACE, group.getId(), repo.getId(), "sca", "sca",
                Fingerprints.sca("org.apache.commons:commons-text", CVE, "pom.xml"), null);
        sub.setVulnId(CVE);
        sub.setToolSeverity("8.0"); // CVSS base 80, well below the clamp so +5 is visible
        scorer.score(sub, "normal");
        sub = subIssues.save(sub);
        UUID subId = sub.getId();
        String fingerprintBefore = sub.getFingerprint();
        int scoreBefore = sub.getScore();
        assertThat(scoreBefore).isEqualTo(80);
        long eventsBefore = events.count();

        service.patch(repo.getId(), "high", null, null);

        SubIssue after = subIssues.findById(subId).orElseThrow();
        assertThat(after.getScore()).isEqualTo(scoreBefore + 5);         // sensitivity high +5
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);   // status unchanged
        assertThat(after.getFingerprint()).isEqualTo(fingerprintBefore); // fingerprint unchanged
        assertThat(after.getScoreBreakdown()).anyMatch(l -> l.code().equals("sensitivity_high"));
        assertThat(events.count()).isEqualTo(eventsBefore);              // 0 triage_event
    }

    @Test
    void settingTheSameSensitivityDoesNotRescore() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc2", 9202L, "ct", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 9202L, "svc/same", "main",
                List.of("Java"))); // default sensitivity = normal
        // no-op change to the same value → still 0 triage_event, scores untouched
        long eventsBefore = events.count();
        service.patch(repo.getId(), "normal", null, null);
        assertThat(events.count()).isEqualTo(eventsBefore);
    }
}
