package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.intel.EpssScore;
import com.company.secscan.intel.EpssScoreRepository;
import com.company.secscan.scan.parse.DeclaredScope;
import com.company.secscan.scan.parse.SecscanConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S5 stability gate: an unchanged rescan writes NO triage_event (0-triage), and toggling include-dev-deps writes
 * only ignore/unignore events for the affected finding — never a solve or reopen (status moves through the
 * triage step, not the scan plan).
 */
class TriageStabilityIT extends TriageSeedSupport {

    @Autowired ReconcileService reconcile;
    @Autowired TriageEventRepository events;
    @Autowired IgnoreRuleRepository ignoreRules;
    @Autowired EpssScoreRepository epssScores;

    private static final String STOCK_CSRF = "opengrep-rules.java.spring.security.audit.spring-csrf-disabled";
    private static final String CUSTOM_CSRF = "opengrep-rules.custom.spring-csrf-disabled";

    @Test
    void aCleanRescanWritesNoTriageEvent() {
        UUID repo = seedRepo();
        UUID scan1 = seedScan(repo);
        seedSca(scan1, repo, "CVE-2022-42889", "org.apache.commons:commons-text", "pom.xml");
        seedSast(scan1, repo, "opengrep-rules.custom.x", "src/A.java", "h1");
        reconcile.reconcile(scan1, WORKSPACE);
        long afterFirst = events.count();

        UUID scan2 = seedScan(repo);
        seedSca(scan2, repo, "CVE-2022-42889", "org.apache.commons:commons-text", "pom.xml");
        seedSast(scan2, repo, "opengrep-rules.custom.x", "src/A.java", "h1");
        reconcile.reconcile(scan2, WORKSPACE);

        assertThat(events.count()).as("a clean rescan writes no new triage_event").isEqualTo(afterFirst);
    }

    @Test
    void aCleanRescanWithEveryTriageSourceWritesNoNewEvent() {
        // Review S8: all triage sources active at once — a .secscan.yml config (exclude path + CVE ignore), a
        // manual rule, dev/test scope, LOW_EPSS on, and a DUPLICATE_RULE pair. An unchanged rescan must write 0
        // new triage_event (the resolver yields the identical desired state, so nothing transitions).
        UUID repo = seedRepo();
        epssScores.save(new EpssScore("CVE-2100-9", new BigDecimal("0.00500"), new BigDecimal("0.1"))); // 0.5% <1%
        // a manual rule-scope ignore (created_by=user) on a custom rule id
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_RULE, repo, "opengrep-rules.custom.manual",
                "team accepted", "user"));
        SecscanConfig cfg = new SecscanConfig(true, List.of("src/legacy"),
                Map.of("CVE-2022-42889", "offline build tool only"), false);
        List<DeclaredScope> scopes = List.of(new DeclaredScope("pom.xml", "junit:junit", "test"));
        TriageContext triage = new TriageContext(cfg, scopes, false, true); // dev-deps off, EPSS on

        UUID scan1 = seedScan(repo);
        seedAllSources(scan1, repo);
        reconcile.reconcile(scan1, WORKSPACE, triage);

        // Sanity: every source fired.
        assertThat(byVuln(repo, "CVE-2022-42889").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);   // secscan.yml CVE
        assertThat(byVuln(repo, "CVE-2020-0001").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);     // TEST_SCOPE
        assertThat(byVuln(repo, "CVE-2100-9").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);        // LOW_EPSS
        assertThat(byFile(repo, "src/legacy/Old.java").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED); // EXCLUDED_PATH
        assertThat(byFile(repo, "src/Mgr.java").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);      // manual rule
        assertThat(byFileAndRule(repo, "src/Sec.java", STOCK_CSRF).getStatus())
                .isEqualTo(SubIssue.STATUS_IGNORED);                                                  // DUPLICATE_RULE
        assertThat(byFileAndRule(repo, "src/Sec.java", CUSTOM_CSRF).getStatus())
                .isEqualTo(SubIssue.STATUS_OPEN);                                                     // winner open

        long afterFirst = events.count();

        UUID scan2 = seedScan(repo);
        seedAllSources(scan2, repo);
        reconcile.reconcile(scan2, WORKSPACE, triage);

        assertThat(events.count()).as("a clean rescan with every triage source active writes no new event")
                .isEqualTo(afterFirst);
    }

    private void seedAllSources(UUID scan, UUID repo) {
        seedSca(scan, repo, "CVE-2022-42889", "org.apache.commons:commons-text", "pom.xml"); // secscan.yml CVE
        seedSca(scan, repo, "CVE-2020-0001", "junit:junit", "pom.xml");                       // TEST_SCOPE
        seedSca(scan, repo, "CVE-2100-9", "org.a:a", "pom.xml");                              // LOW_EPSS
        seedSast(scan, repo, "opengrep-rules.custom.x", "src/legacy/Old.java", "h-legacy");   // EXCLUDED_PATH
        seedSast(scan, repo, "opengrep-rules.custom.manual", "src/Mgr.java", "h-mgr");        // manual rule
        seedSast(scan, repo, STOCK_CSRF, "src/Sec.java", "h-stock");                          // DUPLICATE loser
        seedSast(scan, repo, CUSTOM_CSRF, "src/Sec.java", "h-custom");                        // DUPLICATE winner
    }

    private SubIssue byFileAndRule(UUID repo, String filePath, String ruleId) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> filePath.equals(s.getFilePath()) && ruleId.equals(s.getRuleId()))
                .findFirst().orElseThrow();
    }

    @Test
    void togglingIncludeDevDepsWritesOnlyIgnoreOrUnignoreNeverSolveOrReopen() {
        UUID repo = seedRepo();
        List<DeclaredScope> scopes = List.of(new DeclaredScope("pom.xml", "junit:junit", "test"));

        // Scan 1, dev-deps OFF -> the test-scoped dep is auto-ignored TEST_SCOPE.
        UUID scan1 = seedScan(repo);
        seedSca(scan1, repo, "CVE-2020-0001", "junit:junit", "pom.xml");
        reconcile.reconcile(scan1, WORKSPACE, new TriageContext(SecscanConfig.absent(), scopes, false));
        SubIssue sub = byVuln(repo, "CVE-2020-0001");
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        // Scan 2, dev-deps ON -> the dep is unignored (its cause is gone).
        UUID scan2 = seedScan(repo);
        seedSca(scan2, repo, "CVE-2020-0001", "junit:junit", "pom.xml");
        reconcile.reconcile(scan2, WORKSPACE, new TriageContext(SecscanConfig.absent(), scopes, true));
        assertThat(byVuln(repo, "CVE-2020-0001").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);

        List<TriageEvent> trail = events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, sub.getId());
        assertThat(trail).noneMatch(e -> TriageEvent.REASON_SOLVED_NOT_DETECTED.equals(e.getReasonCode())
                || TriageEvent.REASON_REOPENED.equals(e.getReasonCode()));
        // It did go ignored (TEST_SCOPE) then unignored.
        assertThat(trail).anyMatch(e -> com.company.secscan.triage.ReasonCode.TEST_SCOPE.code()
                .equals(e.getReasonCode()));
        assertThat(trail).anyMatch(e -> TriageEvent.REASON_UNIGNORED.equals(e.getReasonCode()));
    }
}
