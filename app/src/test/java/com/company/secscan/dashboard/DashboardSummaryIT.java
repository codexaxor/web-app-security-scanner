package com.company.secscan.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** OD-14 item 7: the dashboard numbers equal direct SQL, exclude inactive repos from totals (surfacing them on
 *  the inactive line), flag unhealthy scans, and never count another workspace. */
class DashboardSummaryIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    DashboardService service;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    ScanRepository scans;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    JdbcTemplate jdbc;

    private Repository seedRepo(UUID ws, long id, boolean active) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                ws, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        Repository r = new Repository(ws, conn.getId(), id, "svc/" + id, "main", List.of("Java"));
        if (!active) {
            r.deactivate("DEACTIVATED_BY_USER");
        }
        return repositories.save(r);
    }

    private UUID openGroup(UUID ws, UUID repoId, String key, int score) {
        IssueGroup g = new IssueGroup(ws, repoId, "sca", "sca|" + key, key, null);
        g.setSeverityScore(score);
        return groups.save(g).getId();
    }

    private void openSub(UUID ws, UUID groupId, UUID repoId, String type, String fp, int score) {
        SubIssue s = new SubIssue(ws, groupId, repoId, type, type, fp, null);
        s.setScore(score);
        subIssues.save(s);
    }

    @Test
    void dashboardNumbersEqualSqlExcludeInactiveAndIgnoreOtherWorkspaces() {
        Repository active = seedRepo(WORKSPACE, 8401L, true);
        UUID gCrit = openGroup(WORKSPACE, active.getId(), "crit", 95);
        openGroup(WORKSPACE, active.getId(), "high", 75);
        openSub(WORKSPACE, gCrit, active.getId(), "sca", "fp-sca", 95);
        openSub(WORKSPACE, gCrit, active.getId(), "sast", "fp-sast", 72);

        // a partial scan → scan health flags this repo
        Scan scan = Scan.manual(WORKSPACE, active.getId());
        scan.markRunning();
        scan.finish(Scan.STATUS_PARTIAL);
        scans.save(scan);

        // inactive repo with an open finding → excluded from totals, shown on the inactive line
        Repository inactive = seedRepo(WORKSPACE, 8402L, false);
        UUID gIn = openGroup(WORKSPACE, inactive.getId(), "inact", 99);
        openSub(WORKSPACE, gIn, inactive.getId(), "sca", "fp-inact", 99);

        // another workspace — never counted
        Workspace other = workspaces.save(new Workspace("other"));
        Repository otherRepo = seedRepo(other.getId(), 8499L, true);
        UUID gOther = openGroup(other.getId(), otherRepo.getId(), "other", 99);
        openSub(other.getId(), gOther, otherRepo.getId(), "sca", "fp-other", 99);

        DashboardSummaryView d = service.summary(false);

        // Group bands: only the active repo's 2 open groups (inactive + other excluded).
        assertThat(d.groupBands().critical()).isEqualTo(1);
        assertThat(d.groupBands().high()).isEqualTo(1);
        assertThat(d.totalGroups()).isEqualTo(2);
        // Finding bands: 2 open subs in the active repo (crit + high).
        assertThat(d.findingBands().critical()).isEqualTo(1);
        assertThat(d.findingBands().high()).isEqualTo(1);
        assertThat(d.totalFindings()).isEqualTo(2);
        // Type breakdown.
        assertThat(d.byType()).extracting(DashboardSummaryView.TypeCount::type)
                .containsExactlyInAnyOrder("sca", "sast");
        // Scan health: the partial-scan repo is flagged.
        assertThat(d.scanHealth().unhealthyRepoCount()).isEqualTo(1);
        assertThat(d.scanHealth().repos()).singleElement()
                .satisfies(r -> assertThat(r.lastScanStatus()).isEqualTo(Scan.STATUS_PARTIAL));
        // Inactive line.
        assertThat(d.inactive().inactiveRepoCount()).isEqualTo(1);
        assertThat(d.inactive().openFindingsInInactiveRepos()).isEqualTo(1);

        // = direct SQL for the finding critical band, workspace-scoped and active-only.
        Long sqlCrit = jdbc.queryForObject(
                "select count(*) from sub_issue s join repository r on r.id = s.repository_id "
                        + "where s.workspace_id = ? and s.status = 'open' and r.active = true and s.score >= 90",
                Long.class, WORKSPACE);
        assertThat(d.findingBands().critical()).isEqualTo(sqlCrit);
    }
}
