package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S5 gate (D-058): removing an ignore rule restores the finding to its RECONCILE-COMPUTED status (open/solved)
 * with a user {@code triage_event}. The status is keyed on the latest scan where the finding's SCANNER SUCCEEDED
 * — a failed/absent scanner closes nothing, so an unignore then returns to OPEN, never solved (review change #4).
 */
class UnignoreIT extends TriageSeedSupport {

    @Autowired TriageService triage;
    @Autowired ReconcileService reconcile;
    @Autowired TriageEventRepository events;

    @Test
    void removingARuleRestoresOpenWhenTheFindingIsStillSeen() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2022-42889", "org.apache.commons:commons-text", "pom.xml");
        reconcile.reconcile(scan, WORKSPACE);
        var rule = triage.ignoreSubIssue(byVuln(repo, "CVE-2022-42889").getId(), "temp");
        assertThat(byVuln(repo, "CVE-2022-42889").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        triage.removeRule(rule.id());

        SubIssue restored = byVuln(repo, "CVE-2022-42889");
        assertThat(restored.getStatus()).isEqualTo(SubIssue.STATUS_OPEN); // still present in the latest scan
        assertThat(restored.getIgnoreReason()).isNull();
        assertUnignoredEvent(restored);
    }

    @Test
    void removingARuleRestoresSolvedWhenTheFindingIsGoneAndItsScannerSucceeded() {
        UUID repo = seedRepo();
        // Scan 1: the finding is present and we ignore it.
        UUID scan1 = seedScan(repo);
        seedSca(scan1, repo, "CVE-2022-42889", "org.apache.commons:commons-text", "pom.xml");
        reconcile.reconcile(scan1, WORKSPACE);
        var rule = triage.ignoreSubIssue(byVuln(repo, "CVE-2022-42889").getId(), "temp");

        // Scan 2: SCA succeeds (closes findings) but the finding is GONE. It stays ignored (hard rule 6).
        UUID scan2 = seedScan(repo);
        reconcile.reconcile(scan2, WORKSPACE);
        assertThat(byVuln(repo, "CVE-2022-42889").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        // Unignore now: the SCA scanner was authoritative and the finding is absent -> solved.
        triage.removeRule(rule.id());
        assertThat(byVuln(repo, "CVE-2022-42889").getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
    }

    @Test
    void removingARuleReturnsToOpenWhenTheScannerFailedInTheLatestScan() {
        UUID repo = seedRepo();
        // Scan 1: a SAST finding present; ignore it.
        UUID scan1 = seedScan(repo);
        seedSast(scan1, repo, "opengrep-rules.custom.x", "A.java", "h1");
        reconcile.reconcile(scan1, WORKSPACE);
        var rule = triage.ignoreSubIssue(byFile(repo, "A.java").getId(), "temp");

        // Scan 2: SAST FAILED (closes nothing) and the finding is absent. Its scanner did not succeed, so on
        // unignore it must return to OPEN, never solved (failed/absent scanner closes nothing, change #4).
        UUID scan2 = seedScan(repo, true, false); // sca ok, sast failed
        reconcile.reconcile(scan2, WORKSPACE);

        triage.removeRule(rule.id());
        SubIssue restored = byFile(repo, "A.java");
        assertThat(restored.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertUnignoredEvent(restored);
    }

    private void assertUnignoredEvent(SubIssue sub) {
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, sub.getId()))
                .anySatisfy(e -> {
                    assertThat(e.getFromStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
                    assertThat(e.getReasonCode()).isEqualTo(TriageEvent.REASON_UNIGNORED);
                    assertThat(e.getActor()).isEqualTo(TriageEvent.ACTOR_USER);
                });
    }
}
