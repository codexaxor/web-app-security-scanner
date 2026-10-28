package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** M8.6 (item 3): the Repositories list does search, filters, sort and pagination SERVER-SIDE. */
class RepositoryListIT extends AbstractGitLabIT {

    private static final UUID WS = Workspace.DEFAULT_ID;

    @Autowired
    RepositoryService service;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    ScanRepository scans;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    private Repository repo(long id, String path) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WS, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WS, conn.getId(), id, path, "main", List.of("Java")));
    }

    private void openGroup(UUID repoId, String key, int score) {
        IssueGroup g = new IssueGroup(WS, repoId, "sca", "sca|" + repoId + "|" + key, key, null);
        g.setSeverityScore(score);
        groups.save(g);
    }

    private PagedResponse<RepositoryView> list(String search, boolean inactive, boolean openOnly, boolean failed,
                                               String sort, int page, int size) {
        return service.listSelected(search, inactive, openOnly, failed, null, sort, page, size);
    }

    @Test
    void defaultSortIsBySeverityDescAndExcludesInactive() {
        Repository crit = repo(7101L, "team/alpha-critical");
        openGroup(crit.getId(), "c", 95);
        Repository high = repo(7102L, "team/bravo-high");
        openGroup(high.getId(), "h", 75);
        Repository clean = repo(7103L, "team/charlie-clean");
        Repository inactive = repo(7104L, "team/delta-inactive");
        openGroup(inactive.getId(), "c2", 95);
        inactive.deactivate("test");
        repositories.save(inactive);

        List<UUID> ids = list(null, false, false, false, "severity", 0, 50).content().stream()
                .map(RepositoryView::id).toList();
        assertThat(ids).containsExactly(crit.getId(), high.getId(), clean.getId());
        assertThat(ids).doesNotContain(inactive.getId());
    }

    @Test
    void includeInactiveAddsTheInactiveRepo() {
        Repository active = repo(7201L, "team/active");
        Repository inactive = repo(7202L, "team/inactive");
        inactive.deactivate("test");
        repositories.save(inactive);

        List<UUID> off = list(null, false, false, false, "severity", 0, 50).content().stream()
                .map(RepositoryView::id).toList();
        assertThat(off).contains(active.getId()).doesNotContain(inactive.getId());

        List<UUID> on = list(null, true, false, false, "severity", 0, 50).content().stream()
                .map(RepositoryView::id).toList();
        assertThat(on).contains(active.getId(), inactive.getId());
    }

    @Test
    void searchMatchesPathSubstringCaseInsensitively() {
        Repository a = repo(7301L, "payments/gateway");
        repo(7302L, "identity/login");
        List<UUID> ids = list("GATE", false, false, false, "name", 0, 50).content().stream()
                .map(RepositoryView::id).toList();
        assertThat(ids).containsExactly(a.getId());
    }

    @Test
    void hasOpenIssuesFilterExcludesCleanRepos() {
        Repository withIssues = repo(7401L, "team/withissues");
        openGroup(withIssues.getId(), "x", 55);
        repo(7402L, "team/clean");
        List<UUID> ids = list(null, false, true, false, "name", 0, 50).content().stream()
                .map(RepositoryView::id).toList();
        assertThat(ids).containsExactly(withIssues.getId());
    }

    @Test
    void lastScanFailedFilterMatchesFailedOrPartial() {
        Repository failed = repo(7501L, "team/failed");
        finishScan(failed, Scan.STATUS_FAILED);
        Repository partial = repo(7502L, "team/partial");
        finishScan(partial, Scan.STATUS_PARTIAL);
        Repository ok = repo(7503L, "team/ok");
        finishScan(ok, Scan.STATUS_COMPLETED);

        List<UUID> ids = list(null, false, false, true, "name", 0, 50).content().stream()
                .map(RepositoryView::id).toList();
        assertThat(ids).containsExactlyInAnyOrder(failed.getId(), partial.getId());
    }

    @Test
    void paginationIsServerSide() {
        for (int i = 0; i < 5; i++) {
            Repository r = repo(7600L + i, "team/pg-" + i);
            openGroup(r.getId(), "s", 90 - i); // descending severity so order is deterministic
        }
        PagedResponse<RepositoryView> p0 = list(null, false, false, false, "severity", 0, 2);
        assertThat(p0.content()).hasSize(2);
        assertThat(p0.totalElements()).isEqualTo(5);
        assertThat(p0.totalPages()).isEqualTo(3);
        PagedResponse<RepositoryView> p2 = list(null, false, false, false, "severity", 2, 2);
        assertThat(p2.content()).hasSize(1); // last page has the remainder
    }

    private void finishScan(Repository repo, String status) {
        Scan scan = Scan.manual(WS, repo.getId());
        scan.finish(status);
        scans.save(scan);
    }
}
