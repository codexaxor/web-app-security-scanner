package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SF-1 (backend side of the "Medium 65" fix): when the LAST open sub-issue of a group is ignored through an
 * API-time path, the group's derived state is recomputed correctly — {@code status = ignored} (or {@code solved}
 * if a solved sibling exists) and {@code severity_score IS NULL}. This proves the backend never leaves a stale
 * band on an all-ignored group; the observed stale header was a UI cache bug (SubIssuePanel never invalidated the
 * {@code ["issue-group", id]} query), fixed separately in the UI.
 */
class AllIgnoredGroupRollupIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    TriageService triage;
    @Autowired
    GroupRollupService rollup;
    @Autowired
    com.company.secscan.workspace.WorkspaceSettingsService workspaceSettings;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    private UUID repo(long id) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), id, "svc/" + id, "main",
                List.of("Java"))).getId();
    }

    private SubIssue openSub(UUID groupId, UUID repoId, String fp, int score) {
        SubIssue s = new SubIssue(WORKSPACE, groupId, repoId, "sca", "sca", fp, null);
        s.setScore(score);
        s.setEpss(new BigDecimal("0.00010")); // low EPSS so the toggle sweep auto-ignores it
        s.setInKev(false);
        return subIssues.save(s);
    }

    @Test
    void ignoringTheLastOpenSubIssueViaTriageNullsTheGroupScoreAndMarksItIgnored() {
        UUID repoId = repo(9801L);
        IssueGroup g = groups.save(new IssueGroup(WORKSPACE, repoId, "sca", "sca|pg-9801", "org.postgresql:postgresql",
                null));
        SubIssue medium = openSub(g.getId(), repoId, "fp-65", 65);
        SubIssue high = openSub(g.getId(), repoId, "fp-82", 82);
        // Scan-time rollup: both open → severity = max = 82, status open (mirrors the real walkthrough start).
        rollup.recompute(WORKSPACE, g.getId(), Map.of());
        assertThat(groups.findById(g.getId()).orElseThrow().getSeverityScore()).isEqualTo(82);

        triage.ignoreSubIssue(high.getId(), "accepted risk");   // one still open (65) → severity 65, still open
        assertThat(groups.findById(g.getId()).orElseThrow().getSeverityScore()).isEqualTo(65);
        assertThat(groups.findById(g.getId()).orElseThrow().getStatus()).isEqualTo(IssueGroup.STATUS_OPEN);

        triage.ignoreSubIssue(medium.getId(), "accepted risk");  // last open now ignored → all-ignored

        IssueGroup after = groups.findById(g.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(IssueGroup.STATUS_IGNORED);
        assertThat(after.getSeverityScore()).isNull();
    }

    @Test
    void epssSweepIgnoringTheLastOpenSubIssueNullsTheGroupScore() {
        UUID repoId = repo(9802L);
        IssueGroup g = groups.save(new IssueGroup(WORKSPACE, repoId, "sca", "sca|pg-9802", "pkg", null));
        openSub(g.getId(), repoId, "fp-a", 65);
        openSub(g.getId(), repoId, "fp-b", 82);
        rollup.recompute(WORKSPACE, g.getId(), Map.of());
        assertThat(groups.findById(g.getId()).orElseThrow().getSeverityScore()).isEqualTo(82);

        try {
            workspaceSettings.setEpssAutoIgnore(WORKSPACE, true); // sweep: both low-EPSS → auto-ignored

            IssueGroup after = groups.findById(g.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(IssueGroup.STATUS_IGNORED);
            assertThat(after.getSeverityScore()).isNull();
        } finally {
            workspaceSettings.setEpssAutoIgnore(WORKSPACE, false); // restore default so other ITs are unaffected
        }
    }

    @Test
    void aGroupWithASolvedSiblingBecomesSolvedNotIgnoredWhenAllElseIgnored() {
        UUID repoId = repo(9803L);
        IssueGroup g = groups.save(new IssueGroup(WORKSPACE, repoId, "sca", "sca|pg-9803", "pkg", null));
        SubIssue solved = openSub(g.getId(), repoId, "fp-solved", 50);
        SubIssue open = openSub(g.getId(), repoId, "fp-open", 70);
        solved.solve();
        subIssues.save(solved);

        triage.ignoreSubIssue(open.getId(), "accepted risk"); // no open left; a solved sibling exists

        IssueGroup after = groups.findById(g.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(IssueGroup.STATUS_SOLVED);
        assertThat(after.getSeverityScore()).isNull();
    }
}
