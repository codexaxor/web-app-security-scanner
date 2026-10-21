package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.triage.ReasonCode;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S3 gate (D-057): {@code .secscan.yml} exclude.paths + ignore.cves(reason) + include_dev_dependencies are
 * honored and surfaced as ignored-WITH-reason (never dropped), and a SECOND scan whose config no longer lists a
 * CVE/path RE-EVALUATES (unignores) the affected findings with a triage_event (review change #2 revoke lifecycle).
 * Seed-based (no scanner container); the real in-container parse is proven by {@code secscan_config_test.py} and
 * the manual real-GitLab check.
 */
class SecscanYmlIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String IGNORED_CVE = "CVE-2022-42889";
    private static final String REASON = "Only used in an offline build tool";
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired ReconcileService reconcile;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository scanFindings;
    @Autowired SubIssueRepository subIssues;
    @Autowired IgnoreRuleRepository ignoreRules;
    @Autowired TriageEventRepository events;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryRepository repositories;

    @Test
    void excludePathsIgnoreCvesAndRemovalAreAllHonoredAndSurfaced() {
        UUID repo = seedRepository();

        // --- Scan 1: config excludes src/legacy and ignores one present CVE with a reason ---
        UUID scan1 = seedScan(repo);
        seedSca(scan1, repo, IGNORED_CVE, "org.apache.commons:commons-text", "pom.xml");
        seedSast(scan1, repo, "opengrep-rules.custom.x", "build/generated/App.java");   // default glob
        seedSast(scan1, repo, "opengrep-rules.custom.y", "src/legacy/Old.java");         // .secscan.yml path
        seedSast(scan1, repo, "opengrep-rules.custom.z", "src/main/App.java");           // control, stays open

        SecscanConfig cfg = new SecscanConfig(true, List.of("src/legacy"), Map.of(IGNORED_CVE, REASON), false);
        reconcile.reconcile(scan1, WORKSPACE, new TriageContext(cfg, java.util.List.of(), false));

        // Nothing dropped — all four findings persist as sub-issues.
        List<SubIssue> subs = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo);
        assertThat(subs).hasSize(4);

        SubIssue cve = byFile(subs, "pom.xml");
        assertThat(cve.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(cve.getIgnoreReason()).isEqualTo(ReasonCode.IGNORE_RULE.code());
        assertThat(cve.getIgnoredByRuleId()).isNotNull();
        // The CVE ignore is materialized as a .secscan.yml system rule carrying the repo reason.
        IgnoreRule rule = ignoreRules.findById(cve.getIgnoredByRuleId()).orElseThrow();
        assertThat(rule.getCreatedBy()).isEqualTo(IgnoreRule.CREATED_BY_SECSCAN_YML);
        assertThat(rule.getScope()).isEqualTo(IgnoreRule.SCOPE_CVE);
        assertThat(rule.getMatchValue()).isEqualTo(IGNORED_CVE);
        assertThat(rule.getReason()).isEqualTo(REASON);
        assertIgnoredEvent(cve, ReasonCode.IGNORE_RULE.code());

        SubIssue defaultGlob = byFile(subs, "build/generated/App.java");
        assertThat(defaultGlob.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(defaultGlob.getIgnoreReason()).isEqualTo(ReasonCode.EXCLUDED_PATH.code());
        assertThat(defaultGlob.getIgnoredByRuleId()).isNull();
        assertIgnoredEvent(defaultGlob, ReasonCode.EXCLUDED_PATH.code());

        SubIssue legacy = byFile(subs, "src/legacy/Old.java");
        assertThat(legacy.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(legacy.getIgnoreReason()).isEqualTo(ReasonCode.EXCLUDED_PATH.code());

        SubIssue control = byFile(subs, "src/main/App.java");
        assertThat(control.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);

        // --- Scan 2: config no longer lists the CVE or src/legacy → those re-evaluate (unignore) ---
        UUID scan2 = seedScan(repo);
        seedSca(scan2, repo, IGNORED_CVE, "org.apache.commons:commons-text", "pom.xml");
        seedSast(scan2, repo, "opengrep-rules.custom.x", "build/generated/App.java");
        seedSast(scan2, repo, "opengrep-rules.custom.y", "src/legacy/Old.java");
        seedSast(scan2, repo, "opengrep-rules.custom.z", "src/main/App.java");

        reconcile.reconcile(scan2, WORKSPACE, TriageContext.empty());

        List<SubIssue> after = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo);
        // The revoked .secscan.yml CVE rule is gone; the CVE finding returns to open (seen this scan).
        assertThat(ignoreRules.findByWorkspaceIdAndRepositoryIdAndCreatedBy(
                WORKSPACE, repo, IgnoreRule.CREATED_BY_SECSCAN_YML)).isEmpty();
        SubIssue cve2 = byFile(after, "pom.xml");
        assertThat(cve2.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(cve2.getIgnoreReason()).isNull();
        assertUnignoredEvent(cve2);

        // The .secscan.yml path is no longer excluded → that finding reopens too.
        assertThat(byFile(after, "src/legacy/Old.java").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        // The default-glob exclusion is independent of the config → stays ignored.
        assertThat(byFile(after, "build/generated/App.java").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
    }

    private void assertIgnoredEvent(SubIssue sub, String reasonCode) {
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, sub.getId()))
                .anySatisfy(e -> {
                    assertThat(e.getToStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
                    assertThat(e.getReasonCode()).isEqualTo(reasonCode);
                    assertThat(e.getActor()).isEqualTo(TriageEvent.ACTOR_SYSTEM);
                });
    }

    private void assertUnignoredEvent(SubIssue sub) {
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, sub.getId()))
                .anySatisfy(e -> {
                    assertThat(e.getFromStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
                    assertThat(e.getReasonCode()).isEqualTo(TriageEvent.REASON_UNIGNORED);
                });
    }

    private static SubIssue byFile(List<SubIssue> subs, String filePath) {
        return subs.stream().filter(s -> filePath.equals(s.getFilePath())).findFirst().orElseThrow();
    }

    private UUID seedRepository() {
        long seq = SEQ.incrementAndGet();
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + seq, 40L + seq, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(),
                1000L + seq, "svc/app-" + seq, "main", List.of("Java"))).getId();
    }

    private UUID seedScan(UUID repoId) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        for (String scanner : new String[] {ScanTask.SCANNER_SCA, ScanTask.SCANNER_SAST}) {
            ScanTask task = new ScanTask(WORKSPACE, scan.getId(), scanner, ScanTask.STATUS_OK, 0, 5L, null);
            task.setClosesFindings(true);
            tasks.save(task);
        }
        return scan.getId();
    }

    private void seedSca(UUID scanId, UUID repoId, String cve, String pkg, String manifest) {
        ScanFinding f = ScanFinding.sca(WORKSPACE, scanId, repoId, cve, pkg, "Maven", "1.9", "1.10.0", manifest,
                "9.8");
        f.setOsvEnrichment("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H", "CRITICAL", List.of("CWE-94"),
                List.of(cve), "RCE");
        scanFindings.save(f);
    }

    private void seedSast(UUID scanId, UUID repoId, String ruleId, String filePath) {
        scanFindings.save(ScanFinding.sast(WORKSPACE, scanId, repoId, ruleId, filePath, 10,
                "hash-" + ruleId, "HIGH", "ERROR", List.of("CWE-352")));
    }
}
