package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.intel.EpssScore;
import com.company.secscan.intel.EpssScoreRepository;
import com.company.secscan.intel.KevEntry;
import com.company.secscan.intel.KevEntryRepository;
import com.company.secscan.scan.parse.SecscanConfig;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S6 gate (D-060), driven through reconcile with the workspace EPSS toggle carried in the TriageContext:
 * OFF has no effect; ON auto-ignores EPSS &lt; 1% (not KEV) as LOW_EPSS (status + triage_event); the scoring
 * tiers (&lt;5% −10, 5–10% −5) adjust the score with NO triage_event; a KEV-listed low-EPSS CVE is NOT ignored.
 */
class EpssToggleIT extends TriageSeedSupport {

    @Autowired ReconcileService reconcile;
    @Autowired EpssScoreRepository epssScores;
    @Autowired KevEntryRepository kevEntries;
    @Autowired TriageEventRepository events;

    private static TriageContext epss(boolean enabled) {
        return new TriageContext(SecscanConfig.absent(), Map.of(), false, enabled);
    }

    @Test
    void offHasNoEffectEvenForVeryLowEpss() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2100-1", "org.a:a", "pom.xml");
        epssScores.save(new EpssScore("CVE-2100-1", new BigDecimal("0.00100"), new BigDecimal("0.1")));

        reconcile.reconcile(scan, WORKSPACE, epss(false));

        SubIssue sub = byVuln(repo, "CVE-2100-1");
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(sub.getScoreBreakdown()).noneMatch(l -> l.code().equals("epss_tier"));
    }

    @Test
    void onAutoIgnoresLowEpssWithTriageEvent() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2100-2", "org.a:a", "pom.xml");
        epssScores.save(new EpssScore("CVE-2100-2", new BigDecimal("0.00500"), new BigDecimal("0.1"))); // 0.5% < 1%

        reconcile.reconcile(scan, WORKSPACE, epss(true));

        SubIssue sub = byVuln(repo, "CVE-2100-2");
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(sub.getIgnoreReason()).isEqualTo("LOW_EPSS");
        assertThat(sub.getIgnoredByRuleId()).isNull(); // cause-based, not a rule
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, sub.getId()))
                .anyMatch(e -> "LOW_EPSS".equals(e.getReasonCode())
                        && SubIssue.STATUS_IGNORED.equals(e.getToStatus())
                        && TriageEvent.ACTOR_SYSTEM.equals(e.getActor()));
    }

    @Test
    void scoringTierAdjustsScoreWithoutTriageEventAndWithoutIgnoring() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2100-3", "org.a:a", "pom.xml"); // base 98 (CVSS 9.8)
        epssScores.save(new EpssScore("CVE-2100-3", new BigDecimal("0.03000"), new BigDecimal("0.3"))); // 3%: <5%

        reconcile.reconcile(scan, WORKSPACE, epss(true));

        SubIssue sub = byVuln(repo, "CVE-2100-3");
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);               // 3% is not <1% → not ignored
        assertThat(sub.getScore()).isEqualTo(88);                                  // 98 − 10
        assertThat(sub.getScoreBreakdown()).anyMatch(l -> l.code().equals("epss_tier") && l.delta() == -10);
        // Scoring-only: the only events are the lifecycle SEEN; no ignore event.
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, sub.getId()))
                .noneMatch(e -> "LOW_EPSS".equals(e.getReasonCode()));
    }

    @Test
    void kevListedLowEpssCveIsNotAutoIgnored() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2100-4", "org.a:a", "pom.xml");
        epssScores.save(new EpssScore("CVE-2100-4", new BigDecimal("0.00010"), new BigDecimal("0.01"))); // 0.01%
        kevEntries.save(new KevEntry("CVE-2100-4", "exploited", null));

        reconcile.reconcile(scan, WORKSPACE, epss(true));

        SubIssue sub = byVuln(repo, "CVE-2100-4");
        assertThat(sub.isInKev()).isTrue();
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_OPEN); // on KEV → never LOW_EPSS auto-ignored
    }
}
