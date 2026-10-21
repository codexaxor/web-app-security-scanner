package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.api.SubIssueView;
import com.company.secscan.issue.api.TriageEventView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** OD-14 items 5 (events history) and 6 (sub-issue rule fields). */
class SubIssuePanelIT extends AbstractGitLabIT {

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
    IgnoreRuleRepository ignoreRules;
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

    private IssueGroup group(UUID repoId) {
        return groups.save(new IssueGroup(WORKSPACE, repoId, "sca", "sca|pkg-" + repoId, "pkg", null));
    }

    @Test
    void eventsAreListedNewestFirstAnd404ForAnUnknownSubIssue() {
        UUID repoId = repo(8301L);
        IssueGroup g = group(repoId);
        SubIssue s = subIssues.save(new SubIssue(WORKSPACE, g.getId(), repoId, "sca", "sca", "fp-ev", null));
        events.save(TriageEvent.system(WORKSPACE, s.getId(), null, "open", null, null));
        events.save(TriageEvent.user(WORKSPACE, s.getId(), "open", "ignored", null));

        var page = service.subIssueEvents(s.getId(), 0, 50);
        assertThat(page.content()).extracting(TriageEventView::toStatus).containsExactly("ignored", "open");

        assertThatThrownBy(() -> service.subIssueEvents(UUID.randomUUID(), 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void ruleFieldsArePopulatedForARuleHeldFindingAndNullOtherwise() {
        UUID repoId = repo(8302L);
        IssueGroup g = group(repoId);

        IgnoreRule rule = ignoreRules.save(new IgnoreRule(WORKSPACE, "cve", repoId, "CVE-2022-42889",
                "offline build tool only", "user"));
        SubIssue held = new SubIssue(WORKSPACE, g.getId(), repoId, "sca", "sca", "fp-held", null);
        held.ignore("IGNORE_RULE", rule.getId());
        subIssues.save(held);

        SubIssue auto = new SubIssue(WORKSPACE, g.getId(), repoId, "sca", "sca", "fp-auto", null);
        auto.ignore("LOW_EPSS", null); // auto cause: no holding rule
        subIssues.save(auto);

        subIssues.save(new SubIssue(WORKSPACE, g.getId(), repoId, "sca", "sca", "fp-open", null)); // open

        List<SubIssueView> views = service.listSubIssues(g.getId(), 0, 50).content();
        SubIssueView heldView = views.stream().filter(v -> "fp-held".equals(v.ruleReason()) || v.ignoreRuleId() != null)
                .findFirst().orElseThrow();
        assertThat(heldView.ignoreRuleId()).isEqualTo(rule.getId());
        assertThat(heldView.ruleScope()).isEqualTo("cve");
        assertThat(heldView.ruleReason()).isEqualTo("offline build tool only");
        assertThat(heldView.ruleCreatedBy()).isEqualTo("user");

        // The auto-ignored and open findings carry no rule fields.
        assertThat(views).filteredOn(v -> v.ignoreReason() != null && v.ignoreRuleId() == null)
                .allSatisfy(v -> {
                    assertThat(v.ruleScope()).isNull();
                    assertThat(v.ruleReason()).isNull();
                });
    }
}
