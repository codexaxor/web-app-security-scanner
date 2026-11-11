package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.reconcile.Fingerprints;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import com.company.secscan.triage.ReasonCode;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * M8.10 review B-1 (D-094): the operator's retire pass ({@code POST /api/v1/admin/maintenance/retire-ignored-orphans})
 * acts ONLY on the caller's own workspace. With an orphan in Default (the operator's) and one in tenant B, the dry
 * run and the real run as the operator name only the Default orphan — the response carries none of B's sub-issue
 * id, repository id, rule/package or path — and B's row and triage history are unchanged. (Replaces the earlier
 * all-workspaces test, whose cross-tenant behaviour D-094 forbids.)
 */
class IgnoredOrphanRetirerWorkspaceScopeIT extends AbstractApiServerIT {

    static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired SubIssueRepository subIssues;
    @Autowired IssueGroupRepository groups;
    @Autowired TriageEventRepository events;
    @Autowired RepositoryRepository repositories;
    @Autowired GitlabConnectionRepository connections;
    @Autowired IgnoreRuleRepository ignoreRules;
    @Autowired WorkspaceRepository workspaces;

    private final List<UUID> seededRepos = new ArrayList<>();

    record Seeded(UUID sub, UUID repo) {
    }

    @BeforeEach
    void operator() {
        AppUser operator = new AppUser(Workspace.DEFAULT_ID, "operator@test.local", encoder.encode(PASSWORD), "admin");
        operator.promoteToOperator();
        users.save(operator);
    }

    @AfterEach
    void removeSeededRows() {
        for (UUID repo : seededRepos) {
            for (String t : List.of("triage_event", "sub_issue", "ignore_rule", "issue_group", "scan_task", "scan",
                    "repository_event")) {
                String where = switch (t) {
                    case "triage_event" -> "sub_issue_id in (select id from sub_issue where repository_id = ?)";
                    case "scan_task" -> "scan_id in (select id from scan where repository_id = ?)";
                    default -> "repository_id = ?";
                };
                jdbc.update("delete from " + t + " where " + where, repo);
            }
        }
        seededRepos.clear();
    }

    @Test
    void dryRunAndRealRunAsTheOperatorTouchOnlyTheOperatorsOwnWorkspace() {
        UUID b = workspaces.save(new Workspace("Tenant B")).getId();
        Seeded mine = seedOrphan(Workspace.DEFAULT_ID, 7101L, "CVE-2099-7101", "default-app/pom.xml");
        Seeded theirs = seedOrphan(b, 7102L, "CVE-2099-7102", "tenant-b-secret-path/pom.xml");
        List<String> bMarkers = List.of(theirs.sub().toString(), theirs.repo().toString(), "CVE-2099-7102",
                "pkg-CVE-2099-7102", "tenant-b-secret-path");
        HttpSession op = HttpSession.browser(rest, port, "operator@test.local", PASSWORD);

        ResponseEntity<String> dry = op.post("/api/v1/admin/maintenance/retire-ignored-orphans?dryRun=true", "{}");
        assertThat(dry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dry.getBody()).contains(mine.sub().toString()).contains("\"candidates\":1");
        bMarkers.forEach(m -> assertThat(dry.getBody()).as("dry run leaks %s", m).doesNotContain(m));

        ResponseEntity<String> run = op.post("/api/v1/admin/maintenance/retire-ignored-orphans?dryRun=false", "{}");
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(run.getBody()).contains("\"retired\":1");
        bMarkers.forEach(m -> assertThat(run.getBody()).as("real run leaks %s", m).doesNotContain(m));

        // Default's orphan is solved inside Default; B's row and its history are untouched.
        SubIssue solved = subIssues.findById(mine.sub()).orElseThrow();
        assertThat(solved.getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(Workspace.DEFAULT_ID, mine.sub()))
                .extracting(TriageEvent::getReasonCode).contains("SOLVED_NOT_DETECTED");
        SubIssue untouched = subIssues.findById(theirs.sub()).orElseThrow();
        assertThat(untouched.getWorkspaceId()).isEqualTo(b);
        assertThat(untouched.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(untouched.getIgnoredByRuleId()).isNotNull();
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(b, theirs.sub())).isEmpty();
    }

    /** An ignored SCA row held by a CVE rule, absent from the repo's latest authoritative reconciled scan. */
    private Seeded seedOrphan(UUID ws, long projectId, String cve, String path) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                ws, "file:///local", "svc-" + projectId, projectId, "ct", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(ws, conn.getId(), projectId, "svc/" + projectId, "main",
                List.of("Java")));
        seededRepos.add(repo.getId());
        Scan scan = Scan.manual(ws, repo.getId(), "main");
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan.markReconciled();
        scan = scans.save(scan);
        ScanTask task = new ScanTask(ws, scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, 0, 100L, null);
        task.setClosesFindings(true);
        tasks.save(task);

        String key = Fingerprints.groupKey("sca", "Maven/pkg-" + cve);
        IssueGroup g = groups.save(new IssueGroup(ws, repo.getId(), "sca", key, "pkg-" + cve, null));
        IgnoreRule rule = ignoreRules.save(new IgnoreRule(ws, IgnoreRule.SCOPE_CVE, repo.getId(), cve, "accepted",
                "user"));
        SubIssue sub = new SubIssue(ws, g.getId(), repo.getId(), "sca", ScanTask.SCANNER_SCA,
                Fingerprints.sca("pkg-" + cve, cve, path), null);
        sub.setVulnId(cve);
        sub.setPackageName("pkg-" + cve);
        sub.setFilePath(path);
        sub.setCveAliases(List.of(cve));
        sub.ignore(ReasonCode.IGNORE_RULE.code(), rule.getId());
        return new Seeded(subIssues.save(sub).getId(), repo.getId());
    }
}
