package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.intel.EpssScore;
import com.company.secscan.intel.EpssScoreRepository;
import com.company.secscan.scan.parse.DeclaredScope;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.triage.ReasonCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review S8 precedence gate (D-063): when several causes apply to ONE finding, exactly one deterministic
 * reason_code is recorded — ReasonCode declaration order. EXCLUDED_PATH outranks DEV/TEST scope; a (manual or
 * .secscan.yml) IGNORE_RULE outranks LOW_EPSS. Each finding gets exactly ONE ignore event, never two.
 */
class TriagePrecedenceIT extends TriageSeedSupport {

    @Autowired ReconcileService reconcile;
    @Autowired TriageEventRepository events;
    @Autowired EpssScoreRepository epssScores;

    @Test
    void excludedPathOutranksDevTestScopeWithOneReasonCode() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        // junit:junit is BOTH test-scoped AND in an excluded (vendor/) manifest path. EXCLUDED_PATH must win.
        seedSca(scan, repo, "CVE-PREC-1", "junit:junit", "vendor/pom.xml");
        List<DeclaredScope> scopes = List.of(new DeclaredScope("vendor/pom.xml", "junit:junit", "test"));

        reconcile.reconcile(scan, WORKSPACE, new TriageContext(SecscanConfig.absent(), scopes, false));

        SubIssue sub = byVuln(repo, "CVE-PREC-1");
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(sub.getIgnoreReason()).isEqualTo(ReasonCode.EXCLUDED_PATH.code());
        assertThat(ignoreEvents(sub)).singleElement().isEqualTo(ReasonCode.EXCLUDED_PATH.code());
    }

    @Test
    void secscanYmlCveOutranksLowEpssWithOneReasonCode() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        // The CVE is BOTH .secscan.yml-ignored AND low-EPSS (toggle on). IGNORE_RULE must win.
        seedSca(scan, repo, "CVE-2022-42889", "org.apache.commons:commons-text", "pom.xml");
        epssScores.save(new EpssScore("CVE-2022-42889", new BigDecimal("0.00500"), new BigDecimal("0.1"))); // 0.5%
        SecscanConfig cfg = new SecscanConfig(true, List.of(),
                Map.of("CVE-2022-42889", "offline build tool only"), false);

        reconcile.reconcile(scan, WORKSPACE, new TriageContext(cfg, List.of(), false, true));

        SubIssue sub = byVuln(repo, "CVE-2022-42889");
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(sub.getIgnoreReason()).isEqualTo(ReasonCode.IGNORE_RULE.code());
        assertThat(sub.getIgnoredByRuleId()).isNotNull();
        assertThat(ignoreEvents(sub)).singleElement().isEqualTo(ReasonCode.IGNORE_RULE.code());
    }

    /** The reason codes of every IGNORED-transition event for a sub (should be exactly one). */
    private List<String> ignoreEvents(SubIssue sub) {
        return events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, sub.getId()).stream()
                .filter(e -> SubIssue.STATUS_IGNORED.equals(e.getToStatus()))
                .map(TriageEvent::getReasonCode)
                .toList();
    }
}
