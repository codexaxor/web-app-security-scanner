package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.api.FeedSummaryView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** OD-14 item 4 + change 3: band counts (= SQL), last-7-day New/Solved/Auto-ignored honouring type+repo only,
 *  with the 7-day boundary. */
class FeedSummaryIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    IssueService service;
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
    @Autowired
    JdbcTemplate jdbc;

    private Repository seedRepo(long id) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), id, "svc/" + id, "main", List.of("Java")));
    }

    private UUID openGroup(UUID repoId, String key, int score) {
        IssueGroup g = new IssueGroup(WORKSPACE, repoId, "sca", "sca|" + key, key, null);
        g.setSeverityScore(score);
        return groups.save(g).getId();
    }

    private UUID sub(UUID groupId, UUID repoId, String fp) {
        return subIssues.save(new SubIssue(WORKSPACE, groupId, repoId, "sca", "sca", fp, null)).getId();
    }

    private void backdateSubCreatedAt(UUID subId, int days) {
        jdbc.update("update sub_issue set created_at = now() - (? || ' days')::interval where id = ?",
                String.valueOf(days), subId);
    }

    private void event(UUID subId, String toStatus, String actor, int daysAgo) {
        // Backdate the finding itself out of the 7-day New window so these solved/ignored fixtures do not also
        // count as New — New is isolated to the explicit fp-new-* subs.
        backdateSubCreatedAt(subId, 8);
        var e = "system".equals(actor)
                ? TriageEvent.system(WORKSPACE, subId, "open", toStatus, "LOW_EPSS", null)
                : TriageEvent.user(WORKSPACE, subId, "open", toStatus, null);
        UUID id = events.save(e).getId();
        jdbc.update("update triage_event set at = now() - (? || ' days')::interval where id = ?",
                String.valueOf(daysAgo), id);
    }

    @Test
    void bandCountsEqualSqlAndSevenDayBucketsHonourTheWindow() {
        Repository repo = seedRepo(8201L);
        UUID gCrit = openGroup(repo.getId(), "crit", 95);
        openGroup(repo.getId(), "high", 75);

        // New: 2 within 7 days, 1 older (8 days) → counts 2.
        sub(gCrit, repo.getId(), "fp-new-1");
        sub(gCrit, repo.getId(), "fp-new-2");
        UUID old = sub(gCrit, repo.getId(), "fp-old");
        backdateSubCreatedAt(old, 8);

        // Solved: 1 within, 1 older.
        event(sub(gCrit, repo.getId(), "fp-s1"), "solved", "system", 1);
        event(sub(gCrit, repo.getId(), "fp-s2"), "solved", "system", 8);
        // Auto-ignored (actor=system): 1 within, 1 older; plus a USER ignore that must NOT count as auto-ignored.
        event(sub(gCrit, repo.getId(), "fp-a1"), "ignored", "system", 1);
        event(sub(gCrit, repo.getId(), "fp-a2"), "ignored", "system", 8);
        event(sub(gCrit, repo.getId(), "fp-u1"), "ignored", "user", 1);

        FeedSummaryView s = service.feedSummary(null, null, "focus", false);
        assertThat(s.bands().critical()).isEqualTo(1);
        assertThat(s.bands().high()).isEqualTo(1);
        assertThat(s.newFindings()).isEqualTo(2); // only fp-new-1, fp-new-2 (the others are backdated out)
        assertThat(s.solved()).isEqualTo(1);
        assertThat(s.autoIgnored()).isEqualTo(1); // only the system ignore within 7d, not the user ignore

        // band counts = direct SQL (the oracle the UI is checked against).
        Long sqlCrit = jdbc.queryForObject(
                "select count(*) from issue_group where workspace_id = ? and status = 'open' "
                        + "and severity_score >= 90", Long.class, WORKSPACE);
        assertThat(s.bands().critical()).isEqualTo(sqlCrit);

        // The 7-day buckets honour type+repo only, not the view: focus and all agree.
        assertThat(service.feedSummary(null, null, "all", false).newFindings()).isEqualTo(s.newFindings());
    }

    @Test
    void branchChangedClosesAreExcludedFromSolvedSevenDay() {   // D-076
        Repository repo = seedRepo(8202L);
        UUID g = openGroup(repo.getId(), "crit", 95);
        event(sub(g, repo.getId(), "fp-genuine"), "solved", "system", 1);   // a genuine solve → counts
        branchChangedSolve(sub(g, repo.getId(), "fp-branch"), 1);           // a branch-switch close → excluded

        FeedSummaryView s = service.feedSummary(null, null, "focus", false);
        assertThat(s.solved()).isEqualTo(1);                               // only the genuine solve
    }

    private void branchChangedSolve(UUID subId, int daysAgo) {
        backdateSubCreatedAt(subId, 8);
        UUID id = events.save(TriageEvent.system(WORKSPACE, subId, "open", "solved",
                TriageEvent.REASON_BRANCH_CHANGED, null)).getId();
        jdbc.update("update triage_event set at = now() - (? || ' days')::interval where id = ?",
                String.valueOf(daysAgo), id);
    }
}
