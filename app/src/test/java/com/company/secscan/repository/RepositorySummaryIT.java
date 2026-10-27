package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanWarning;
import com.company.secscan.scan.ScanWarningRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** OD-14 item 1: the Repositories-screen rollups are computed in SQL, equal direct SQL, and never count another
 *  workspace's rows. */
class RepositorySummaryIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    RepositoryService service;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanWarningRepository warnings;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    JdbcTemplate jdbc;

    private Repository seedRepo(UUID ws, long id) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                ws, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(ws, conn.getId(), id, "svc/" + id, "main", List.of("Java")));
    }

    private void seedOpenGroup(UUID ws, UUID repoId, String key, int score) {
        IssueGroup g = new IssueGroup(ws, repoId, "sca", "sca|" + key, key, null);
        g.setSeverityScore(score);
        groups.save(g); // default status open
    }

    private void seedIgnoredSub(UUID ws, UUID repoId, String fp) {
        IssueGroup g = groups.save(new IssueGroup(ws, repoId, "sast", "sast|" + fp, fp, null));
        SubIssue s = new SubIssue(ws, g.getId(), repoId, "sast", "sast", fp, null);
        s.ignore("IGNORE_RULE", null);
        subIssues.save(s);
    }

    @Test
    void rollupsEqualDirectSqlAndExcludeOtherWorkspaces() {
        Repository repo = seedRepo(WORKSPACE, 9501L);
        seedOpenGroup(WORKSPACE, repo.getId(), "crit", 95);
        seedOpenGroup(WORKSPACE, repo.getId(), "high", 75);
        seedOpenGroup(WORKSPACE, repo.getId(), "med", 50);
        seedOpenGroup(WORKSPACE, repo.getId(), "low", 20);
        seedIgnoredSub(WORKSPACE, repo.getId(), "fp-ign-1");
        seedIgnoredSub(WORKSPACE, repo.getId(), "fp-ign-2");
        Scan scan = scans.save(Scan.manual(WORKSPACE, repo.getId()));
        warnings.save(new ScanWarning(WORKSPACE, repo.getId(), scan.getId(), "W1", "warn 1", null));
        warnings.save(new ScanWarning(WORKSPACE, repo.getId(), scan.getId(), "W2", "warn 2", "f"));

        // A SECOND workspace with its own repo + open critical group whose counts must never leak.
        Workspace other = workspaces.save(new Workspace("other"));
        Repository otherRepo = seedRepo(other.getId(), 9599L);
        seedOpenGroup(other.getId(), otherRepo.getId(), "crit", 99);

        // includeInactive=true preserves this rollup test's original "list all" behaviour (M8.6 default is active-only).
        PagedResponse<RepositoryView> page = service.listSelected(null, true, false, false, "severity", 0, 50);
        // The other workspace's repo is never listed.
        assertThat(page.content()).extracting(RepositoryView::id).doesNotContain(otherRepo.getId());

        RepositoryView row = page.content().stream().filter(r -> r.id().equals(repo.getId())).findFirst()
                .orElseThrow();
        assertThat(row.openBySeverity().critical()).isEqualTo(1);
        assertThat(row.openBySeverity().high()).isEqualTo(1);
        assertThat(row.openBySeverity().medium()).isEqualTo(1);
        assertThat(row.openBySeverity().low()).isEqualTo(1);
        assertThat(row.ignoredCount()).isEqualTo(2);
        assertThat(row.lastScan()).isNotNull();
        assertThat(row.lastScan().id()).isEqualTo(scan.getId());
        assertThat(row.warningCount()).isEqualTo(2);

        // = direct SQL, workspace-scoped (the oracle the UI is checked against).
        Long sqlCritical = jdbc.queryForObject(
                "select count(*) from issue_group where workspace_id = ? and repository_id = ? "
                        + "and status = 'open' and severity_score >= 90",
                Long.class, WORKSPACE, repo.getId());
        assertThat(row.openBySeverity().critical()).isEqualTo(sqlCritical);
        Long sqlIgnored = jdbc.queryForObject(
                "select count(*) from sub_issue where workspace_id = ? and repository_id = ? and status = 'ignored'",
                Long.class, WORKSPACE, repo.getId());
        assertThat(row.ignoredCount()).isEqualTo(sqlIgnored);
    }
}
