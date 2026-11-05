package com.company.secscan.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IgnoreRule;
import com.company.secscan.issue.IgnoreRuleRepository;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.issue.TriageEvent;
import com.company.secscan.issue.TriageEventRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanWarning;
import com.company.secscan.scan.ScanWarningRepository;
import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * M8.10 item 7 (D-093): tenant isolation over real HTTP. Workspace A owns a connection, repository, scan (+ warning),
 * issue group, sub-issue, triage event and ignore rule. User B (workspace B), signed in by session, gets <b>404 —
 * never 403</b> — on EVERY endpoint that takes one of A's ids (reads AND mutations), sees none of A's data on every
 * list endpoint, and A's data is unchanged afterwards. User A gets through on the same reads (so the 404s are
 * isolation, not broken endpoints). {@link #CASES} is the authoritative per-endpoint list:
 * {@code TenantIsolationCoverageIT} fails the build if a {@code /api/v1} mapping is neither covered here nor
 * explicitly {@link #EXEMPT}.
 */
class TenantIsolationIT extends AbstractApiServerIT {

    static final String PASSWORD = "correct-horse-battery";

    /** How a case is judged. */
    enum Kind { BY_ID, BY_ID_MUTATION, LIST }

    /** One endpoint: its mapping key ("METHOD pattern"), the concrete request for B, and how to judge it. */
    record Case(String mapping, Kind kind, Function<Ids, String> path, Function<Ids, String> body) {
        HttpMethod method() {
            return HttpMethod.valueOf(mapping.substring(0, mapping.indexOf(' ')));
        }
    }

    /** A's ids. */
    record Ids(UUID conn, UUID repo, UUID scan, UUID group, UUID sub, UUID rule) {
    }

    static final List<Case> CASES = List.of(
            // repositories
            new Case("GET /api/v1/repositories/{id}", Kind.BY_ID, i -> "/api/v1/repositories/" + i.repo(), null),
            new Case("GET /api/v1/repositories/{id}/branches", Kind.BY_ID_MUTATION,   // provider call for A: skip A
                    i -> "/api/v1/repositories/" + i.repo() + "/branches", null),
            new Case("PATCH /api/v1/repositories/{id}", Kind.BY_ID_MUTATION, i -> "/api/v1/repositories/" + i.repo(),
                    i -> "{\"sensitivity\":\"high\",\"active\":false}"),
            new Case("POST /api/v1/repositories", Kind.BY_ID_MUTATION, i -> "/api/v1/repositories",
                    i -> "{\"connectionId\":\"" + i.conn() + "\",\"gitlabProjectIds\":[301]}"),
            new Case("GET /api/v1/repositories", Kind.LIST, i -> "/api/v1/repositories?includeInactive=true", null),
            // scans
            new Case("POST /api/v1/repositories/{repositoryId}/scans", Kind.BY_ID_MUTATION,
                    i -> "/api/v1/repositories/" + i.repo() + "/scans", null),
            new Case("GET /api/v1/repositories/{repositoryId}/scans", Kind.BY_ID,
                    i -> "/api/v1/repositories/" + i.repo() + "/scans", null),
            new Case("GET /api/v1/scans/{scanId}", Kind.BY_ID, i -> "/api/v1/scans/" + i.scan(), null),
            new Case("GET /api/v1/scans/{scanId}/warnings", Kind.BY_ID, i -> "/api/v1/scans/" + i.scan() + "/warnings",
                    null),
            new Case("GET /api/v1/scans", Kind.LIST, i -> "/api/v1/scans", null),
            // issues
            new Case("GET /api/v1/issue-groups/{groupId}", Kind.BY_ID, i -> "/api/v1/issue-groups/" + i.group(), null),
            new Case("GET /api/v1/issue-groups/{groupId}/sub-issues", Kind.BY_ID,
                    i -> "/api/v1/issue-groups/" + i.group() + "/sub-issues", null),
            new Case("GET /api/v1/sub-issues/{subIssueId}/events", Kind.BY_ID,
                    i -> "/api/v1/sub-issues/" + i.sub() + "/events", null),
            new Case("GET /api/v1/issue-groups", Kind.LIST,
                    i -> "/api/v1/issue-groups?view=all&includeInactive=true", null),
            new Case("GET /api/v1/issue-groups/categories", Kind.LIST,
                    i -> "/api/v1/issue-groups/categories?view=all&includeInactive=true", null),
            new Case("GET /api/v1/issue-groups/summary", Kind.LIST,
                    i -> "/api/v1/issue-groups/summary?view=all&includeInactive=true", null),
            // triage
            new Case("POST /api/v1/sub-issues/{subIssueId}/ignore", Kind.BY_ID_MUTATION,
                    i -> "/api/v1/sub-issues/" + i.sub() + "/ignore", i -> "{\"reason\":\"steal\"}"),
            new Case("POST /api/v1/issue-groups/{groupId}/ignore", Kind.BY_ID_MUTATION,
                    i -> "/api/v1/issue-groups/" + i.group() + "/ignore", i -> "{\"reason\":\"steal\"}"),
            new Case("DELETE /api/v1/ignore-rules/{ruleId}", Kind.BY_ID_MUTATION,
                    i -> "/api/v1/ignore-rules/" + i.rule(), null),
            new Case("POST /api/v1/ignore-rules", Kind.BY_ID_MUTATION, i -> "/api/v1/ignore-rules",
                    i -> "{\"scope\":\"cve\",\"repositoryId\":\"" + i.repo() + "\",\"matchValue\":\"CVE-2099-1\","
                            + "\"reason\":\"steal\"}"),
            new Case("GET /api/v1/ignore-rules", Kind.LIST, i -> "/api/v1/ignore-rules", null),
            new Case("GET /api/v1/ignored", Kind.LIST, i -> "/api/v1/ignored", null),
            // connections / sources
            new Case("PUT /api/v1/gitlab/connections/{id}", Kind.BY_ID_MUTATION,
                    i -> "/api/v1/gitlab/connections/" + i.conn(),
                    i -> "{\"baseUrl\":\"https://gitlab.example.test\",\"token\":\"glpat-EXAMPLE\",\"path\":\"a/app\"}"),
            new Case("DELETE /api/v1/gitlab/connections/{id}", Kind.BY_ID_MUTATION,
                    i -> "/api/v1/gitlab/connections/" + i.conn(), null),
            new Case("GET /api/v1/gitlab/connections/{id}/projects", Kind.BY_ID_MUTATION,   // provider call for A
                    i -> "/api/v1/gitlab/connections/" + i.conn() + "/projects", null),
            new Case("GET /api/v1/gitlab/connections", Kind.LIST, i -> "/api/v1/gitlab/connections", null),
            new Case("GET /api/v1/sources/connections/{id}/repositories", Kind.BY_ID_MUTATION,
                    i -> "/api/v1/sources/connections/" + i.conn() + "/repositories", null),
            new Case("POST /api/v1/sources/connections/{id}/select", Kind.BY_ID_MUTATION,
                    i -> "/api/v1/sources/connections/" + i.conn() + "/select", i -> "{\"providerRepoIds\":[\"301\"]}"),
            // dashboard / settings
            new Case("GET /api/v1/dashboard/summary", Kind.LIST, i -> "/api/v1/dashboard/summary?includeInactive=true",
                    null),
            new Case("GET /api/v1/workspace/settings", Kind.LIST, i -> "/api/v1/workspace/settings", null));

    /**
     * Mappings that take NO foreign id and act only in the caller's own workspace — proven elsewhere or by
     * construction (each value says why). {@code /api/v1/auth/**} and {@code /api/v1/admin/**} are excluded by
     * the coverage test as a whole (pre-authentication / operator-only, D-094: {@code OperatorAccessIT}).
     */
    static final Map<String, String> EXEMPT = Map.of(
            "POST /api/v1/gitlab/connections/test", "tests credentials only; reads/writes nothing in any workspace",
            "POST /api/v1/gitlab/connections", "creates a connection in the caller's workspace (@CurrentWorkspace)",
            "POST /api/v1/sources/github/owner", "creates a connection in the caller's workspace (@CurrentWorkspace)",
            "POST /api/v1/sources/github/repo", "creates a connection in the caller's workspace (@CurrentWorkspace)",
            "POST /api/v1/sources/public", "creates a connection in the caller's workspace (@CurrentWorkspace)",
            "PUT /api/v1/workspace/settings", "writes only the caller's workspace — PrincipalWorkspaceResolutionIT");

    static Set<String> coveredMappings() {
        return CASES.stream().map(Case::mapping).collect(Collectors.toSet());
    }

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    AppUserRepository users;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanWarningRepository warnings;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    TriageEventRepository events;
    @Autowired
    IgnoreRuleRepository rules;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JdbcTemplate jdbc;

    UUID a;
    Ids ids;

    @BeforeEach
    void seedTwoTenants() {
        a = workspaces.save(new Workspace("Tenant A")).getId();
        UUID b = workspaces.save(new Workspace("Tenant B")).getId();
        String hash = encoder.encode(PASSWORD);
        users.save(new AppUser(a, "a@tenant.test", hash, "admin"));
        users.save(new AppUser(b, "b@tenant.test", hash, "admin"));

        UUID conn = connections.save(GitlabConnection.forProject(a, "file:///tmp/tenant-a", "a/app", 301L, "ct",
                "read_api,read_repository", null)).getId();
        UUID repo = repositories.save(new Repository(a, conn, 301L, "a/app-secret-name", "main", List.of("Java")))
                .getId();
        Scan scan = Scan.manual(a, repo, "main");
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        UUID scanId = scans.save(scan).getId();
        warnings.save(new ScanWarning(a, repo, scanId, "MISSING_GRADLE_LOCKFILE", "no lockfile", "build.gradle"));
        UUID group = groups.save(new IssueGroup(a, repo, "secret", "secret|a-rule", "Tenant A secret group", null))
                .getId();
        UUID sub = subIssues.save(new SubIssue(a, group, repo, "secret", "secret", "fp-tenant-a", scanId)).getId();
        events.save(TriageEvent.user(a, sub, "open", "open", null));
        UUID rule = rules.save(new IgnoreRule(a, IgnoreRule.SCOPE_CVE, repo, "CVE-2000-0001", "tenant-a-rule",
                "user")).getId();
        ids = new Ids(conn, repo, scanId, group, sub, rule);
    }

    @AfterEach
    void removeTenantRows() {
        for (String t : List.of("triage_event", "sub_issue", "ignore_rule", "issue_group", "scan_warning",
                "scan_task", "scan_job", "scan", "repository_event")) {
            jdbc.update("delete from " + t + " where workspace_id <> '00000000-0000-0000-0000-000000000001'");
        }
    }

    @Test
    void userBGets404OnEveryEndpointThatTakesAnIdOfA() {
        HttpSession b = HttpSession.browser(rest, port, "b@tenant.test", PASSWORD);
        for (Case c : CASES) {
            if (c.kind() == Kind.LIST) {
                continue;
            }
            ResponseEntity<String> res = b.send(c.method(), c.path().apply(ids),
                    c.body() == null ? "{}" : c.body().apply(ids));
            assertThat(res.getStatusCode()).as("%s as B", c.mapping()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void userAGetsThroughOnTheSameReads() {
        HttpSession owner = HttpSession.browser(rest, port, "a@tenant.test", PASSWORD);
        for (Case c : CASES) {
            if (c.kind() == Kind.BY_ID_MUTATION) {
                continue;   // mutations / provider calls are not replayed as A
            }
            assertThat(owner.get(c.path().apply(ids)).getStatusCode()).as("%s as A", c.mapping())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void userBSeesNoneOfAsDataOnAnyListEndpoint() {
        HttpSession b = HttpSession.browser(rest, port, "b@tenant.test", PASSWORD);
        List<String> markers = List.of(ids.conn().toString(), ids.repo().toString(), ids.scan().toString(),
                ids.group().toString(), ids.sub().toString(), ids.rule().toString(), "a/app-secret-name",
                "Tenant A secret group", "tenant-a-rule");
        for (Case c : CASES) {
            if (c.kind() != Kind.LIST) {
                continue;
            }
            ResponseEntity<String> res = b.get(c.path().apply(ids));
            assertThat(res.getStatusCode()).as("%s as B", c.mapping()).isEqualTo(HttpStatus.OK);
            for (String m : markers) {
                assertThat(res.getBody()).as("%s leaks %s", c.mapping(), m).doesNotContain(m);
            }
        }
    }

    @Test
    void asDataIsUnchangedAfterEveryAttemptByB() {
        HttpSession b = HttpSession.browser(rest, port, "b@tenant.test", PASSWORD);
        for (Case c : CASES) {
            if (c.kind() == Kind.BY_ID_MUTATION) {
                b.send(c.method(), c.path().apply(ids), c.body() == null ? "{}" : c.body().apply(ids));
            }
        }
        Repository repo = repositories.findById(ids.repo()).orElseThrow();
        assertThat(repo.isActive()).isTrue();
        assertThat(repo.getSensitivity()).isNotEqualTo("high");
        assertThat(connections.findById(ids.conn())).isPresent();
        assertThat(rules.findById(ids.rule())).isPresent();
        assertThat(subIssues.findById(ids.sub()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(jdbc.queryForObject("select count(*) from scan where workspace_id = ?", Long.class, a))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from ignore_rule where workspace_id <> ?", Long.class, a))
                .isZero();
    }
}
