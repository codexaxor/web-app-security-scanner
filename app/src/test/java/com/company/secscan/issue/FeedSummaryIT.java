package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.api.FeedSummaryView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
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
    ScanRepository scans;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    JdbcTemplate jdbc;

    private Repository seedRepo(long id) {
        return seedRepoIn(WORKSPACE, id);
    }

    /** A repository tracking "main" in the given workspace (S-3: lets a test seed two workspaces). */
    private Repository seedRepoIn(UUID ws, long id) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                ws, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(ws, conn.getId(), id, "svc/" + id, "main", List.of("Java")));
    }

    private UUID openGroupIn(UUID ws, UUID repoId, String key, int score) {
        IssueGroup g = new IssueGroup(ws, repoId, "sca", "sca|" + key, key, null);
        g.setSeverityScore(score);
        return groups.save(g).getId();
    }

    private UUID scanOnIn(UUID ws, UUID repoId, String branch) {
        Scan s = Scan.manual(ws, repoId, branch);
        s.finish(Scan.STATUS_COMPLETED);
        return scans.save(s).getId();
    }

    private UUID subFirstSeenIn(UUID ws, UUID groupId, UUID repoId, String fp, UUID firstSeenScanId) {
        return subIssues.save(new SubIssue(ws, groupId, repoId, "sca", "sca", fp, firstSeenScanId)).getId();
    }

    private UUID openGroup(UUID repoId, String key, int score) {
        IssueGroup g = new IssueGroup(WORKSPACE, repoId, "sca", "sca|" + key, key, null);
        g.setSeverityScore(score);
        return groups.save(g).getId();
    }

    /** A completed scan on {@code branch} for the repo — its id is used as a sub-issue's first-seen scan. */
    private UUID scanOn(UUID repoId, String branch) {
        Scan s = Scan.manual(WORKSPACE, repoId, branch);
        s.finish(Scan.STATUS_COMPLETED);
        return scans.save(s).getId();
    }

    /** A sub first seen on the repo's tracked branch "main" (so the branch-scoped New·7d query counts it, D-083). */
    private UUID sub(UUID groupId, UUID repoId, String fp) {
        return subFirstSeenOn(groupId, repoId, fp, scanOn(repoId, "main"));
    }

    private UUID subFirstSeenOn(UUID groupId, UUID repoId, String fp, UUID firstSeenScanId) {
        return subIssues.save(new SubIssue(WORKSPACE, groupId, repoId, "sca", "sca", fp, firstSeenScanId)).getId();
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
        event(sub(g, repo.getId(), "fp-genuine"), "solved", "system", 1);   // a genuine solve (reason set) → counts
        solvedWithReason(sub(g, repo.getId(), "fp-nullreason"), null, 1);    // a solve with NULL reason → counts
        solvedWithReason(sub(g, repo.getId(), "fp-branch"), TriageEvent.REASON_BRANCH_CHANGED, 1); // excluded

        FeedSummaryView s = service.feedSummary(null, null, "focus", false);
        assertThat(s.solved()).isEqualTo(2);   // genuine + null-reason; the branch-change close is excluded
    }

    @Test
    void newCountsOnlyFindingsFirstSeenOnTrackedBranch() {   // D-083
        Repository repo = seedRepo(8203L);
        repo.setScanBranch("dev");                 // the repo now tracks "dev" (effective branch)
        repositories.save(repo);
        UUID g = openGroup(repo.getId(), "crit", 95);

        subFirstSeenOn(g, repo.getId(), "fp-on-dev", scanOn(repo.getId(), "dev"));    // counts (tracked branch)
        subFirstSeenOn(g, repo.getId(), "fp-on-main", scanOn(repo.getId(), "main"));  // excluded (old branch)

        assertThat(service.feedSummary(null, null, "focus", false).newFindings()).isEqualTo(1);

        // SQL oracle: distinct subs created in 7d whose first-seen scan branch = the repo's effective branch.
        Long sql = jdbc.queryForObject(
                "select count(distinct s.id) from sub_issue s join scan fs on fs.id = s.first_seen_scan_id "
                        + "join repository r on r.id = s.repository_id "
                        + "where s.workspace_id = ? and s.created_at >= now() - interval '7 days' "
                        + "and fs.branch = coalesce(r.scan_branch, r.default_branch)",
                Long.class, WORKSPACE);
        assertThat(sql).isEqualTo(1);
    }

    @Test
    void newCountsOnlyTheCallersWorkspace() {   // S-3: workspace isolation of the New·7d query (countNewSince)
        // Workspace A (the default) and workspace B each have a repository tracking a same-named branch "main"
        // with one finding first seen on that branch inside the 7-day window. A same-named branch in B must not
        // leak into A's count (and vice versa): the query filters sub_issue, repository and scan all by :ws.
        UUID wsA = WORKSPACE;
        UUID wsB = workspaces.save(new Workspace("ws-b")).getId();

        Repository repoA = seedRepoIn(wsA, 8301L);
        Repository repoB = seedRepoIn(wsB, 8302L);
        UUID gA = openGroupIn(wsA, repoA.getId(), "a", 95);
        UUID gB = openGroupIn(wsB, repoB.getId(), "b", 95);
        subFirstSeenIn(wsA, gA, repoA.getId(), "fp-a", scanOnIn(wsA, repoA.getId(), "main"));
        subFirstSeenIn(wsB, gB, repoB.getId(), "fp-b", scanOnIn(wsB, repoB.getId(), "main"));

        Instant since = Instant.now().minus(Duration.ofDays(7));
        // Each workspace sees exactly its own one finding — B's same-branch finding never leaks into A's count.
        assertThat(subIssues.countNewSince(wsA, null, null, false, since)).isEqualTo(1);
        assertThat(subIssues.countNewSince(wsB, null, null, false, since)).isEqualTo(1);
        // The repository filter is workspace-scoped too: A's repo id under B's workspace matches nothing.
        assertThat(subIssues.countNewSince(wsA, repoA.getId(), null, false, since)).isEqualTo(1);
        assertThat(subIssues.countNewSince(wsB, repoA.getId(), null, false, since)).isZero();
    }

    private void solvedWithReason(UUID subId, String reason, int daysAgo) {
        backdateSubCreatedAt(subId, 8);
        UUID id = events.save(TriageEvent.system(WORKSPACE, subId, "open", "solved", reason, null)).getId();
        jdbc.update("update triage_event set at = now() - (? || ' days')::interval where id = ?",
                String.valueOf(daysAgo), id);
    }
}
