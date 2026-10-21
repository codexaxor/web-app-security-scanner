package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.scan.parse.DeclaredScope;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.triage.ReasonCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SF1: a manual rule over a finding that ALSO has a scan-time-only auto cause (TEST_SCOPE / DUPLICATE_RULE)
 * persists that cause in {@code auto_ignore_reason}. Removing the rule RE-REASONS the finding to the auto cause
 * (no unignore, NO event), and the next clean reconcile writes no event either.
 */
class RuleRemovalKeepsScanTimeCauseIT extends TriageSeedSupport {

    private static final String STOCK_CSRF = "opengrep-rules.java.spring.security.audit.spring-csrf-disabled";
    private static final String CUSTOM_CSRF = "opengrep-rules.custom.spring-csrf-disabled";

    @Autowired ReconcileService reconcile;
    @Autowired TriageService triage;
    @Autowired TriageEventRepository events;

    @Test
    void removingARuleOverATestScopeFindingReReasonsToTestScopeWithNoEvent() {
        UUID repo = seedRepo();
        List<DeclaredScope> scopes = List.of(new DeclaredScope("pom.xml", "junit:junit", "test"));
        TriageContext ctx = new TriageContext(SecscanConfig.absent(), scopes, false);

        UUID scan1 = seedScan(repo);
        seedSca(scan1, repo, "CVE-SF1-1", "junit:junit", "pom.xml");
        reconcile.reconcile(scan1, WORKSPACE, ctx);
        SubIssue sub = byVuln(repo, "CVE-SF1-1");
        assertThat(sub.getIgnoreReason()).isEqualTo(ReasonCode.TEST_SCOPE.code());
        assertThat(sub.getAutoIgnoreReason()).isEqualTo(ReasonCode.TEST_SCOPE.code());

        // Manual sub-issue ignore over it → IGNORE_RULE holds it (quiet re-point), auto cause preserved.
        UUID ruleId = triage.ignoreSubIssue(sub.getId(), "team accepted").id();
        sub = byVuln(repo, "CVE-SF1-1");
        assertThat(sub.getIgnoreReason()).isEqualTo(ReasonCode.IGNORE_RULE.code());
        assertThat(sub.getAutoIgnoreReason()).isEqualTo(ReasonCode.TEST_SCOPE.code());
        long before = events.count();

        // Remove the rule → re-reason to TEST_SCOPE, stays ignored, NO event.
        triage.removeRule(ruleId);
        sub = byVuln(repo, "CVE-SF1-1");
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(sub.getIgnoreReason()).isEqualTo(ReasonCode.TEST_SCOPE.code());
        assertThat(sub.getIgnoredByRuleId()).isNull();
        assertThat(events.count()).as("rule removal over a scan-time cause writes no event").isEqualTo(before);

        // Next clean reconcile → still TEST_SCOPE, no event.
        UUID scan2 = seedScan(repo);
        seedSca(scan2, repo, "CVE-SF1-1", "junit:junit", "pom.xml");
        reconcile.reconcile(scan2, WORKSPACE, ctx);
        assertThat(byVuln(repo, "CVE-SF1-1").getIgnoreReason()).isEqualTo(ReasonCode.TEST_SCOPE.code());
        assertThat(events.count()).as("a clean rescan after re-reason writes no event").isEqualTo(before);
    }

    @Test
    void removingARuleOverADuplicateRuleFindingReReasonsToDuplicateRuleWithNoEvent() {
        UUID repo = seedRepo();

        UUID scan1 = seedScan(repo);
        seedSast(scan1, repo, STOCK_CSRF, "src/Sec.java", "h-stock");
        seedSast(scan1, repo, CUSTOM_CSRF, "src/Sec.java", "h-custom");
        reconcile.reconcile(scan1, WORKSPACE);
        SubIssue stock = byFileAndRule(repo, "src/Sec.java", STOCK_CSRF);
        assertThat(stock.getIgnoreReason()).isEqualTo(ReasonCode.DUPLICATE_RULE.code());
        assertThat(stock.getAutoIgnoreReason()).isEqualTo(ReasonCode.DUPLICATE_RULE.code());

        UUID ruleId = triage.ignoreSubIssue(stock.getId(), "accepted").id();
        stock = byFileAndRule(repo, "src/Sec.java", STOCK_CSRF);
        assertThat(stock.getIgnoreReason()).isEqualTo(ReasonCode.IGNORE_RULE.code());
        assertThat(stock.getAutoIgnoreReason()).isEqualTo(ReasonCode.DUPLICATE_RULE.code());
        long before = events.count();

        triage.removeRule(ruleId);
        stock = byFileAndRule(repo, "src/Sec.java", STOCK_CSRF);
        assertThat(stock.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(stock.getIgnoreReason()).isEqualTo(ReasonCode.DUPLICATE_RULE.code());
        assertThat(stock.getIgnoredByRuleId()).isNull();
        assertThat(events.count()).isEqualTo(before);

        UUID scan2 = seedScan(repo);
        seedSast(scan2, repo, STOCK_CSRF, "src/Sec.java", "h-stock");
        seedSast(scan2, repo, CUSTOM_CSRF, "src/Sec.java", "h-custom");
        reconcile.reconcile(scan2, WORKSPACE);
        assertThat(byFileAndRule(repo, "src/Sec.java", STOCK_CSRF).getIgnoreReason())
                .isEqualTo(ReasonCode.DUPLICATE_RULE.code());
        assertThat(events.count()).isEqualTo(before);
    }

    private SubIssue byFileAndRule(UUID repo, String filePath, String ruleId) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> filePath.equals(s.getFilePath()) && ruleId.equals(s.getRuleId()))
                .findFirst().orElseThrow();
    }
}
