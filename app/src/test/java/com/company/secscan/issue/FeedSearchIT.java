package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.api.IssueGroupView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** OD-14 item 3: Focus/All semantics, band filter, sort (severity desc NULLS LAST), repositoryPath,
 *  includeInactive and workspace isolation. */
class FeedSearchIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    IssueService service;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    SubIssueRepository subIssues;

    private Repository seedRepo(UUID ws, long id, boolean active) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                ws, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        Repository r = new Repository(ws, conn.getId(), id, "svc/" + id, "main", List.of("Java"));
        if (!active) {
            r.deactivate("DEACTIVATED_BY_USER");
        }
        return repositories.save(r);
    }

    private IssueGroup group(UUID ws, UUID repoId, String key, Integer score, String status) {
        IssueGroup g = new IssueGroup(ws, repoId, "sca", "sca|" + key, key, null);
        g.setSeverityScore(score);
        g.setStatus(status);
        return groups.save(g);
    }

    @Test
    void focusShowsOpenOnlyAllAddsIgnoredSolvedNeverShown() {
        Repository repo = seedRepo(WORKSPACE, 8101L, true);
        group(WORKSPACE, repo.getId(), "open1", 95, IssueGroup.STATUS_OPEN);
        group(WORKSPACE, repo.getId(), "solved1", 80, IssueGroup.STATUS_SOLVED);
        group(WORKSPACE, repo.getId(), "ignored1", null, IssueGroup.STATUS_IGNORED); // all-ignored → null score

        var focus = service.listGroups(null, null, null, "focus", null, false, 0, 50);
        assertThat(focus.content()).extracting(IssueGroupView::title).containsExactly("open1");

        var all = service.listGroups(null, null, null, "all", null, false, 0, 50);
        assertThat(all.content()).extracting(IssueGroupView::title).containsExactlyInAnyOrder("open1", "ignored1");
        // The all-ignored group has a null score (shown "Ignored", no band).
        assertThat(all.content()).filteredOn(g -> g.title().equals("ignored1"))
                .singleElement().satisfies(g -> assertThat(g.severityScore()).isNull());
    }

    @Test
    void feedRowCarriesSubIssueCountsBrokenDownByStatus() {
        Repository repo = seedRepo(WORKSPACE, 8109L, true);
        IssueGroup mixed = group(WORKSPACE, repo.getId(), "mixed", 90, IssueGroup.STATUS_OPEN);
        group(WORKSPACE, repo.getId(), "empty", 50, IssueGroup.STATUS_OPEN); // no sub-issues
        subIssues.save(new SubIssue(WORKSPACE, mixed.getId(), repo.getId(), "sca", "sca", "fp-o1", null));
        subIssues.save(new SubIssue(WORKSPACE, mixed.getId(), repo.getId(), "sca", "sca", "fp-o2", null));
        SubIssue ign = new SubIssue(WORKSPACE, mixed.getId(), repo.getId(), "sca", "sca", "fp-ig", null);
        ign.ignore("DEV_DEPENDENCY", null);
        subIssues.save(ign);
        SubIssue sol = new SubIssue(WORKSPACE, mixed.getId(), repo.getId(), "sca", "sca", "fp-sv", null);
        sol.solve();
        subIssues.save(sol);

        var page = service.listGroups(repo.getId(), null, null, "all", null, false, 0, 50);
        assertThat(page.content()).filteredOn(v -> v.title().equals("mixed")).singleElement().satisfies(v -> {
            assertThat(v.openCount()).isEqualTo(2);
            assertThat(v.ignoredCount()).isEqualTo(1);
            assertThat(v.solvedCount()).isEqualTo(1);
        });
        assertThat(page.content()).filteredOn(v -> v.title().equals("empty")).singleElement().satisfies(v -> {
            assertThat(v.openCount()).isEqualTo(0);
            assertThat(v.ignoredCount()).isEqualTo(0);
            assertThat(v.solvedCount()).isEqualTo(0);
        });
    }

    @Test
    void bandFilterExcludesNullScoreIgnoredGroups() {
        Repository repo = seedRepo(WORKSPACE, 8102L, true);
        group(WORKSPACE, repo.getId(), "crit", 95, IssueGroup.STATUS_OPEN);
        group(WORKSPACE, repo.getId(), "high", 75, IssueGroup.STATUS_OPEN);
        group(WORKSPACE, repo.getId(), "ignored", null, IssueGroup.STATUS_IGNORED);

        assertThat(service.listGroups(null, null, null, "all", "critical", false, 0, 50).content())
                .extracting(IssueGroupView::title).containsExactly("crit");
        // No band filter returns the null-score ignored group too (in All view).
        assertThat(service.listGroups(null, null, null, "all", null, false, 0, 50).totalElements()).isEqualTo(3);
    }

    @Test
    void sortsBySeverityDescNullsLast() {
        Repository repo = seedRepo(WORKSPACE, 8103L, true);
        group(WORKSPACE, repo.getId(), "mid", 50, IssueGroup.STATUS_OPEN);
        group(WORKSPACE, repo.getId(), "top", 95, IssueGroup.STATUS_OPEN);
        group(WORKSPACE, repo.getId(), "ign", null, IssueGroup.STATUS_IGNORED);

        var all = service.listGroups(null, null, null, "all", null, false, 0, 50);
        assertThat(all.content()).extracting(IssueGroupView::title).containsExactly("top", "mid", "ign");
    }

    @Test
    void repositoryPathIsPopulatedAndInactiveExcludedByDefault() {
        Repository active = seedRepo(WORKSPACE, 8104L, true);
        Repository inactive = seedRepo(WORKSPACE, 8105L, false);
        group(WORKSPACE, active.getId(), "a", 90, IssueGroup.STATUS_OPEN);
        group(WORKSPACE, inactive.getId(), "b", 90, IssueGroup.STATUS_OPEN);

        var def = service.listGroups(null, null, null, "focus", null, false, 0, 50);
        assertThat(def.content()).extracting(IssueGroupView::title).containsExactly("a");
        assertThat(def.content().get(0).repositoryPath()).isEqualTo("svc/8104");

        var incl = service.listGroups(null, null, null, "focus", null, true, 0, 50);
        assertThat(incl.content()).extracting(IssueGroupView::title).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void neverShowsAnotherWorkspacesGroups() {
        Repository repo = seedRepo(WORKSPACE, 8106L, true);
        group(WORKSPACE, repo.getId(), "mine", 90, IssueGroup.STATUS_OPEN);
        Workspace other = workspaces.save(new Workspace("other"));
        Repository otherRepo = seedRepo(other.getId(), 8199L, true);
        group(other.getId(), otherRepo.getId(), "theirs", 99, IssueGroup.STATUS_OPEN);

        var focus = service.listGroups(null, null, null, "focus", null, true, 0, 50);
        assertThat(focus.content()).extracting(IssueGroupView::title).containsExactly("mine");
    }
}
