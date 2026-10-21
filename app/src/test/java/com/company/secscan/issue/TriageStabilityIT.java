package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.scan.parse.DeclaredScope;
import com.company.secscan.scan.parse.SecscanConfig;
import java.util.List;
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
