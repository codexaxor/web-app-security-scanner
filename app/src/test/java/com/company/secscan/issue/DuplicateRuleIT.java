package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.triage.ReasonCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S6 gate (D-061): when the curated stock CSRF rule and its winning custom rule BOTH fire for the same file in
 * one scan, the stock LOSER is auto-ignored DUPLICATE_RULE and the custom WINNER stays open. Both ids are the
 * real ones captured from Opengrep output (see duplicate-sast-rules.properties).
 */
class DuplicateRuleIT extends TriageSeedSupport {

    private static final String STOCK = "opengrep-rules.java.spring.security.audit.spring-csrf-disabled";
    private static final String CUSTOM = "opengrep-rules.custom.spring-csrf-disabled";
    private static final String FILE = "src/main/java/SecurityConfig.java";

    @Autowired ReconcileService reconcile;

    @Test
    void stockCsrfIsAutoIgnoredWhenCustomWinsForTheSameFile() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSast(scan, repo, STOCK, FILE, "h-stock");
        seedSast(scan, repo, CUSTOM, FILE, "h-custom");

        reconcile.reconcile(scan, WORKSPACE);

        SubIssue stock = subByRule(repo, STOCK);
        SubIssue custom = subByRule(repo, CUSTOM);
        assertThat(stock.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(stock.getIgnoreReason()).isEqualTo(ReasonCode.DUPLICATE_RULE.code());
        assertThat(custom.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    @Test
    void stockCsrfStaysOpenWhenTheCustomWinnerIsAbsent() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSast(scan, repo, STOCK, FILE, "h-stock"); // only the stock rule fired this scan

        reconcile.reconcile(scan, WORKSPACE);

        assertThat(subByRule(repo, STOCK).getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    private SubIssue subByRule(UUID repo, String ruleId) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> ruleId.equals(s.getRuleId())).findFirst().orElseThrow();
    }
}
